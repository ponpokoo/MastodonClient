import { check, randomId } from './protocol.mjs';

// Small trial limits. Tombstones count toward capacity and are not recycled.
export const LIMITS = Object.freeze({ registrations: 100, messages: 200, perRegistration: 20, dailyRequests: 2000 });
export class D1Store {
  constructor(db) { this.db = db; }
  query(sql, ...args) { return this.db.prepare(sql).bind(...args); }
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
  async register(id, hash, token) {
    const before = await this.registration(id, hash);
    check(!before?.deleted, 410, 'registration_retired');
    const deliveryId = randomId();
    const row = await this.query(`INSERT INTO registrations(id, management_hash, delivery_id, fcm_token)
      SELECT ?, ?, ?, ? WHERE EXISTS(SELECT 1 FROM registrations WHERE id = ?)
        OR (SELECT count(*) FROM registrations) < ?
      ON CONFLICT(id) DO UPDATE SET fcm_token = excluded.fcm_token, invalid = 0
        WHERE registrations.management_hash = excluded.management_hash AND registrations.deleted = 0
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
        ON CONFLICT(id) DO UPDATE SET deleted = 1, fcm_token = NULL, delivery_id = NULL
          WHERE registrations.management_hash = excluded.management_hash`, id, hash, id, LIMITS.registrations),
      this.query(`DELETE FROM messages WHERE registration_id = ? AND EXISTS
        (SELECT 1 FROM registrations WHERE id = ? AND management_hash = ? AND deleted = 1)`, id, id, hash),
    ]);
    if (!results[0].meta.changes) {
      await this.registration(id, hash);
      check(false, 429, 'registration_capacity');
    }
  }
  async destination(deliveryId) {
    const row = await this.query('SELECT id FROM registrations WHERE delivery_id = ? AND deleted = 0 AND invalid = 0', deliveryId).first();
    check(row, 410, 'subscription_gone');
    return row.id;
  }
  async enqueue(registrationId, message, now) {
    const id = randomId();
    if (message.ttl === 0) return id;
    const result = await this.query(`INSERT INTO messages
      (id, registration_id, encoding, headers, body, expires_at, next_attempt)
      SELECT ?, ?, ?, ?, ?, ?, ? WHERE
        EXISTS(SELECT 1 FROM registrations WHERE id = ? AND deleted = 0 AND invalid = 0)
        AND (SELECT count(*) FROM messages) < ?
        AND (SELECT count(*) FROM messages WHERE registration_id = ?) < ?`,
    id, registrationId, message.encoding, message.headers, message.body, message.expires_at, now,
    registrationId, LIMITS.messages, registrationId, LIMITS.perRegistration).run();
    if (!result.meta.changes) {
      const row = await this.query('SELECT deleted, invalid FROM registrations WHERE id = ?', registrationId).first();
      check(row && !row.deleted && !row.invalid, 410, 'subscription_gone');
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
    ]);
  }
  async due(now) {
    // One retry per tick deliberately keeps CPU use bounded on Workers Free.
    return this.query(`SELECT id FROM messages WHERE delivered = 0 AND next_attempt <= ?
      AND lease_until <= ? AND expires_at > ? ORDER BY next_attempt LIMIT 1`, now, now, now).first();
  }
  async claim(id, now) {
    const lease = randomId();
    const row = await this.query(`UPDATE messages SET lease_id = ?, lease_until = ?
      WHERE id = ? AND delivered = 0 AND lease_until <= ? AND next_attempt <= ? AND expires_at > ?
        AND EXISTS(SELECT 1 FROM registrations r WHERE r.id = messages.registration_id AND r.deleted = 0 AND r.invalid = 0)
      RETURNING *`, lease, now + 30000, id, now, now, now).first();
    if (!row) return undefined;
    const registration = await this.query('SELECT fcm_token FROM registrations WHERE id = ? AND deleted = 0 AND invalid = 0', row.registration_id).first();
    return registration?.fcm_token ? { ...row, token: registration.fcm_token } : undefined;
  }
  async complete(message, result, transport, now) {
    const guard = `id = ? AND lease_id = ? AND EXISTS(SELECT 1 FROM registrations r
      WHERE r.id = messages.registration_id AND r.fcm_token = ? AND r.deleted = 0 AND r.invalid = 0)`;
    const args = [message.id, message.lease_id, message.token];
    if (result.kind === 'invalid') {
      // All registrations using this invalid token are retired, but only while this
      // claim still belongs to the same token. A late response cannot undo a refresh.
      await this.db.batch([
        this.query(`UPDATE registrations SET invalid = 1 WHERE fcm_token = ? AND EXISTS
          (SELECT 1 FROM messages WHERE ${guard})`, message.token, ...args),
        this.query(`DELETE FROM messages WHERE registration_id IN
          (SELECT id FROM registrations WHERE fcm_token = ? AND invalid = 1)`, message.token),
        this.query('UPDATE registrations SET fcm_token = NULL WHERE fcm_token = ? AND invalid = 1', message.token),
      ]);
    } else if (result.kind === 'expired' || (result.kind === 'success' && transport === 'inline') ||
      (result.kind === 'retry' && message.attempts >= 7)) {
      await this.query(`DELETE FROM messages WHERE ${guard}`, ...args).run();
    } else if (result.kind === 'success') {
      await this.query(`UPDATE messages SET delivered = 1, lease_id = NULL, lease_until = 0 WHERE ${guard}`, ...args).run();
    } else {
      const delay = Math.max(result.delaySeconds ?? 60, Math.min(3600, 60 * 2 ** message.attempts));
      await this.query(`UPDATE messages SET attempts = attempts + 1, next_attempt = ?, lease_id = NULL, lease_until = 0
        WHERE ${guard}`, now + delay * 1000, ...args).run();
    }
    // If a token changed during I/O, release our old claim for the new destination.
    await this.query(`UPDATE messages SET lease_id = NULL, lease_until = 0, next_attempt = ?
      WHERE id = ? AND lease_id = ?`, now, message.id, message.lease_id).run();
  }
}
