package proto

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"testing"
)

func testKeys(t *testing.T) Keys {
	t.Helper()
	m, err := NewMasterKey()
	if err != nil {
		t.Fatal(err)
	}
	return Derive(m)
}

func TestSealOpenRoundTrip(t *testing.T) {
	k := testKeys(t)
	env, err := Seal(k, "blob:x", []byte("hola ñ 🙂"))
	if err != nil {
		t.Fatal(err)
	}
	got, err := Open(map[string]Keys{k.ID: k}, "blob:x", env)
	if err != nil || string(got) != "hola ñ 🙂" {
		t.Fatalf("got %q, %v", got, err)
	}
}

func TestOpenWrongAADFails(t *testing.T) {
	k := testKeys(t)
	env, _ := Seal(k, "blob:x", []byte("a"))
	if _, err := Open(map[string]Keys{k.ID: k}, "blob:y", env); !errors.Is(err, ErrAuth) {
		t.Fatalf("want ErrAuth, got %v", err)
	}
}

func TestOpenUnknownKeyID(t *testing.T) {
	k, other := testKeys(t), testKeys(t)
	env, _ := Seal(k, "a", []byte("a"))
	if _, err := Open(map[string]Keys{other.ID: other}, "a", env); !errors.Is(err, ErrUnknownKey) {
		t.Fatalf("want ErrUnknownKey, got %v", err)
	}
}

func TestBlobIDStableAndKeyed(t *testing.T) {
	k, other := testKeys(t), testKeys(t)
	a, b := BlobID(k, []byte("x")), BlobID(k, []byte("x"))
	if a != b || len(a) != 32 {
		t.Fatalf("unstable or wrong length: %s %s", a, b)
	}
	if BlobID(other, []byte("x")) == a {
		t.Fatal("blob id not keyed")
	}
}

func TestDeriveDistinctKeys(t *testing.T) {
	k := testKeys(t)
	ks := [][32]byte{k.Content, k.BlobID, k.ServerAuth, k.MacAuth}
	for i := range ks {
		for j := i + 1; j < len(ks); j++ {
			if ks[i] == ks[j] {
				t.Fatalf("keys %d and %d equal", i, j)
			}
		}
	}
}

func TestCompressesBeforeEncrypt(t *testing.T) {
	env, _ := Seal(testKeys(t), "a", bytes.Repeat([]byte("a"), 10000))
	if len(env) >= 200 {
		t.Fatalf("envelope too large: %d", len(env))
	}
}

type vectors struct {
	Master     string `json:"master"`
	KeyID      string `json:"keyId"`
	Content    string `json:"content"`
	BlobIDKey  string `json:"blobIdKey"`
	ServerAuth string `json:"serverAuth"`
	MacAuth    string `json:"macAuth"`
	ServerTok  string `json:"serverToken"`
	Plaintext  string `json:"plaintext"`
	AAD        string `json:"aad"`
	BlobID     string `json:"blobId"`
	Envelope   string `json:"envelope"`
	EnvelopeV2 string `json:"envelopeV2"`
}

func vectorMaster() MasterKey {
	var m MasterKey
	for i := range m {
		m[i] = byte(i)
	}
	return m
}

func TestWriteVectors(t *testing.T) {
	if os.Getenv("UPDATE_VECTORS") != "1" {
		t.Skip("set UPDATE_VECTORS=1 to regenerate")
	}
	m := vectorMaster()
	k := Derive(m)
	var nonce [nonceSize]byte
	for i := range nonce {
		nonce[i] = byte(i)
	}
	plain := `{"hello":"mundo ñ"}`
	env, err := sealWithNonce(k, "blob:abc", []byte(plain), nonce)
	if err != nil {
		t.Fatal(err)
	}
	v := vectors{
		Master: hex.EncodeToString(m[:]), KeyID: k.ID,
		Content: hex.EncodeToString(k.Content[:]), BlobIDKey: hex.EncodeToString(k.BlobID[:]),
		ServerAuth: hex.EncodeToString(k.ServerAuth[:]), MacAuth: hex.EncodeToString(k.MacAuth[:]),
		ServerTok: TokenString(k.ServerAuth),
		Plaintext: plain, AAD: "blob:abc", BlobID: BlobID(k, []byte(plain)),
		Envelope: base64.StdEncoding.EncodeToString(env),
	}
	v2, err := seal(k, envelopeRaw, "blob:abc", []byte(plain), nonce)
	if err != nil {
		t.Fatal(err)
	}
	v.EnvelopeV2 = base64.StdEncoding.EncodeToString(v2)
	b, _ := json.MarshalIndent(v, "", "  ")
	if err := os.WriteFile("testdata/vectors.json", append(b, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestVectorsStillValid(t *testing.T) {
	b, err := os.ReadFile("testdata/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	k := Derive(vectorMaster())
	if hex.EncodeToString(k.Content[:]) != v.Content || k.ID != v.KeyID {
		t.Fatal("derivation changed")
	}
	env, _ := base64.StdEncoding.DecodeString(v.Envelope)
	got, err := Open(map[string]Keys{k.ID: k}, v.AAD, env)
	if err != nil || string(got) != v.Plaintext {
		t.Fatalf("got %q %v", got, err)
	}
	v2, _ := base64.StdEncoding.DecodeString(v.EnvelopeV2)
	if got, err := Open(map[string]Keys{k.ID: k}, v.AAD, v2); err != nil || string(got) != v.Plaintext {
		t.Fatalf("v2 vector: %q %v", got, err)
	}
	if BlobID(k, []byte(v.Plaintext)) != v.BlobID {
		t.Fatal("blob id changed")
	}
}

func TestOpenV2Raw(t *testing.T) {
	k := testKeys(t)
	env, err := SealRaw(k, "command:x", []byte("hola ✎"))
	if err != nil {
		t.Fatal(err)
	}
	if env[0] != 0x02 {
		t.Fatalf("version %d", env[0])
	}
	got, err := Open(map[string]Keys{k.ID: k}, "command:x", env)
	if err != nil || string(got) != "hola ✎" {
		t.Fatalf("got %q %v", got, err)
	}
}

func TestV2NotDecompressed(t *testing.T) {
	k := testKeys(t)
	v1, _ := Seal(k, "a", []byte("compressed?"))
	// A v2 envelope whose plaintext happens to be Brotli bytes must come back verbatim.
	raw := v1[headerSize:]
	env, _ := SealRaw(k, "a", raw)
	got, err := Open(map[string]Keys{k.ID: k}, "a", env)
	if err != nil || !bytes.Equal(got, raw) {
		t.Fatalf("v2 was transformed: %v", err)
	}
}

func TestCommandJSONShape(t *testing.T) {
	b, _ := json.Marshal(Command{ID: "i", Type: CommandDraft, DraftID: "d", Rev: 3})
	var m map[string]any
	json.Unmarshal(b, &m)
	for _, k := range []string{"id", "type", "draftId", "rev"} {
		if _, ok := m[k]; !ok {
			t.Fatalf("missing %s in %s", k, b)
		}
	}
	if CommandAAD("i") != "command:i" || ResultAAD("i") != "result:i" || StateAAD("s") != "state:s" {
		t.Fatal("aad strings")
	}
}
