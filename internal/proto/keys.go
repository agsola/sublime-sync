// Package proto holds the wire format shared by the agent, the server and (by
// mirroring it) the Android app: key derivation, envelopes and JSON types.
package proto

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"io"

	"golang.org/x/crypto/hkdf"
)

// MasterKey is the 256-bit secret shared between the Mac and the phones.
type MasterKey [32]byte

func NewMasterKey() (MasterKey, error) {
	var m MasterKey
	_, err := rand.Read(m[:])
	return m, err
}

// KeyID identifies a master key without revealing it: hex of the first 4
// bytes of its SHA-256.
func (m MasterKey) KeyID() string {
	sum := sha256.Sum256(m[:])
	return hex.EncodeToString(sum[:4])
}

// Keys are the purpose-specific keys derived from a master key.
type Keys struct {
	ID         string
	Content    [32]byte
	BlobID     [32]byte
	ServerAuth [32]byte
	MacAuth    [32]byte
}

const (
	infoContent    = "sublime-sync/content"
	infoBlobID     = "sublime-sync/blobid"
	infoServerAuth = "sublime-sync/server-auth"
	infoMacAuth    = "sublime-sync/mac-auth"
)

func Derive(m MasterKey) Keys {
	k := Keys{ID: m.KeyID()}
	derive(m, infoContent, &k.Content)
	derive(m, infoBlobID, &k.BlobID)
	derive(m, infoServerAuth, &k.ServerAuth)
	derive(m, infoMacAuth, &k.MacAuth)
	return k
}

func derive(m MasterKey, info string, out *[32]byte) {
	r := hkdf.New(sha256.New, m[:], nil, []byte(info))
	if _, err := io.ReadFull(r, out[:]); err != nil {
		panic(err) // cannot happen for 32 bytes
	}
}

// TokenString is how a token travels in "Authorization: Bearer ...".
func TokenString(token [32]byte) string {
	return base64.RawURLEncoding.EncodeToString(token[:])
}

// TokenHash is what servers store instead of the token: hex SHA-256 of the
// token string.
func TokenHash(token [32]byte) string {
	return HashBearer(TokenString(token))
}

// HashBearer hashes a bearer string as received on the wire.
func HashBearer(bearer string) string {
	sum := sha256.Sum256([]byte(bearer))
	return hex.EncodeToString(sum[:])
}
