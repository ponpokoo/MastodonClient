import assert from 'node:assert/strict';
import { before, test } from 'node:test';
import { FcmSender } from '../src/fcm.mjs';

let config;
before(async () => {
  const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  config = { projectId: 'test-project', clientEmail: 'test@test-project.iam.gserviceaccount.com',
    privateKey: `-----BEGIN PRIVATE KEY-----\n${Buffer.from(await crypto.subtle.exportKey('pkcs8', pair.privateKey)).toString('base64')}\n-----END PRIVATE KEY-----` };
});
const tokenReply = () => Response.json({ access_token: 'local-mock', expires_in: 3600 });
const success = () => Response.json({ name: 'projects/test-project/messages/local' });

test('concurrent sends share OAuth refresh and refresh before token expiration', async () => {
  let time = 1000000; let auth = 0;
  const sender = new FcmSender(config, { now: () => time, fetcher: async url => {
    if (url.includes('oauth2')) { auth++; return tokenReply(); } return success();
  } });
  const results = await Promise.all([sender.send('device', {}, time + 86400000), sender.send('device', {}, time + 86400000)]);
  assert.deepEqual(results.map(r => r.kind), ['success', 'success']); assert.equal(auth, 1);
  time += 3550000;
  await sender.send('device', {}, time + 10000); assert.equal(auth, 2);
});
test('FCM 401 clears cached auth and does not retire device', async () => {
  let auth = 0; let sends = 0;
  const sender = new FcmSender(config, { fetcher: async url => {
    if (url.includes('oauth2')) { auth++; return tokenReply(); }
    return ++sends === 1 ? Response.json({}, { status: 401 }) : success();
  } });
  assert.equal((await sender.send('device', {}, Date.now() + 10000)).kind, 'retry');
  assert.equal((await sender.send('device', {}, Date.now() + 10000)).kind, 'success'); assert.equal(auth, 2);
});
test('expiry during auth prevents sending a late push', async () => {
  let time = 1000; let sends = 0;
  const sender = new FcmSender(config, { now: () => time, fetcher: async url => {
    if (url.includes('oauth2')) { time = 11000; return tokenReply(); } sends++; return success();
  } });
  assert.equal((await sender.send('device', {}, 11000)).kind, 'expired'); assert.equal(sends, 0);
});
test('network errors and malformed OAuth response remain retryable without exposing error text', async () => {
  for (const fetcher of [async () => { throw new Error('sensitive-credential'); }, async () => Response.json({})]) {
    const sender = new FcmSender(config, { fetcher });
    assert.deepEqual(await sender.send('device', {}, Date.now() + 10000), { kind: 'retry', delaySeconds: 60 });
  }
});
test('HTTP-date Retry-After is honored and ordinary INVALID_ARGUMENT does not retire device', async () => {
  const time = 1750000000000;
  const sender = new FcmSender(config, { now: () => time, fetcher: async url => url.includes('oauth2') ? tokenReply() :
    Response.json({ error: { status: 'INVALID_ARGUMENT' } }, { status: 400,
      headers: { 'Retry-After': new Date(time + 300000).toUTCString() } }) });
  assert.deepEqual(await sender.send('device', {}, time + 3600000), { kind: 'retry', delaySeconds: 300 });
});
