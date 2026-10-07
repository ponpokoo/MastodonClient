import { base64url, check, deliveryData, hybridDeliveryData, digest, envelope, HttpError, managementHash, normalizeServerKey, parsePush, randomId, readBody, unbase64url, verifyVapid } from './protocol.mjs';
import { FcmSender } from './fcm.mjs';
import { capacityLimits, D1Store, LIMITS } from './store.mjs';

function configuration(env) {
  check(env.RELAY_ENABLED === 'true', 503, 'relay_disabled');
  try {
    const origin = new URL(env.PUBLIC_ORIGIN);
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error();
    const configuredKeys = JSON.parse(env.VAPID_PUBLIC_KEYS ?? '[]');
    if (!Array.isArray(configuredKeys) || configuredKeys.length > 20) throw new Error();
    const keys = configuredKeys.map(value => {
      const raw = unbase64url(value);
      if (raw.length !== 65 || raw[0] !== 4) throw new Error();
      return base64url(raw);
    });
    if (!env.DB || !/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(env.FCM_PROJECT_ID ?? '') ||
      !/^[^\s@]+@[^\s@]+\.iam\.gserviceaccount\.com$/.test(env.FCM_CLIENT_EMAIL ?? '') ||
      !env.FCM_PRIVATE_KEY?.includes('BEGIN PRIVATE KEY')) throw new Error();
    return { origin: origin.origin, keys, projectId: env.FCM_PROJECT_ID, clientEmail: env.FCM_CLIENT_EMAIL, privateKey: env.FCM_PRIVATE_KEY,
      legacyUntil: Date.parse(env.LEGACY_V1_UNTIL ?? ''),
      registrationEnabled: env.REGISTRATION_ENABLED === 'true', pushEnabled: env.PUSH_ENABLED === 'true',
      deliveryEnabled: env.DELIVERY_ENABLED === 'true' };
  } catch { throw new HttpError(503, 'relay_not_configured'); }
}
function reply(status, value, headers = {}) {
  return new Response(value === undefined ? null : JSON.stringify(value), {
    status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers },
  });
}

export function createWorker({ now = Date.now, fetcher = (url, init) => fetch(url, init), report = code => console.error(code), limits = LIMITS, deliveryMode = 'queued' } = {}) {
  // Trusted entry point only: no request or environment flag can switch existing subscriptions.
  if (!['queued', 'hybrid'].includes(deliveryMode)) throw new TypeError('invalid_delivery_mode');
  const hybrid = deliveryMode === 'hybrid';
  const admissionLimits = capacityLimits(limits);
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
    if (result.kind === 'permanent') report('relay_delivery_configuration_error');
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
        const store = new D1Store(env.DB, admissionLimits);
        if (request.method === 'GET' && url.pathname === '/v2/capabilities') {
          return reply(200, { registrationVersion: 2, keyBinding: true,
            ...(hybrid ? { deliveryMode: 'hybrid', deliveryVersion: 2, syncRequired: true } : {}) });
        }
        const registration = /^\/v([12])\/registrations\/([A-Za-z0-9_-]{22,128})$/.exec(url.pathname);
        if (registration && request.method === 'PUT') {
          check(!hybrid || registration[1] === '2', 426, 'bound_registration_required');
          const hash = await managementHash(request.headers.get('authorization'));
          check(request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() === 'application/json', 415, 'json_required');
          const bytes = await readBody(request, 8192);
          let body;
          try { body = JSON.parse(new TextDecoder().decode(bytes)); } catch { throw new HttpError(400, 'invalid_json'); }
          check(body && typeof body.fcmToken === 'string' && body.fcmToken.length > 0 && body.fcmToken.length <= 4096 &&
            !/[\s\x00-\x1f]/.test(body.fcmToken), 400, 'invalid_registration');
          const id = registration[2];
          const before = await store.registration(id, hash);
          // Authenticate existing registrations before allocating per-registration counters.
          await store.throttle(before ? 'update' : 'create', before ? id : 'global', now(), before ? 30 : 10);
          let result;
          if (registration[1] === '2') {
            check(Object.keys(body).every(key => ['fcmToken', 'serverKey', 'revision'].includes(key)) &&
              Number.isSafeInteger(body.revision) && body.revision > 0 && (body.serverKey == null || typeof body.serverKey === 'string'), 400, 'invalid_registration');
            const key = body.serverKey == null ? null : await normalizeServerKey(body.serverKey);
            const requestHash = await digest(JSON.stringify([body.fcmToken, key, body.revision]));
            result = await store.registerBound(id, hash, body.fcmToken, key, body.revision, requestHash, now(), config.registrationEnabled);
          } else {
            check(now() < config.legacyUntil && config.keys.length, 426, 'bound_registration_required');
            check(Object.keys(body).every(key => key === 'fcmToken'), 400, 'invalid_registration');
            check(before || config.registrationEnabled, 503, 'registration_paused');
            result = await store.register(id, hash, body.fcmToken);
          }
          return reply(result.created ? 201 : 200, { endpoint: `${config.origin}/push/${result.deliveryId}`,
            ...(registration[1] === '2' ? { revision: result.revision, state: result.state } : {}) });
        }
        if (registration && request.method === 'DELETE') {
          const hash = await managementHash(request.headers.get('authorization'));
          if (!await store.registration(registration[2], hash)) await store.throttle('unknown_delete', 'global', now(), 10);
          await store.remove(registration[2], hash, !hybrid);
          return reply(204);
        }
        const message = /^\/v1\/registrations\/([A-Za-z0-9_-]{22,128})\/messages\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
        if (message && request.method === 'GET') {
          check(!hybrid, 404, 'not_found');
          const hash = await managementHash(request.headers.get('authorization'));
          check(await store.registration(message[1], hash), 404, 'not_found');
          await store.throttle('fetch', message[1], now(), 60);
          return reply(200, envelope(await store.message(message[1], message[2], hash, now())));
        }
        const push = /^\/push\/([A-Za-z0-9_-]{43})$/.exec(url.pathname);
        if (push && request.method === 'POST') {
          check(config.pushEnabled, 503, 'push_paused');
          const destination = await store.destination(push[1]);
          check(!hybrid || destination.protocol === 2, 426, 'bound_registration_required');
          check(destination.protocol === 1 ? now() < config.legacyUntil && config.keys.length :
            destination.server_key && (!destination.pending_until || destination.pending_until > now()), 403, 'key_binding_pending');
          await store.throttle('push', destination.id, now(), 360);
          await store.admit(now());
          await verifyVapid(request.headers, config.origin,
            destination.protocol === 2 ? [destination.server_key] : config.keys, now());
          const bytes = await readBody(request, 65536);
          const payload = parsePush(request.headers, bytes, now());
          if (hybrid) {
            const current = await store.directDestination(destination, now());
            const id = randomId();
            if (payload.ttl > 0) {
              // No durable recovery: never acknowledge success before FCM accepts the send.
              check(config.deliveryEnabled, 503, 'delivery_paused');
              const data = hybridDeliveryData({ ...payload, id, registration_id: current.id });
              const result = await getSender(config).send(current.fcm_token, data, payload.expires_at);
              if (result.kind === 'invalid') {
                check(await store.invalidateDirect(current, now()), 409, 'binding_changed');
                throw new HttpError(410, 'subscription_gone');
              }
              if (result.kind === 'retry') {
                report('relay_delivery_retry');
                throw new HttpError(503, 'delivery_unavailable', result.delaySeconds ?? 60);
              }
              if (result.kind === 'permanent') {
                report('relay_delivery_configuration_error');
                throw new HttpError(502, 'delivery_configuration_error');
              }
              check(result.kind === 'success', 503, 'delivery_expired');
            }
            return reply(201, undefined, { TTL: String(payload.ttl), Location: `${config.origin}/receipts/${id}` });
          }
          const id = await store.enqueue(destination.id, payload, now(), destination);
          if (payload.ttl > 0 && config.deliveryEnabled) {
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
          error instanceof HttpError && error.retryAfter !== undefined ? { 'Retry-After': String(error.retryAfter) } :
            error.status === 429 ? { 'Retry-After': error.code === 'daily_capacity' ? String(86400 - Math.floor(now() / 1000) % 86400) : '60' } : {});
      }
    },
    async scheduled(_controller, env, _ctx) {
      if (env.RELAY_ENABLED !== 'true') return;
      try {
        const config = configuration(env);
        const store = new D1Store(env.DB, admissionLimits);
        if (hybrid) { await store.pruneDirect(now()); return; }
        await store.prune(now());
        if (!config.deliveryEnabled) return;
        const candidates = await store.due(now());
        for (const candidate of candidates) {
          // Free permits 50 D1 statements/invocation. Reserve the worst-case claim,
          // token retirement and stale-result release before starting another send.
          if (store.statements > 45) break;
          await deliver(store, candidate.id, config);
        }
      } catch {
        report('relay_scheduled_failed');
        throw new Error('relay_scheduled_failed');
      }
    },
  };
}

export default createWorker();
