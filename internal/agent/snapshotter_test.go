package agent

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/store"
)

func testKeys(t *testing.T) proto.Keys {
	t.Helper()
	m, _ := proto.NewMasterKey()
	return proto.Derive(m)
}

func openStore(t *testing.T, path string) *store.Store {
	t.Helper()
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

func tabs(texts ...string) []proto.Tab {
	out := make([]proto.Tab, len(texts))
	for i, s := range texts {
		out[i] = proto.Tab{ID: int64(i + 1), Window: 1, Index: i, Title: "t", Text: s, Dirty: true}
	}
	return out
}

func TestIngestCreatesSnapshot(t *testing.T) {
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	var notified int64
	s := NewSnapshotter(st, testKeys(t), func(seq int64) { notified = seq })
	seq, changed, err := s.Ingest(tabs("a", "b"), proto.SourcePlugin, time.Now())
	if err != nil || !changed || seq != 1 || notified != 1 {
		t.Fatalf("seq=%d changed=%v err=%v notified=%d", seq, changed, err, notified)
	}
	snap, _ := st.Latest()
	if len(snap.Blobs) != 2 || snap.Uploaded {
		t.Fatalf("%+v", snap)
	}
}

func TestUnchangedIsNoop(t *testing.T) {
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	s := NewSnapshotter(st, testKeys(t), nil)
	s.Ingest(tabs("a"), proto.SourcePlugin, time.Now())
	if _, changed, _ := s.Ingest(tabs("a"), proto.SourcePlugin, time.Now()); changed {
		t.Fatal("unchanged tabs produced a snapshot")
	}
}

func TestIgnoresEmptySnapshot(t *testing.T) {
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	s := NewSnapshotter(st, testKeys(t), nil)
	s.Ingest(tabs("a"), proto.SourcePlugin, time.Now())
	if _, changed, _ := s.Ingest(nil, proto.SourcePlugin, time.Now()); changed {
		t.Fatal("empty snapshot stored")
	}
	if n, _ := st.MaxSeq(); n != 1 {
		t.Fatal(n)
	}
}

func TestTruncatesLargeTab(t *testing.T) {
	text := strings.Repeat("ñ", 3*1024*1024) // 6 MiB of 2-byte runes
	limit := 5 << 20
	got, truncated := truncateUTF8(text+"x", limit, false)
	if !truncated || len(got) > limit || len(got) < limit-3 || !utf8.ValidString(got) {
		t.Fatalf("len=%d truncated=%v valid=%v", len(got), truncated, utf8.ValidString(got))
	}
	odd := "a" + text // forces a cut in the middle of a rune
	got, _ = truncateUTF8(odd, limit, false)
	if !utf8.ValidString(got) {
		t.Fatal("cut inside a rune")
	}
}

func TestFitsCompressedLimit(t *testing.T) {
	if testing.Short() {
		t.Skip("large texts")
	}
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	s := NewSnapshotter(st, testKeys(t), nil)
	// 30 MB of log-like text compresses far below the server limit: kept whole.
	logText := strings.Repeat("2026-10-30 12:00:01 INFO request handled in 12 ms ñ\n", 30<<20/52)
	// 30 MB of random base64 barely compresses: must be cut to fit.
	rnd := make([]byte, 23<<20)
	rand.Read(rnd)
	noisy := base64.StdEncoding.EncodeToString(rnd)
	in := tabs(logText, noisy)
	if _, _, err := s.Ingest(in, proto.SourcePlugin, time.Now()); err != nil {
		t.Fatal(err)
	}
	snap, _ := st.Latest()
	k := s.keys
	for _, id := range snap.Blobs {
		env, _ := st.GetBlob(id)
		if len(env) > proto.MaxBlobEnvelope {
			t.Fatalf("blob %d bytes > limit", len(env))
		}
		plain, err := proto.Open(map[string]proto.Keys{k.ID: k}, proto.BlobAAD(id), env)
		if err != nil {
			t.Fatal(err)
		}
		var tab proto.Tab
		json.Unmarshal(plain, &tab)
		switch tab.ID {
		case 1:
			if tab.Truncated || len(tab.Text) != len(logText) {
				t.Fatalf("compressible tab cut: %d of %d", len(tab.Text), len(logText))
			}
		case 2:
			if !tab.Truncated || !utf8.ValidString(tab.Text) || len(tab.Text) < 10<<20 {
				t.Fatalf("noisy tab: truncated=%v len=%d", tab.Truncated, len(tab.Text))
			}
		}
	}
}

func TestDedupesBlobs(t *testing.T) {
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	s := NewSnapshotter(st, testKeys(t), nil)
	s.Ingest(tabs("a", "b", "c"), proto.SourcePlugin, time.Now())
	s.Ingest(tabs("a", "B", "c"), proto.SourcePlugin, time.Now())
	first, _ := st.GetSnapshot(1)
	second, _ := st.GetSnapshot(2)
	shared := 0
	for _, a := range first.Blobs {
		for _, b := range second.Blobs {
			if a == b {
				shared++
			}
		}
	}
	if shared != 2 {
		t.Fatalf("shared %d blobs, want 2", shared)
	}
}

func TestSeqPersistsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a.db")
	k := testKeys(t)
	st := openStore(t, path)
	s := NewSnapshotter(st, k, nil)
	s.Ingest(tabs("a"), proto.SourcePlugin, time.Now())
	s.Ingest(tabs("b"), proto.SourcePlugin, time.Now())
	st.Close()

	st2 := openStore(t, path)
	s2 := NewSnapshotter(st2, k, nil)
	if _, changed, _ := s2.Ingest(tabs("b"), proto.SourcePlugin, time.Now()); changed {
		t.Fatal("same tabs after restart produced a snapshot")
	}
	seq, _, _ := s2.Ingest(tabs("c"), proto.SourcePlugin, time.Now().Add(-time.Hour)) // clock went back
	if seq != 3 {
		t.Fatalf("seq %d, want 3", seq)
	}
}

func TestModifiedTracksTextChanges(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a.db")
	k := testKeys(t)
	st := openStore(t, path)
	s := NewSnapshotter(st, k, nil)
	t0 := time.Date(2026, 10, 30, 10, 0, 0, 0, time.UTC)
	file := "/x/notes.md"
	in := func(texts ...string) []proto.Tab {
		ts := tabs(texts...)
		ts[0].Path, ts[0].Dirty, ts[0].Modified = &file, false, 1234 // saved file with mtime
		return ts
	}
	s.Ingest(in("saved", "a", "b"), proto.SourcePlugin, t0)
	s.Ingest(in("saved", "a", "B"), proto.SourcePlugin, t0.Add(time.Hour))
	st.Close()

	st2 := openStore(t, path) // restart keeps the times
	s2 := NewSnapshotter(st2, k, nil)
	got := in("saved", "a", "B")
	s2.stampModified(got, t0.Add(2*time.Hour))
	if got[0].Modified != 1234 {
		t.Fatalf("file mtime not kept: %d", got[0].Modified)
	}
	if got[1].Modified != t0.UnixMilli() {
		t.Fatalf("unchanged tab got %d", got[1].Modified)
	}
	if got[2].Modified != t0.Add(time.Hour).UnixMilli() {
		t.Fatalf("changed tab got %d", got[2].Modified)
	}
}

func TestModifiedSurvivesSessionFallback(t *testing.T) {
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	s := NewSnapshotter(st, testKeys(t), nil)
	t0 := time.Date(2026, 10, 30, 10, 0, 0, 0, time.UTC)
	s.Ingest(tabs("a", "b"), proto.SourcePlugin, t0)
	// The session-file fallback reports the same tabs under other ids.
	other := tabs("a", "b")
	for i := range other {
		other[i].ID += 4000
	}
	s.Ingest(other, proto.SourceSession, t0.Add(time.Hour))
	// Back to the plugin: the original times must still be known.
	back := tabs("a", "b")
	s.stampModified(back, t0.Add(2*time.Hour))
	if back[0].Modified != t0.UnixMilli() {
		t.Fatalf("modified reset to %d", back[0].Modified)
	}
}

func TestModifiedSurvivesNewViewIDs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a.db")
	k := testKeys(t)
	st := openStore(t, path)
	s := NewSnapshotter(st, k, nil)
	t0 := time.Date(2026, 10, 30, 10, 0, 0, 0, time.UTC)
	s.Ingest(tabs("a", "b", "c"), proto.SourcePlugin, t0)
	st.Close()

	// After a reboot Sublime restores the unsaved tabs under new view ids,
	// in another order.
	s2 := NewSnapshotter(openStore(t, path), k, nil)
	got := tabs("c", "a", "b")
	for i := range got {
		got[i].ID += 100
	}
	s2.stampModified(got, t0.Add(24*time.Hour))
	for i, tb := range got {
		if tb.Modified != t0.UnixMilli() {
			t.Fatalf("tab %d got %d, want the original time", i, tb.Modified)
		}
	}
}
