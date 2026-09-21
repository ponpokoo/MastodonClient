CREATE TABLE IF NOT EXISTS registrations (
  id TEXT PRIMARY KEY,
  management_hash TEXT NOT NULL,
  delivery_id TEXT UNIQUE,
  fcm_token TEXT,
  deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1)),
  invalid INTEGER NOT NULL DEFAULT 0 CHECK (invalid IN (0, 1))
);
CREATE INDEX IF NOT EXISTS registrations_token ON registrations(fcm_token);

CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  registration_id TEXT NOT NULL REFERENCES registrations(id),
  encoding TEXT NOT NULL,
  headers TEXT NOT NULL,
  body TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  next_attempt INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  delivered INTEGER NOT NULL DEFAULT 0 CHECK (delivered IN (0, 1)),
  lease_id TEXT,
  lease_until INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS messages_due ON messages(delivered, next_attempt);
CREATE INDEX IF NOT EXISTS messages_expiry ON messages(expires_at);
CREATE INDEX IF NOT EXISTS messages_registration ON messages(registration_id);

-- Application admission limit, not a replacement for edge abuse protection.
CREATE TABLE IF NOT EXISTS daily_usage (
  day INTEGER PRIMARY KEY,
  count INTEGER NOT NULL
);
