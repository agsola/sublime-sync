package store

import (
	"errors"
	"testing"
	"time"
)

func TestCommandIdempotent(t *testing.T) {
	s := openTest(t)
	if err := s.AddCommand("a", 1, []byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := s.AddCommand("a", 2, []byte("y")); err != nil {
		t.Fatal(err)
	}
	c, err := s.GetCommand("a")
	if err != nil || string(c.Envelope) != "x" || c.Created != 1 {
		t.Fatalf("%+v %v", c, err)
	}
	if _, err := s.GetCommand("nope"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestPendingExcludesResolved(t *testing.T) {
	s := openTest(t)
	s.AddCommand("a", 1, []byte("x"))
	s.AddCommand("b", 2, []byte("y"))
	s.SetCommandResult("a", []byte("r"), 3)
	p, _ := s.PendingCommands()
	if len(p) != 1 || p[0].ID != "b" {
		t.Fatalf("%+v", p)
	}
	c, _ := s.GetCommand("a")
	if string(c.Result) != "r" || c.ResultAt != 3 {
		t.Fatalf("%+v", c)
	}
}

func TestPruneCommands(t *testing.T) {
	s := openTest(t)
	now := time.Now()
	s.AddCommand("old", 1, []byte("x"))
	s.SetCommandResult("old", []byte("r"), now.Add(-8*24*time.Hour).UnixMilli())
	s.AddCommand("new", 1, []byte("x"))
	s.SetCommandResult("new", []byte("r"), now.UnixMilli())
	s.AddCommand("open", now.UnixMilli(), []byte("x"))
	s.AddCommand("stale", now.Add(-8*24*time.Hour).UnixMilli(), []byte("x"))
	if n, err := s.PruneCommands(now); err != nil || n != 2 {
		t.Fatalf("pruned %d %v", n, err)
	}
	if _, err := s.GetCommand("open"); err != nil {
		t.Fatal("unresolved command pruned")
	}
}

func TestStateCAS(t *testing.T) {
	s := openTest(t)
	if _, _, err := s.GetState("p"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	v, err := s.PutState("p", 0, []byte("a"))
	if err != nil || v != 1 {
		t.Fatalf("%d %v", v, err)
	}
	if _, err := s.PutState("p", 0, []byte("b")); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("create twice: %v", err)
	}
	if v, err = s.PutState("p", 1, []byte("c")); err != nil || v != 2 {
		t.Fatalf("%d %v", v, err)
	}
	if _, err := s.PutState("p", 1, []byte("d")); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("stale: %v", err)
	}
	ver, env, _ := s.GetState("p")
	if ver != 2 || string(env) != "c" {
		t.Fatalf("%d %s", ver, env)
	}
}

func TestAppliedGuard(t *testing.T) {
	s := openTest(t)
	if ok, _ := s.WasApplied("x"); ok {
		t.Fatal("fresh id applied")
	}
	s.MarkApplied("x", 5)
	if ok, _ := s.WasApplied("x"); !ok {
		t.Fatal("not remembered")
	}
}

func TestResultNotOverwritten(t *testing.T) {
	s := openTest(t)
	s.AddCommand("a", 1, []byte("x"))
	s.SetCommandResult("a", []byte("conflict"), 2)
	s.SetCommandResult("a", []byte("applied again"), 3)
	c, _ := s.GetCommand("a")
	if string(c.Result) != "conflict" {
		t.Fatalf("first result overwritten: %s", c.Result)
	}
}
