// Package store keeps encrypted blobs and snapshot manifests in SQLite. It is
// used by both the relay server and the Mac agent; it never sees plaintext.
package store

import (
	"database/sql"
	"errors"
	"fmt"

	_ "modernc.org/sqlite"
)

var (
	ErrNotFound  = errors.New("store: not found")
	ErrStaleSeq  = errors.New("store: seq not newer than latest")
	errNoBlobRef = errors.New("store: snapshot references a missing blob")
)

type Store struct {
	db *sql.DB
}

// Snapshot is a sealed manifest plus the blob ids it references.
type Snapshot struct {
	Seq      int64
	At       int64 // unix ms, when this store received it
	Envelope []byte
	Blobs    []string
	Uploaded bool
}

const schema = `
CREATE TABLE IF NOT EXISTS blobs (id TEXT PRIMARY KEY, env BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS snapshots (
	seq INTEGER PRIMARY KEY,
	at INTEGER NOT NULL,
	env BLOB NOT NULL,
	uploaded INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS snapshot_blobs (
	seq INTEGER NOT NULL REFERENCES snapshots(seq) ON DELETE CASCADE,
	blob_id TEXT NOT NULL,
	PRIMARY KEY (seq, blob_id)
);
CREATE INDEX IF NOT EXISTS snapshot_blobs_blob ON snapshot_blobs(blob_id);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, val TEXT NOT NULL);
`

func Open(path string) (*Store, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) PutBlob(id string, env []byte) error {
	_, err := s.db.Exec(`INSERT OR IGNORE INTO blobs(id, env) VALUES(?, ?)`, id, env)
	return err
}

func (s *Store) HasBlob(id string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM blobs WHERE id = ?`, id).Scan(&n)
	return n > 0, err
}

func (s *Store) GetBlob(id string) ([]byte, error) {
	var env []byte
	err := s.db.QueryRow(`SELECT env FROM blobs WHERE id = ?`, id).Scan(&env)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return env, err
}

// AddSnapshot stores a snapshot whose blobs must already be present.
func (s *Store) AddSnapshot(snap Snapshot) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var maxSeq sql.NullInt64
	if err := tx.QueryRow(`SELECT MAX(seq) FROM snapshots`).Scan(&maxSeq); err != nil {
		return err
	}
	if maxSeq.Valid && snap.Seq <= maxSeq.Int64 {
		return ErrStaleSeq
	}
	if _, err := tx.Exec(`INSERT INTO snapshots(seq, at, env, uploaded) VALUES(?, ?, ?, ?)`,
		snap.Seq, snap.At, snap.Envelope, snap.Uploaded); err != nil {
		return err
	}
	for _, id := range snap.Blobs {
		var n int
		if err := tx.QueryRow(`SELECT COUNT(*) FROM blobs WHERE id = ?`, id).Scan(&n); err != nil {
			return err
		}
		if n == 0 {
			return fmt.Errorf("%w: %s", errNoBlobRef, id)
		}
		if _, err := tx.Exec(`INSERT OR IGNORE INTO snapshot_blobs(seq, blob_id) VALUES(?, ?)`, snap.Seq, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// IsMissingBlob reports whether err came from AddSnapshot referencing an
// unknown blob.
func IsMissingBlob(err error) bool { return errors.Is(err, errNoBlobRef) }

func (s *Store) Latest() (Snapshot, error) {
	return s.getSnapshot(`SELECT seq, at, env, uploaded FROM snapshots ORDER BY seq DESC LIMIT 1`)
}

func (s *Store) GetSnapshot(seq int64) (Snapshot, error) {
	return s.getSnapshot(`SELECT seq, at, env, uploaded FROM snapshots WHERE seq = ?`, seq)
}

func (s *Store) getSnapshot(q string, args ...any) (Snapshot, error) {
	var snap Snapshot
	err := s.db.QueryRow(q, args...).Scan(&snap.Seq, &snap.At, &snap.Envelope, &snap.Uploaded)
	if errors.Is(err, sql.ErrNoRows) {
		return snap, ErrNotFound
	}
	if err != nil {
		return snap, err
	}
	snap.Blobs, err = s.blobsOf(snap.Seq)
	return snap, err
}

func (s *Store) blobsOf(seq int64) ([]string, error) {
	rows, err := s.db.Query(`SELECT blob_id FROM snapshot_blobs WHERE seq = ? ORDER BY blob_id`, seq)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// List returns snapshots (without envelopes) newest first, with seq < before
// (before <= 0 means no bound).
func (s *Store) List(before int64, limit int) ([]Snapshot, error) {
	if before <= 0 {
		before = 1<<62 - 1
	}
	rows, err := s.db.Query(`SELECT seq, at FROM snapshots WHERE seq < ? ORDER BY seq DESC LIMIT ?`, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Snapshot
	for rows.Next() {
		var snap Snapshot
		if err := rows.Scan(&snap.Seq, &snap.At); err != nil {
			return nil, err
		}
		out = append(out, snap)
	}
	return out, rows.Err()
}

// PendingUploads returns snapshots not yet uploaded, oldest first.
func (s *Store) PendingUploads() ([]Snapshot, error) {
	rows, err := s.db.Query(`SELECT seq FROM snapshots WHERE uploaded = 0 ORDER BY seq`)
	if err != nil {
		return nil, err
	}
	var seqs []int64
	for rows.Next() {
		var seq int64
		if err := rows.Scan(&seq); err != nil {
			rows.Close()
			return nil, err
		}
		seqs = append(seqs, seq)
	}
	rows.Close()
	out := make([]Snapshot, 0, len(seqs))
	for _, seq := range seqs {
		snap, err := s.GetSnapshot(seq)
		if err != nil {
			return nil, err
		}
		out = append(out, snap)
	}
	return out, nil
}

func (s *Store) MarkUploaded(seq int64) error {
	_, err := s.db.Exec(`UPDATE snapshots SET uploaded = 1 WHERE seq = ?`, seq)
	return err
}

func (s *Store) MaxSeq() (int64, error) {
	var n sql.NullInt64
	err := s.db.QueryRow(`SELECT MAX(seq) FROM snapshots`).Scan(&n)
	return n.Int64, err
}

func (s *Store) GetMeta(key string) (string, error) {
	var v string
	err := s.db.QueryRow(`SELECT val FROM meta WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return v, err
}

func (s *Store) SetMeta(key, val string) error {
	_, err := s.db.Exec(`INSERT INTO meta(key, val) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET val = excluded.val`, key, val)
	return err
}
