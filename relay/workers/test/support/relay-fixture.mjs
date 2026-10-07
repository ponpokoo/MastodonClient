import assert from 'node:assert/strict';
import { after, before, beforeEach } from 'node:test';
import { readFile } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createWorker } from '../../src/worker.mjs';
import { base64url, bytesOf, randomId } from '../../src/protocol.mjs';

// Each test file owns its runtime, clock, credentials and mock delivery state.
export function createRelayFixture() {
  const origin = 'https://relay.example';
  const start = Date.now();
  const h = {};
  async function signedHeaders({ aud = origin, exp = Math.floor(h.clock / 1000) + 3600, keyPair = h.pair, key = h.publicKey, legacy = false } = {}) {
    const head = base64url(bytesOf(JSON.stringify({ alg: 'ES256', typ: 'JWT' })));
    const body = base64url(bytesOf(JSON.stringify({ aud, exp, sub: 'mailto:test@example.test' })));
    const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, keyPair.privateKey, bytesOf(`${head}.${body}`));
    const jwt = `${head}.${body}.${base64url(new Uint8Array(signature))}`;
    return legacy ? { Authorization: `WebPush ${jwt}`, 'Crypto-Key': `p256ecdsa=${key};dh=BAAA` } : { Authorization: `vapid t=${jwt}, k=${key}` };
  }
  const account = () => ({ id: randomId(), management: randomId() });
  function request(path, method = 'GET', body, headers = {}) {
    return h.app.fetch(new Request(new URL(path, origin), { method, body, headers }), h.env, { waitUntil(promise) { h.pending.push(promise); } });
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
  async function settle() { const work = h.pending ?? []; h.pending = []; await Promise.all(work); }
  const count = async table => (await h.db.prepare(`SELECT count(*) AS n FROM ${table}`).first()).n;
  function putBound(owner, { key = h.publicKey, revision = 1, token = 'test-device-token' } = {}) {
    return request(`/v2/registrations/${owner.id}`, 'PUT', JSON.stringify({ fcmToken: token, serverKey: key, revision }),
      { Authorization: `Bearer ${owner.management}`, 'Content-Type': 'application/json' });
  }
  async function fakeGoogle(url, init) {
    assert.equal(init.redirect, 'manual');
    if (url === 'https://oauth2.googleapis.com/token') {
      h.oauthCalls++;
      const jwt = new URLSearchParams(init.body).get('assertion').split('.');
      assert.equal(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', h.rsa.publicKey,
        Buffer.from(jwt[2], 'base64url'), bytesOf(`${jwt[0]}.${jwt[1]}`)), true);
      const claims = JSON.parse(Buffer.from(jwt[1], 'base64url'));
      assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
      assert.equal(claims.aud, url);
      return Response.json({ access_token: 'mock-access-token', expires_in: 3600 });
    }
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/test-project/messages:send');
    assert.equal(init.headers.Authorization, 'Bearer mock-access-token');
    const body = JSON.parse(init.body); h.sent.push(body.message);
    const outcome = h.outcomes.shift();
    if (typeof outcome === 'function') return outcome();
    return outcome ?? Response.json({ name: 'projects/test-project/messages/mock' });
  }

  before(async () => {
    h.mf = new Miniflare(convertV4MiniflareOptions({ modules: true, script: 'export default {fetch(){return new Response("test")}}',
      compatibilityDate: '2026-09-21', d1Databases: ['DB', 'MIGRATION'] }));
    h.db = await h.mf.getD1Database('DB');
    const sql = (await Promise.all(['0001_initial.sql','0002_bound_registrations.sql'].map(name => readFile(new URL('../../migrations/' + name, import.meta.url), 'utf8')))).join('\n');
    await h.db.batch(sql.replace(/--[^\n]*/g, '').split(';').filter(s => s.trim()).map(s => h.db.prepare(s)));
    h.pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    h.publicKey = base64url(new Uint8Array(await crypto.subtle.exportKey('raw', h.pair.publicKey)));
    h.rsa = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
    h.privatePem = `-----BEGIN PRIVATE KEY-----\n${Buffer.from(await crypto.subtle.exportKey('pkcs8', h.rsa.privateKey)).toString('base64')}\n-----END PRIVATE KEY-----`;
  });
  beforeEach(async () => {
    await h.db.batch(['DELETE FROM messages', 'DELETE FROM registrations', 'DELETE FROM daily_usage', 'DELETE FROM request_usage'].map(sql => h.db.prepare(sql)));
    h.clock = start; h.pending = []; h.sent = []; h.oauthCalls = 0; h.outcomes = []; h.logs = [];
    h.env = { DB: h.db, RELAY_ENABLED: 'true', REGISTRATION_ENABLED: 'true', PUSH_ENABLED: 'true', DELIVERY_ENABLED: 'true', LEGACY_V1_UNTIL: new Date(start + 86400000).toISOString(), PUBLIC_ORIGIN: origin, VAPID_PUBLIC_KEYS: JSON.stringify([h.publicKey]),
      FCM_PROJECT_ID: 'test-project', FCM_CLIENT_EMAIL: 'relay@test-project.iam.gserviceaccount.com', FCM_PRIVATE_KEY: h.privatePem };
    h.app = createWorker({ now: () => h.clock, fetcher: fakeGoogle, report: code => h.logs.push(code) });
  });
  after(async () => { await settle(); await h.mf?.dispose(); });

  return { state: h, origin, start, signedHeaders, account, request, put, remove,
    getMessage, register, push, settle, count, putBound, fakeGoogle };
}
