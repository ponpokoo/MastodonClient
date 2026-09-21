import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';

const randomId = () => randomBytes(32).toString('base64url');
const hash = value => createHash('sha256').update(value).digest('hex');
export class HttpError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
function check(condition, status, code) { if (!condition) throw new HttpError(status, code); }
const limits = { registrations: 1000, messages: 1000, perRegistration: 100, ttl: 86400 };

export class MockSender {
  constructor(mode = 'success') {
    if (!['success', 'transient', 'invalid', 'fail-once'].includes(mode)) throw new Error('Invalid mock mode');
    this.mode = mode; this.attempts = 0; this.accepted = 0;
  }
  async send(_token, _data) {
    this.attempts++;
    if (this.mode === 'transient' || (this.mode === 'fail-once' && this.attempts === 1)) return 'transient';
    if (this.mode === 'invalid') return 'invalid';
    this.accepted++;
    return 'success';
  }
}

export class Relay {
  constructor(store, sender, { now = Date.now } = {}) {
    this.store = store; this.sender = sender; this.now = now; this.running = false;
  }
  authenticate(id, authorization) {
    check(/^[A-Za-z0-9_-]{22,128}$/.test(id), 400, 'invalid_registration_id');
    check(/^Bearer [A-Za-z0-9_-]{43,128}$/.test(authorization ?? ''), 401, 'unauthorized');
    const digest = hash(authorization.slice(7));
    const record = this.store.state.registrations[id];
    if (record) check(timingSafeEqual(Buffer.from(record.managementHash, 'hex'), Buffer.from(digest, 'hex')), 403, 'forbidden');
    return { record, digest };
  }
  register(id, authorization, body) {
    const { record, digest } = this.authenticate(id, authorization);
    check(!record?.deleted, 410, 'registration_retired');
    check(body && typeof body.fcmToken === 'string' && body.fcmToken.length > 0 && body.fcmToken.length <= 4096 &&
      !/[\s\x00-\x1f]/.test(body.fcmToken) && Object.keys(body).every(key => key === 'fcmToken'), 400, 'invalid_registration');
    check(record || Object.keys(this.store.state.registrations).length < limits.registrations, 429, 'registration_capacity');
    const deliveryId = record?.deliveryId ?? randomId();
    this.store.change(state => {
      state.registrations[id] = { managementHash: digest, deliveryId, fcmToken: body.fcmToken, deleted: false, invalid: false };
    });
    return { deliveryId, created: !record };
  }
  remove(id, authorization) {
    const { record, digest } = this.authenticate(id, authorization);
    // A tombstone is required even if a delayed initial PUT has not reached this process yet.
    check(record || Object.keys(this.store.state.registrations).length < limits.registrations, 429, 'registration_capacity');
    this.store.change(state => {
      state.registrations[id] = { managementHash: digest, deleted: true };
      for (const [key, message] of Object.entries(state.messages)) if (message.registrationId === id) delete state.messages[key];
    });
  }
  prune() {
    const expired = Object.values(this.store.state.messages).some(message => message.expiresAt <= this.now());
    if (expired) this.store.change(state => {
      for (const [id, message] of Object.entries(state.messages)) if (message.expiresAt <= this.now()) delete state.messages[id];
    });
  }
  enqueue(deliveryId, headers, bytes) {
    const entry = Object.entries(this.store.state.registrations).find(([, record]) => record.deliveryId === deliveryId && !record.deleted && !record.invalid);
    check(entry, 410, 'subscription_gone');
    check(/^\d{1,10}$/.test(headers.ttl ?? ''), 400, 'invalid_ttl');
    const ttl = Math.min(Number(headers.ttl), limits.ttl);
    const encoding = headers['content-encoding'];
    check(['aes128gcm', 'aesgcm'].includes(encoding), 415, 'unsupported_encoding');
    check(bytes.length > 0 && bytes.length <= 65536, 413, 'invalid_payload_size');
    const cryptoHeaders = {};
    for (const name of ['encryption', 'crypto-key']) {
      if (headers[name] !== undefined) {
        check(typeof headers[name] === 'string' && headers[name].length <= 2048, 400, 'invalid_encryption_headers');
        cryptoHeaders[name] = headers[name];
      }
    }
    check(encoding !== 'aesgcm' || (cryptoHeaders.encryption && cryptoHeaders['crypto-key']), 400, 'missing_encryption_headers');
    this.prune();
    const [registrationId] = entry;
    const id = randomId();
    // TTL 0 permits immediate discard. This queued implementation does not retain it.
    if (ttl === 0) return { id, ttl };
    const messages = Object.values(this.store.state.messages);
    check(messages.length < limits.messages && messages.filter(m => m.registrationId === registrationId).length < limits.perRegistration, 429, 'queue_capacity');
    this.store.change(state => {
      state.messages[id] = { id, registrationId, encoding, headers: cryptoHeaders, body: bytes.toString('base64url'),
        expiresAt: this.now() + ttl * 1000, nextAttempt: this.now(), attempts: 0, delivered: false };
    });
    return { id, ttl };
  }
  envelope(message) {
    return { version: '1', registrationId: message.registrationId, messageId: message.id,
      encoding: message.encoding, headers: JSON.stringify(message.headers), body: message.body };
  }
  deliveryData(message) {
    const data = this.envelope(message);
    return Buffer.byteLength(JSON.stringify(data)) <= 3500 ? { ...data, transport: 'inline' } :
      { version: '1', registrationId: message.registrationId, messageId: message.id, transport: 'fetch' };
  }
  fetch(id, messageId, authorization) {
    const { record } = this.authenticate(id, authorization);
    check(record && !record.deleted && !record.invalid, 404, 'not_found');
    const message = this.store.state.messages[messageId];
    check(message && message.registrationId === id && message.expiresAt > this.now(), 404, 'not_found');
    return this.envelope(message);
  }
  async tick() {
    if (this.running) return;
    this.running = true;
    try {
      this.prune();
      for (const candidate of Object.values(this.store.state.messages)) {
        const message = this.store.state.messages[candidate.id];
        if (!message || message.delivered || message.nextAttempt > this.now() || message.expiresAt <= this.now()) continue;
        const record = this.store.state.registrations[message.registrationId];
        if (!record || record.deleted || record.invalid) continue;
        const token = record.fcmToken;
        const data = this.deliveryData(message);
        let result;
        try { result = await this.sender.send(token, data, { priority: 'high', ttlSeconds: Math.max(0, Math.floor((message.expiresAt - this.now()) / 1000)) }); }
        catch { result = 'transient'; }
        this.store.change(state => {
          const current = state.messages[message.id];
          const registration = state.registrations[message.registrationId];
          if (!current || !registration || registration.deleted) return;
          if (current.expiresAt <= this.now()) { delete state.messages[message.id]; return; }
          // A response for an old token must not invalidate or acknowledge the new destination.
          if (registration.fcmToken !== token) { current.nextAttempt = this.now(); return; }
          if (result === 'invalid') {
            for (const [id, target] of Object.entries(state.registrations)) {
              if (target.fcmToken !== token) continue;
              target.invalid = true; delete target.fcmToken;
              for (const [key, item] of Object.entries(state.messages)) if (item.registrationId === id) delete state.messages[key];
            }
          } else if (result === 'success') {
            if (data.transport === 'fetch') current.delivered = true;
            else delete state.messages[message.id];
          } else {
            current.attempts++;
            if (current.attempts >= 8) delete state.messages[message.id];
            else current.nextAttempt = this.now() + Math.min(300000, 1000 * 2 ** (current.attempts - 1));
          }
        });
      }
    } finally { this.running = false; }
  }
  stats() {
    return { registrations: Object.values(this.store.state.registrations).filter(r => !r.deleted && !r.invalid).length,
      queued: Object.values(this.store.state.messages).filter(m => !m.delivered).length,
      retained: Object.values(this.store.state.messages).filter(m => m.delivered).length };
  }
}
