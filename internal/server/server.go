// Package server is the relay: it accepts encrypted uploads from the Mac and
// serves them, plus the history, to phones.
package server

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/store"
)

const (
	MaxBlobBytes     = 16 << 20
	MaxSnapshotBytes = 4 << 20
	metaAuthHash     = "auth_hash"
)

// New builds the relay handler. hub must be running (hub.Run).
func New(st *store.Store, installToken string, hub *api.Hub) http.Handler {
	authHash := func() string { h, _ := st.GetMeta(metaAuthHash); return h }
	auth := api.BearerAuth(authHash)
	mux := http.NewServeMux()
	api.ReadRoutes(mux, st, auth, hub)

	api.WriteRoutes(mux, st, auth, hub, nil)

	// The Mac collects pending commands and posts their results.
	mux.HandleFunc("GET /v1/commands", func(w http.ResponseWriter, r *http.Request) {
		if !auth(r) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		cmds, err := st.PendingCommands()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		out := make([]api.CommandJSON, 0, len(cmds))
		for _, c := range cmds {
			out = append(out, api.CommandJSON{ID: c.ID, Created: c.Created, Envelope: c.Envelope})
		}
		api.WriteJSON(w, out)
	})
	mux.HandleFunc("PUT /v1/commands/{id}/result", func(w http.ResponseWriter, r *http.Request) {
		if !auth(r) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		env, err := readAll(w, r, api.MaxCommandBytes)
		if err != nil {
			return
		}
		id := r.PathValue("id")
		if err := st.SetCommandResult(id, env, time.Now().UnixMilli()); errors.Is(err, store.ErrNotFound) {
			http.NotFound(w, r)
			return
		} else if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		hub.MarkMacSeen()
		hub.NotifyResult(id)
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("POST /v1/register", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			AuthHash string `json:"authHash"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil || len(body.AuthHash) != 64 {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		registered := authHash() != ""
		switch {
		case registered && auth(r): // key rotation, authorised by the current key
		case validInstallToken(r, installToken):
			if registered {
				http.Error(w, "already registered", http.StatusConflict)
				return
			}
		default:
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if err := st.SetMeta(metaAuthHash, body.AuthHash); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("PUT /v1/blobs/{id}", func(w http.ResponseWriter, r *http.Request) {
		if !auth(r) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		id := r.PathValue("id")
		if !api.ValidBlobID(id) {
			http.Error(w, "bad id", http.StatusBadRequest)
			return
		}
		env, err := readAll(w, r, MaxBlobBytes)
		if err != nil {
			return
		}
		if err := st.PutBlob(id, env); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		hub.MarkMacSeen()
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("POST /v1/snapshots", func(w http.ResponseWriter, r *http.Request) {
		if !auth(r) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		var body api.SnapshotJSON
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, MaxSnapshotBytes)).Decode(&body); err != nil || body.Seq <= 0 || len(body.Envelope) == 0 {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		err := st.AddSnapshot(store.Snapshot{Seq: body.Seq, At: time.Now().UnixMilli(), Envelope: body.Envelope, Blobs: body.Blobs, Uploaded: true})
		switch {
		case errors.Is(err, store.ErrStaleSeq):
			http.Error(w, "stale seq", http.StatusConflict)
			return
		case store.IsMissingBlob(err):
			http.Error(w, err.Error(), http.StatusUnprocessableEntity)
			return
		case err != nil:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		hub.MarkMacSeen()
		hub.NotifySnapshot(body.Seq)
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}

func validInstallToken(r *http.Request, want string) bool {
	got := r.Header.Get("X-Install-Token")
	return want != "" && subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func readAll(w http.ResponseWriter, r *http.Request, limit int64) ([]byte, error) {
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		} else {
			http.Error(w, "read error", http.StatusBadRequest)
		}
	}
	return b, err
}
