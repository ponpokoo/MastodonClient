import { randomBytes } from 'node:crypto';
const origin = 'http://127.0.0.1:8787';
const id = randomBytes(32).toString('base64url');
const headers = { Authorization: `Bearer ${randomBytes(32).toString('base64url')}`, 'Content-Type': 'application/json' };
const registration = `${origin}/v1/registrations/${id}`;
async function expect(response, status) { if (response.status !== status) throw new Error(`Unexpected HTTP status ${response.status}`); return response; }
try {
  const response = await expect(await fetch(registration, { method: 'PUT', headers, body: JSON.stringify({ fcmToken: 'local-demo-token' }) }), 201);
  const { endpoint } = await response.json();
  await expect(await fetch(endpoint, { method: 'POST', headers: { TTL: '60', 'Content-Encoding': 'aes128gcm' },
    body: randomBytes(128) }), 201);
  await new Promise(resolve => setTimeout(resolve, 1500));
  const health = await (await fetch(`${origin}/health`)).json();
  console.log(JSON.stringify({ mode: health.mode, queued: health.queued, retained: health.retained,
    mockAttempts: health.mockAttempts, mockAccepted: health.mockAccepted }));
  console.log('Synthetic bytes accepted; cryptographic validity and Android delivery are not tested.');
} finally {
  await expect(await fetch(registration, { method: 'DELETE', headers }), 204);
}
