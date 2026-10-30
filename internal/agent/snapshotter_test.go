package agent

import (
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
	got, truncated := truncateUTF8(text+"x", proto.MaxTabBytes, false)
	if !truncated || len(got) > proto.MaxTabBytes || len(got) < proto.MaxTabBytes-3 || !utf8.ValidString(got) {
		t.Fatalf("len=%d truncated=%v valid=%v", len(got), truncated, utf8.ValidString(got))
	}
	odd := "a" + text // forces a cut in the middle of a rune
	got, _ = truncateUTF8(odd, proto.MaxTabBytes, false)
	if !utf8.ValidString(got) {
		t.Fatal("cut inside a rune")
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
