package agent

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/server"
	"github.com/agsola/sublime-sync/internal/store"
)

type relay struct {
	url   string
	st    *store.Store
	keys  proto.Keys
	fails atomic.Int32 // number of requests to fail with 503
	heads atomic.Int32
	puts  atomic.Int32
}

func newRelay(t *testing.T) *relay {
	t.Helper()
	st := openStore(t, filepath.Join(t.TempDir(), "server.db"))
	hub := api.NewHub(time.Minute, time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go hub.Run(ctx)
	k := testKeys(t)
	st.SetMeta("auth_hash", proto.TokenHash(k.ServerAuth))
	r := &relay{st: st, keys: k}
	inner := server.New(st, "", hub)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if r.fails.Load() > 0 {
			r.fails.Add(-1)
			http.Error(w, "down", http.StatusServiceUnavailable)
			return
		}
		switch req.Method {
		case http.MethodHead:
			r.heads.Add(1)
		case http.MethodPut:
			r.puts.Add(1)
		}
		inner.ServeHTTP(w, req)
	}))
	t.Cleanup(srv.Close)
	r.url = srv.URL
	return r
}

func fastUploader(st *store.Store, r *relay) *Uploader {
	u := NewUploader(st, r.url, proto.TokenString(r.keys.ServerAuth))
	u.minBackoff, u.maxBackoff, u.heartbeat, u.poll = 10*time.Millisecond, 50*time.Millisecond, 50*time.Millisecond, 20*time.Millisecond
	return u
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("timeout")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestUploaderPushesPending(t *testing.T) {
	r := newRelay(t)
	local := openStore(t, filepath.Join(t.TempDir(), "agent.db"))
	s := NewSnapshotter(local, r.keys, nil)
	s.Ingest(tabs("a", "b"), proto.SourcePlugin, time.Now())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go fastUploader(local, r).Run(ctx)
	waitFor(t, func() bool { n, _ := r.st.MaxSeq(); return n == 1 })
	waitFor(t, func() bool { p, _ := local.PendingUploads(); return len(p) == 0 })
	snap, _ := r.st.Latest()
	if len(snap.Blobs) != 2 {
		t.Fatalf("%+v", snap)
	}
}

func TestUploaderRetriesAfterServerDown(t *testing.T) {
	r := newRelay(t)
	r.fails.Store(2)
	local := openStore(t, filepath.Join(t.TempDir(), "agent.db"))
	NewSnapshotter(local, r.keys, nil).Ingest(tabs("a"), proto.SourcePlugin, time.Now())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go fastUploader(local, r).Run(ctx)
	waitFor(t, func() bool { n, _ := r.st.MaxSeq(); return n == 1 })
}

func TestUploaderSkipsExistingBlobs(t *testing.T) {
	r := newRelay(t)
	local := openStore(t, filepath.Join(t.TempDir(), "agent.db"))
	s := NewSnapshotter(local, r.keys, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	u := fastUploader(local, r)
	go u.Run(ctx)
	s.Ingest(tabs("a", "b", "c"), proto.SourcePlugin, time.Now())
	u.Kick()
	waitFor(t, func() bool { n, _ := r.st.MaxSeq(); return n == 1 })
	before := r.puts.Load()
	s.Ingest(tabs("a", "b", "C"), proto.SourcePlugin, time.Now())
	u.Kick()
	waitFor(t, func() bool { n, _ := r.st.MaxSeq(); return n == 2 })
	if got := r.puts.Load() - before; got != 1 {
		t.Fatalf("uploaded %d blobs, want 1", got)
	}
}

func TestUploaderMarksMacOnline(t *testing.T) {
	r := newRelay(t)
	local := openStore(t, filepath.Join(t.TempDir(), "agent.db"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go fastUploader(local, r).Run(ctx)
	waitFor(t, func() bool {
		req, _ := http.NewRequest("GET", r.url+"/v1/status", nil)
		req.Header.Set("Authorization", "Bearer "+proto.TokenString(r.keys.ServerAuth))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return false
		}
		defer resp.Body.Close()
		var st api.Status
		json.NewDecoder(resp.Body).Decode(&st)
		return st.MacOnline
	})
}

func TestTailnetAddrFilter(t *testing.T) {
	addrs := []netip.Addr{
		netip.MustParseAddr("192.168.1.5"),
		netip.MustParseAddr("100.63.0.1"),
		netip.MustParseAddr("fd7a:115c:a1e0::1"),
		netip.MustParseAddr("100.101.1.2"),
	}
	got, ok := pickTailnet(addrs)
	if !ok || got.String() != "100.101.1.2" {
		t.Fatalf("%v %v", got, ok)
	}
	if _, ok := pickTailnet(addrs[:3]); ok {
		t.Fatal("picked a non-tailnet address")
	}
}

func TestUploaderHandlesServerAhead(t *testing.T) {
	r := newRelay(t)
	// The relay already holds seq 5 (e.g. the Mac's database was lost).
	r.st.PutBlob(strings.Repeat("ab", 16), []byte("x"))
	r.st.AddSnapshot(store.Snapshot{Seq: 5, Envelope: []byte("m"), Blobs: []string{strings.Repeat("ab", 16)}, Uploaded: true})

	local := openStore(t, filepath.Join(t.TempDir(), "agent.db"))
	s := NewSnapshotter(local, r.keys, nil)
	u := fastUploader(local, r)
	stale := make(chan int64, 1)
	u.OnStale = func(n int64) {
		s.SeedSeq(n)
		s.ForceNext()
		stale <- n
	}
	s.Ingest(tabs("a"), proto.SourcePlugin, time.Now())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go u.Run(ctx)
	select {
	case n := <-stale:
		if n != 5 {
			t.Fatalf("stale at %d", n)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("OnStale not called")
	}
	seq, changed, _ := s.Ingest(tabs("a"), proto.SourcePlugin, time.Now())
	if !changed || seq != 6 {
		t.Fatalf("seq=%d changed=%v, want 6 true", seq, changed)
	}
	u.Kick()
	waitFor(t, func() bool { n, _ := r.st.MaxSeq(); return n == 6 })
}
