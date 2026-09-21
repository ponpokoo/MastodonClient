import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { Store } from './store.mjs';
import { Relay, MockSender, HttpError } from './relay.mjs';

async function body(request, maximum) {
  if (Number(request.headers['content-length']) > maximum) throw new HttpError(413, 'body_too_large');
  const chunks = []; let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maximum) throw new HttpError(413, 'body_too_large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export async function start({ directory = resolve('data'), port = 8787, sender = new MockSender(),
  interval = 250, publicOrigin, now = Date.now } = {}) {
  if (publicOrigin) {
    const url = new URL(publicOrigin);
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('Public origin must be an HTTPS origin');
    publicOrigin = url.origin;
  }
  const store = new Store(directory);
  const relay = new Relay(store, sender, { now });
  let origin;
  let requests = 0; let windowStart = now();
  const server = createServer({ maxHeaderSize: 8192 }, async (request, response) => {
    const reply = (status, value, headers = {}) => {
      response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers });
      response.end(value === undefined ? undefined : JSON.stringify(value));
    };
    try {
      if (now() - windowStart >= 60000) { requests = 0; windowStart = now(); }
      if (++requests > 300) throw new HttpError(429, 'rate_limited');
      const url = new URL(request.url, origin);
      if (url.search) throw new HttpError(400, 'unexpected_query');
      if (request.method === 'GET' && url.pathname === '/health') {
        reply(200, { status: 'ok', mode: 'local-mock', ...relay.stats(),
          mockAttempts: sender.attempts ?? 0, mockAccepted: sender.accepted ?? 0 }); return;
      }
      const registration = /^\/v1\/registrations\/([A-Za-z0-9_-]{22,128})$/.exec(url.pathname);
      if (registration && request.method === 'PUT') {
        if (request.headers['content-type']?.split(';')[0] !== 'application/json') throw new HttpError(415, 'json_required');
        let parsed;
        const bytes = await body(request, 8192);
        try { parsed = JSON.parse(bytes.toString('utf8')); } catch { throw new HttpError(400, 'invalid_json'); }
        const result = relay.register(registration[1], request.headers.authorization, parsed);
        reply(result.created ? 201 : 200, { endpoint: `${origin}/push/${result.deliveryId}` }); return;
      }
      if (registration && request.method === 'DELETE') {
        relay.remove(registration[1], request.headers.authorization); reply(204); return;
      }
      const push = /^\/push\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
      if (push && request.method === 'POST') {
        const result = relay.enqueue(push[1], request.headers, await body(request, 65536));
        reply(201, undefined, { TTL: String(result.ttl), Location: `${origin}/receipts/${result.id}` }); return;
      }
      const message = /^\/v1\/registrations\/([A-Za-z0-9_-]{22,128})\/messages\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
      if (message && request.method === 'GET') {
        reply(200, relay.fetch(message[1], message[2], request.headers.authorization)); return;
      }
      throw new HttpError(404, 'not_found');
    } catch (error) {
      // Never expose exception messages, request paths, ciphertext or credentials.
      reply(error instanceof HttpError ? error.status : 500,
        { error: error instanceof HttpError ? error.code : 'internal_error' },
        error.status === 429 ? { 'Retry-After': '60' } : {});
      request.resume();
    }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  try {
    await new Promise((accept, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', accept); });
  } catch (error) { store.close(); throw error; }
  origin = publicOrigin ?? `http://127.0.0.1:${server.address().port}`;
  let pending = Promise.resolve();
  const timer = interval ? setInterval(() => {
    if (!relay.running) pending = relay.tick().catch(() => { /* Persistent state remains available for retry. */ });
  }, interval) : undefined;
  return { origin, relay, store, sender,
    async close() {
      clearInterval(timer);
      server.closeIdleConnections();
      await new Promise((accept, reject) => server.close(error => error ? reject(error) : accept()));
      await pending;
      store.close();
    },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const app = await start({ directory: resolve(process.env.RELAY_DATA_DIR ?? 'data'),
      port: Number(process.env.PORT ?? 8787), publicOrigin: process.env.RELAY_PUBLIC_ORIGIN,
      sender: new MockSender(process.env.MOCK_FCM_MODE ?? 'success') });
    console.log('Nagisa Relay local mock listening on 127.0.0.1 (no real FCM delivery).');
    let closing = false;
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => {
      if (closing) return; closing = true;
      await app.close();
    });
  } catch { console.error('Relay failed to start. Check configuration, data permissions and process.lock.'); process.exitCode = 1; }
}
