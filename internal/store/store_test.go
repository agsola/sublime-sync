package store

import (
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func add(t *testing.T, s *Store, seq, at int64, uploaded bool, blobs ...string) {
	t.Helper()
	for _, b := range blobs {
		if err := s.PutBlob(b, []byte("env-"+b)); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.AddSnapshot(Snapshot{Seq: seq, At: at, Envelope: []byte("m"), Blobs: blobs, Uploaded: uploaded}); err != nil {
		t.Fatal(err)
	}
}

func TestAddAndLatest(t *testing.T) {
	s := openTest(t)
	add(t, s, 1, 100, true, "a")
	add(t, s, 2, 200, true, "a", "b")
	got, err := s.Latest()
	if err != nil || got.Seq != 2 || len(got.Blobs) != 2 {
		t.Fatalf("%+v %v", got, err)
	}
}

func TestStaleSeqRejected(t *testing.T) {
	s := openTest(t)
	add(t, s, 5, 100, true)
	if err := s.AddSnapshot(Snapshot{Seq: 5, Envelope: []byte("x")}); !errors.Is(err, ErrStaleSeq) {
		t.Fatalf("want ErrStaleSeq, got %v", err)
	}
}

func TestMissingBlobRejected(t *testing.T) {
	s := openTest(t)
	err := s.AddSnapshot(Snapshot{Seq: 1, Envelope: []byte("x"), Blobs: []string{"nope"}})
	if !IsMissingBlob(err) {
		t.Fatalf("want missing blob, got %v", err)
	}
}

func TestListPagination(t *testing.T) {
	s := openTest(t)
	for i := int64(1); i <= 10; i++ {
		add(t, s, i, i, true)
	}
	page, _ := s.List(0, 3)
	if len(page) != 3 || page[0].Seq != 10 || page[2].Seq != 8 {
		t.Fatalf("%+v", page)
	}
	page, _ = s.List(8, 100)
	if len(page) != 7 || page[0].Seq != 7 {
		t.Fatalf("%+v", page)
	}
}

func TestPruneTimeMachine(t *testing.T) {
	s := openTest(t)
	now := time.Date(2026, 10, 30, 12, 0, 0, 0, time.UTC)
	start := now.Add(-40 * 24 * time.Hour)
	var seq int64
	for at := start; !at.After(now); at = at.Add(time.Minute) {
		seq++
		add(t, s, seq, at.UnixMilli(), true, fmt.Sprintf("b%d", seq%7))
	}
	if _, _, err := s.Prune(now); err != nil {
		t.Fatal(err)
	}
	kept, _ := s.List(0, 1<<30)
	counts := map[string]int{}
	for _, k := range kept {
		counts[bucket(now.UnixMilli(), k.At)]++
	}
	for b, n := range counts {
		if n != 1 {
			t.Fatalf("bucket %s has %d snapshots", b, n)
		}
	}
	if kept[0].Seq != seq {
		t.Fatal("latest pruned")
	}
	// Each kept snapshot must be the newest of its bucket.
	for _, k := range kept {
		if k.Seq == seq {
			continue
		}
		next := k.At + time.Minute.Milliseconds()
		if bucket(now.UnixMilli(), next) == bucket(now.UnixMilli(), k.At) {
			t.Fatalf("kept snapshot %d is not the newest of its bucket", k.Seq)
		}
	}
	// 24h of 5-min buckets (~288) + 29 days of hours (~696) + ~10 days.
	if len(kept) < 950 || len(kept) > 1010 {
		t.Fatalf("unexpected kept count %d", len(kept))
	}
}

func TestPruneGCsUnreferencedBlobs(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	add(t, s, 1, now.Add(-time.Minute*2).UnixMilli(), true, "old")
	add(t, s, 2, now.Add(-time.Minute).UnixMilli(), true, "new")
	_, blobs, err := s.Prune(now)
	if err != nil || blobs != 1 {
		t.Fatalf("removed %d blobs, %v", blobs, err)
	}
	if ok, _ := s.HasBlob("old"); ok {
		t.Fatal("old blob kept")
	}
}

func TestPruneKeepsUnuploaded(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	add(t, s, 1, now.Add(-2*time.Second).UnixMilli(), false)
	add(t, s, 2, now.Add(-time.Second).UnixMilli(), true)
	if n, _, _ := s.Prune(now); n != 0 {
		t.Fatalf("pruned %d", n)
	}
}

func TestMeta(t *testing.T) {
	s := openTest(t)
	if _, err := s.GetMeta("x"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	s.SetMeta("x", "1")
	s.SetMeta("x", "2")
	if v, _ := s.GetMeta("x"); v != "2" {
		t.Fatal(v)
	}
}
