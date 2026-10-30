// Command sublime-sync-server is the relay. Configuration comes from the
// environment: LISTEN (default 127.0.0.1:8787), DATA_DIR and INSTALL_TOKEN.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/server"
	"github.com/agsola/sublime-sync/internal/store"
)

func main() {
	listen := envOr("LISTEN", "127.0.0.1:8787")
	dataDir := envOr("DATA_DIR", "./data")
	installToken := os.Getenv("INSTALL_TOKEN")
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		log.Fatal(err)
	}
	st, err := store.Open(filepath.Join(dataDir, "server.db"))
	if err != nil {
		log.Fatal(err)
	}
	defer st.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	hub := api.NewHub(90*time.Second, 30*time.Second)
	go hub.Run(ctx)
	go func() {
		for {
			if n, b, err := st.Prune(time.Now()); err != nil {
				log.Printf("prune: %v", err)
			} else if n+b > 0 {
				log.Printf("prune: removed %d snapshots, %d blobs", n, b)
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Hour):
			}
		}
	}()

	srv := &http.Server{Addr: listen, Handler: server.New(st, installToken, hub), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(shutdown)
	}()
	log.Printf("listening on %s", listen)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
