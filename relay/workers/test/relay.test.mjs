import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { Miniflare, Response as MiniflareResponse, convertV4MiniflareOptions } from 'miniflare';
import { createWorker } from '../src/worker.mjs';
import { base64url, randomId, parsePush } from '../src/protocol.mjs';
import { capacityLimits, D1Store, LIMITS } from '../src/store.mjs';
import { createRelayFixture } from './support/relay-fixture.mjs';

const { state: h, origin, signedHeaders, account, request, put, remove,
  getMessage, register, push, settle, count, putBound, fakeGoogle } = createRelayFixture();

test('trusted capacity candidates are validated, snapshotted and enforced without changing defaults', async () => {
  for (const limits of [{ registrations: NaN }, { messages: 0 }, { pending: 121 }, { registrations: 10001 }]) {
    assert.throws(() => capacityLimits(limits), /invalid_capacity_limits/);
  }
  const candidate = { registrations: 2, pending: 1, messages: 2, perRegistration: 2 };
  h.app = createWorker({ now: () => h.clock, fetcher: fakeGoogle, limits: candidate });
  candidate.registrations = 10;
  const first = await putBound(account()), second = await putBound(account());
  assert.equal(first.status, 201); assert.equal(second.status, 201);
  assert.equal((await putBound(account())).status, 429);
  h.env.DELIVERY_ENABLED = 'false';
  const endpoint = (await first.json()).endpoint;
  assert.equal((await push(endpoint)).status, 201);
  assert.equal((await push(endpoint)).status, 201);
  assert.equal((await push(endpoint)).status, 429);
  assert.equal(LIMITS.registrations, 120); assert.equal(LIMITS.messages, 1000);
  h.app = createWorker({ now: () => h.clock, fetcher: fakeGoogle });
  assert.equal((await putBound(account())).status, 201);
});

test('additive migration preserves preexisting v1 IDs, tombstones and ciphertext', async () => {
  const migrationDb = await h.mf.getD1Database('MIGRATION');
  for (const name of ['0001_initial.sql', '0002_bound_registrations.sql']) {
    if (name.startsWith('0002')) {
      await migrationDb.batch([
        migrationDb.prepare('INSERT INTO registrations(id,management_hash,delivery_id,fcm_token) VALUES(?,?,?,?)').bind('active','hash','delivery','fake-device'),
        migrationDb.prepare('INSERT INTO registrations(id,management_hash,deleted) VALUES(?,?,1)').bind('retired','other-hash'),
        migrationDb.prepare('INSERT INTO registrations(id,management_hash,invalid) VALUES(?,?,1)').bind('invalid','invalid-hash'),
        migrationDb.prepare('INSERT INTO messages(id,registration_id,encoding,headers,body,expires_at,next_attempt) VALUES(?,?,?,?,?,?,?)')
          .bind('message','active','aes128gcm','{}','AQID',h.clock + 3600000,h.clock),
      ]);
    }
    const sql = await readFile(new URL('../migrations/' + name, import.meta.url), 'utf8');
    await migrationDb.batch(sql.replace(/--[^\n]*/g, '').split(';').filter(s => s.trim()).map(s => migrationDb.prepare(s)));
  }
  const active = await migrationDb.prepare('SELECT * FROM registrations WHERE id = ?').bind('active').first();
  assert.equal(active.delivery_id, 'delivery'); assert.equal(active.management_hash, 'hash');
  assert.equal(active.fcm_token, 'fake-device'); assert.equal(active.protocol, 1);
  assert.equal((await migrationDb.prepare('SELECT deleted FROM registrations WHERE id = ?').bind('retired').first()).deleted, 1);
  assert.ok((await migrationDb.prepare('SELECT invalid_since FROM registrations WHERE id = ?').bind('invalid').first()).invalid_since > 0);
  assert.equal((await migrationDb.prepare('SELECT body FROM messages WHERE id = ?').bind('message').first()).body, 'AQID');
});

test('pending key binding rejects Push, finalization is replayable and preserves endpoint', async () => {
  h.env.VAPID_PUBLIC_KEYS = '[]';
  const owner = account();
  const pendingResponse = await putBound(owner, { key: null });
  assert.equal(pendingResponse.status, 201);
  const pendingRegistration = await pendingResponse.json();
  assert.equal(pendingRegistration.state, 'pending');
  assert.equal((await push(pendingRegistration.endpoint)).status, 403);
  const finalized = await putBound(owner, { revision: 2, key: h.publicKey + '=' });
  assert.equal(finalized.status, 200);
  assert.equal((await finalized.json()).endpoint, pendingRegistration.endpoint);
  assert.equal((await putBound(owner, { revision: 2 })).status, 200);
  assert.equal((await putBound(owner, { revision: 2, token: 'changed' })).status, 409);
  assert.equal((await putBound(owner, { revision: 1 })).status, 409);
  assert.equal((await put(owner)).status, 426);
  assert.equal((await push(pendingRegistration.endpoint)).status, 201); await settle();
});

test('per-subscription VAPID isolates two servers and authenticated key rotation removes old key', async () => {
  const other = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const otherKey = base64url(new Uint8Array(await crypto.subtle.exportKey('raw', other.publicKey)));
  const first = account(), second = account();
  const a = await (await putBound(first, {})).json();
  const b = await (await putBound(second, { key: otherKey })).json();
  assert.equal((await push(a.endpoint)).status, 201); await settle();
  assert.equal((await push(b.endpoint)).status, 403);
  assert.equal((await push(b.endpoint, undefined, await signedHeaders({ keyPair: other, key: otherKey }))).status, 201); await settle();
  assert.equal((await putBound({ ...first, management: randomId() }, { key: otherKey, revision: 2 })).status, 403);
  assert.equal((await putBound(first, { key: otherKey, revision: 2 })).status, 200);
  assert.equal((await push(a.endpoint)).status, 403);
  assert.equal((await push(a.endpoint, undefined, await signedHeaders({ keyPair: other, key: otherKey, legacy: true }))).status, 201); await settle();
});


test('legacy database migration keeps endpoints and tombstones, legacy grace expires closed', async () => {
  const { owner, endpoint } = await register();
  assert.equal((await putBound(owner)).status, 200);
  const row = await h.db.prepare('SELECT delivery_id,protocol,server_key FROM registrations WHERE id = ?').bind(owner.id).first();
  assert.equal(`${origin}/push/${row.delivery_id}`, endpoint); assert.equal(row.protocol, 2);
  const old = await register(); h.env.LEGACY_V1_UNTIL = new Date(h.clock).toISOString();
  assert.equal((await push(old.endpoint)).status, 403);
  assert.equal((await put(old.owner)).status, 426);
  assert.equal((await remove(old.owner)).status, 204);
  assert.equal((await putBound(old.owner)).status, 410);
});

test('invalid points and expired pending registrations cannot accept Push or revive', async () => {
  const owner = account();
  const invalid = base64url(new Uint8Array([4, ...new Uint8Array(64)]));
  assert.equal((await putBound(owner, { key: invalid })).status, 400);
  const result = await putBound(owner, { key: null }); const endpoint = (await result.json()).endpoint;
  h.clock += 86400001; await h.app.scheduled({}, h.env, {});
  assert.equal((await push(endpoint)).status, 410);
  assert.equal((await putBound(owner, { revision: 2 })).status, 410);
});

test('partial stops and full notification capacity preserve authenticated update, fetch and removal', async () => {
  const owner = account();
  const endpoint = (await (await putBound(owner, {})).json()).endpoint;
  await push(endpoint, new Uint8Array(4000)); await settle(); const id = h.sent[0].data.messageId;
  h.env.REGISTRATION_ENABLED = 'false'; h.env.PUSH_ENABLED = 'false'; h.env.DELIVERY_ENABLED = 'false';
  assert.equal((await putBound(account(), {})).status, 503);
  assert.equal((await putBound(owner, { revision: 2, token: 'new-token' })).status, 200);
  assert.equal((await push(endpoint)).status, 503);
  assert.equal((await getMessage(owner, id)).status, 200);
  await h.db.prepare('INSERT INTO daily_usage(day,count) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET count = excluded.count')
    .bind(Math.floor(h.clock / 86400000), LIMITS.dailyRequests).run();
  assert.equal((await remove(owner)).status, 204); assert.equal((await getMessage(owner, id)).status, 404);
});

test('Cron drains twenty queued messages fairly within the Free D1 statement ceiling', async () => {
  const { owner } = await register(); const second = await register(); const store = new D1Store(h.db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), h.clock);
  for (let i = 0; i < 25; i++) await store.enqueue(owner.id, payload, h.clock);
  await store.enqueue(second.owner.id, payload, h.clock);
  let statements = 0;
  h.env.DB = { prepare(sql) { statements++; return h.db.prepare(sql); }, batch: queries => h.db.batch(queries) };
  await h.app.scheduled({}, h.env, {});
  assert.ok(statements <= 50);
  assert.equal(h.sent.length, 20); assert.equal(await count('messages'), 6);
  assert.equal(h.sent.slice(0, 2).some(message => message.data.registrationId === second.owner.id), true);
});

test('racing identical v2 registrations are idempotent and a key change fences a validated old Push', async () => {
  const owner = account();
  const results = await Promise.all([putBound(owner), putBound(owner)]);
  assert.deepEqual(results.map(result => result.status).sort(), [200,201]);
  const endpoint = (await results[0].json()).endpoint;
  const store = new D1Store(h.db), binding = await store.destination(endpoint.split('/').at(-1));
  const other = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const key = base64url(new Uint8Array(await crypto.subtle.exportKey('raw', other.publicKey)));
  assert.equal((await putBound(owner, { key, revision: 2 })).status, 200);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), h.clock);
  await assert.rejects(store.enqueue(owner.id, payload, h.clock, binding), error => error.code === 'binding_changed');
  assert.equal(await count('messages'), 0);
});

test('permanent FCM failures are reported and dropped without retiring the device', async () => {
  const { owner, endpoint } = await register();
  for (const status of [400,401,403,404]) {
    h.outcomes.push(Response.json({ error: { status: 'CONFIGURATION_ERROR' } }, { status }));
    await push(endpoint); await settle();
    assert.equal(await count('messages'), 0);
    assert.equal((await h.db.prepare('SELECT invalid FROM registrations WHERE id = ?').bind(owner.id).first()).invalid, 0);
  }
  assert.equal(h.logs.filter(code => code === 'relay_delivery_configuration_error').length, 4);
});

test('retiring restored registrations prevents deleted subscriptions and delayed updates from reviving', async () => {
  const { owner, endpoint } = await register();
  await push(endpoint, new Uint8Array(4000)); await settle();
  // Model a backup restored with an old active registration and retained ciphertext.
  h.env.RELAY_ENABLED = 'false';
  const sql = await readFile(new URL('../operations/retire-restored-registrations.sql', import.meta.url), 'utf8');
  await h.db.batch(sql.replace(/--[^\n]*/g, '').split(';').filter(s => s.trim()).map(s => h.db.prepare(s)));
  h.env.RELAY_ENABLED = 'true';
  assert.equal((await push(endpoint)).status, 410);
  assert.equal((await put(owner)).status, 410);
  assert.equal((await putBound(owner)).status, 410);
  assert.equal(await count('messages'), 0);
  const row = await h.db.prepare('SELECT deleted,fcm_token,delivery_id,server_key FROM registrations WHERE id = ?').bind(owner.id).first();
  assert.deepEqual(row, { deleted: 1, fcm_token: null, delivery_id: null, server_key: null });
  assert.equal((await remove(owner)).status, 204);
  assert.equal((await putBound(account())).status, 201);
});

test('registration is idempotent, token refresh preserves endpoint, secrets are hashed', async () => {
  const { owner, endpoint } = await register();
  const response = await put(owner, 'new-token'); assert.equal(response.status, 200);
  assert.equal((await response.json()).endpoint, endpoint);
  assert.equal((await put({ ...owner, management: randomId() })).status, 403);
  const row = await h.db.prepare('SELECT * FROM registrations').first();
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
  assert.equal(h.sent.length, 2); assert.equal(h.oauthCalls, 1);
  assert.equal(h.sent[0].notification, undefined);
  assert.equal(h.sent[0].android.priority, 'HIGH'); assert.equal(h.sent[0].android.ttl, '3600s');
  assert.equal(h.sent[0].data.registrationId, owner.id);
  assert.equal(h.sent[0].data.transport, 'inline');
  assert.equal(h.sent[0].data.body, 'AQID');
  assert.equal(await count('messages'), 0);
});
test('large ciphertext uses authenticated fetch and expires at original TTL', async () => {
  const { owner, endpoint } = await register(); const bytes = new Uint8Array(65536).fill(123);
  const response = await push(endpoint, bytes, { TTL: '10' }); await settle();
  assert.equal(response.status, 201); assert.equal(h.sent[0].data.transport, 'fetch');
  const id = h.sent[0].data.messageId;
  assert.equal((await getMessage({ ...owner, management: randomId() }, id)).status, 403);
  const other = await register(); assert.equal((await getMessage(other.owner, id)).status, 404);
  const content = await (await getMessage(owner, id)).json();
  assert.deepEqual(Buffer.from(content.body, 'base64url'), Buffer.from(bytes));
  assert.equal(await count('messages'), 1);
  h.clock += 10000;
  assert.equal((await getMessage(owner, id)).status, 404);
  await h.app.scheduled({}, h.env, {}); assert.equal(await count('messages'), 0);
});
test('missing, wrong-origin, expired, overlong and tampered VAPID tokens are rejected', async () => {
  const { endpoint } = await register();
  const variants = [{ Authorization: '' }, await signedHeaders({ aud: 'https://elsewhere.example' }),
    await signedHeaders({ exp: Math.floor(h.clock / 1000) }), await signedHeaders({ exp: Math.floor(h.clock / 1000) + 86401 })];
  const valid = await signedHeaders();
  variants.push({ Authorization: valid.Authorization.replace('t=', 't=A') });
  for (const headers of variants) assert.equal((await push(endpoint, undefined, headers)).status, 401);
  assert.equal(await count('messages'), 0); assert.equal(h.sent.length, 0);
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
  assert.deepEqual(JSON.parse(h.sent[0].data.headers), { encryption: 'salt=AQID', 'crypto-key': 'dh=BAAA' });
  assert.equal(JSON.stringify(h.sent).includes('p256ecdsa'), false);
});
test('TTL zero is discarded; malformed TTL, encoding and oversized bodies are rejected', async () => {
  const { endpoint } = await register();
  assert.equal((await push(endpoint, undefined, { TTL: '0' })).status, 201);
  assert.equal(await count('messages'), 0); assert.equal(h.sent.length, 0);
  assert.equal((await push(endpoint, undefined, { TTL: '-1' })).status, 400);
  assert.equal((await push(endpoint, undefined, { 'Content-Encoding': 'gzip' })).status, 415);
  assert.equal((await push(endpoint, new Uint8Array(65537))).status, 413);
  assert.equal((await push(endpoint, undefined, { 'Content-Encoding': 'aesgcm' })).status, 400);
});
test('D1 capacity checks are atomic under parallel enqueue and registration attempts', async () => {
  const { owner } = await register(); const store = new D1Store(h.db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), h.clock);
  const attempts = await Promise.allSettled(Array.from({ length: LIMITS.perRegistration + 5 }, () => store.enqueue(owner.id, payload, h.clock)));
  assert.equal(attempts.filter(r => r.status === 'fulfilled').length, LIMITS.perRegistration);
  assert.equal(await count('messages'), LIMITS.perRegistration);
  await h.db.batch(Array.from({ length: LIMITS.registrations - 1 }, () => h.db.prepare('INSERT INTO registrations(id,management_hash,deleted) VALUES(?,?,1)').bind(randomId(), randomId())));
  assert.equal((await put(account())).status, 429);
});
test('FCM 429 honors Retry-After and Cron retries the durable message', async () => {
  h.outcomes.push(Response.json({ error: { status: 'RESOURCE_EXHAUSTED' } }, { status: 429, headers: { 'Retry-After': '180' } }));
  const { endpoint } = await register(); await push(endpoint); await settle();
  assert.equal(await count('messages'), 1);
  h.clock += 179000; await h.app.scheduled({}, h.env, {}); assert.equal(h.sent.length, 1);
  h.clock += 1000; await h.app.scheduled({}, h.env, {}); assert.equal(h.sent.length, 2);
  assert.equal(await count('messages'), 0);
  assert.equal(h.sent[0].data.messageId, h.sent[1].data.messageId);
  assert.equal(h.sent[1].android.ttl, '3420s');
});
test('overlapping workers and crashed leases recover without simultaneous sends', async () => {
  const { owner } = await register(); const store = new D1Store(h.db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), h.clock);
  const id = await store.enqueue(owner.id, payload, h.clock);
  const claims = await Promise.all([store.claim(id, h.clock), store.claim(id, h.clock)]);
  assert.equal(claims.filter(Boolean).length, 1);
  await h.app.scheduled({}, h.env, {}); assert.equal(h.sent.length, 0);
  h.clock += 60001;
  await Promise.all([h.app.scheduled({}, h.env, {}), h.app.scheduled({}, h.env, {})]);
  assert.equal(h.sent.length, 1); assert.equal(await count('messages'), 0);
});
test('only typed UNREGISTERED retires the FCM token across its registrations', async () => {
  h.outcomes.push(Response.json({ error: { status: 'NOT_FOUND' } }, { status: 404 }));
  const { endpoint } = await register(); const second = await register();
  await push(endpoint); await settle();
  assert.equal((await h.db.prepare('SELECT invalid FROM registrations WHERE id = ?').bind(second.owner.id).first()).invalid, 0);
  h.outcomes.push(Response.json({ error: { details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' }] } }, { status: 404 }));
  await push(second.endpoint); await settle();
  const rows = (await h.db.prepare('SELECT invalid, fcm_token FROM registrations').all()).results;
  assert.equal(rows.every(row => row.invalid === 1 && row.fcm_token === null), true);
  assert.equal(await count('messages'), 0); assert.equal((await push(endpoint)).status, 410);
  assert.equal((await put(second.owner, 'refreshed-token')).status, 200);
});
test('late invalid-token and success responses cannot invalidate or acknowledge a new token', async () => {
  for (const kind of ['invalid', 'success']) {
    const { owner } = await register(account(), `old-${kind}`); const store = new D1Store(h.db);
    const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), h.clock);
    const id = await store.enqueue(owner.id, payload, h.clock); const claim = await store.claim(id, h.clock);
    assert.equal((await put(owner, `new-${kind}`)).status, 200);
    await store.complete(claim, { kind }, 'inline', h.clock);
    const row = await h.db.prepare('SELECT * FROM registrations WHERE id = ?').bind(owner.id).first();
    assert.equal(row.invalid, 0); assert.equal(row.fcm_token, `new-${kind}`);
    assert.equal((await store.claim(id, h.clock)).token, `new-${kind}`);
  }
});
test('success on the eighth attempt retains fetch ciphertext until expiry', async () => {
  const { owner } = await register(); const store = new D1Store(h.db);
  const payload = parsePush(new Headers({ TTL: '3600', 'Content-Encoding': 'aes128gcm' }), new Uint8Array(4000), h.clock);
  const id = await store.enqueue(owner.id, payload, h.clock);
  await h.db.prepare('UPDATE messages SET attempts = 7 WHERE id = ?').bind(id).run();
  await h.app.scheduled({}, h.env, {});
  assert.equal((await getMessage(owner, id)).status, 200);
  assert.equal((await h.db.prepare('SELECT delivered FROM messages WHERE id = ?').bind(id).first()).delivered, 1);
});
test('retry limit and expiration discard undeliverable messages', async () => {
  const { owner } = await register(); const store = new D1Store(h.db);
  const payload = parsePush(new Headers({ TTL: '1', 'Content-Encoding': 'aes128gcm' }), new Uint8Array([1]), h.clock);
  await store.enqueue(owner.id, payload, h.clock); h.clock += 1000;
  await h.app.scheduled({}, h.env, {}); assert.equal(h.sent.length, 0); assert.equal(await count('messages'), 0);
  payload.expires_at = h.clock + 3600000;
  const id = await store.enqueue(owner.id, payload, h.clock);
  await h.db.prepare('UPDATE messages SET attempts = 7 WHERE id = ?').bind(id).run();
  h.outcomes.push(Response.json({}, { status: 503 }));
  await h.app.scheduled({}, h.env, {}); assert.equal(await count('messages'), 0);
});
test('disabled/missing configuration fails closed; quota cannot overflow under concurrency', async () => {
  h.env.RELAY_ENABLED = 'false'; assert.equal((await put(account())).status, 503);
  assert.equal((await (await request('/health')).json()).status, 'disabled');
  h.env.RELAY_ENABLED = 'true'; h.env.PUBLIC_ORIGIN = 'http://unsafe.example'; assert.equal((await put(account())).status, 503); h.env.PUBLIC_ORIGIN = origin;
  h.env.VAPID_PUBLIC_KEYS = JSON.stringify([h.publicKey]);
  await h.db.prepare('INSERT INTO daily_usage(day,count) VALUES(?,?)').bind(Math.floor(h.clock / 86400000), LIMITS.dailyRequests - 1).run();
  const { endpoint } = await register(); const responses = await Promise.all([push(endpoint), push(endpoint)]); await settle();
  assert.deepEqual(responses.map(r => r.status).sort(), [201, 429]);
  assert.equal((await h.db.prepare('SELECT count FROM daily_usage').first()).count, LIMITS.dailyRequests);
});
test('production bundle runs in workerd with D1 and validates WebCrypto VAPID', async () => {
  const script = await readFile(new URL('../dist/worker.js', import.meta.url), 'utf8');
  const runtime = new Miniflare(convertV4MiniflareOptions({ modules: true, script, compatibilityDate: '2026-09-21', d1Databases: ['DB'],
    bindings: Object.fromEntries(Object.entries(h.env).filter(([key]) => key !== 'DB')),
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
    const sql = (await Promise.all(['0001_initial.sql','0002_bound_registrations.sql'].map(name => readFile(new URL('../migrations/' + name, import.meta.url), 'utf8')))).join('\n');
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
    h.outcomes.push(() => { sentSignal.resolve(); return Response.json({ name: 'projects/test-project/messages/local' }); });
    const send = await runtime.dispatchFetch(endpoint, { method: 'POST', body: new Uint8Array([1]),
      headers: { ...(await signedHeaders()), TTL: '3600', 'Content-Encoding': 'aes128gcm' } });
    assert.equal(send.status, 201);
    await Promise.race([sentSignal.promise, new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error(`local FCM send did not run (OAuth requests: ${h.oauthCalls})`)), 5000); timer.unref();
    })]);
    assert.equal(h.sent.at(-1).data.transport, 'inline'); assert.equal(h.oauthCalls, 1);
  } finally { await runtime.dispose(); }
});
