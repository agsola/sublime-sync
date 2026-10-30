package agent

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"log"
	"net"
	"os"
	"time"

	"github.com/agsola/sublime-sync/internal/proto"
)

// PluginMessage is one line sent by the Sublime plugin over the Unix socket.
type PluginMessage struct {
	Type    string         `json:"type"` // "snapshot", "poll", "results" or "bye"
	Tabs    []proto.Tab    `json:"tabs"`
	Results []proto.Result `json:"results"`
}

// PluginReply is the agent's single answer line once the plugin has finished
// writing (shut down its write side): the commands waiting for Sublime.
type PluginReply struct {
	Type     string          `json:"type"` // "commands"
	Commands []proto.Command `json:"commands"`
}

// PluginHandler receives what the plugin sends and supplies queued commands.
type PluginHandler interface {
	OnTabs([]proto.Tab)
	OnResults([]proto.Result)
	Pending() []proto.Command
}

// maxPluginLine bounds one message: all tabs, each already capped by the plugin.
const maxPluginLine = 256 << 20

// ListenPlugin accepts plugin connections on a 0600 Unix socket until ctx
// ends. Each connection is one exchange: the plugin's lines, then our reply.
func ListenPlugin(ctx context.Context, sockPath string, h PluginHandler) error {
	if err := os.Remove(sockPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	ln, err := net.Listen("unix", sockPath)
	if err != nil {
		return err
	}
	if err := os.Chmod(sockPath, 0o600); err != nil {
		ln.Close()
		return err
	}
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		// One connection at a time, so snapshots are ingested in send order.
		handlePlugin(conn, h)
	}
}

func handlePlugin(conn net.Conn, h PluginHandler) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	sc := bufio.NewScanner(conn)
	sc.Buffer(make([]byte, 1<<20), maxPluginLine)
	for sc.Scan() {
		var m PluginMessage
		if err := json.Unmarshal(sc.Bytes(), &m); err != nil {
			log.Printf("plugin: bad message: %v", err)
			continue
		}
		switch m.Type {
		case "snapshot":
			h.OnTabs(m.Tabs)
		case "results":
			h.OnResults(m.Results)
		}
	}
	reply, _ := json.Marshal(PluginReply{Type: "commands", Commands: h.Pending()})
	conn.Write(append(reply, '\n')) // fails harmlessly for plugins that don't read
}
