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
	go ListenPlugin(ctx, sock, tabsOnly(func(tabs []proto.Tab) { got <- tabs }))
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

type tabsOnly func([]proto.Tab)

func (f tabsOnly) OnTabs(t []proto.Tab) bool { f(t); return true }
func (tabsOnly) OnResults([]proto.Result)    {}
func (tabsOnly) Pending() []proto.Command    { return nil }

func TestUnchangedTabsFilledFromCache(t *testing.T) {
	f := NewTabFiller()
	full := []proto.Tab{{ID: 1, Title: "a", Text: "big text"}, {ID: 2, Title: "b", Text: "x"}}
	if got, ok := f.Fill(full); !ok || got[0].Text != "big text" {
		t.Fatalf("full message: %v %+v", ok, got)
	}
	partial := []proto.Tab{{ID: 1, Title: "a", Unchanged: true}, {ID: 2, Title: "b", Text: "y"}}
	got, ok := f.Fill(partial)
	if !ok || got[0].Text != "big text" || got[0].Unchanged || got[1].Text != "y" {
		t.Fatalf("partial: %v %+v", ok, got)
	}
}

func TestMissingCacheAsksForFull(t *testing.T) {
	f := NewTabFiller() // e.g. the agent just restarted
	if _, ok := f.Fill([]proto.Tab{{ID: 7, Unchanged: true}}); ok {
		t.Fatal("accepted a tab whose text it doesn't have")
	}
}

func TestLatestQueueKeepsNewest(t *testing.T) {
	q := NewLatestQueue()
	q.Put([]proto.Tab{{ID: 1}})
	q.Put([]proto.Tab{{ID: 2}}) // replaces the one not yet taken
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	got, ok := q.Take(ctx)
	if !ok || got[0].ID != 2 {
		t.Fatalf("%v %+v", ok, got)
	}
	ctx2, cancel2 := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel2()
	if _, ok := q.Take(ctx2); ok {
		t.Fatal("took a snapshot twice")
	}
}

func TestPluginExchangeLargeMessageIsFast(t *testing.T) {
	sock := shortSock(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	got := make(chan []proto.Tab, 1)
	go ListenPlugin(ctx, sock, tabsOnly(func(tabs []proto.Tab) { got <- tabs }))
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
	big := strings.Repeat("x", 30<<20)
	start := time.Now()
	go func() {
		conn.Write([]byte(`{"type":"snapshot","tabs":[{"id":1,"text":"` + big + `"}]}` + "\n"))
		conn.(*net.UnixConn).CloseWrite()
	}()
	select {
	case tabs := <-got:
		if len(tabs[0].Text) != len(big) {
			t.Fatal("text cut")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("30 MB message took more than 10 s (quadratic read?)")
	}
	t.Logf("30 MB read in %s", time.Since(start))
}
