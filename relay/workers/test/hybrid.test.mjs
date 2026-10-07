import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { Miniflare, Response as MiniflareResponse, convertV4MiniflareOptions } from 'miniflare';
import { createWorker } from '../src/worker.mjs';
import { base64url, bytesOf, deliveryData, hybridDeliveryData, randomId } from '../src/protocol.mjs';
import { D1Store, LIMITS } from '../src/store.mjs';
import { createRelayFixture } from './support/relay-fixture.mjs';

const { state: h, origin, signedHeaders, account, request, put, remove,
  getMessage, push, count, putBound, fakeGoogle } = createRelayFixture();

function hybridApp(fetcher = fakeGoogle) {
  h.app = createWorker({ deliveryMode: 'hybrid', now: () => h.clock, fetcher, report: code => h.logs.push(code) });
}
async function hybridRegistration(owner = account(), token = 'test-device-token') {
  const response = await putBound(owner, { token }); assert.equal(response.status, 201);
  return { owner, endpoint: (await response.json()).endpoint };
}
const unregistered = () => Response.json({ error: { details: [
  { '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' },
] } }, { status: 404 });

test('hybrid data uses the existing JSON boundary and strips large ciphertext entirely', () => {
  const message = { id: randomId(), registration_id: randomId(), encoding: 'aes128gcm', headers: '{}' };
  let lastInline;
  for (let size = 2300; size <= 2600; size++) {
    message.body = base64url(new Uint8Array(size));
    const expected = deliveryData(message), actual = hybridDeliveryData(message);
    if (expected.transport === 'inline') { assert.deepEqual(actual, expected); lastInline = size; }
    else assert.deepEqual(actual, { version: '2', registrationId: message.registration_id, messageId: message.id, transport: 'sync_required' });
  }
  assert.equal(lastInline, 2471);
  message.body = base64url(new Uint8Array(65536));
  assert.equal(bytesOf(JSON.stringify(hybridDeliveryData(message))).length < 3500, true);
});

test('hybrid is selected by trusted code only, requires bound registration and has no body GET', async () => {
  assert.throws(() => createWorker({ deliveryMode: 'unknown' }), /invalid_delivery_mode/);
  h.env.DELIVERY_MODE = 'hybrid';
  assert.deepEqual(await (await request('/v2/capabilities')).json(), { registrationVersion: 2, keyBinding: true });
  hybridApp();
  assert.equal((await put(account())).status, 426);
  const { owner, endpoint } = await hybridRegistration();
  const capabilities = await (await request('/v2/capabilities')).json();
  assert.equal(capabilities.deliveryVersion, 2); assert.equal(capabilities.syncRequired, true);
  assert.equal((await push(endpoint, new Uint8Array(1024))).status, 201);
  assert.equal(h.sent[0].data.transport, 'inline'); assert.equal(h.sent[0].data.version, '1');
  const response = await push(endpoint, new Uint8Array(4096)); assert.equal(response.status, 201);
  assert.equal(h.sent[1].data.transport, 'sync_required'); assert.equal(h.sent[1].data.version, '2');
  assert.equal('body' in h.sent[1].data, false); assert.equal('headers' in h.sent[1].data, false);
  assert.equal(await count('messages'), 0); assert.equal(h.pending.length, 0);
  const id = response.headers.get('Location').split('/').at(-1);
  assert.equal((await getMessage(owner, id)).status, 404);
});

test('hybrid waits for FCM acceptance and never acknowledges a failed send as stored', async () => {
  hybridApp(); const { endpoint } = await hybridRegistration();
  const started = Promise.withResolvers(), finish = Promise.withResolvers();
  h.outcomes.push(() => { started.resolve(); return finish.promise; });
  let responded = false;
  const responsePromise = push(endpoint).then(response => { responded = true; return response; });
  await started.promise;
  assert.equal(responded, false); assert.equal(await count('messages'), 0);
  finish.resolve(Response.json({}, { status: 429, headers: { 'Retry-After': '120' } }));
  const response = await responsePromise;
  assert.equal(response.status, 503); assert.equal(response.headers.get('Retry-After'), '120');
  assert.deepEqual(await response.json(), { error: 'delivery_unavailable' });
  await h.app.scheduled({}, h.env, {}); assert.equal(h.sent.length, 1); assert.equal(await count('messages'), 0);
  h.outcomes.push(Response.json({}, { status: 400 }));
  assert.equal((await push(endpoint)).status, 502);
  assert.equal((await h.db.prepare('SELECT invalid FROM registrations').first()).invalid, 0);
  assert.deepEqual(h.logs, ['relay_delivery_retry', 'relay_delivery_configuration_error']);
});

test('hybrid keeps TTL zero discard, rejects paused delivery and expires during authentication', async () => {
  hybridApp(); const { endpoint } = await hybridRegistration();
  h.env.DELIVERY_ENABLED = 'false';
  assert.equal((await push(endpoint)).status, 503);
  assert.equal((await push(endpoint, undefined, { TTL: '0' })).status, 201);
  assert.equal(h.sent.length, 0); assert.equal(await count('messages'), 0);
  h.env.DELIVERY_ENABLED = 'true';
  hybridApp(async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token') h.clock += 2000;
    return fakeGoogle(url, init);
  });
  assert.equal((await push(endpoint, undefined, { TTL: '1' })).status, 503);
  assert.equal(h.sent.length, 0); assert.equal(await count('messages'), 0);
});

test('hybrid rechecks key revisions and fences stale UNREGISTERED responses', async () => {
  hybridApp(); const { owner, endpoint } = await hybridRegistration();
  const store = new D1Store(h.db), binding = await store.destination(endpoint.split('/').at(-1));
  assert.equal((await putBound(owner, { revision: 2, token: 'refreshed' })).status, 200);
  await assert.rejects(store.directDestination(binding, h.clock), error => error.code === 'binding_changed');
  h.outcomes.push(async () => {
    assert.equal((await putBound(owner, { revision: 3, token: 'latest' })).status, 200);
    return unregistered();
  });
  assert.equal((await push(endpoint)).status, 409);
  const current = await h.db.prepare('SELECT invalid,fcm_token FROM registrations WHERE id=?').bind(owner.id).first();
  assert.deepEqual(current, { invalid: 0, fcm_token: 'latest' });
  h.outcomes.push(unregistered()); assert.equal((await push(endpoint)).status, 410);
  assert.equal((await push(endpoint)).status, 410); assert.equal(await count('messages'), 0);
});

test('hybrid Push, removal and daily maintenance never access messages', async () => {
  hybridApp(); const { owner, endpoint } = await hybridRegistration();
  const sql = [];
  h.env.DB = { prepare(statement) { sql.push(statement); return h.db.prepare(statement); }, batch: items => h.db.batch(items) };
  assert.equal((await push(endpoint)).status, 201);
  assert.equal(sql.length, 4);
  assert.equal((await remove(owner)).status, 204);
  const pendingOwner = account(); await putBound(pendingOwner, { key: null });
  h.clock += 2 * 86400000;
  const before = sql.length; await h.app.scheduled({}, h.env, {}); assert.equal(sql.length - before, 3);
  assert.equal(sql.some(statement => /\bmessages\b/.test(statement)), false);
  assert.equal((await h.db.prepare('SELECT deleted,fcm_token,server_key FROM registrations WHERE id=?').bind(pendingOwner.id).first()).deleted, 1);
  assert.equal(await count('request_usage'), 0); assert.equal(await count('daily_usage'), 0);
  assert.equal((await putBound(owner, { revision: 2 })).status, 410);
});

test('hybrid rejects invalid signatures and oversized bodies while preserving admission limits', async () => {
  hybridApp(); const { endpoint } = await hybridRegistration();
  assert.equal((await push(endpoint, undefined, await signedHeaders({ aud: 'https://wrong.example' }))).status, 401);
  assert.equal((await push(endpoint, new Uint8Array(65537))).status, 413);
  await h.db.prepare('UPDATE daily_usage SET count=?').bind(LIMITS.dailyRequests).run();
  const limited = await push(endpoint); assert.equal(limited.status, 429);
  assert.equal(limited.headers.has('Retry-After'), true); assert.equal(h.sent.length, 0);
});

test('hybrid bundle runs in workerd with inline, sync_required and transient failure', async () => {
  const script = await readFile(new URL('../dist/hybrid/hybrid-worker.js', import.meta.url), 'utf8');
  const runtime = new Miniflare(convertV4MiniflareOptions({ modules: true, script, compatibilityDate: '2026-09-21', d1Databases: ['DB'],
    bindings: Object.fromEntries(Object.entries(h.env).filter(([key]) => key !== 'DB')),
    outboundService: async incoming => {
      const result = await fakeGoogle(incoming.url, { method: incoming.method,
        headers: { Authorization: incoming.headers.get('authorization') }, body: await incoming.text(), redirect: 'manual' });
      return new MiniflareResponse(await result.text(), { status: result.status, headers: Object.fromEntries(result.headers) });
    },
  }));
  try {
    const remoteDb = await runtime.getD1Database('DB');
    const schema = (await Promise.all(['0001_initial.sql','0002_bound_registrations.sql'].map(name => readFile(new URL('../migrations/' + name, import.meta.url), 'utf8')))).join('\n');
    await remoteDb.batch(schema.replace(/--[^\n]*/g, '').split(';').filter(sql => sql.trim()).map(sql => remoteDb.prepare(sql)));
    const owner = account();
    const registered = await runtime.dispatchFetch(`${origin}/v2/registrations/${owner.id}`, { method: 'PUT',
      headers: { Authorization: `Bearer ${owner.management}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ fcmToken: 'candidate-only', serverKey: h.publicKey, revision: 1 }) });
    assert.equal(registered.status, 201); const endpoint = (await registered.json()).endpoint;
    const send = async bytes => runtime.dispatchFetch(endpoint, { method: 'POST', body: new Uint8Array(bytes),
      headers: { ...(await signedHeaders()), TTL: '3600', 'Content-Encoding': 'aes128gcm' } });
    assert.equal((await send(1024)).status, 201); assert.equal(h.sent.at(-1).data.transport, 'inline');
    assert.equal((await send(4096)).status, 201); assert.equal(h.sent.at(-1).data.transport, 'sync_required');
    h.outcomes.push(Response.json({}, { status: 503 })); assert.equal((await send(4096)).status, 503);
    assert.equal((await remoteDb.prepare('SELECT count(*) AS n FROM messages').first()).n, 0);
  } finally { await runtime.dispose(); }
});
