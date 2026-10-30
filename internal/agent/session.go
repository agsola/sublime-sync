package agent

import (
	"context"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/agsola/sublime-sync/internal/proto"
)

type sessionFile struct {
	Windows []struct {
		WindowID int64 `json:"window_id"`
		Buffers  []struct {
			File     *string `json:"file"`
			Contents *string `json:"contents"`
			Settings struct {
				Name string `json:"name"`
			} `json:"settings"`
		} `json:"buffers"`
	} `json:"windows"`
}

// ParseSession extracts the open tabs from Sublime's "Auto Save Session"
// file. Buffers backed by a file without unsaved contents are read from disk.
func ParseSession(r io.Reader) ([]proto.Tab, error) {
	var s sessionFile
	if err := json.NewDecoder(r).Decode(&s); err != nil {
		return nil, err
	}
	var tabs []proto.Tab
	for wi, w := range s.Windows {
		win := w.WindowID
		if win == 0 {
			win = int64(wi + 1)
		}
		for bi, b := range w.Buffers {
			t := proto.Tab{ID: win*1000 + int64(bi), Window: win, Index: bi, Path: b.File}
			switch {
			case b.Contents != nil:
				t.Text, t.Dirty = *b.Contents, true
			case b.File != nil:
				text, ok := readSmallText(*b.File)
				if !ok {
					continue
				}
				t.Text = text
				if fi, err := os.Stat(*b.File); err == nil {
					t.Modified = fi.ModTime().UnixMilli()
				}
			default:
				continue
			}
			t.Title = TabTitle(b.Settings.Name, b.File, t.Text)
			tabs = append(tabs, t)
		}
	}
	return tabs, nil
}

func readSmallText(path string) (string, bool) {
	f, err := os.Open(path)
	if err != nil {
		return "", false
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, proto.MaxTabBytes+1))
	if err != nil || !utf8.Valid(b) {
		return "", false
	}
	return string(b), true
}

// TabTitle mirrors the plugin's rule: view name, file name, first non-empty
// line (max 80 chars), "untitled".
func TabTitle(name string, path *string, text string) string {
	if name != "" {
		return name
	}
	if path != nil && *path != "" {
		return filepath.Base(*path)
	}
	for _, line := range strings.Split(text, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			if utf8.RuneCountInString(line) > 80 {
				line = string([]rune(line)[:80])
			}
			return line
		}
	}
	return "untitled"
}

// WatchSession polls the session file every interval and calls onTabs when
// it changed and the plugin has been silent for longer than silence.
func WatchSession(ctx context.Context, path string, interval, silence time.Duration, lastPlugin func() time.Time, onTabs func([]proto.Tab)) {
	var lastMod time.Time
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		if time.Since(lastPlugin()) < silence {
			continue
		}
		fi, err := os.Stat(path)
		if err != nil || !fi.ModTime().After(lastMod) {
			continue
		}
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		tabs, err := ParseSession(f)
		f.Close()
		if err != nil {
			continue
		}
		lastMod = fi.ModTime()
		onTabs(tabs)
	}
}
