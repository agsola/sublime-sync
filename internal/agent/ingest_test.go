package agent

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/agsola/sublime-sync/internal/proto"
)

func shortSock(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "ss")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return filepath.Join(dir, "a.sock")
}

func TestListenPluginReceivesTabs(t *testing.T) {
	sock := shortSock(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	got := make(chan []proto.Tab, 1)
	go ListenPlugin(ctx, sock, func(tabs []proto.Tab) { got <- tabs })
	var conn net.Conn
	var err error
	for i := 0; i < 50; i++ {
		if conn, err = net.Dial("unix", sock); err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	conn.Write([]byte("garbage\n" + `{"type":"snapshot","tabs":[{"id":1,"title":"x","text":"ñ"}]}` + "\n"))
	select {
	case tabs := <-got:
		if len(tabs) != 1 || tabs[0].Text != "ñ" {
			t.Fatalf("%+v", tabs)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no tabs")
	}
	fi, _ := os.Stat(sock)
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("socket perms %v", fi.Mode().Perm())
	}
}

func TestParseSessionUnsavedAndFile(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "notes.md")
	os.WriteFile(file, []byte("saved text"), 0o600)
	raw, _ := os.ReadFile("testdata/session_min.json")
	tabs, err := ParseSession(strings.NewReader(strings.Replace(string(raw), "FILE_PLACEHOLDER", file, 1)))
	if err != nil {
		t.Fatal(err)
	}
	if len(tabs) != 2 {
		t.Fatalf("got %d tabs: %+v", len(tabs), tabs)
	}
	if !tabs[0].Dirty || tabs[0].Title != "first line" || tabs[0].Window != 4 {
		t.Fatalf("%+v", tabs[0])
	}
	if tabs[1].Dirty || tabs[1].Title != "notes.md" || tabs[1].Text != "saved text" {
		t.Fatalf("%+v", tabs[1])
	}
}

func TestParseSessionMalformed(t *testing.T) {
	if _, err := ParseSession(strings.NewReader("{not json")); err == nil {
		t.Fatal("want error")
	}
}

func TestTabTitle(t *testing.T) {
	p := "/a/b/c.txt"
	long := strings.Repeat("x", 100)
	cases := []struct {
		name string
		path *string
		text string
		want string
	}{
		{"named", &p, "t", "named"},
		{"", &p, "t", "c.txt"},
		{"", nil, "\n  \n  hello \nworld", "hello"},
		{"", nil, long, long[:80]},
		{"", nil, "  ", "untitled"},
	}
	for _, c := range cases {
		if got := TabTitle(c.name, c.path, c.text); got != c.want {
			t.Errorf("got %q want %q", got, c.want)
		}
	}
}

func TestWatchSessionOnlyWhenPluginSilent(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "s.json")
	os.WriteFile(path, []byte(`{"windows":[{"buffers":[{"contents":"a","settings":{}}]}]}`), 0o600)
	var calls atomic.Int32
	var pluginAt atomic.Int64
	pluginAt.Store(time.Now().UnixNano())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go WatchSession(ctx, path, 20*time.Millisecond, 200*time.Millisecond,
		func() time.Time { return time.Unix(0, pluginAt.Load()) },
		func([]proto.Tab) { calls.Add(1) })
	time.Sleep(120 * time.Millisecond)
	if calls.Load() != 0 {
		t.Fatal("read session while plugin active")
	}
	time.Sleep(250 * time.Millisecond)
	if calls.Load() != 1 {
		t.Fatalf("calls %d, want 1 (file unchanged after first read)", calls.Load())
	}
}
