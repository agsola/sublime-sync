// Package api serves the read side of the protocol. Both the relay server and
// the Mac agent (over Tailscale) expose it, so the phone talks to both alike.
package api

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/agsola/sublime-sync/internal/store"
)

// Auth decides whether a request carries a valid credential.
type Auth func(*http.Request) bool

// BearerAuth accepts "Authorization: Bearer <token>" whose hash equals
// tokenHash(). An empty hash rejects everything.
func BearerAuth(tokenHash func() string) Auth {
	return func(r *http.Request) bool {
		want := tokenHash()
		bearer, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if want == "" || !ok || bearer == "" {
			return false
		}
		return subtle.ConstantTimeCompare([]byte(proto.HashBearer(bearer)), []byte(want)) == 1
	}
}

// SnapshotJSON is how a snapshot travels over HTTP.
type SnapshotJSON struct {
	Seq      int64    `json:"seq"`
	At       int64    `json:"at,omitempty"`
	Envelope []byte   `json:"envelope"` // base64 in JSON
	Blobs    []string `json:"blobs,omitempty"`
}

type ListItem struct {
	Seq int64 `json:"seq"`
	At  int64 `json:"at"`
}

type Status struct {
	MacOnline   bool  `json:"macOnline"`
	MacLastSeen int64 `json:"macLastSeen"`
	LatestSeq   int64 `json:"latestSeq"`
}

var blobIDRe = regexp.MustCompile(`^[0-9a-f]{32}$`)

func ValidBlobID(id string) bool { return blobIDRe.MatchString(id) }

// ReadRoutes registers the read-only endpoints shared by server and agent.
func ReadRoutes(mux *http.ServeMux, st *store.Store, auth Auth, hub *Hub) {
	guard := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if !auth(r) {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			h(w, r)
		}
	}
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	mux.HandleFunc("GET /v1/snapshots/latest", guard(func(w http.ResponseWriter, r *http.Request) {
		snap, err := st.Latest()
		writeSnapshot(w, snap, err)
	}))
	mux.HandleFunc("GET /v1/snapshots/{seq}", guard(func(w http.ResponseWriter, r *http.Request) {
		seq, err := strconv.ParseInt(r.PathValue("seq"), 10, 64)
		if err != nil {
			http.Error(w, "bad seq", http.StatusBadRequest)
			return
		}
		snap, err := st.GetSnapshot(seq)
		writeSnapshot(w, snap, err)
	}))
	mux.HandleFunc("GET /v1/snapshots", guard(func(w http.ResponseWriter, r *http.Request) {
		before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > 500 {
			limit = 500
		}
		snaps, err := st.List(before, limit)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		items := make([]ListItem, 0, len(snaps))
		for _, s := range snaps {
			items = append(items, ListItem{Seq: s.Seq, At: s.At})
		}
		WriteJSON(w, items)
	}))
	mux.HandleFunc("GET /v1/blobs/{id}", guard(func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if !ValidBlobID(id) {
			http.Error(w, "bad id", http.StatusBadRequest)
			return
		}
		env, err := st.GetBlob(id)
		if errors.Is(err, store.ErrNotFound) {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
		w.Write(env)
	}))
	mux.HandleFunc("GET /v1/status", guard(func(w http.ResponseWriter, r *http.Request) {
		online, seen := hub.MacOnline()
		latest, _ := st.MaxSeq()
		WriteJSON(w, Status{MacOnline: online, MacLastSeen: seen, LatestSeq: latest})
	}))
	mux.HandleFunc("GET /v1/ws", guard(hub.ServeWS))
}

func writeSnapshot(w http.ResponseWriter, snap store.Snapshot, err error) {
	if errors.Is(err, store.ErrNotFound) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	WriteJSON(w, SnapshotJSON{Seq: snap.Seq, At: snap.At, Envelope: snap.Envelope})
}

func WriteJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}
