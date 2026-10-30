package store

import "time"

// Prune applies the Time Machine retention policy, using each snapshot's At:
//   - younger than 24h: keep the newest per 5-minute bucket
//   - younger than 30 days: keep the newest per hour
//   - older: keep the newest per day
//
// The latest snapshot and snapshots not yet uploaded are always kept. Blobs no
// longer referenced by any snapshot are deleted.
func (s *Store) Prune(now time.Time) (removedSnapshots, removedBlobs int, err error) {
	rows, err := s.db.Query(`SELECT seq, at, uploaded FROM snapshots ORDER BY seq DESC`)
	if err != nil {
		return 0, 0, err
	}
	type row struct {
		seq      int64
		at       int64
		uploaded bool
	}
	var all []row
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.seq, &r.at, &r.uploaded); err != nil {
			rows.Close()
			return 0, 0, err
		}
		all = append(all, r)
	}
	rows.Close()

	nowMs := now.UnixMilli()
	seen := map[string]bool{}
	var drop []int64
	for i, r := range all { // newest first, so the first per bucket is kept
		key := bucket(nowMs, r.at)
		if i == 0 || !r.uploaded || !seen[key] {
			seen[key] = true
			continue
		}
		drop = append(drop, r.seq)
	}

	tx, err := s.db.Begin()
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()
	for _, seq := range drop {
		if _, err := tx.Exec(`DELETE FROM snapshots WHERE seq = ?`, seq); err != nil {
			return 0, 0, err
		}
	}
	res, err := tx.Exec(`DELETE FROM blobs WHERE id NOT IN (SELECT blob_id FROM snapshot_blobs)`)
	if err != nil {
		return 0, 0, err
	}
	n, _ := res.RowsAffected()
	return len(drop), int(n), tx.Commit()
}

func bucket(nowMs, atMs int64) string {
	age := time.Duration(nowMs-atMs) * time.Millisecond
	t := time.UnixMilli(atMs).UTC()
	switch {
	case age < 24*time.Hour:
		return "m" + t.Truncate(5*time.Minute).Format(time.RFC3339)
	case age < 30*24*time.Hour:
		return "h" + t.Truncate(time.Hour).Format(time.RFC3339)
	default:
		return "d" + t.Format("2006-01-02")
	}
}
