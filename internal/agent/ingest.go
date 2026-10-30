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
	Type string      `json:"type"` // "snapshot" or "bye"
	Tabs []proto.Tab `json:"tabs"`
}

// maxPluginLine bounds one message: all tabs, each already capped by the plugin.
const maxPluginLine = 256 << 20

// ListenPlugin accepts plugin connections on a 0600 Unix socket and calls
// onTabs for every snapshot message, until ctx ends.
func ListenPlugin(ctx context.Context, sockPath string, onTabs func([]proto.Tab)) error {
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
		handlePlugin(conn, onTabs)
	}
}

func handlePlugin(conn net.Conn, onTabs func([]proto.Tab)) {
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	sc := bufio.NewScanner(conn)
	sc.Buffer(make([]byte, 1<<20), maxPluginLine)
	for sc.Scan() {
		var m PluginMessage
		if err := json.Unmarshal(sc.Bytes(), &m); err != nil {
			log.Printf("plugin: bad message: %v", err)
			continue
		}
		if m.Type == "snapshot" {
			onTabs(m.Tabs)
		}
	}
}
