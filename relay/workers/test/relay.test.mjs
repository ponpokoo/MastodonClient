import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { Miniflare, Response as MiniflareResponse, convertV4MiniflareOptions } from 'miniflare';
import { createWorker } from '../src/worker.mjs';
import { base64url, bytesOf, randomId, parsePush } from '../src/protocol.mjs';
import { D1Store, LIMITS } from '../src/store.mjs';

const origin = 'https://relay.example';
const start = Date.now();
let mf, db, pair, publicKey, privatePem, rsa, clock, app, env, pending, sent, oauthCalls, outcomes, logs;
async function signedHeaders({ aud = origin, exp = Math.floor(clock / 1000) + 3600, keyPair = pair, key = publicKey, legacy = false } = {}) {
  const head = base64url(bytesOf(JSON.stringify({ alg: 'ES256', typ: 'JWT' })));
  const body = base64url(bytesOf(JSON.stringify({ aud, exp, sub: 'mailto:test@example.test' })));
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, keyPair.privateKey, bytesOf(`${head}.${body}`));
  const jwt = `${head}.${body}.${base64url(new Uint8Array(signature))}`;
  return legacy ? { Authorization: `WebPush ${jwt}`, 'Crypto-Key': `p256ecdsa=${key};dh=BAAA` } : { Authorization: `vapid t=${jwt}, k=${key}` };
}
const account = () => ({ id: randomId(), management: randomId() });
function request(path, method = 'GET', body, headers = {}) {
  return app.fetch(new Request(new URL(path, origin), { method, body, headers }), env, { waitUntil(promise) { pending.push(promise); } });
}
async function put(owner, token = 'test-device-token') {
  return request(`/v1/registrations/${owner.id}`, 'PUT', JSON.stringify({ fcmToken: token }),
    { Authorization: `Bearer ${owner.management}`, 'Content-Type': 'application/json' });
}
const remove = owner => request(`/v1/registrations/${owner.id}`, 'DELETE', undefined, { Authorization: `Bearer ${owner.management}` });
const getMessage = (owner, id) => request(`/v1/registrations/${owner.id}/messages/${id}`, 'GET', undefined, { Authorization: `Bearer ${owner.management}` });
async function register(owner = account(), token) {
  const response = await put(owner, token); assert.equal(response.status, 201);
  return { owner, endpoint: (await response.json()).endpoint };
}
async function push(endpoint, bytes = new Uint8Array([1, 2, 3]), headers = {}) {
  return request(endpoint, 'POST', bytes, { ...(await signedHeaders()), TTL: '3600', 'Content-Encoding': 'aes128gcm', ...headers });
}
async function settle() { const work = pending ?? []; pending = []; await Promise.all(work); }
const count = async table => (await db.prepare(`SELECT count(*) AS n FROM ${table}`).first()).n;
async function fakeGoogle(url, init) {
  assert.equal(init.redirect, 'manual');
  if (url === 'https://oauth2.googleapis.com/token') {
    oauthCalls++;
    const jwt = new URLSearchParams(init.body).get('assertion').split('.');
    assert.equal(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', rsa.publicKey,
      Buffer.from(jwt[2], 'base64url'), bytesOf(`${jwt[0]}.${jwt[1]}`)), true);
    const claims = JSON.parse(Buffer.from(jwt[1], 'base64url'));
    assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
    assert.equal(claims.aud, url);
    return Response.json({ access_token: 'mock-access-token', expires_in: 3600 });
  }
  assert.equal(url, 'https://fcm.googleapis.com/v1/projects/test-project/messages:send');
  assert.equal(init.headers.Authorization, 'Bearer mock-access-token');
  const body = JSON.parse(init.body); sent.push(body.message);
  const outcome = outcomes.shift();
  if (typeof outcome === 'function') return outcome();
  return outcome ?? Response.json({ name: 'projects/test-project/messages/mock' });
}

before(async () => {
  mf = new Miniflare(convertV4MiniflareOptions({ modules: true, script: 'export default {fetch(){return new Response("test")}}',
    compatibilityDate: '2026-09-21', d1Databases: ['DB'] }));
  db = await mf.getD1Database('DB');
  const sql = await readFile(new URL('../migrations/0001_initial.sql', import.meta.url), 'utf8');
  await db.batch(sql.replace(/--[^\n]*/g, '').split(';').filter(s => s.trim()).map(s => db.prepare(s)));
  pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  publicKey = base64url(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)));
  rsa = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  privatePem = `-----BEGIN PRIVATE KEY-----\n${Buffer.from(await crypto.subtle.exportKey('pkcs8', rsa.privateKey)).toString('base64')}\n-----END PRIVATE KEY-----`;
});
beforeEach(async () => {
  await db.batch(['DELETE FROM messages', 'DELETE FROM registrations', 'DELETE FROM daily_usage'].map(sql => db.prepare(sql)));
  clock = start; pending = []; sent = []; oauthCalls = 0; outcomes = []; logs = [];
  env = { DB: db, RELAY_ENABLED: 'true', PUBLIC_ORIGIN: origin, VAPID_PUBLIC_KEYS: JSON.stringify([publicKey]),
    FCM_PROJECT_ID: 'test-project', FCM_CLIENT_EMAIL: 'relay@test-project.iam.gserviceaccount.com', FCM_PRIVATE_KEY: privatePem };
  app = createWorker({ now: () => clock, fetcher: fakeGoogle, report: code => logs.push(code) });
});
after(async () => { await settle(); await mf?.dispose(); });

test('registration is idempotent, token refresh preserves endpoint, secrets are hashed', async () => {
  const { owner, endpoint } = await register();
  const response = await put(owner, 'new-token'); assert.equal(response.status, 200);
  assert.equal((await response.json()).endpoint, endpoint);
  assert.equal((await put({ ...owner, management: randomId() })).status, 403);
  const row = await db.prepare('SELECT * FROM registrations').first();
  assert.notEqual(row.management_hash, owner.management);
  assert.equal(row.fcm_token, 'new-token');
  assert.equal(endpoint.includes(owner.id), false);
});
test('competing initial registrations cannot overwrite ownership', async () => {
  const owner = account(); const other = { ...owner, management: randomId() };
  const responses = await Promise.all([put(owner), put(other)]);
  assert.deepEqual(responses.map(r => r.status).sort(), [201, 403]);
  assert.equal(await count('registrations'), 1);
});
test('delete creates a tombstone, rejects delayed PUT and invalidates endpoint and messages', async () => {
  const absent = account(); assert.equal((await remove(absent)).status, 204);
  assert.equal((await put(absent)).status, 410);
  const { owner, endpoint } = await register();
  const response = await push(endpoint, new Uint8Array(4000)); await settle();
  const id = response.headers.get('location').split('/').at(-1);
  assert.equal((await remove({ ...owner, management: randomId() })).status, 403);
  assert.equal((await remove(owner)).status, 204);
  assert.equal((await remove(owner)).status, 204);
  assert.equal((await push(endpoint)).status, 410);
  assert.equal((await getMessage(owner, id)).status, 404);
  assert.equal(await count('messages'), 0);
});
test('valid push produces a data-only FCM message and reuses OAuth access token', async () => {
  const { owner, endpoint } = await register();
  for (let i = 0; i < 2; i++) { assert.equal((await push(endpoint)).status, 201); await settle(); }
  assert.equal(sent.length, 2); assert.equal(oauthCalls, 1);
  assert.equal(sent[0].notification, undefined);
  assert.equal(sent[0].android.priority, 'HIGH'); assert.equal(sent[0].android.ttl, '3600s');
  assert.equal(sent[0].data.registrationId, owner.id);
  assert.equal(sent[0].data.transport, 'inline');
  assert.equal(sent[0].data.body, 'AQID');
  assert.equal(await count('messages'), 0);
});
test('large ciphertext uses authenticated fetch and expires at original TTL', async () => {
  const { owner, endpoint } = await register(); const bytes = new Uint8Array(65536).fill(123);
  const response = await push(endpoint, bytes, { TTL: '10' }); await settle();
  assert.equal(response.status, 201); assert.equal(sent[0].data.transport, 'fetch');
  const id = sent[0].data.messageId;
  assert.equal((await getMessage({ ...owner, management: randomId() }, id)).status, 403);
  const other = await register(); assert.equal((await getMessage(other.owner, id)).status, 404);
  const content = await (await getMessage(owner, id)).json();
  assert.deepEqual(Buffer.from(content.body, 'base64url'), Buffer.from(bytes));
  assert.equal(await count('messages'), 1);
  clock += 10000;
  assert.equal((await getMessage(owner, id)).status, 404);
  await app.scheduled({}, env, {}); assert.equal(await count('messages'), 0);
});
test('missing, wrong-origin, expired, overlong and tampered VAPID tokens are rejected', async () => {
  const { endpoint } = await register();
  const variants = [{ Authorization: '' }, await signedHeaders({ aud: 'https://elsewhere.example' }),
    await signedHeaders({ exp: Math.floor(clock / 1000) }), await signedHeaders({ exp: Math.floor(clock / 1000) + 86401 })];
  const valid = await signedHeaders();
  variants.push({ Authorization: valid.Authorization.replace('t=', 't=A') });
  for (const headers of variants) assert.equal((await push(endpoint, undefined, headers)).status, 401);
  assert.equal(await count('messages'), 0); assert.equal(sent.length, 0);
});
test('a valid signature from an unconfigured server is forbidden', async () => {
  const { endpoint } = await register();
  const other = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const key = base64url(new Uint8Array(await crypto.subtle.exportKey('raw', other.publicKey)));
  assert.equal((await push(endpoint, undefined, await signedHeaders({ keyPair: other, key }))).status, 403);
});
test('legacy WebPush/aesgcm validates VAPID and forwards only encryption material', async () => {
  const { endpoint } = await register();
  assert.equal((await push(endpoint, undefined, { ...(await signedHeaders({ legacy: true })),
    'Content-Encoding': 'aesgcm', Encryption: 'salt=AQID' })).status, 201);
  await settle();
  assert.deepEqual(JSON.parse(sent[0].data.headers), { encryption: 'salt=AQID', 'crypto-key': 'dh=BAAA' });
  assert.equal(JSON.stringify(sent).includes('p256ecdsa'), false);
});
test('TTL zero is discarded; malformed TTL, encoding and oversized bodies are rejected', async () => {
  const { endpoint } = await register();
  assert.equal((await push(endpoint, undefined, { TTL: '0' })).status, 201);
  assert.equal(await count('messages'), 0); assert.equal(sent.length, 0);
  assert.equal((await push(endpoint, undefined, { TTL: '-1' })).status, 400);
  assert.equal((await push(endpoint, undefined, { 'Content-Encoding': 'gzip' })).status, 415);
  assert.equal((await push(endpoint, new Uint8Array(65537))).status, 413);
  assert.equal((await push(endpoint, undefined, { 'Content-Encoding': 'aesgcm' })).status, 400);
});
test('D1 capacity checks are atomic under parallel enqueue and registration attempts', async () => {
  const { owner } = await register(); const store = new D1Store(db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), clock);
  const attempts = await Promise.allSettled(Array.from({ length: 25 }, () => store.enqueue(owner.id, payload, clock)));
  assert.equal(attempts.filter(r => r.status === 'fulfilled').length, LIMITS.perRegistration);
  assert.equal(await count('messages'), LIMITS.perRegistration);
  await db.batch(Array.from({ length: 99 }, () => db.prepare('INSERT INTO registrations(id,management_hash,deleted) VALUES(?,?,1)').bind(randomId(), randomId())));
  assert.equal((await put(account())).status, 429);
});
test('FCM 429 honors Retry-After and Cron retries the durable message', async () => {
  outcomes.push(Response.json({ error: { status: 'RESOURCE_EXHAUSTED' } }, { status: 429, headers: { 'Retry-After': '180' } }));
  const { endpoint } = await register(); await push(endpoint); await settle();
  assert.equal(await count('messages'), 1);
  clock += 179000; await app.scheduled({}, env, {}); assert.equal(sent.length, 1);
  clock += 1000; await app.scheduled({}, env, {}); assert.equal(sent.length, 2);
  assert.equal(await count('messages'), 0);
  assert.equal(sent[0].data.messageId, sent[1].data.messageId);
  assert.equal(sent[1].android.ttl, '3420s');
});
test('overlapping workers and crashed leases recover without simultaneous sends', async () => {
  const { owner } = await register(); const store = new D1Store(db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), clock);
  const id = await store.enqueue(owner.id, payload, clock);
  const claims = await Promise.all([store.claim(id, clock), store.claim(id, clock)]);
  assert.equal(claims.filter(Boolean).length, 1);
  await app.scheduled({}, env, {}); assert.equal(sent.length, 0);
  clock += 30001;
  await Promise.all([app.scheduled({}, env, {}), app.scheduled({}, env, {})]);
  assert.equal(sent.length, 1); assert.equal(await count('messages'), 0);
});
test('only typed UNREGISTERED retires the FCM token across its registrations', async () => {
  outcomes.push(Response.json({ error: { status: 'NOT_FOUND' } }, { status: 404 }));
  const { endpoint } = await register(); const second = await register();
  await push(endpoint); await settle();
  assert.equal((await db.prepare('SELECT invalid FROM registrations WHERE id = ?').bind(second.owner.id).first()).invalid, 0);
  outcomes.push(Response.json({ error: { details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' }] } }, { status: 404 }));
  clock += 60000; await app.scheduled({}, env, {});
  const rows = (await db.prepare('SELECT invalid, fcm_token FROM registrations').all()).results;
  assert.equal(rows.every(row => row.invalid === 1 && row.fcm_token === null), true);
  assert.equal(await count('messages'), 0); assert.equal((await push(endpoint)).status, 410);
  assert.equal((await put(second.owner, 'refreshed-token')).status, 200);
});
test('late invalid-token and success responses cannot invalidate or acknowledge a new token', async () => {
  for (const kind of ['invalid', 'success']) {
    const { owner } = await register(account(), `old-${kind}`); const store = new D1Store(db);
    const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), clock);
    const id = await store.enqueue(owner.id, payload, clock); const claim = await store.claim(id, clock);
    assert.equal((await put(owner, `new-${kind}`)).status, 200);
    await store.complete(claim, { kind }, 'inline', clock);
    const row = await db.prepare('SELECT * FROM registrations WHERE id = ?').bind(owner.id).first();
    assert.equal(row.invalid, 0); assert.equal(row.fcm_token, `new-${kind}`);
    assert.equal((await store.claim(id, clock)).token, `new-${kind}`);
  }
});
test('success on the eighth attempt retains fetch ciphertext until expiry', async () => {
  const { owner } = await register(); const store = new D1Store(db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array(4000), clock);
  const id = await store.enqueue(owner.id, payload, clock);
  await db.prepare('UPDATE messages SET attempts = 7 WHERE id = ?').bind(id).run();
  await app.scheduled({}, env, {});
  assert.equal((await getMessage(owner, id)).status, 200);
  assert.equal((await db.prepare('SELECT delivered FROM messages WHERE id = ?').bind(id).first()).delivered, 1);
});
test('retry limit and expiration discard undeliverable messages', async () => {
  const { owner } = await register(); const store = new D1Store(db);
  const payload = parsePush(new Headers({ TTL: '1', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), clock);
  await store.enqueue(owner.id, payload, clock); clock += 1000;
  await app.scheduled({}, env, {}); assert.equal(sent.length, 0); assert.equal(await count('messages'), 0);
  payload.expires_at = clock + 3600000;
  const id = await store.enqueue(owner.id, payload, clock);
  await db.prepare('UPDATE messages SET attempts = 7 WHERE id = ?').bind(id).run();
  outcomes.push(Response.json({}, { status: 503 }));
  await app.scheduled({}, env, {}); assert.equal(await count('messages'), 0);
});
test('disabled/missing configuration fails closed; quota cannot overflow under concurrency', async () => {
  env.RELAY_ENABLED = 'false'; assert.equal((await put(account())).status, 503);
  assert.equal((await (await request('/health')).json()).status, 'disabled');
  env.RELAY_ENABLED = 'true'; env.VAPID_PUBLIC_KEYS = '[]'; assert.equal((await put(account())).status, 503);
  env.VAPID_PUBLIC_KEYS = JSON.stringify([publicKey]);
  await db.prepare('INSERT INTO daily_usage(day,count) VALUES(?,?)').bind(Math.floor(clock / 86400000), 1999).run();
  const responses = await Promise.all([put(account()), put(account())]);
  assert.deepEqual(responses.map(r => r.status).sort(), [201, 429]);
  assert.equal((await db.prepare('SELECT count FROM daily_usage').first()).count, 2000);
});
test('production bundle runs in workerd with D1 and validates WebCrypto VAPID', async () => {
  const script = await readFile(new URL('../dist/worker.js', import.meta.url), 'utf8');
  const runtime = new Miniflare(convertV4MiniflareOptions({ modules: true, script, compatibilityDate: '2026-09-21', d1Databases: ['DB'],
    bindings: Object.fromEntries(Object.entries(env).filter(([key]) => key !== 'DB')),
    outboundService: async incoming => {
      const result = await fakeGoogle(incoming.url, {
        method: incoming.method, headers: { Authorization: incoming.headers.get('authorization') },
        body: await incoming.text(), redirect: 'manual',
      });
      return new MiniflareResponse(await result.text(), { status: result.status, headers: Object.fromEntries(result.headers) });
    },
  }));
  try {
    const remoteDb = await runtime.getD1Database('DB');
    const sql = await readFile(new URL('../migrations/0001_initial.sql', import.meta.url), 'utf8');
    await remoteDb.batch(sql.replace(/--[^\n]*/g, '').split(';').filter(s => s.trim()).map(s => remoteDb.prepare(s)));
    const owner = account();
    const response = await runtime.dispatchFetch(`${origin}/v1/registrations/${owner.id}`, { method: 'PUT',
      headers: { Authorization: `Bearer ${owner.management}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ fcmToken: 'local-only' }) });
    assert.equal(response.status, 201); const endpoint = (await response.json()).endpoint;
    // TTL 0 exercises workerd crypto and SQL without ever contacting Google.
    const accepted = await runtime.dispatchFetch(endpoint, { method: 'POST', body: new Uint8Array([1]),
      headers: { ...(await signedHeaders()), TTL: '0', 'Content-Encoding': 'aes128gcm' } });
    assert.equal(accepted.status, 201);
    const rejected = await runtime.dispatchFetch(endpoint, { method: 'POST', body: new Uint8Array([1]),
      headers: { ...(await signedHeaders({ aud: 'https://wrong.example' })), TTL: '0', 'Content-Encoding': 'aes128gcm' } });
    assert.equal(rejected.status, 401);
    // Mock all outbound traffic while running the real worker's RSA signing and FCM adapter.
    const sentSignal = Promise.withResolvers();
    outcomes.push(() => { sentSignal.resolve(); return Response.json({ name: 'projects/test-project/messages/local' }); });
    const send = await runtime.dispatchFetch(endpoint, { method: 'POST', body: new Uint8Array([1]),
      headers: { ...(await signedHeaders()), TTL: '3600', 'Content-Encoding': 'aes128gcm' } });
    assert.equal(send.status, 201);
    await Promise.race([sentSignal.promise, new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error(`local FCM send did not run (OAuth requests: ${oauthCalls})`)), 5000); timer.unref();
    })]);
    assert.equal(sent.at(-1).data.transport, 'inline'); assert.equal(oauthCalls, 1);
  } finally { await runtime.dispose(); }
});
