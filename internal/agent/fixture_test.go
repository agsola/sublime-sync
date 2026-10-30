package agent

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/agsola/sublime-sync/internal/proto"
)

// TestWriteAndroidFixture writes sealed snapshots the Android unit tests read
// (internal/proto/testdata/fixture.json). Run with UPDATE_VECTORS=1.
func TestWriteAndroidFixture(t *testing.T) {
	if os.Getenv("UPDATE_VECTORS") != "1" {
		t.Skip("set UPDATE_VECTORS=1 to regenerate")
	}
	var m proto.MasterKey
	for i := range m {
		m[i] = byte(i)
	}
	st := openStore(t, filepath.Join(t.TempDir(), "f.db"))
	s := NewSnapshotter(st, proto.Derive(m), nil)
	at := time.Date(2026, 10, 30, 12, 0, 0, 0, time.UTC)
	s.Ingest(tabs("uno", "dos ñ"), proto.SourcePlugin, at)
	s.Ingest(tabs("uno", "DOS 🙂"), proto.SourcePlugin, at.Add(time.Minute))

	type snap struct {
		Seq      int64  `json:"seq"`
		At       int64  `json:"at"`
		Envelope string `json:"envelope"`
	}
	out := struct {
		Snapshots []snap            `json:"snapshots"`
		Blobs     map[string]string `json:"blobs"`
	}{Blobs: map[string]string{}}
	for seq := int64(1); seq <= 2; seq++ {
		sn, err := st.GetSnapshot(seq)
		if err != nil {
			t.Fatal(err)
		}
		out.Snapshots = append(out.Snapshots, snap{sn.Seq, sn.At, base64.StdEncoding.EncodeToString(sn.Envelope)})
		for _, id := range sn.Blobs {
			env, _ := st.GetBlob(id)
			out.Blobs[id] = base64.StdEncoding.EncodeToString(env)
		}
	}
	b, _ := json.MarshalIndent(out, "", "  ")
	if err := os.WriteFile("../proto/testdata/fixture.json", append(b, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
}
