package agent

import (
	"os"
	"testing"
)

func TestConfigRoundTrip(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	dir, err := DataDir()
	if err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(dir)
	if fi.Mode().Perm() != 0o700 {
		t.Fatalf("dir perms %v", fi.Mode().Perm())
	}
	want := Config{Server: "https://sync.example.com", Mac: "http://100.100.100.100:8788", OldKeyIDs: []string{"abcd0123"}}
	if err := SaveConfig(dir, want); err != nil {
		t.Fatal(err)
	}
	got, err := LoadConfig(dir)
	if err != nil || got.Server != want.Server || got.Mac != want.Mac || len(got.OldKeyIDs) != 1 {
		t.Fatalf("%+v %v", got, err)
	}
	fi, _ = os.Stat(dir + "/config.json")
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("file perms %v", fi.Mode().Perm())
	}
}
