package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/store"
	"github.com/coder/websocket"
)

type fixture struct {
	srv   *httptest.Server
	hub   *api.Hub
	keys  proto.Keys
	token string
}

func newFixture(t *testing.T, timeout time.Duration) *fixture {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "s.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	hub := api.NewHub(timeout, time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go hub.Run(ctx)
	srv := httptest.NewServer(New(st, "install-secret", hub))
	t.Cleanup(srv.Close)
	m, _ := proto.NewMasterKey()
	k := proto.Derive(m)
	return &fixture{srv: srv, hub: hub, keys: k, token: proto.TokenString(k.ServerAuth)}
}

func (f *fixture) do(t *testing.T, method, path, bearer string, hdr map[string]string, body []byte) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(method, f.srv.URL+path, bytes.NewReader(body))
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func (f *fixture) register(t *testing.T) {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"authHash": proto.TokenHash(f.keys.ServerAuth)})
	if r := f.do(t, "POST", "/v1/register", "", map[string]string{"X-Install-Token": "install-secret"}, body); r.StatusCode != 204 {
		t.Fatalf("register: %d", r.StatusCode)
	}
}

func (f *fixture) upload(t *testing.T, seq int64) {
	t.Helper()
	blob := strings.Repeat("ab", 16)
	if r := f.do(t, "PUT", "/v1/blobs/"+blob, f.token, nil, []byte("env")); r.StatusCode != 204 {
		t.Fatalf("put blob: %d", r.StatusCode)
	}
	b, _ := json.Marshal(api.SnapshotJSON{Seq: seq, Envelope: []byte("manifest"), Blobs: []string{blob}})
	if r := f.do(t, "POST", "/v1/snapshots", f.token, nil, b); r.StatusCode != 204 {
		t.Fatalf("post snapshot: %d", r.StatusCode)
	}
}

func TestRegisterOnce(t *testing.T) {
	f := newFixture(t, time.Minute)
	body, _ := json.Marshal(map[string]string{"authHash": strings.Repeat("0", 64)})
	if r := f.do(t, "POST", "/v1/register", "", map[string]string{"X-Install-Token": "wrong"}, body); r.StatusCode != 403 {
		t.Fatalf("wrong token: %d", r.StatusCode)
	}
	f.register(t)
	if r := f.do(t, "POST", "/v1/register", "", map[string]string{"X-Install-Token": "install-secret"}, body); r.StatusCode != 409 {
		t.Fatalf("second register: %d", r.StatusCode)
	}
}

func TestRotateWithCurrentToken(t *testing.T) {
	f := newFixture(t, time.Minute)
	f.register(t)
	m, _ := proto.NewMasterKey()
	nk := proto.Derive(m)
	body, _ := json.Marshal(map[string]string{"authHash": proto.TokenHash(nk.ServerAuth)})
	if r := f.do(t, "POST", "/v1/register", f.token, nil, body); r.StatusCode != 204 {
		t.Fatalf("rotate: %d", r.StatusCode)
	}
	if r := f.do(t, "GET", "/v1/status", f.token, nil, nil); r.StatusCode != 401 {
		t.Fatalf("old token still valid: %d", r.StatusCode)
	}
	if r := f.do(t, "GET", "/v1/status", proto.TokenString(nk.ServerAuth), nil, nil); r.StatusCode != 200 {
		t.Fatalf("new token rejected: %d", r.StatusCode)
	}
}

func TestUnauthorized401(t *testing.T) {
	f := newFixture(t, time.Minute)
	f.register(t)
	for _, p := range []struct{ m, path string }{
		{"GET", "/v1/snapshots/latest"}, {"GET", "/v1/snapshots"}, {"GET", "/v1/snapshots/1"},
		{"GET", "/v1/blobs/" + strings.Repeat("a", 32)}, {"PUT", "/v1/blobs/" + strings.Repeat("a", 32)},
		{"POST", "/v1/snapshots"}, {"GET", "/v1/status"}, {"GET", "/v1/ws?role=phone"},
	} {
		if r := f.do(t, p.m, p.path, "bad", nil, nil); r.StatusCode != 401 {
			t.Errorf("%s %s: %d", p.m, p.path, r.StatusCode)
		}
	}
	if r := f.do(t, "GET", "/health", "", nil, nil); r.StatusCode != 200 {
		t.Errorf("health: %d", r.StatusCode)
	}
}

func TestUploadThenReadLatest(t *testing.T) {
	f := newFixture(t, time.Minute)
	f.register(t)
	f.upload(t, 7)
	r := f.do(t, "GET", "/v1/snapshots/latest", f.token, nil, nil)
	var got api.SnapshotJSON
	json.NewDecoder(r.Body).Decode(&got)
	if got.Seq != 7 || string(got.Envelope) != "manifest" || got.At == 0 {
		t.Fatalf("%+v", got)
	}
	r = f.do(t, "GET", "/v1/blobs/"+strings.Repeat("ab", 16), f.token, nil, nil)
	if r.StatusCode != 200 {
		t.Fatal(r.StatusCode)
	}
}

func TestPostSnapshotStaleSeq409(t *testing.T) {
	f := newFixture(t, time.Minute)
	f.register(t)
	f.upload(t, 3)
	b, _ := json.Marshal(api.SnapshotJSON{Seq: 3, Envelope: []byte("m")})
	if r := f.do(t, "POST", "/v1/snapshots", f.token, nil, b); r.StatusCode != 409 {
		t.Fatal(r.StatusCode)
	}
}

func TestBodyLimit(t *testing.T) {
	f := newFixture(t, time.Minute)
	f.register(t)
	big := make([]byte, MaxBlobBytes+1)
	if r := f.do(t, "PUT", "/v1/blobs/"+strings.Repeat("c", 32), f.token, nil, big); r.StatusCode != 413 {
		t.Fatal(r.StatusCode)
	}
}

func dialWS(t *testing.T, f *fixture, role string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(f.srv.URL, "http")+"/v1/ws?role="+role,
		&websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + f.token}}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.CloseNow() })
	return c
}

func readEvent(t *testing.T, c *websocket.Conn) api.Event {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var e api.Event
	json.Unmarshal(b, &e)
	return e
}

func TestSnapshotNotifiesPhones(t *testing.T) {
	f := newFixture(t, time.Minute)
	f.register(t)
	phone := dialWS(t, f, "phone")
	if e := readEvent(t, phone); e.Type != "presence" {
		t.Fatalf("%+v", e)
	}
	f.upload(t, 9)
	for {
		e := readEvent(t, phone)
		if e.Type == "snapshot" {
			if e.Seq != 9 {
				t.Fatalf("%+v", e)
			}
			return
		}
	}
}

func TestMacOfflineAfterHeartbeatTimeout(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	f.register(t)
	phone := dialWS(t, f, "phone")
	readEvent(t, phone) // initial presence (offline)
	f.hub.MarkMacSeen()
	if e := readEvent(t, phone); e.Type != "presence" || !*e.MacOnline {
		t.Fatalf("want online, got %+v", e)
	}
	if e := readEvent(t, phone); e.Type != "presence" || *e.MacOnline {
		t.Fatalf("want offline, got %+v", e)
	}
	if online, _ := f.hub.MacOnline(); online {
		t.Fatal("still online")
	}
}
