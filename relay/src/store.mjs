import { mkdirSync, openSync, closeSync, readFileSync, writeFileSync, fsyncSync, renameSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

/** Single-process local storage. Persist before replacing the in-memory snapshot. */
export class Store {
  constructor(directory) {
    mkdirSync(directory, { recursive: true });
    this.path = join(directory, 'state.json');
    this.lockPath = join(directory, 'process.lock');
    this.lock = openSync(this.lockPath, 'wx', 0o600);
    try {
      writeFileSync(this.lock, String(process.pid));
      try { this.state = JSON.parse(readFileSync(this.path, 'utf8')); }
      catch (error) {
        if (error.code !== 'ENOENT') throw error;
        this.state = { version: 1, registrations: {}, messages: {} };
      }
      if (this.state.version !== 1 || !this.state.registrations || !this.state.messages) throw new Error('Unsupported relay store');
      if (Array.isArray(this.state.registrations) || Array.isArray(this.state.messages) ||
          typeof this.state.registrations !== 'object' || typeof this.state.messages !== 'object') throw new Error('Invalid relay store');
      for (const [id, record] of Object.entries(this.state.registrations)) {
        if (!/^[A-Za-z0-9_-]{22,128}$/.test(id) || !record || !/^[a-f0-9]{64}$/.test(record.managementHash) ||
            typeof record.deleted !== 'boolean' || (!record.deleted &&
              (!/^[A-Za-z0-9_-]{43}$/.test(record.deliveryId) || typeof record.invalid !== 'boolean' ||
                (!record.invalid && typeof record.fcmToken !== 'string')))) throw new Error('Invalid registration storage');
      }
      for (const [id, message] of Object.entries(this.state.messages)) {
        if (!message || id !== message.id || !/^[A-Za-z0-9_-]{43}$/.test(id) ||
            !Object.hasOwn(this.state.registrations, message.registrationId) ||
            !Number.isFinite(message.expiresAt) || !Number.isFinite(message.nextAttempt) ||
            !Number.isInteger(message.attempts) || typeof message.delivered !== 'boolean' ||
            !['aes128gcm', 'aesgcm'].includes(message.encoding) || typeof message.body !== 'string' ||
            !message.headers || typeof message.headers !== 'object') throw new Error('Invalid message storage');
      }
    } catch (error) { this.close(); throw error; }
  }

  change(update) {
    const next = structuredClone(this.state);
    const result = update(next);
    const temporary = `${this.path}.tmp`;
    const descriptor = openSync(temporary, 'w', 0o600);
    try { writeFileSync(descriptor, JSON.stringify(next)); fsyncSync(descriptor); }
    finally { closeSync(descriptor); }
    renameSync(temporary, this.path);
    this.state = next;
    return result;
  }

  close() {
    if (this.lock !== undefined) {
      closeSync(this.lock); this.lock = undefined;
      unlinkSync(this.lockPath);
    }
  }
}
