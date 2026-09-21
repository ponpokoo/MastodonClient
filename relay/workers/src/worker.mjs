import { base64url, check, deliveryData, envelope, HttpError, managementHash, parsePush, readBody, unbase64url, verifyVapid } from './protocol.mjs';
import { FcmSender } from './fcm.mjs';
import { D1Store } from './store.mjs';

function configuration(env) {
  check(env.RELAY_ENABLED === 'true', 503, 'relay_disabled');
  try {
    const origin = new URL(env.PUBLIC_ORIGIN);
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error();
    const configuredKeys = JSON.parse(env.VAPID_PUBLIC_KEYS);
    if (!Array.isArray(configuredKeys) || !configuredKeys.length || configuredKeys.length > 20) throw new Error();
    const keys = configuredKeys.map(value => {
      const raw = unbase64url(value);
      if (raw.length !== 65 || raw[0] !== 4) throw new Error();
      return base64url(raw);
    });
    if (!env.DB || !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(env.FCM_PROJECT_ID ?? '') ||
      !/^[^\s@]+@[^\s@]+\.iam\.gserviceaccount\.com$/.test(env.FCM_CLIENT_EMAIL ?? '') ||
      !env.FCM_PRIVATE_KEY?.includes('BEGIN PRIVATE KEY')) throw new Error();
    return { origin: origin.origin, keys, projectId: env.FCM_PROJECT_ID, clientEmail: env.FCM_CLIENT_EMAIL, privateKey: env.FCM_PRIVATE_KEY };
  } catch { throw new HttpError(503, 'relay_not_configured'); }
}
function reply(status, value, headers = {}) {
  return new Response(value === undefined ? null : JSON.stringify(value), {
    status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers },
  });
}

export function createWorker({ now = Date.now, fetcher = (url, init) => fetch(url, init), report = code => console.error(code) } = {}) {
  let sender, senderConfig;
  function getSender(config) {
    if (!senderConfig || ['projectId', 'clientEmail', 'privateKey'].some(key => config[key] !== senderConfig[key])) {
      senderConfig = config; sender = new FcmSender(config, { fetcher, now });
    }
    return sender;
  }
  async function deliver(store, id, config) {
    const message = await store.claim(id, now());
    if (!message) return;
    const data = deliveryData(message);
    const result = await getSender(config).send(message.token, data, message.expires_at);
    await store.complete(message, result, data.transport, now());
    if (result.kind === 'retry') report('relay_delivery_retry');
  }
  return {
    async fetch(request, env, ctx) {
      try {
        const url = new URL(request.url);
        check(!url.search, 400, 'unexpected_query');
        if (request.method === 'GET' && url.pathname === '/health') {
          if (env.RELAY_ENABLED !== 'true') return reply(200, { status: 'disabled', mode: 'workers-d1' });
          configuration(env);
          return reply(200, { status: 'configured', mode: 'workers-d1' });
        }
        const config = configuration(env);
        const store = new D1Store(env.DB);
        const registration = /^\/v1\/registrations\/([A-Za-z0-9_-]{22,128})$/.exec(url.pathname);
        if (registration && request.method === 'PUT') {
          const hash = await managementHash(request.headers.get('authorization'));
          check(request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() === 'application/json', 415, 'json_required');
          const bytes = await readBody(request, 8192);
          let body;
          try { body = JSON.parse(new TextDecoder().decode(bytes)); } catch { throw new HttpError(400, 'invalid_json'); }
          check(body && typeof body.fcmToken === 'string' && body.fcmToken.length > 0 && body.fcmToken.length <= 4096 &&
            !/[\s\x00-\x1f]/.test(body.fcmToken) && Object.keys(body).every(key => key === 'fcmToken'), 400, 'invalid_registration');
          await store.admit(now());
          const result = await store.register(registration[1], hash, body.fcmToken);
          return reply(result.created ? 201 : 200, { endpoint: `${config.origin}/push/${result.deliveryId}` });
        }
        if (registration && request.method === 'DELETE') {
          const hash = await managementHash(request.headers.get('authorization'));
          await store.admit(now());
          await store.remove(registration[1], hash);
          return reply(204);
        }
        const message = /^\/v1\/registrations\/([A-Za-z0-9_-]{22,128})\/messages\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
        if (message && request.method === 'GET') {
          const hash = await managementHash(request.headers.get('authorization'));
          await store.admit(now());
          return reply(200, envelope(await store.message(message[1], message[2], hash, now())));
        }
        const push = /^\/push\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
        if (push && request.method === 'POST') {
          await verifyVapid(request.headers, config.origin, config.keys, now());
          const bytes = await readBody(request, 65536);
          const payload = parsePush(request.headers, bytes, now());
          await store.admit(now());
          const registrationId = await store.destination(push[1]);
          const id = await store.enqueue(registrationId, payload, now());
          if (payload.ttl > 0) {
            // Commit before acknowledging Mastodon. If waitUntil is interrupted,
            // the D1 row and expiring lease allow Cron to recover delivery.
            ctx.waitUntil(deliver(store, id, config).catch(() => report('relay_delivery_failed')));
          }
          return reply(201, undefined, { TTL: String(payload.ttl), Location: `${config.origin}/receipts/${id}` });
        }
        return reply(404, { error: 'not_found' });
      } catch (error) {
        if (!(error instanceof HttpError)) report('relay_request_failed');
        return reply(error instanceof HttpError ? error.status : 500,
          { error: error instanceof HttpError ? error.code : 'internal_error' },
          error.status === 429 ? { 'Retry-After': error.code === 'daily_capacity' ? String(86400 - Math.floor(now() / 1000) % 86400) : '60' } : {});
      }
    },
    async scheduled(_controller, env, _ctx) {
      if (env.RELAY_ENABLED !== 'true') return;
      try {
        const config = configuration(env);
        const store = new D1Store(env.DB);
        await store.prune(now());
        const candidate = await store.due(now());
        if (candidate) await deliver(store, candidate.id, config);
      } catch {
        report('relay_scheduled_failed');
        throw new Error('relay_scheduled_failed');
      }
    },
  };
}

export default createWorker();
