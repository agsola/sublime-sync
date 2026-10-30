package proto

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"strconv"

	"github.com/andybalholm/brotli"
)

const (
	envelopeVersion = 0x01
	// envelopeRaw is the same layout without Brotli, used by the phone, which
	// can decompress Brotli but not compress it.
	envelopeRaw   = 0x02
	nonceSize     = 12
	headerSize    = 1 + 4 + nonceSize
	brotliQuality = 5
	// MaxPlaintext bounds decompression so a bad envelope can't exhaust memory.
	MaxPlaintext = 64 << 20
)

var (
	ErrUnknownKey = errors.New("proto: envelope encrypted with an unknown key")
	ErrAuth       = errors.New("proto: envelope authentication failed")
	ErrFormat     = errors.New("proto: malformed envelope")
)

// Seal compresses plaintext with Brotli and encrypts it with AES-256-GCM.
// Layout: version(1) | keyID(4) | nonce(12) | ciphertext+tag.
func Seal(k Keys, aad string, plaintext []byte) ([]byte, error) {
	var nonce [nonceSize]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, err
	}
	return sealWithNonce(k, aad, plaintext, nonce)
}

func sealWithNonce(k Keys, aad string, plaintext []byte, nonce [nonceSize]byte) ([]byte, error) {
	var buf bytes.Buffer
	w := brotli.NewWriterLevel(&buf, brotliQuality)
	if _, err := w.Write(plaintext); err != nil {
		return nil, err
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return seal(k, envelopeVersion, aad, buf.Bytes(), nonce)
}

// SealRaw encrypts without compression (envelope version 2), as the phone does.
func SealRaw(k Keys, aad string, plaintext []byte) ([]byte, error) {
	var nonce [nonceSize]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, err
	}
	return seal(k, envelopeRaw, aad, plaintext, nonce)
}

func seal(k Keys, version byte, aad string, payload []byte, nonce [nonceSize]byte) ([]byte, error) {
	id, err := hex.DecodeString(k.ID)
	if err != nil || len(id) != 4 {
		return nil, errors.New("proto: bad key id")
	}
	gcm, err := newGCM(k.Content)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, headerSize+len(payload)+gcm.Overhead())
	out = append(out, version)
	out = append(out, id...)
	out = append(out, nonce[:]...)
	return gcm.Seal(out, nonce[:], payload, []byte(aad)), nil
}

// Open decrypts an envelope (version 1 or 2) with whichever key in keyring
// matches its key id.
func Open(keyring map[string]Keys, aad string, env []byte) ([]byte, error) {
	if len(env) < headerSize || (env[0] != envelopeVersion && env[0] != envelopeRaw) {
		return nil, ErrFormat
	}
	k, ok := keyring[hex.EncodeToString(env[1:5])]
	if !ok {
		return nil, ErrUnknownKey
	}
	gcm, err := newGCM(k.Content)
	if err != nil {
		return nil, err
	}
	compressed, err := gcm.Open(nil, env[5:headerSize], env[headerSize:], []byte(aad))
	if err != nil {
		return nil, ErrAuth
	}
	if env[0] == envelopeRaw {
		if len(compressed) > MaxPlaintext {
			return nil, ErrFormat
		}
		return compressed, nil
	}
	plain, err := io.ReadAll(io.LimitReader(brotli.NewReader(bytes.NewReader(compressed)), MaxPlaintext+1))
	if err != nil {
		return nil, ErrFormat
	}
	if len(plain) > MaxPlaintext {
		return nil, ErrFormat
	}
	return plain, nil
}

// EnvelopeKeyID returns the key id an envelope was sealed with.
func EnvelopeKeyID(env []byte) (string, error) {
	if len(env) < headerSize || (env[0] != envelopeVersion && env[0] != envelopeRaw) {
		return "", ErrFormat
	}
	return hex.EncodeToString(env[1:5]), nil
}

// BlobID is a keyed hash of a tab's plaintext, so identical tabs dedupe
// without the server learning anything about their contents.
func BlobID(k Keys, plaintext []byte) string {
	mac := hmac.New(sha256.New, k.BlobID[:])
	mac.Write(plaintext)
	return hex.EncodeToString(mac.Sum(nil))[:32]
}

func BlobAAD(id string) string     { return "blob:" + id }
func ManifestAAD(seq int64) string { return "manifest:" + strconv.FormatInt(seq, 10) }
func CommandAAD(id string) string  { return "command:" + id }
func ResultAAD(id string) string   { return "result:" + id }
func StateAAD(name string) string  { return "state:" + name }

func newGCM(key [32]byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}
