import { check, randomId } from './protocol.mjs';

// Enrollment and active subscriptions are separate. Tombstones are never recycled.
export const LIMITS = Object.freeze({ registrations: 120, tombstones: 10000, pending: 30,
  messages: 1000, perRegistration: 100, dailyRequests: 20000 });
export class D1Store {
  constructor(db) { this.db = db; this.statements = 0; }
  query(sql, ...args) { this.statements++; return this.db.prepare(sql).bind(...args); }
  async admit(now) {
    const row = await this.query(`INSERT INTO daily_usage(day, count) VALUES (?, 1)
      ON CONFLICT(day) DO UPDATE SET count = count + 1 WHERE count < ? RETURNING count`,
    Math.floor(now / 86400000), LIMITS.dailyRequests).first();
    check(row, 429, 'daily_capacity');
  }
  async registration(id, hash) {
    const row = await this.query('SELECT * FROM registrations WHERE id = ?', id).first();
    check(!row || row.management_hash === hash, 403, 'forbidden');
    return row;
  }
  async throttle(kind, subject, now, maximum, seconds = 60) {
    const row = await this.query(`INSERT INTO request_usage(kind,subject,window,count) VALUES(?,?,?,1)
      ON CONFLICT(kind,subject,window) DO UPDATE SET count = count + 1 WHERE count < ? RETURNING count`,
    kind, subject, Math.floor(now / (seconds * 1000)), maximum).first();
    check(row, 429, 'rate_limit');
  }
  async registerBound(id, hash, token, key, revision, requestHash, now, allowNew) {
    const before = await this.registration(id, hash);
    check(!before?.deleted, 410, 'registration_retired');
    check(!before?.pending_until || before.pending_until > now, 410, 'registration_retired');
    check(before || allowNew, 503, 'registration_paused');
    if (before?.protocol === 2 && before.revision === revision) {
      check(before.request_hash === requestHash, 409, 'revision_conflict');
      check(!before.invalid, 409, 'registration_invalid');
      return { created: false, deliveryId: before.delivery_id, revision, state: before.server_key ? 'active' : 'pending' };
    }
    check(!before || revision > before.revision, 409, 'revision_conflict');
    // An active key cannot be cleared by a token-only/pending request.
    check(!before?.server_key || key, 409, 'binding_required');
    const deliveryId = randomId();
    const row = await this.query(`INSERT INTO registrations
      (id,management_hash,delivery_id,fcm_token,protocol,server_key,revision,request_hash,pending_until)
      SELECT ?,?,?,?,2,?,?,?,? WHERE
        (EXISTS(SELECT 1 FROM registrations WHERE id = ?) OR
          ((SELECT count(*) FROM registrations WHERE deleted = 0) < ? AND (SELECT count(*) FROM registrations) < ?))
        AND (? IS NOT NULL OR EXISTS(SELECT 1 FROM registrations WHERE id = ? AND deleted = 0 AND protocol = 2 AND server_key IS NULL)
          OR (SELECT count(*) FROM registrations WHERE deleted = 0 AND protocol = 2 AND server_key IS NULL) < ?)
      ON CONFLICT(id) DO UPDATE SET fcm_token = excluded.fcm_token, invalid = 0, invalid_since = NULL,
        protocol = 2, server_key = excluded.server_key, revision = excluded.revision,
        request_hash = excluded.request_hash,
        pending_until = CASE WHEN excluded.server_key IS NULL THEN coalesce(registrations.pending_until, excluded.pending_until) ELSE NULL END
      WHERE registrations.management_hash = excluded.management_hash AND registrations.deleted = 0
        AND registrations.revision < excluded.revision
        AND (registrations.server_key IS NULL OR excluded.server_key IS NOT NULL)
      RETURNING delivery_id, server_key`,
    id, hash, deliveryId, token, key, revision, requestHash, key ? null : now + 86400000,
    id, LIMITS.registrations, LIMITS.tombstones, key, id, LIMITS.pending).first();
    if (!row) {
      const current = await this.registration(id, hash);
      check(!current?.deleted, 410, 'registration_retired');
      if (current?.revision === revision && current.request_hash === requestHash && !current.invalid) {
        return { created: false, deliveryId: current.delivery_id, revision, state: current.server_key ? 'active' : 'pending' };
      }
      check(!current || current.revision < revision, 409, 'revision_conflict');
      check(false, 429, 'registration_capacity');
    }
    return { created: row.delivery_id === deliveryId, deliveryId: row.delivery_id, revision, state: row.server_key ? 'active' : 'pending' };
  }
  async register(id, hash, token) {
    const before = await this.registration(id, hash);
    check(!before?.deleted, 410, 'registration_retired');
    check(before?.protocol !== 2, 426, 'bound_registration_required');
    const deliveryId = randomId();
    const row = await this.query(`INSERT INTO registrations(id, management_hash, delivery_id, fcm_token)
      SELECT ?, ?, ?, ? WHERE EXISTS(SELECT 1 FROM registrations WHERE id = ?)
        OR (SELECT count(*) FROM registrations) < ?
      ON CONFLICT(id) DO UPDATE SET fcm_token = excluded.fcm_token, invalid = 0
        WHERE registrations.management_hash = excluded.management_hash AND registrations.deleted = 0 AND registrations.protocol = 1
      RETURNING delivery_id`, id, hash, deliveryId, token, id, LIMITS.registrations).first();
    if (!row) {
      const current = await this.registration(id, hash);
      check(!current?.deleted, 410, 'registration_retired');
      check(false, 429, 'registration_capacity');
    }
    return { created: row.delivery_id === deliveryId, deliveryId: row.delivery_id };
  }
  async remove(id, hash) {
    await this.registration(id, hash);
    // Authorization is also checked within the SQL, so a racing initial PUT cannot
    // replace the owner or allow another caller to remove its queued messages.
    const results = await this.db.batch([
      this.query(`INSERT INTO registrations(id, management_hash, deleted)
        SELECT ?, ?, 1 WHERE EXISTS(SELECT 1 FROM registrations WHERE id = ?)
          OR (SELECT count(*) FROM registrations) < ?
        ON CONFLICT(id) DO UPDATE SET deleted = 1, fcm_token = NULL, delivery_id = NULL, server_key = NULL, pending_until = NULL, invalid_since = NULL
          WHERE registrations.management_hash = excluded.management_hash`, id, hash, id, LIMITS.tombstones),
      this.query(`DELETE FROM messages WHERE registration_id = ? AND EXISTS
        (SELECT 1 FROM registrations WHERE id = ? AND management_hash = ? AND deleted = 1)`, id, id, hash),
    ]);
    if (!results[0].meta.changes) {
      await this.registration(id, hash);
      check(false, 429, 'registration_capacity');
    }
  }
  async destination(deliveryId) {
    const row = await this.query('SELECT id, protocol, server_key, revision, pending_until FROM registrations WHERE delivery_id = ? AND deleted = 0 AND invalid = 0', deliveryId).first();
    check(row, 410, 'subscription_gone');
    return row;
  }
  async enqueue(registrationId, message, now, binding = null) {
    const id = randomId();
    if (message.ttl === 0) return id;
    const result = await this.query(`INSERT INTO messages
      (id, registration_id, encoding, headers, body, expires_at, next_attempt)
      SELECT ?, ?, ?, ?, ?, ?, ? WHERE
        EXISTS(SELECT 1 FROM registrations WHERE id = ? AND deleted = 0 AND invalid = 0
          AND (protocol = 1 OR server_key IS NOT NULL)
          AND (? = 0 OR (protocol = ? AND server_key IS ? AND revision = ?)))
        AND (SELECT count(*) FROM messages) < ?
        AND (SELECT count(*) FROM messages WHERE registration_id = ?) < ?`,
    id, registrationId, message.encoding, message.headers, message.body, message.expires_at, now,
    registrationId, binding ? 1 : 0, binding?.protocol ?? 1, binding?.server_key ?? null, binding?.revision ?? 0,
    LIMITS.messages, registrationId, LIMITS.perRegistration).run();
    if (!result.meta.changes) {
      const row = await this.query('SELECT deleted, invalid FROM registrations WHERE id = ?', registrationId).first();
      check(row && !row.deleted && !row.invalid, 410, 'subscription_gone');
      if (binding) {
        const current = await this.query('SELECT protocol,server_key,revision FROM registrations WHERE id = ?', registrationId).first();
        check(current?.protocol === binding.protocol && current.server_key === binding.server_key && current.revision === binding.revision,
          409, 'binding_changed');
      }
      check(false, 429, 'queue_capacity');
    }
    return id;
  }
  async message(id, messageId, hash, now) {
    const registration = await this.registration(id, hash);
    check(registration && !registration.deleted && !registration.invalid, 404, 'not_found');
    const row = await this.query(`SELECT m.* FROM messages m JOIN registrations r ON r.id = m.registration_id
      WHERE m.id = ? AND m.registration_id = ? AND m.expires_at > ? AND r.deleted = 0 AND r.invalid = 0`, messageId, id, now).first();
    check(row, 404, 'not_found');
    return row;
  }
  async prune(now) {
    await this.db.batch([
      this.query('DELETE FROM messages WHERE expires_at <= ?', now),
      this.query('DELETE FROM daily_usage WHERE day < ?', Math.floor(now / 86400000) - 1),
      this.query('DELETE FROM request_usage WHERE window < ?', Math.floor(now / 60000) - 1),
      this.query(`DELETE FROM messages WHERE registration_id IN (SELECT id FROM registrations WHERE deleted = 0
        AND (pending_until <= ? OR invalid_since <= ?))`, now, now - 30 * 86400000),
      this.query(`UPDATE registrations SET deleted = 1, fcm_token = NULL, delivery_id = NULL, server_key = NULL,
        pending_until = NULL, invalid_since = NULL WHERE deleted = 0 AND (pending_until <= ? OR invalid_since <= ?)`, now, now - 30 * 86400000),
    ]);
  }
  async due(now, maximum = 20) {
    // Round-robin ordering gives each registration its oldest due message first.
    return (await this.query(`SELECT id FROM (SELECT m.id,m.next_attempt,
      row_number() OVER (PARTITION BY m.registration_id ORDER BY m.next_attempt,m.id) AS turn
      FROM messages m JOIN registrations r ON r.id = m.registration_id
      WHERE m.delivered = 0 AND m.next_attempt <= ? AND m.lease_until <= ? AND m.expires_at > ?
        AND r.deleted = 0 AND r.invalid = 0 AND (r.protocol = 1 OR r.server_key IS NOT NULL))
      ORDER BY turn,next_attempt,id LIMIT ?`, now, now, now, maximum).all()).results;
  }
  async claim(id, now) {
    const lease = randomId();
    const row = await this.query(`UPDATE messages SET lease_id = ?, lease_until = ?
      WHERE id = ? AND delivered = 0 AND lease_until <= ? AND next_attempt <= ? AND expires_at > ?
        AND EXISTS(SELECT 1 FROM registrations r WHERE r.id = messages.registration_id AND r.deleted = 0 AND r.invalid = 0
          AND (r.protocol = 1 OR r.server_key IS NOT NULL))
      RETURNING *, (SELECT fcm_token FROM registrations WHERE id = messages.registration_id AND deleted = 0 AND invalid = 0) AS token`,
    lease, now + 60000, id, now, now, now).first();
    if (!row) return undefined;
    return row.token ? row : undefined;
  }
  async complete(message, result, transport, now) {
    const guard = `id = ? AND lease_id = ? AND EXISTS(SELECT 1 FROM registrations r
      WHERE r.id = messages.registration_id AND r.fcm_token = ? AND r.deleted = 0 AND r.invalid = 0)`;
    const args = [message.id, message.lease_id, message.token];
    let changed = false;
    if (result.kind === 'invalid') {
      // All registrations using this invalid token are retired, but only while this
      // claim still belongs to the same token. A late response cannot undo a refresh.
      const results = await this.db.batch([
        this.query(`UPDATE registrations SET invalid = 1, invalid_since = ? WHERE fcm_token = ? AND EXISTS
          (SELECT 1 FROM messages WHERE ${guard})`, now, message.token, ...args),
        this.query(`DELETE FROM messages WHERE registration_id IN
          (SELECT id FROM registrations WHERE fcm_token = ? AND invalid = 1)`, message.token),
        this.query('UPDATE registrations SET fcm_token = NULL WHERE fcm_token = ? AND invalid = 1', message.token),
      ]);
      changed = results.some(result => result.meta.changes > 0);
    } else if (result.kind === 'permanent' || result.kind === 'expired' || (result.kind === 'success' && transport === 'inline') ||
      (result.kind === 'retry' && message.attempts >= 7)) {
      changed = (await this.query(`DELETE FROM messages WHERE ${guard}`, ...args).run()).meta.changes > 0;
    } else if (result.kind === 'success') {
      changed = (await this.query(`UPDATE messages SET delivered = 1, lease_id = NULL, lease_until = 0 WHERE ${guard}`, ...args).run()).meta.changes > 0;
    } else {
      const jitter = (result.jitter ?? Math.random()) * 0.25;
      const delay = Math.max(result.delaySeconds ?? 60, Math.min(3600, 60 * 2 ** message.attempts) * (1 + jitter));
      changed = (await this.query(`UPDATE messages SET attempts = attempts + 1, next_attempt = ?, lease_id = NULL, lease_until = 0
        WHERE ${guard}`, now + delay * 1000, ...args).run()).meta.changes > 0;
    }
    // If a token changed during I/O, release our old claim for the new destination.
    if (!changed) await this.query(`UPDATE messages SET lease_id = NULL, lease_until = 0, next_attempt = ?
      WHERE id = ? AND lease_id = ?`, now, message.id, message.lease_id).run();
  }
}
