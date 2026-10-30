// Package agent is the Mac side: it receives tab snapshots from the Sublime
// plugin (or the session file), seals them and ships them to the relay while
// serving them to the phone over Tailscale.
package agent

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/store"
)

const (
	metaSeq      = "seq"
	metaModTimes = "modtimes"
	maxModTimes  = 2000 // most recently modified tabs remembered
)

// modTime remembers when a tab's text last changed.
type modTime struct {
	Hash     string `json:"h"`
	Modified int64  `json:"m"`
}

// Snapshotter turns tab lists into sealed blobs and manifests in the local store.
type Snapshotter struct {
	st     *store.Store
	keys   proto.Keys
	notify func(seq int64)

	mu       sync.Mutex
	last     []proto.TabRef
	modTimes map[string]modTime
	// fitted maps the blob id of an oversized tab to the id of its cut version,
	// so it isn't compressed and cut again on every snapshot.
	fitted map[string]string
}

func NewSnapshotter(st *store.Store, k proto.Keys, notify func(seq int64)) *Snapshotter {
	return &Snapshotter{st: st, keys: k, notify: notify}
}

// Ingest stores a new snapshot if the tabs changed. Empty tab lists are
// ignored: they happen when Sublime quits, and the phone should keep showing
// the last tabs rather than nothing.
func (s *Snapshotter) Ingest(tabs []proto.Tab, source string, now time.Time) (seq int64, changed bool, err error) {
	if len(tabs) == 0 {
		return 0, false, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	s.stampModified(tabs, now)
	refs := make([]proto.TabRef, 0, len(tabs))
	blobs := make([]string, 0, len(tabs))
	seen := map[string]bool{}
	for _, t := range tabs {
		t.Text, t.Truncated = truncateUTF8(t.Text, proto.MaxTabBytes, t.Truncated)
		plain, err := json.Marshal(t)
		if err != nil {
			return 0, false, err
		}
		id := proto.BlobID(s.keys, plain)
		if cut, ok := s.fitted[id]; ok {
			id = cut
		} else if has, err := s.st.HasBlob(id); err != nil {
			return 0, false, err
		} else if !has {
			orig := id
			var env []byte
			t, plain, id, env, err = s.sealFitting(t, plain)
			if err != nil {
				return 0, false, err
			}
			if err := s.st.PutBlob(id, env); err != nil {
				return 0, false, err
			}
			if id != orig {
				if s.fitted == nil {
					s.fitted = map[string]string{}
				}
				s.fitted[orig] = id
			}
		}
		refs = append(refs, proto.TabRef{Blob: id, Window: t.Window, Index: t.Index})
		if seen[id] {
			continue
		}
		seen[id] = true
		blobs = append(blobs, id)
	}
	if s.last == nil {
		s.last = s.loadLastRefs()
	}
	if sameRefs(refs, s.last) {
		return 0, false, nil
	}

	seq, err = s.nextSeq()
	if err != nil {
		return 0, false, err
	}
	m := proto.Manifest{Seq: seq, Created: now.UnixMilli(), Source: source, Tabs: refs}
	plain, _ := json.Marshal(m)
	env, err := proto.Seal(s.keys, proto.ManifestAAD(seq), plain)
	if err != nil {
		return 0, false, err
	}
	if err := s.st.AddSnapshot(store.Snapshot{Seq: seq, At: now.UnixMilli(), Envelope: env, Blobs: blobs}); err != nil {
		return 0, false, err
	}
	if err := s.st.SetMeta(metaSeq, strconv.FormatInt(seq, 10)); err != nil {
		return 0, false, err
	}
	s.last = refs
	if s.notify != nil {
		s.notify(seq)
	}
	return seq, true, nil
}

// stampModified sets Tab.Modified. Saved files keep the mtime the plugin sent;
// other tabs keep their previous time while the text is unchanged, and get
// now when it changed. Times are persisted so restarts don't reset them.
func (s *Snapshotter) stampModified(tabs []proto.Tab, now time.Time) {
	if s.modTimes == nil {
		s.modTimes = map[string]modTime{}
		if v, err := s.st.GetMeta(metaModTimes); err == nil {
			json.Unmarshal([]byte(v), &s.modTimes)
		}
	}
	// Entries of tabs not in this snapshot are kept (e.g. the session-file
	// fallback names tabs differently); the map is capped below.
	next := make(map[string]modTime, len(s.modTimes)+len(tabs))
	for k, v := range s.modTimes {
		next[k] = v
	}
	for i := range tabs {
		t := &tabs[i]
		key := "v:" + strconv.FormatInt(t.ID, 10)
		if t.Path != nil && *t.Path != "" {
			key = "p:" + *t.Path
		}
		sum := sha256.Sum256([]byte(t.Text))
		h := hex.EncodeToString(sum[:16])
		switch prev, ok := s.modTimes[key]; {
		case t.Modified > 0 && !t.Dirty:
			// mtime from disk, as sent by the plugin
		case ok && prev.Hash == h:
			t.Modified = prev.Modified
		default:
			t.Modified = now.UnixMilli()
		}
		next[key] = modTime{Hash: h, Modified: t.Modified}
	}
	if len(next) > maxModTimes {
		keep := make([]string, 0, len(next))
		for k := range next {
			keep = append(keep, k)
		}
		sort.Slice(keep, func(i, j int) bool { return next[keep[i]].Modified > next[keep[j]].Modified })
		for _, k := range keep[maxModTimes:] {
			delete(next, k)
		}
	}
	s.modTimes = next
	if b, err := json.Marshal(next); err == nil {
		s.st.SetMeta(metaModTimes, string(b))
	}
}

// SeedSeq makes the next snapshot's seq greater than n (used when the relay
// is ahead of us, e.g. after the local database was lost).
func (s *Snapshotter) SeedSeq(n int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next, err := s.nextSeq()
	if err != nil || next > n {
		return err
	}
	return s.st.SetMeta(metaSeq, strconv.FormatInt(n, 10))
}

// ForceNext makes the next non-empty Ingest store a snapshot even if the tabs
// did not change.
func (s *Snapshotter) ForceNext() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.last = []proto.TabRef{}
}

// sealFitting seals a tab, cutting its text until the sealed blob fits the
// relay's per-blob limit and its JSON fits what readers will decompress.
func (s *Snapshotter) sealFitting(t proto.Tab, plain []byte) (proto.Tab, []byte, string, []byte, error) {
	for {
		id := proto.BlobID(s.keys, plain)
		env, err := proto.Seal(s.keys, proto.BlobAAD(id), plain)
		if err != nil {
			return t, nil, "", nil, err
		}
		if len(env) <= proto.MaxBlobEnvelope && len(plain) <= proto.MaxPlaintext {
			return t, plain, id, env, nil
		}
		ratio := min(float64(proto.MaxBlobEnvelope)/float64(len(env)), float64(proto.MaxPlaintext)/float64(len(plain))) * 0.95
		t.Text, t.Truncated = truncateUTF8(t.Text, int(float64(len(t.Text))*ratio), true)
		if plain, err = json.Marshal(t); err != nil {
			return t, nil, "", nil, err
		}
	}
}

// nextSeq never goes backwards, even if the clock does or snapshots were pruned.
func (s *Snapshotter) nextSeq() (int64, error) {
	var metaVal int64
	if v, err := s.st.GetMeta(metaSeq); err == nil {
		metaVal, _ = strconv.ParseInt(v, 10, 64)
	} else if !errors.Is(err, store.ErrNotFound) {
		return 0, err
	}
	maxSeq, err := s.st.MaxSeq()
	if err != nil {
		return 0, err
	}
	return max(metaVal, maxSeq) + 1, nil
}

func (s *Snapshotter) loadLastRefs() []proto.TabRef {
	snap, err := s.st.Latest()
	if err != nil {
		return []proto.TabRef{}
	}
	plain, err := proto.Open(map[string]proto.Keys{s.keys.ID: s.keys}, proto.ManifestAAD(snap.Seq), snap.Envelope)
	if err != nil {
		return []proto.TabRef{}
	}
	var m proto.Manifest
	if json.Unmarshal(plain, &m) != nil {
		return []proto.TabRef{}
	}
	return m.Tabs
}

func sameRefs(a, b []proto.TabRef) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// truncateUTF8 cuts s to at most n bytes without splitting a rune.
func truncateUTF8(s string, n int, already bool) (string, bool) {
	if len(s) <= n {
		return s, already
	}
	cut := n
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut], true
}
