package agent

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/agsola/sublime-sync/internal/proto"
	"github.com/zalando/go-keyring"
)

const (
	keychainService = "io.github.agsola.sublime-sync"
	keychainCurrent = "master-key"
)

// Config is the agent's local, non-secret configuration.
type Config struct {
	Server    string   `json:"server"`
	Mac       string   `json:"mac"`
	OldKeyIDs []string `json:"oldKeyIds,omitempty"`
}

// DataDir is ~/Library/Application Support/sublime-sync, created 0700.
func DataDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, "Library", "Application Support", "sublime-sync")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, os.Chmod(dir, 0o700)
}

func LoadConfig(dir string) (Config, error) {
	var c Config
	b, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err != nil {
		return c, err
	}
	return c, json.Unmarshal(b, &c)
}

func SaveConfig(dir string, c Config) error {
	b, _ := json.MarshalIndent(c, "", "  ")
	return os.WriteFile(filepath.Join(dir, "config.json"), append(b, '\n'), 0o600)
}

// ErrNoKey means the agent has not been initialised yet.
var ErrNoKey = errors.New("no master key in the keychain; run 'sublime-sync-agent init'")

func LoadMasterKey() (proto.MasterKey, error) {
	return loadKey(keychainCurrent)
}

func loadKey(account string) (proto.MasterKey, error) {
	var m proto.MasterKey
	s, err := keyring.Get(keychainService, account)
	if errors.Is(err, keyring.ErrNotFound) {
		return m, ErrNoKey
	}
	if err != nil {
		return m, err
	}
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil || len(b) != len(m) {
		return m, fmt.Errorf("corrupt key in keychain")
	}
	copy(m[:], b)
	return m, nil
}

func SaveMasterKey(m proto.MasterKey) error {
	return keyring.Set(keychainService, keychainCurrent, base64.RawURLEncoding.EncodeToString(m[:]))
}

// ArchiveMasterKey keeps an old key so history sealed with it stays readable.
func ArchiveMasterKey(m proto.MasterKey) error {
	return keyring.Set(keychainService, keychainCurrent+"-"+m.KeyID(), base64.RawURLEncoding.EncodeToString(m[:]))
}

// Keyring returns derived keys for the current key and archived ones.
func Keyring(c Config) (current proto.Keys, all map[string]proto.Keys, err error) {
	m, err := LoadMasterKey()
	if err != nil {
		return current, nil, err
	}
	current = proto.Derive(m)
	all = map[string]proto.Keys{current.ID: current}
	for _, id := range c.OldKeyIDs {
		if old, err := loadKey(keychainCurrent + "-" + id); err == nil {
			all[id] = proto.Derive(old)
		}
	}
	return current, all, nil
}
