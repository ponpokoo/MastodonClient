-- Additive migration: keep IDs, delivery endpoints, tombstones and queued ciphertext.
ALTER TABLE registrations ADD COLUMN protocol INTEGER NOT NULL DEFAULT 1;
ALTER TABLE registrations ADD COLUMN server_key TEXT;
ALTER TABLE registrations ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE registrations ADD COLUMN request_hash TEXT;
ALTER TABLE registrations ADD COLUMN pending_until INTEGER;
ALTER TABLE registrations ADD COLUMN invalid_since INTEGER;
-- Historical invalidation time was not recorded. Start its retention clock at migration.
UPDATE registrations SET invalid_since = unixepoch() * 1000 WHERE invalid = 1 AND deleted = 0;
CREATE INDEX registrations_pending ON registrations(pending_until);
CREATE TABLE request_usage (
  kind TEXT NOT NULL,
  subject TEXT NOT NULL,
  window INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY(kind, subject, window)
);
