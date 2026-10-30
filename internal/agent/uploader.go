package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/store"
	"github.com/coder/websocket"
)

// Uploader pushes pending snapshots to the relay and keeps a WebSocket open
// so the relay knows the Mac is online.
type Uploader struct {
	st        *store.Store
	serverURL string
	bearer    string
	client    *http.Client
	kick      chan struct{}

	// OnStale is called when the server already holds a newer seq than ours
	// (e.g. the local database was lost), with the server's latest seq.
	OnStale func(serverLatest int64)

	// Tunables, overridden in tests.
	minBackoff, maxBackoff, heartbeat, poll time.Duration
}

func NewUploader(st *store.Store, serverURL, bearer string) *Uploader {
	return &Uploader{
		st: st, serverURL: strings.TrimRight(serverURL, "/"), bearer: bearer,
		client:     &http.Client{Timeout: 60 * time.Second},
		kick:       make(chan struct{}, 1),
		minBackoff: time.Second, maxBackoff: 5 * time.Minute,
		heartbeat: 30 * time.Second, poll: time.Minute,
	}
}

// Kick asks for an upload as soon as possible.
func (u *Uploader) Kick() {
	select {
	case u.kick <- struct{}{}:
	default:
	}
}

func (u *Uploader) Run(ctx context.Context) {
	go u.presenceLoop(ctx)
	backoff := u.minBackoff
	for {
		err := u.pushPending(ctx)
		wait := u.poll
		if err != nil {
			log.Printf("upload: %v (retry in %s)", err, backoff)
			wait, backoff = backoff, min(backoff*2, u.maxBackoff)
		} else {
			backoff = u.minBackoff
		}
		select {
		case <-ctx.Done():
			return
		case <-u.kick:
			if err != nil { // honour the backoff even when kicked
				select {
				case <-ctx.Done():
					return
				case <-time.After(wait):
				}
			}
		case <-time.After(wait):
		}
	}
}

func (u *Uploader) pushPending(ctx context.Context) error {
	pending, err := u.st.PendingUploads()
	if err != nil {
		return err
	}
	for _, snap := range pending {
		for _, id := range snap.Blobs {
			if err := u.ensureBlob(ctx, id); err != nil {
				return err
			}
		}
		body, _ := json.Marshal(api.SnapshotJSON{Seq: snap.Seq, Envelope: snap.Envelope, Blobs: snap.Blobs})
		status, err := u.do(ctx, http.MethodPost, "/v1/snapshots", body)
		if err != nil {
			return err
		}
		if status == http.StatusConflict {
			latest, err := u.ServerLatest(ctx)
			if err != nil {
				return err
			}
			if u.OnStale != nil {
				u.OnStale(latest)
			}
		} else if status != http.StatusNoContent {
			return fmt.Errorf("post snapshot %d: HTTP %d", snap.Seq, status)
		}
		if err := u.st.MarkUploaded(snap.Seq); err != nil {
			return err
		}
	}
	return nil
}

// ServerLatest returns the newest seq the relay holds.
func (u *Uploader) ServerLatest(ctx context.Context) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.serverURL+"/v1/status", nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Authorization", "Bearer "+u.bearer)
	resp, err := u.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("status: HTTP %d", resp.StatusCode)
	}
	var st api.Status
	if err := json.NewDecoder(resp.Body).Decode(&st); err != nil {
		return 0, err
	}
	return st.LatestSeq, nil
}

func (u *Uploader) ensureBlob(ctx context.Context, id string) error {
	status, err := u.do(ctx, http.MethodHead, "/v1/blobs/"+id, nil)
	if err != nil {
		return err
	}
	if status == http.StatusOK {
		return nil
	}
	if status != http.StatusNotFound {
		return fmt.Errorf("head blob: HTTP %d", status)
	}
	env, err := u.st.GetBlob(id)
	if err != nil {
		return err
	}
	status, err = u.do(ctx, http.MethodPut, "/v1/blobs/"+id, env)
	if err != nil {
		return err
	}
	if status != http.StatusNoContent {
		return fmt.Errorf("put blob: HTTP %d", status)
	}
	return nil
}

func (u *Uploader) do(ctx context.Context, method, path string, body []byte) (int, error) {
	req, err := http.NewRequestWithContext(ctx, method, u.serverURL+path, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Authorization", "Bearer "+u.bearer)
	resp, err := u.client.Do(req)
	if err != nil {
		return 0, err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	return resp.StatusCode, nil
}

// presenceLoop keeps a role=mac WebSocket with periodic heartbeats,
// reconnecting with backoff.
func (u *Uploader) presenceLoop(ctx context.Context) {
	wsURL := "ws" + strings.TrimPrefix(u.serverURL, "http") + "/v1/ws?role=mac"
	backoff := u.minBackoff
	for ctx.Err() == nil {
		start := time.Now()
		err := u.presenceOnce(ctx, wsURL)
		if ctx.Err() != nil {
			return
		}
		if time.Since(start) > time.Minute {
			backoff = u.minBackoff
		}
		log.Printf("presence: %v (reconnect in %s)", err, backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, u.maxBackoff)
	}
}

func (u *Uploader) presenceOnce(ctx context.Context, wsURL string) error {
	dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	c, _, err := websocket.Dial(dctx, wsURL, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + u.bearer}},
	})
	cancel()
	if err != nil {
		return err
	}
	defer c.CloseNow()
	// Coming back online is a good moment to flush anything queued.
	u.Kick()
	ctx = c.CloseRead(ctx)
	hb, _ := json.Marshal(api.Event{Type: "heartbeat"})
	t := time.NewTicker(u.heartbeat)
	defer t.Stop()
	for {
		wctx, wc := context.WithTimeout(ctx, 10*time.Second)
		err := c.Write(wctx, websocket.MessageText, hb)
		if err == nil {
			// A ping that isn't answered in time means the connection is dead
			// even if writes still succeed (e.g. after a network change).
			err = c.Ping(wctx)
		}
		wc()
		if err != nil {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
	}
}
