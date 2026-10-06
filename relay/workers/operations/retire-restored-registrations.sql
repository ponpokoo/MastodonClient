-- Manual recovery only, with RELAY_ENABLED=false and all partial switches false.
-- Use when post-backup removals cannot be reconstructed. Never delete tombstones.
DELETE FROM messages;
UPDATE registrations SET deleted = 1, fcm_token = NULL, delivery_id = NULL,
  server_key = NULL, pending_until = NULL, invalid_since = NULL, request_hash = NULL;
