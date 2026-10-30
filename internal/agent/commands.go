package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/store"
)

// Inbox holds validated phone commands until the Sublime plugin applies them.
// Commands are decrypted and checked here; the plugin never sees keys.
type Inbox struct {
	st      *store.Store
	current proto.Keys
	keyring map[string]proto.Keys
	now     func() time.Time

	// OnResult receives each sealed result; fromServer says where the command came from.
	OnResult func(id string, env []byte, fromServer bool)

	mu         sync.Mutex
	pending    map[string]proto.Command
	fromServer map[string]bool
}

func NewInbox(st *store.Store, current proto.Keys, keyring map[string]proto.Keys, now func() time.Time) *Inbox {
	return &Inbox{st: st, current: current, keyring: keyring, now: now,
		pending: map[string]proto.Command{}, fromServer: map[string]bool{}}
}

// Load re-queues commands stored but not answered before a restart.
func (in *Inbox) Load() error {
	cmds, err := in.st.PendingCommands()
	if err != nil {
		return err
	}
	for _, c := range cmds {
		in.accept(c.ID, c.Envelope, false)
	}
	return nil
}

// Accept stores, validates and queues a sealed command. Invalid, replayed or
// too old commands are answered with a "rejected" result instead.
func (in *Inbox) Accept(id string, env []byte, fromServer bool) error {
	if err := in.st.AddCommand(id, in.now().UnixMilli(), env); err != nil {
		return err
	}
	if c, err := in.st.GetCommand(id); err == nil && c.Result != nil {
		// Already answered (e.g. the result upload failed): send it again.
		if in.OnResult != nil {
			in.OnResult(id, c.Result, fromServer)
		}
		return nil
	}
	in.accept(id, env, fromServer)
	return nil
}

func (in *Inbox) accept(id string, env []byte, fromServer bool) {
	in.mu.Lock()
	if fromServer {
		in.fromServer[id] = true
	}
	in.mu.Unlock()
	if applied, _ := in.st.WasApplied(id); applied {
		in.Complete(proto.Result{ID: id, Status: proto.ResultRejected, Reason: "replayed"})
		return
	}
	plain, err := proto.Open(in.keyring, proto.CommandAAD(id), env)
	var c proto.Command
	if err != nil || json.Unmarshal(plain, &c) != nil || c.ID != id {
		in.Complete(proto.Result{ID: id, Status: proto.ResultRejected, Reason: "invalid"})
		return
	}
	if in.now().UnixMilli()-c.Created > proto.MaxCommandAge {
		in.Complete(proto.Result{ID: id, Status: proto.ResultRejected, Reason: "too old"})
		return
	}
	if c.Type != proto.CommandDraft && c.Type != proto.CommandClose {
		in.Complete(proto.Result{ID: id, Status: proto.ResultRejected, Reason: "unknown type"})
		return
	}
	var superseded []string
	in.mu.Lock()
	if _, queued := in.pending[id]; queued {
		in.mu.Unlock() // the relay returned it again before Sublime applied it
		return
	}
	if c.Type == proto.CommandDraft {
		// Only the newest revision of a draft is worth applying.
		for oid, o := range in.pending {
			if oid == id || o.Type != proto.CommandDraft || o.DraftID != c.DraftID {
				continue
			}
			if o.Rev >= c.Rev {
				superseded = append(superseded, id)
				break
			}
			superseded = append(superseded, oid)
		}
	}
	if len(superseded) == 0 || superseded[0] != id {
		in.pending[id] = c
	}
	for _, sid := range superseded {
		delete(in.pending, sid)
	}
	in.mu.Unlock()
	for _, sid := range superseded {
		in.Complete(proto.Result{ID: sid, Status: proto.ResultApplied, Reason: "superseded"})
	}
}

// Pending returns queued commands, oldest first.
func (in *Inbox) Pending() []proto.Command {
	in.mu.Lock()
	defer in.mu.Unlock()
	out := make([]proto.Command, 0, len(in.pending))
	for _, c := range in.pending {
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Created != out[j].Created {
			return out[i].Created < out[j].Created
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// Complete seals and stores a result, and forgets the command.
func (in *Inbox) Complete(r proto.Result) {
	if r.At == 0 {
		r.At = in.now().UnixMilli()
	}
	plain, _ := json.Marshal(r)
	env, err := proto.Seal(in.current, proto.ResultAAD(r.ID), plain)
	if err != nil {
		log.Printf("commands: seal result: %v", err)
		return
	}
	if err := in.st.SetCommandResult(r.ID, env, r.At); err != nil {
		log.Printf("commands: store result %s: %v", r.ID, err)
	}
	if c, err := in.st.GetCommand(r.ID); err == nil && c.Result != nil {
		env = c.Result // a duplicate apply must not replace the first answer
	}
	in.st.MarkApplied(r.ID, r.At)
	in.mu.Lock()
	delete(in.pending, r.ID)
	fromServer := in.fromServer[r.ID]
	delete(in.fromServer, r.ID)
	in.mu.Unlock()
	if in.OnResult != nil {
		in.OnResult(r.ID, env, fromServer)
	}
}

// CommandFetcher pulls pending commands from the relay and uploads results.
type CommandFetcher struct {
	in        *Inbox
	serverURL string
	bearer    string
	client    *http.Client
	kick      chan struct{}

	mu     sync.Mutex
	unsent map[string][]byte // results whose upload failed
}

func NewCommandFetcher(in *Inbox, serverURL, bearer string) *CommandFetcher {
	return &CommandFetcher{in: in, serverURL: strings.TrimRight(serverURL, "/"), bearer: bearer,
		client: &http.Client{Timeout: 30 * time.Second}, kick: make(chan struct{}, 1), unsent: map[string][]byte{}}
}

// Kick fetches as soon as possible (e.g. on a WebSocket "command" event).
func (f *CommandFetcher) Kick() {
	select {
	case f.kick <- struct{}{}:
	default:
	}
}

func (f *CommandFetcher) Run(ctx context.Context, every time.Duration) {
	for {
		if err := f.FetchOnce(ctx); err != nil && ctx.Err() == nil {
			log.Printf("commands: fetch: %v", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-f.kick:
		case <-time.After(every):
		}
	}
}

// FetchOnce retries unsent results, then queues the relay's pending commands.
func (f *CommandFetcher) FetchOnce(ctx context.Context) error {
	f.mu.Lock()
	retry := f.unsent
	f.unsent = map[string][]byte{}
	f.mu.Unlock()
	for id, env := range retry {
		f.UploadResult(id, env, true)
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, f.serverURL+"/v1/commands?pending=1", nil)
	req.Header.Set("Authorization", "Bearer "+f.bearer)
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var cmds []api.CommandJSON
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<20)).Decode(&cmds); err != nil {
		return err
	}
	for _, c := range cmds {
		if api.ValidID(c.ID) {
			f.in.Accept(c.ID, c.Envelope, true)
		}
	}
	return nil
}

// UploadResult sends a result to the relay when the command came from it;
// failures are retried on the next fetch.
func (f *CommandFetcher) UploadResult(id string, env []byte, fromServer bool) {
	if !fromServer {
		return
	}
	req, _ := http.NewRequest(http.MethodPut, f.serverURL+"/v1/commands/"+id+"/result", bytes.NewReader(env))
	req.Header.Set("Authorization", "Bearer "+f.bearer)
	resp, err := f.client.Do(req)
	if err == nil {
		resp.Body.Close()
		if resp.StatusCode == http.StatusNoContent || resp.StatusCode == http.StatusNotFound {
			return
		}
	}
	f.mu.Lock()
	f.unsent[id] = env
	f.mu.Unlock()
}
