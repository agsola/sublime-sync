package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Event is what travels over /v1/ws.
type Event struct {
	Type      string `json:"type"` // "snapshot", "presence", "heartbeat", "command", "result"
	Seq       int64  `json:"seq,omitempty"`
	ID        string `json:"id,omitempty"`
	MacOnline *bool  `json:"macOnline,omitempty"`
}

// Hub fans snapshot notifications out to phones and tracks Mac presence.
type Hub struct {
	timeout      time.Duration
	pingInterval time.Duration
	alwaysOnline bool

	mu       sync.Mutex
	phones   map[chan Event]struct{}
	macs     map[chan Event]struct{}
	lastSeen time.Time
	online   bool
}

// NewHub creates a hub; the Mac counts as online for heartbeatTimeout after
// it was last seen.
func NewHub(heartbeatTimeout, pingInterval time.Duration) *Hub {
	return &Hub{timeout: heartbeatTimeout, pingInterval: pingInterval, phones: map[chan Event]struct{}{}, macs: map[chan Event]struct{}{}}
}

// NewSelfHub is used by the agent, which is the Mac and therefore always online.
func NewSelfHub(pingInterval time.Duration) *Hub {
	h := NewHub(time.Hour, pingInterval)
	h.alwaysOnline = true
	return h
}

// Run watches for the Mac going offline until ctx ends.
func (h *Hub) Run(ctx context.Context) {
	t := time.NewTicker(h.timeout / 4)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			h.mu.Lock()
			if h.online && time.Since(h.lastSeen) > h.timeout {
				h.online = false
				h.broadcastLocked(presence(false))
			}
			h.mu.Unlock()
		}
	}
}

func presence(online bool) Event { return Event{Type: "presence", MacOnline: &online} }

func (h *Hub) MarkMacSeen() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lastSeen = time.Now()
	if !h.online {
		h.online = true
		h.broadcastLocked(presence(true))
	}
}

func (h *Hub) MacOnline() (bool, int64) {
	if h.alwaysOnline {
		return true, time.Now().UnixMilli()
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	var seen int64
	if !h.lastSeen.IsZero() {
		seen = h.lastSeen.UnixMilli()
	}
	return h.online && time.Since(h.lastSeen) <= h.timeout, seen
}

func (h *Hub) NotifySnapshot(seq int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.broadcastLocked(Event{Type: "snapshot", Seq: seq})
}

// NotifyCommand tells connected Macs that a phone queued a command.
func (h *Hub) NotifyCommand() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.macs {
		select {
		case ch <- Event{Type: "command"}:
		default:
		}
	}
}

// NotifyResult tells phones that the Mac answered a command.
func (h *Hub) NotifyResult(id string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.broadcastLocked(Event{Type: "result", ID: id})
}

func (h *Hub) broadcastLocked(e Event) {
	for ch := range h.phones {
		select {
		case ch <- e:
		default: // slow client: drop; it refetches on reconnect anyway
		}
	}
}

// ServeWS handles /v1/ws?role=mac|phone (already authenticated).
func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request) {
	role := r.URL.Query().Get("role")
	if role != "mac" && role != "phone" {
		http.Error(w, "bad role", http.StatusBadRequest)
		return
	}
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer c.CloseNow()
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	go func() { // keepalive pings, under Cloudflare's 100 s idle limit
		t := time.NewTicker(h.pingInterval)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				pctx, pc := context.WithTimeout(ctx, h.pingInterval)
				err := c.Ping(pctx)
				pc()
				if err != nil {
					cancel()
					return
				}
			}
		}
	}()

	ch := make(chan Event, 16)
	set := h.phones
	if role == "mac" {
		set = h.macs
	}
	h.mu.Lock()
	set[ch] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(set, ch)
		h.mu.Unlock()
	}()
	if role == "mac" {
		h.MarkMacSeen()
	} else {
		online, _ := h.MacOnline()
		ch <- presence(online)
	}
	go func() { // phones only read to notice the close; the Mac sends heartbeats
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				cancel()
				return
			}
			var e Event
			if role == "mac" && json.Unmarshal(data, &e) == nil && e.Type == "heartbeat" {
				h.MarkMacSeen()
			}
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case e := <-ch:
			b, _ := json.Marshal(e)
			wctx, wc := context.WithTimeout(ctx, 10*time.Second)
			err := c.Write(wctx, websocket.MessageText, b)
			wc()
			if err != nil {
				return
			}
		}
	}
}
