package agent

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/agsola/sublime-sync/internal/api"
	"github.com/agsola/sublime-sync/internal/proto"
)

type resultSink struct {
	mu  sync.Mutex
	got map[string]proto.Result
}

func newInboxT(t *testing.T, now time.Time) (*Inbox, proto.Keys, *resultSink) {
	t.Helper()
	k := testKeys(t)
	st := openStore(t, filepath.Join(t.TempDir(), "a.db"))
	sink := &resultSink{got: map[string]proto.Result{}}
	in := NewInbox(st, k, map[string]proto.Keys{k.ID: k}, func() time.Time { return now })
	in.OnResult = func(id string, env []byte, fromServer bool) {
		plain, err := proto.Open(map[string]proto.Keys{k.ID: k}, proto.ResultAAD(id), env)
		if err != nil {
			t.Errorf("result not sealed: %v", err)
			return
		}
		var r proto.Result
		json.Unmarshal(plain, &r)
		sink.mu.Lock()
		sink.got[id] = r
		sink.mu.Unlock()
	}
	return in, k, sink
}

func seal(t *testing.T, k proto.Keys, c proto.Command) []byte {
	t.Helper()
	b, _ := json.Marshal(c)
	env, err := proto.SealRaw(k, proto.CommandAAD(c.ID), b)
	if err != nil {
		t.Fatal(err)
	}
	return env
}

func TestCommandsWaitForPlugin(t *testing.T) {
	now := time.Now()
	in, k, sink := newInboxT(t, now)
	c := proto.Command{ID: "c1", Type: proto.CommandDraft, Created: now.UnixMilli(), DraftID: "d", Text: "hola", Rev: 1}
	if err := in.Accept("c1", seal(t, k, c), false); err != nil {
		t.Fatal(err)
	}
	p := in.Pending()
	if len(p) != 1 || p[0].Text != "hola" {
		t.Fatalf("%+v", p)
	}
	in.Complete(proto.Result{ID: "c1", Status: proto.ResultApplied, UID: "u1"})
	if len(in.Pending()) != 0 {
		t.Fatal("still pending after result")
	}
	if sink.got["c1"].Status != proto.ResultApplied || sink.got["c1"].UID != "u1" {
		t.Fatalf("%+v", sink.got)
	}
}

func TestReplayRejected(t *testing.T) {
	now := time.Now()
	in, k, sink := newInboxT(t, now)
	c := proto.Command{ID: "c1", Type: proto.CommandClose, Created: now.UnixMilli(), UID: "u"}
	in.Accept("c1", seal(t, k, c), false)
	in.Complete(proto.Result{ID: "c1", Status: proto.ResultApplied})
	in.Accept("c1", seal(t, k, c), false)
	if len(in.Pending()) != 0 {
		t.Fatal("replayed command queued")
	}
	old := proto.Command{ID: "c2", Type: proto.CommandClose, Created: now.Add(-8 * 24 * time.Hour).UnixMilli(), UID: "u"}
	in.Accept("c2", seal(t, k, old), false)
	if len(in.Pending()) != 0 || sink.got["c2"].Status != proto.ResultRejected {
		t.Fatalf("old command: pending=%d result=%+v", len(in.Pending()), sink.got["c2"])
	}
	in.Accept("c3", []byte("garbage"), false)
	if sink.got["c3"].Status != proto.ResultRejected {
		t.Fatalf("garbage: %+v", sink.got["c3"])
	}
}

func TestOlderRevisionIgnored(t *testing.T) {
	now := time.Now()
	in, k, sink := newInboxT(t, now)
	mk := func(id string, rev int64) []byte {
		return seal(t, k, proto.Command{ID: id, Type: proto.CommandDraft, Created: now.UnixMilli(), DraftID: "d", Text: id, Rev: rev})
	}
	in.Accept("r2", mk("r2", 2), false)
	in.Accept("r1", mk("r1", 1), false) // arrives late
	in.Accept("r3", mk("r3", 3), false)
	p := in.Pending()
	if len(p) != 1 || p[0].ID != "r3" {
		t.Fatalf("pending %+v", p)
	}
	for _, id := range []string{"r1", "r2"} {
		if sink.got[id].Status != proto.ResultApplied || sink.got[id].Reason != "superseded" {
			t.Fatalf("%s: %+v", id, sink.got[id])
		}
	}
}

func TestPendingSurvivesRestart(t *testing.T) {
	now := time.Now()
	k := testKeys(t)
	path := filepath.Join(t.TempDir(), "a.db")
	st := openStore(t, path)
	in := NewInbox(st, k, map[string]proto.Keys{k.ID: k}, func() time.Time { return now })
	in.Accept("c1", seal(t, k, proto.Command{ID: "c1", Type: proto.CommandClose, Created: now.UnixMilli(), UID: "u"}), false)
	st.Close()
	st2 := openStore(t, path)
	in2 := NewInbox(st2, k, map[string]proto.Keys{k.ID: k}, func() time.Time { return now })
	if err := in2.Load(); err != nil {
		t.Fatal(err)
	}
	if len(in2.Pending()) != 1 {
		t.Fatal("pending command lost on restart")
	}
}

func TestServerCommandResultUploaded(t *testing.T) {
	r := newRelay(t)
	now := time.Now()
	c := proto.Command{ID: "s1", Type: proto.CommandClose, Created: now.UnixMilli(), UID: "u"}
	b, _ := json.Marshal(c)
	env, _ := proto.SealRaw(r.keys, proto.CommandAAD("s1"), b)
	r.st.AddCommand("s1", now.UnixMilli(), env)

	local := openStore(t, filepath.Join(t.TempDir(), "agent.db"))
	in := NewInbox(local, r.keys, map[string]proto.Keys{r.keys.ID: r.keys}, time.Now)
	f := NewCommandFetcher(in, r.url, proto.TokenString(r.keys.ServerAuth))
	in.OnResult = f.UploadResult
	if err := f.FetchOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(in.Pending()) != 1 {
		t.Fatal("server command not fetched")
	}
	in.Complete(proto.Result{ID: "s1", Status: proto.ResultApplied})
	waitFor(t, func() bool { c, _ := r.st.GetCommand("s1"); return c.Result != nil })
}

func TestPluginExchange(t *testing.T) {
	sock := shortSock(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h := &fakeHandler{pending: []proto.Command{{ID: "c1", Type: proto.CommandClose, UID: "u"}}}
	go ListenPlugin(ctx, sock, h)
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
	conn.Write([]byte(`{"type":"results","results":[{"id":"c0","status":"applied"}]}` + "\n" + `{"type":"poll"}` + "\n"))
	conn.(*net.UnixConn).CloseWrite()
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	var m PluginReply
	json.Unmarshal([]byte(line), &m)
	if m.Type != "commands" || len(m.Commands) != 1 || m.Commands[0].ID != "c1" {
		t.Fatalf("%s", line)
	}
	if len(h.results) != 1 || h.results[0].ID != "c0" {
		t.Fatalf("results %+v", h.results)
	}
}

type fakeHandler struct {
	mu      sync.Mutex
	tabs    [][]proto.Tab
	results []proto.Result
	pending []proto.Command
}

func (f *fakeHandler) OnTabs(t []proto.Tab) { f.mu.Lock(); f.tabs = append(f.tabs, t); f.mu.Unlock() }
func (f *fakeHandler) OnResults(r []proto.Result) {
	f.mu.Lock()
	f.results = append(f.results, r...)
	f.mu.Unlock()
}
func (f *fakeHandler) Pending() []proto.Command { return f.pending }

var _ = api.CommandJSON{}

func TestDuplicateAcceptKeepsPending(t *testing.T) {
	now := time.Now()
	in, k, sink := newInboxT(t, now)
	env := seal(t, k, proto.Command{ID: "c1", Type: proto.CommandDraft, Created: now.UnixMilli(), DraftID: "d", Text: "x", Rev: 1})
	in.Accept("c1", env, true)
	in.Accept("c1", env, true) // the relay returns it again before Sublime applied it
	if p := in.Pending(); len(p) != 1 || p[0].ID != "c1" {
		t.Fatalf("pending %+v", p)
	}
	if _, ok := sink.got["c1"]; ok {
		t.Fatalf("answered before Sublime applied it: %+v", sink.got["c1"])
	}
}
