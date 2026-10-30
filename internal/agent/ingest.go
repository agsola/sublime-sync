package agent

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"os"
	"sync"
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
// writing (shut down its write side): the commands waiting for Sublime, and
// whether the plugin must send every tab's text next time.
type PluginReply struct {
	Type     string          `json:"type"` // "commands"
	Commands []proto.Command `json:"commands"`
	NeedFull bool            `json:"needFull,omitempty"`
}

// TabFiller restores the text of tabs the plugin marked unchanged, from the
// last text it sent for that view. The plugin only resends changed tabs, so
// big unchanged tabs don't travel (and get encoded) on every snapshot.
type TabFiller struct {
	mu   sync.Mutex
	last map[int64]proto.Tab
}

func NewTabFiller() *TabFiller { return &TabFiller{last: map[int64]proto.Tab{}} }

// Fill completes a snapshot; ok is false if an unchanged tab's text is unknown
// (e.g. after an agent restart), in which case the snapshot must be dropped.
func (f *TabFiller) Fill(tabs []proto.Tab) ([]proto.Tab, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]proto.Tab, len(tabs))
	next := make(map[int64]proto.Tab, len(tabs))
	for i, t := range tabs {
		if t.Unchanged {
			prev, ok := f.last[t.ID]
			if !ok {
				return nil, false
			}
			t.Text, t.Truncated, t.Unchanged = prev.Text, prev.Truncated, false
		}
		out[i] = t
		next[t.ID] = t
	}
	f.last = next
	return out, true
}

// PluginHandler receives what the plugin sends and supplies queued commands.
// OnTabs returns false when the plugin must resend every tab in full.
type PluginHandler interface {
	OnTabs([]proto.Tab) bool
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
	conn.SetDeadline(time.Now().Add(2 * time.Minute))
	// bufio.Reader scans each chunk once; bufio.Scanner rescans the whole
	// pending line on every read, which crawls with multi-MB snapshots.
	r := bufio.NewReaderSize(io.LimitReader(conn, maxPluginLine), 1<<20)
	needFull := false
	var readErr error
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			if err != io.EOF || len(line) > 0 {
				readErr = err
			}
			break
		}
		var m PluginMessage
		if err := json.Unmarshal(line, &m); err != nil {
			log.Printf("plugin: bad message: %v", err)
			continue
		}
		switch m.Type {
		case "snapshot":
			if !h.OnTabs(m.Tabs) {
				needFull = true
			}
		case "results":
			h.OnResults(m.Results)
		}
	}
	if readErr != nil {
		log.Printf("plugin: cut message: %v", readErr)
		needFull = true // be safe and ask for everything again
	}
	reply, _ := json.Marshal(PluginReply{Type: "commands", Commands: h.Pending(), NeedFull: needFull})
	conn.Write(append(reply, '\n')) // fails harmlessly for plugins that don't read
}

// LatestQueue hands snapshots to a single slow consumer, keeping only the
// newest one waiting: the plugin gets its answer at once instead of waiting
// for big tabs to be compressed and encrypted.
type LatestQueue struct {
	mu    sync.Mutex
	tabs  []proto.Tab
	ready chan struct{}
}

func NewLatestQueue() *LatestQueue { return &LatestQueue{ready: make(chan struct{}, 1)} }

func (q *LatestQueue) Put(tabs []proto.Tab) {
	q.mu.Lock()
	q.tabs = tabs
	q.mu.Unlock()
	select {
	case q.ready <- struct{}{}:
	default:
	}
}

// Take waits for the next snapshot; ok is false when ctx ends first.
func (q *LatestQueue) Take(ctx context.Context) ([]proto.Tab, bool) {
	for {
		select {
		case <-ctx.Done():
			return nil, false
		case <-q.ready:
		}
		q.mu.Lock()
		tabs := q.tabs
		q.tabs = nil
		q.mu.Unlock()
		if tabs != nil {
			return tabs, true
		}
	}
}
