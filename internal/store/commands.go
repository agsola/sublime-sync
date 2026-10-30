package store

import (
	"database/sql"
	"errors"
	"time"
)

// ErrVersionConflict means a state write used an outdated version.
var ErrVersionConflict = errors.New("store: state version conflict")

// Command is a sealed phone→Mac command and, once handled, its sealed result.
type Command struct {
	ID       string
	Created  int64
	Envelope []byte
	Result   []byte // nil until the Mac answers
	ResultAt int64
}

// AddCommand stores a command; adding the same id again does nothing.
func (s *Store) AddCommand(id string, created int64, env []byte) error {
	_, err := s.db.Exec(`INSERT OR IGNORE INTO commands(id, created, env) VALUES(?, ?, ?)`, id, created, env)
	return err
}

func (s *Store) GetCommand(id string) (Command, error) {
	var c Command
	var at sql.NullInt64
	err := s.db.QueryRow(`SELECT id, created, env, result, result_at FROM commands WHERE id = ?`, id).
		Scan(&c.ID, &c.Created, &c.Envelope, &c.Result, &at)
	if errors.Is(err, sql.ErrNoRows) {
		return c, ErrNotFound
	}
	c.ResultAt = at.Int64
	return c, err
}

// PendingCommands returns commands without a result, oldest first.
func (s *Store) PendingCommands() ([]Command, error) {
	rows, err := s.db.Query(`SELECT id, created, env FROM commands WHERE result IS NULL ORDER BY created, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Command
	for rows.Next() {
		var c Command
		if err := rows.Scan(&c.ID, &c.Created, &c.Envelope); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// SetCommandResult records the first answer to a command; later answers
// (e.g. a duplicate apply) never replace it.
func (s *Store) SetCommandResult(id string, env []byte, at int64) error {
	res, err := s.db.Exec(`UPDATE commands SET result = ?, result_at = ? WHERE id = ? AND result IS NULL`, env, at, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		if _, err := s.GetCommand(id); err != nil {
			return err
		}
	}
	return nil
}

// PruneCommands drops commands answered more than 7 days ago, and unanswered
// ones older than that (the Mac rejects them anyway).
func (s *Store) PruneCommands(now time.Time) (int, error) {
	cutoff := now.Add(-7 * 24 * time.Hour).UnixMilli()
	res, err := s.db.Exec(`DELETE FROM commands WHERE (result IS NOT NULL AND result_at < ?) OR (result IS NULL AND created < ?)`, cutoff, cutoff)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// GetState returns a named sealed document and its version.
func (s *Store) GetState(name string) (int64, []byte, error) {
	var v int64
	var env []byte
	err := s.db.QueryRow(`SELECT version, env FROM state WHERE name = ?`, name).Scan(&v, &env)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil, ErrNotFound
	}
	return v, env, err
}

// PutState writes a document only if its current version is ifVersion
// (0 = must not exist yet) and returns the new version.
func (s *Store) PutState(name string, ifVersion int64, env []byte) (int64, error) {
	var res sql.Result
	var err error
	if ifVersion == 0 {
		res, err = s.db.Exec(`INSERT OR IGNORE INTO state(name, version, env) VALUES(?, 1, ?)`, name, env)
	} else {
		res, err = s.db.Exec(`UPDATE state SET version = version + 1, env = ? WHERE name = ? AND version = ?`, env, name, ifVersion)
	}
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, ErrVersionConflict
	}
	return ifVersion + 1, nil
}

// MarkApplied remembers that the Mac applied a command, so replays are refused.
func (s *Store) MarkApplied(id string, at int64) error {
	_, err := s.db.Exec(`INSERT OR IGNORE INTO applied(id, at) VALUES(?, ?)`, id, at)
	return err
}

func (s *Store) WasApplied(id string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM applied WHERE id = ?`, id).Scan(&n)
	return n > 0, err
}
