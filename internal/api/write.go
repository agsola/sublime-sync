package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"github.com/agsola/sublime-sync/internal/store"
)

const (
	MaxCommandBytes = 4 << 20
	MaxStateBytes   = 1 << 20
)

// CommandJSON is how a sealed command (and its sealed result) travels.
type CommandJSON struct {
	ID       string `json:"id"`
	Created  int64  `json:"created,omitempty"`
	Envelope []byte `json:"envelope,omitempty"`
	Result   []byte `json:"result,omitempty"`
}

type StateJSON struct {
	Version  int64  `json:"version"`
	Envelope []byte `json:"envelope,omitempty"`
}

var idRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// ValidID accepts command ids and state names.
func ValidID(id string) bool { return idRe.MatchString(id) }

// WriteRoutes registers the phone-writable endpoints shared by the relay and
// the agent. onCommand, if set, is called after a command is stored.
func WriteRoutes(mux *http.ServeMux, st *store.Store, auth Auth, hub *Hub, onCommand func(id string, env []byte)) {
	guard := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if !auth(r) {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			h(w, r)
		}
	}
	mux.HandleFunc("POST /v1/commands", guard(func(w http.ResponseWriter, r *http.Request) {
		var c CommandJSON
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, MaxCommandBytes)).Decode(&c); err != nil {
			var tooLarge *http.MaxBytesError
			if errors.As(err, &tooLarge) {
				http.Error(w, "too large", http.StatusRequestEntityTooLarge)
			} else {
				http.Error(w, "bad body", http.StatusBadRequest)
			}
			return
		}
		if !ValidID(c.ID) || len(c.Envelope) == 0 {
			http.Error(w, "bad command", http.StatusBadRequest)
			return
		}
		if err := st.AddCommand(c.ID, time.Now().UnixMilli(), c.Envelope); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if onCommand != nil {
			onCommand(c.ID, c.Envelope)
		}
		hub.NotifyCommand()
		w.WriteHeader(http.StatusNoContent)
	}))
	mux.HandleFunc("GET /v1/commands/{id}", guard(func(w http.ResponseWriter, r *http.Request) {
		c, err := st.GetCommand(r.PathValue("id"))
		if errors.Is(err, store.ErrNotFound) {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		WriteJSON(w, CommandJSON{ID: c.ID, Created: c.Created, Result: c.Result})
	}))
	mux.HandleFunc("GET /v1/state/{name}", guard(func(w http.ResponseWriter, r *http.Request) {
		v, env, err := st.GetState(r.PathValue("name"))
		if errors.Is(err, store.ErrNotFound) {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		WriteJSON(w, StateJSON{Version: v, Envelope: env})
	}))
	mux.HandleFunc("PUT /v1/state/{name}", guard(func(w http.ResponseWriter, r *http.Request) {
		name := r.PathValue("name")
		ifVersion, err := strconv.ParseInt(r.Header.Get("If-Match"), 10, 64)
		if !ValidID(name) || err != nil || ifVersion < 0 {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		env, err := io.ReadAll(http.MaxBytesReader(w, r.Body, MaxStateBytes))
		if err != nil || len(env) == 0 {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		v, err := st.PutState(name, ifVersion, env)
		if errors.Is(err, store.ErrVersionConflict) {
			cur, curEnv, _ := st.GetState(name)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusConflict)
			json.NewEncoder(w).Encode(StateJSON{Version: cur, Envelope: curEnv})
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		WriteJSON(w, StateJSON{Version: v})
	}))
}
