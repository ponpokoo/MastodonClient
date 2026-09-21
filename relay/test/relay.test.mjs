import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { Store } from '../src/store.mjs';
import { Relay, MockSender } from '../src/relay.mjs';
import { start } from '../src/server.mjs';

const id = 'a'.repeat(43), other = 'b'.repeat(43), auth = `Bearer ${'s'.repeat(43)}`;
const headers = { ttl: '60', 'content-encoding': 'aes128gcm' };
function cleanup(directory) {
  const target = resolve(directory);
  assert.equal(dirname(target), resolve(tmpdir()));
  assert.match(basename(target), /^nagisa-(relay|http)-/);
  rmSync(target, { recursive: true });
}
function setup(t, sender = new MockSender()) {
  const directory = mkdtempSync(join(tmpdir(), 'nagisa-relay-'));
  let store = new Store(directory);
  let clock = 1000;
  let relay = new Relay(store, sender, { now: () => clock });
  t.after(() => { store.close(); cleanup(directory); });
  return { get relay() { return relay; }, get store() { return store; }, directory, sender,
    advance(ms) { clock += ms; },
    reopen() { store.close(); store = new Store(directory); relay = new Relay(store, sender, { now: () => clock }); },
  };
}
const register = (relay, registration = id, token = 'fcm') => relay.register(registration, auth, { fcmToken: token }).deliveryId;
const rejectsStatus = (action, status) => assert.throws(action, error => error.status === status);

test('registration is idempotent, rotates tokens and never persists management secret', t => {
  const app = setup(t);
  const delivery = register(app.relay);
  assert.equal(register(app.relay, id, 'new-token'), delivery);
  rejectsStatus(() => app.relay.register(id, `Bearer ${'x'.repeat(43)}`, { fcmToken: 'stolen' }), 403);
  assert.equal(app.store.state.registrations[id].fcmToken, 'new-token');
  assert.equal(readFileSync(join(app.directory, 'state.json'), 'utf8').includes(auth.slice(7)), false);
  app.reopen();
  assert.equal(register(app.relay), delivery);
});

test('DELETE before a delayed initial PUT creates an authenticated tombstone', t => {
  const app = setup(t);
  app.relay.remove(id, auth);
  app.reopen();
  rejectsStatus(() => register(app.relay), 410);
  app.relay.remove(id, auth);
  rejectsStatus(() => app.relay.remove(id, `Bearer ${'x'.repeat(43)}`), 403);
});

test('ciphertext and legacy encryption headers survive restart and reach sender unchanged', async t => {
  const sent = [];
  const app = setup(t, { async send(token, data) { sent.push({ token, data }); return 'success'; } });
  const delivery = register(app.relay);
  const bytes = Buffer.from([0, 255, 128, 1, 2]);
  app.relay.enqueue(delivery, { ttl: '60', 'content-encoding': 'aesgcm', encryption: 'salt=example', 'crypto-key': 'dh=example' }, bytes);
  app.reopen();
  await app.relay.tick();
  assert.equal(sent.length, 1);
  assert.deepEqual(Buffer.from(sent[0].data.body, 'base64url'), bytes);
  assert.deepEqual(JSON.parse(sent[0].data.headers), { encryption: 'salt=example', 'crypto-key': 'dh=example' });
  assert.equal(app.relay.stats().queued, 0);
});

test('transient failure respects backoff and survives restart', async t => {
  const app = setup(t, new MockSender('fail-once'));
  app.relay.enqueue(register(app.relay), headers, Buffer.from('ciphertext'));
  await app.relay.tick();
  assert.equal(app.sender.attempts, 1);
  app.reopen();
  await app.relay.tick();
  assert.equal(app.sender.attempts, 1);
  app.advance(1000); await app.relay.tick();
  assert.equal(app.sender.accepted, 1);
  assert.equal(app.relay.stats().queued, 0);
});

test('expired and zero-TTL messages are not delivered', async t => {
  const app = setup(t);
  const delivery = register(app.relay);
  app.relay.enqueue(delivery, { ...headers, ttl: '0' }, Buffer.from('zero'));
  app.relay.enqueue(delivery, { ...headers, ttl: '1' }, Buffer.from('expired'));
  app.advance(1000); await app.relay.tick();
  assert.equal(app.sender.attempts, 0);
  assert.equal(app.relay.stats().queued, 0);
});

test('large ciphertext uses authenticated fetch without truncation and expires', async t => {
  const sent = [];
  const app = setup(t, { async send(_token, data) { sent.push(data); return 'success'; } });
  const bytes = Buffer.alloc(65536, 255);
  const { id: messageId } = app.relay.enqueue(register(app.relay), headers, bytes);
  register(app.relay, other);
  await app.relay.tick();
  assert.equal(sent[0].transport, 'fetch');
  assert.ok(Buffer.byteLength(JSON.stringify(sent[0])) < 3500);
  assert.deepEqual(Buffer.from(app.relay.fetch(id, messageId, auth).body, 'base64url'), bytes);
  rejectsStatus(() => app.relay.fetch(other, messageId, auth), 404);
  rejectsStatus(() => app.relay.fetch(id, messageId, `Bearer ${'x'.repeat(43)}`), 403);
  app.advance(60000);
  rejectsStatus(() => app.relay.fetch(id, messageId, auth), 404);
  await app.relay.tick(); assert.equal(app.relay.stats().retained, 0);
});

test('invalid FCM destination clears all affected queues and accepts later token rotation', async t => {
  const app = setup(t, new MockSender('invalid'));
  const first = register(app.relay), second = register(app.relay, other);
  app.relay.enqueue(first, headers, Buffer.from('one'));
  app.relay.enqueue(second, headers, Buffer.from('two'));
  await app.relay.tick();
  assert.equal(app.relay.stats().registrations, 0);
  assert.equal(app.relay.stats().queued, 0);
  rejectsStatus(() => app.relay.enqueue(first, headers, Buffer.from('late')), 410);
  assert.equal(register(app.relay, id, 'rotated'), first);
  assert.equal(app.relay.stats().registrations, 1);
});

test('token rotation during an in-flight invalid response does not disable new destination', async t => {
  let complete;
  const app = setup(t, { send() { return new Promise(resolve => { complete = resolve; }); } });
  app.relay.enqueue(register(app.relay), headers, Buffer.from('one'));
  const running = app.relay.tick();
  register(app.relay, id, 'rotated');
  complete('invalid'); await running;
  assert.equal(app.store.state.registrations[id].fcmToken, 'rotated');
  assert.equal(app.relay.stats().queued, 1);
});

test('removal clears pending payload and in-flight completion cannot recreate it', async t => {
  let complete;
  const app = setup(t, { send() { return new Promise(resolve => { complete = resolve; }); } });
  const delivery = register(app.relay);
  app.relay.enqueue(delivery, headers, Buffer.from('one'));
  const running = app.relay.tick();
  app.relay.remove(id, auth); complete('transient'); await running;
  assert.equal(app.relay.stats().queued, 0);
  rejectsStatus(() => app.relay.enqueue(delivery, headers, Buffer.from('late')), 410);
});

test('malformed, oversized and unsupported messages are rejected before storage', t => {
  const app = setup(t); const delivery = register(app.relay);
  rejectsStatus(() => app.relay.enqueue(delivery, { ...headers, ttl: '-1' }, Buffer.from('x')), 400);
  rejectsStatus(() => app.relay.enqueue(delivery, { ...headers, 'content-encoding': 'gzip' }, Buffer.from('x')), 415);
  rejectsStatus(() => app.relay.enqueue(delivery, { ...headers, 'content-encoding': 'aesgcm' }, Buffer.from('x')), 400);
  rejectsStatus(() => app.relay.enqueue(delivery, headers, Buffer.alloc(65537)), 413);
  assert.equal(app.relay.stats().queued, 0);
});

test('retries are bounded and another process cannot open the same store', async t => {
  const app = setup(t, new MockSender('transient'));
  assert.throws(() => new Store(app.directory), error => error.code === 'EEXIST');
  app.relay.enqueue(register(app.relay), { ...headers, ttl: '86400' }, Buffer.from('x'));
  for (let i = 0; i < 9; i++) { await app.relay.tick(); app.advance(300000); }
  assert.equal(app.sender.attempts, 8);
  assert.equal(app.relay.stats().queued, 0);
});

test('HTTP contract supports registration, binary push, health and removal without reflecting secrets', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'nagisa-http-'));
  const app = await start({ directory, port: 0, interval: 0 });
  t.after(async () => { await app.close(); cleanup(directory); });
  const url = `${app.origin}/v1/registrations/${id}`;
  const requestHeaders = { Authorization: auth, 'Content-Type': 'application/json' };
  let response = await fetch(url, { method: 'PUT', headers: requestHeaders, body: JSON.stringify({ fcmToken: 'test-fcm' }) });
  assert.equal(response.status, 201);
  const { endpoint } = await response.json();
  response = await fetch(endpoint, { method: 'POST', headers, body: Buffer.from([0, 255]) });
  assert.equal(response.status, 201); assert.equal(response.headers.get('ttl'), '60');
  await app.relay.tick();
  assert.equal(app.sender.accepted, 1);
  response = await fetch(url, { method: 'PUT', headers: { ...requestHeaders, Authorization: 'Bearer secret' }, body: '{}' });
  assert.equal(response.status, 401);
  assert.equal((await response.text()).includes('secret'), false);
  response = await fetch(url, { method: 'DELETE', headers: requestHeaders }); assert.equal(response.status, 204);
  response = await fetch(endpoint, { method: 'POST', headers, body: Buffer.from('x') }); assert.equal(response.status, 410);
  response = await fetch(`${app.origin}/health`); assert.equal((await response.json()).registrations, 0);
});

test('message expiring during another send is not sent afterward', async t => {
  let app;
  let attempts = 0;
  app = setup(t, { async send() { attempts++; app.advance(2000); return 'success'; } });
  const delivery = register(app.relay);
  app.relay.enqueue(delivery, headers, Buffer.from('first'));
  app.relay.enqueue(delivery, { ...headers, ttl: '1' }, Buffer.from('second'));
  await app.relay.tick();
  assert.equal(attempts, 1);
});

test('corrupted stored state fails closed and remains untouched', t => {
  const app = setup(t);
  app.store.close();
  const path = join(app.directory, 'state.json');
  const damaged = '{"version":1,"registrations":[],"messages":{}}';
  writeFileSync(path, damaged);
  assert.throws(() => new Store(app.directory));
  assert.equal(readFileSync(path, 'utf8'), damaged);
});

test('per-registration capacity is bounded and removal releases queue storage', t => {
  const app = setup(t); const delivery = register(app.relay);
  for (let i = 0; i < 100; i++) app.relay.enqueue(delivery, headers, Buffer.from('x'));
  rejectsStatus(() => app.relay.enqueue(delivery, headers, Buffer.from('x')), 429);
  app.relay.remove(id, auth);
  assert.equal(app.relay.stats().queued, 0);
});

test('HTTP rejects excessive requests with retry guidance', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'nagisa-http-'));
  const app = await start({ directory, port: 0, interval: 0, now: () => 1000 });
  t.after(async () => { await app.close(); cleanup(directory); });
  for (let i = 0; i < 300; i++) {
    const response = await fetch(`${app.origin}/health`);
    assert.equal(response.status, 200); await response.text();
  }
  const response = await fetch(`${app.origin}/health`);
  assert.equal(response.status, 429); assert.equal(response.headers.get('retry-after'), '60');
});
