// Command sublime-sync-agent runs on the Mac. Subcommands:
//
//	init --server URL --install-token TOKEN [--mac-url URL]
//	pair     show the pairing QR code for the phone
//	rotate   replace the master key (old history stays readable)
//	run      run the agent (normally started by launchd)
//	status   show local and server state
package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/agsola/sublime-sync/internal/agent"
	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/store"
	"github.com/mdp/qrterminal/v3"
)

const phonePort = 8788

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "init":
		err = cmdInit(os.Args[2:])
	case "pair":
		err = cmdPair()
	case "rotate":
		err = cmdRotate()
	case "run":
		err = cmdRun()
	case "status":
		err = cmdStatus()
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: sublime-sync-agent init|pair|rotate|run|status")
	os.Exit(2)
}

func cmdInit(args []string) error {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	serverURL := fs.String("server", "", "relay URL, e.g. https://sync.example.com")
	installToken := fs.String("install-token", "", "token printed by the server installer")
	macURL := fs.String("mac-url", "", "URL the phone uses to reach this Mac over Tailscale (default: http://<tailscale-ip>:8788)")
	fs.Parse(args)
	if *serverURL == "" || *installToken == "" {
		return errors.New("--server and --install-token are required")
	}
	dir, err := agent.DataDir()
	if err != nil {
		return err
	}
	m, err := agent.LoadMasterKey()
	if errors.Is(err, agent.ErrNoKey) {
		if m, err = proto.NewMasterKey(); err != nil {
			return err
		}
		if err := agent.SaveMasterKey(m); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	k := proto.Derive(m)
	if *macURL == "" {
		addr, ok := agent.TailscaleAddr()
		if !ok {
			return errors.New("Tailscale is not up; start it or pass --mac-url")
		}
		*macURL = fmt.Sprintf("http://%s:%d", addr, phonePort)
	}
	server := strings.TrimRight(*serverURL, "/")
	if err := register(server, map[string]string{"X-Install-Token": *installToken}, proto.TokenHash(k.ServerAuth)); err != nil {
		return err
	}
	cfg, _ := agent.LoadConfig(dir)
	cfg.Server, cfg.Mac = server, *macURL
	if err := agent.SaveConfig(dir, cfg); err != nil {
		return err
	}
	fmt.Println("Initialised. Next: 'sublime-sync-agent pair' and scan the QR with the app.")
	return nil
}

func register(server string, headers map[string]string, authHash string) error {
	body, _ := json.Marshal(map[string]string{"authHash": authHash})
	req, _ := http.NewRequest(http.MethodPost, server+"/v1/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	switch resp.StatusCode {
	case http.StatusNoContent:
		return nil
	case http.StatusConflict:
		return errors.New("server already registered to another key; use 'rotate' from the Mac that holds it, or reinstall the server data")
	default:
		return fmt.Errorf("register: HTTP %d %s", resp.StatusCode, strings.TrimSpace(string(msg)))
	}
}

func cmdPair() error {
	dir, err := agent.DataDir()
	if err != nil {
		return err
	}
	cfg, err := agent.LoadConfig(dir)
	if err != nil {
		return fmt.Errorf("not initialised: %w", err)
	}
	m, err := agent.LoadMasterKey()
	if err != nil {
		return err
	}
	payload, _ := json.Marshal(proto.PairPayload{V: 1, Key: base64.RawURLEncoding.EncodeToString(m[:]), Server: cfg.Server, Mac: cfg.Mac})
	fmt.Println("Scan this with the sublime-sync app. It contains your secret key:")
	fmt.Println("do not photograph or share it. Press Ctrl-C when done.")
	fmt.Println()
	qrterminal.GenerateWithConfig(string(payload), qrterminal.Config{
		Level: qrterminal.M, Writer: os.Stdout, HalfBlocks: true,
		BlackChar: qrterminal.BLACK_BLACK, WhiteBlackChar: qrterminal.WHITE_BLACK,
		WhiteChar: qrterminal.WHITE_WHITE, BlackWhiteChar: qrterminal.BLACK_WHITE, QuietZone: 2,
	})
	fmt.Printf("\nServer: %s\nMac:    %s\nKey id: %s\n", cfg.Server, cfg.Mac, m.KeyID())
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	fmt.Print("\033[2J\033[H") // clear the screen so the key doesn't linger
	return nil
}

func cmdRotate() error {
	dir, err := agent.DataDir()
	if err != nil {
		return err
	}
	cfg, err := agent.LoadConfig(dir)
	if err != nil {
		return fmt.Errorf("not initialised: %w", err)
	}
	old, err := agent.LoadMasterKey()
	if err != nil {
		return err
	}
	nm, err := proto.NewMasterKey()
	if err != nil {
		return err
	}
	ok, nk := proto.Derive(old), proto.Derive(nm)
	if err := register(cfg.Server, map[string]string{"Authorization": "Bearer " + proto.TokenString(ok.ServerAuth)}, proto.TokenHash(nk.ServerAuth)); err != nil {
		return err
	}
	if err := agent.ArchiveMasterKey(old); err != nil {
		return err
	}
	if err := agent.SaveMasterKey(nm); err != nil {
		return err
	}
	cfg.OldKeyIDs = append(cfg.OldKeyIDs, old.KeyID())
	if err := agent.SaveConfig(dir, cfg); err != nil {
		return err
	}
	fmt.Println("Key rotated. Restart the agent, then run 'sublime-sync-agent pair' and re-pair the phone.")
	return nil
}

func cmdRun() error {
	dir, err := agent.DataDir()
	if err != nil {
		return err
	}
	cfg, err := agent.LoadConfig(dir)
	if err != nil {
		return fmt.Errorf("not initialised: %w", err)
	}
	keys, keyring, err := agent.Keyring(cfg)
	if err != nil {
		return err
	}
	st, err := store.Open(filepath.Join(dir, "agent.db"))
	if err != nil {
		return err
	}
	defer st.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	hub := api.NewSelfHub(30 * time.Second)
	up := agent.NewUploader(st, cfg.Server, proto.TokenString(keys.ServerAuth))
	snap := agent.NewSnapshotter(st, keys, func(seq int64) {
		up.Kick()
		hub.NotifySnapshot(seq)
	})
	// If the relay is ahead (local database lost or replaced), continue after it.
	resync := func(serverLatest int64) {
		if err := snap.SeedSeq(serverLatest); err != nil {
			log.Printf("seed seq: %v", err)
		}
		snap.ForceNext()
		log.Printf("relay is at seq %d; continuing after it", serverLatest)
	}
	up.OnStale = resync
	if latest, err := up.ServerLatest(ctx); err == nil {
		if local, _ := st.MaxSeq(); latest > local {
			resync(latest)
		}
	}
	// Phone commands: from the relay (fetched) or straight over Tailscale.
	inbox := agent.NewInbox(st, keys, keyring, time.Now)
	fetcher := agent.NewCommandFetcher(inbox, cfg.Server, proto.TokenString(keys.ServerAuth))
	inbox.OnResult = func(id string, env []byte, fromServer bool) {
		hub.NotifyResult(id)
		go fetcher.UploadResult(id, env, fromServer)
	}
	if err := inbox.Load(); err != nil {
		log.Printf("commands: load: %v", err)
	}
	up.OnEvent = func(e api.Event) {
		if e.Type == "command" {
			fetcher.Kick()
		}
	}
	go fetcher.Run(ctx, 30*time.Second)

	var lastPlugin atomic.Int64
	ingest := func(source string) func([]proto.Tab) {
		return func(tabs []proto.Tab) {
			if source == proto.SourcePlugin {
				lastPlugin.Store(time.Now().UnixNano())
			}
			if seq, changed, err := snap.Ingest(tabs, source, time.Now()); err != nil {
				log.Printf("ingest: %v", err)
			} else if changed {
				log.Printf("snapshot %d from %s (%d tabs)", seq, source, len(tabs))
			}
		}
	}

	go up.Run(ctx)
	go func() {
		// Snapshots are ingested in the background (newest wins), so the plugin
		// never waits for big tabs to be compressed and encrypted.
		queue := agent.NewLatestQueue()
		go func() {
			ingestPlugin := ingest(proto.SourcePlugin)
			for {
				tabs, ok := queue.Take(ctx)
				if !ok {
					return
				}
				ingestPlugin(tabs)
			}
		}()
		h := pluginHandler{onTabs: queue.Put, inbox: inbox, filler: agent.NewTabFiller()}
		if err := agent.ListenPlugin(ctx, filepath.Join(dir, "agent.sock"), h); err != nil {
			log.Printf("plugin socket: %v", err)
			stop()
		}
	}()
	home, _ := os.UserHomeDir()
	session := filepath.Join(home, "Library", "Application Support", "Sublime Text", "Local", "Auto Save Session.sublime_session")
	go agent.WatchSession(ctx, session, time.Minute, 2*time.Minute,
		func() time.Time { return time.Unix(0, lastPlugin.Load()) }, ingest(proto.SourceSession))

	mux := http.NewServeMux()
	macHash := proto.TokenHash(keys.MacAuth)
	macAuth := api.BearerAuth(func() string { return macHash })
	api.ReadRoutes(mux, st, macAuth, hub)
	api.WriteRoutes(mux, st, macAuth, hub, func(id string, env []byte) { inbox.Accept(id, env, false) })
	go agent.ServeTailnet(ctx, mux, phonePort, 30*time.Second)

	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Hour):
			}
			st.PruneCommands(time.Now())
			if _, _, err := st.Prune(time.Now()); err != nil {
				log.Printf("prune: %v", err)
			}
		}
	}()

	log.Printf("agent running (key %s, server %s)", keys.ID, cfg.Server)
	<-ctx.Done()
	return nil
}

func cmdStatus() error {
	dir, err := agent.DataDir()
	if err != nil {
		return err
	}
	cfg, err := agent.LoadConfig(dir)
	if err != nil {
		return fmt.Errorf("not initialised: %w", err)
	}
	keys, _, err := agent.Keyring(cfg)
	if err != nil {
		return err
	}
	st, err := store.Open(filepath.Join(dir, "agent.db"))
	if err != nil {
		return err
	}
	defer st.Close()
	latest, _ := st.MaxSeq()
	pending, _ := st.PendingUploads()
	fmt.Printf("Key id:          %s\nServer:          %s\nMac (Tailscale): %s\n", keys.ID, cfg.Server, cfg.Mac)
	fmt.Printf("Local snapshot:  %d (%d pending upload)\n", latest, len(pending))

	req, _ := http.NewRequest(http.MethodGet, cfg.Server+"/v1/status", nil)
	req.Header.Set("Authorization", "Bearer "+proto.TokenString(keys.ServerAuth))
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("Server:          unreachable (%v)\n", err)
		return nil
	}
	defer resp.Body.Close()
	var s api.Status
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&s) != nil {
		fmt.Printf("Server:          HTTP %d\n", resp.StatusCode)
		return nil
	}
	fmt.Printf("Server snapshot: %d, Mac online: %v\n", s.LatestSeq, s.MacOnline)
	return nil
}

// pluginHandler connects the plugin socket to the snapshotter and the inbox.
type pluginHandler struct {
	onTabs func([]proto.Tab)
	inbox  *agent.Inbox
	filler *agent.TabFiller
}

func (h pluginHandler) OnTabs(t []proto.Tab) bool {
	full, ok := h.filler.Fill(t)
	if !ok {
		return false
	}
	h.onTabs(full)
	return true
}
func (h pluginHandler) OnResults(rs []proto.Result) {
	for _, r := range rs {
		h.inbox.Complete(r)
	}
}
func (h pluginHandler) Pending() []proto.Command { return h.inbox.Pending() }
