const utf8 = new TextEncoder();
export const bytesOf = value => utf8.encode(value);
export class HttpError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
export function check(condition, status, code) {
  if (!condition) throw new HttpError(status, code);
}
export function base64url(bytes) {
  let value = '';
  for (let offset = 0; offset < bytes.length; offset += 4096) {
    value += String.fromCharCode(...bytes.subarray(offset, offset + 4096));
  }
  return btoa(value).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
export function unbase64url(value) {
  check(typeof value === 'string' && /^[A-Za-z0-9_-]+={0,2}$/.test(value), 401, 'invalid_vapid');
  const raw = value.replace(/=+$/, '');
  check(raw.length % 4 !== 1, 401, 'invalid_vapid');
  const bytes = Uint8Array.from(atob(raw.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - raw.length % 4) % 4)), c => c.charCodeAt(0));
  check(base64url(bytes) === raw, 401, 'invalid_vapid');
  return bytes;
}
export const randomId = () => base64url(crypto.getRandomValues(new Uint8Array(32)));
export const digest = async value => base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', bytesOf(value))));
export async function readBody(request, maximum) {
  check(Number(request.headers.get('content-length') ?? 0) <= maximum, 413, 'body_too_large');
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > maximum) { await reader.cancel(); throw new HttpError(413, 'body_too_large'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
  return result;
}
export async function managementHash(authorization) {
  check(/^Bearer [A-Za-z0-9_-]{43,128}$/.test(authorization ?? ''), 401, 'unauthorized');
  return digest(authorization.slice(7));
}
export function envelope(message) {
  return { version: '1', registrationId: message.registration_id, messageId: message.id,
    encoding: message.encoding, headers: message.headers, body: message.body };
}
export function deliveryData(message) {
  const data = { ...envelope(message), transport: 'inline' };
  return bytesOf(JSON.stringify(data)).length <= 3500 ? data :
    { version: '1', registrationId: message.registration_id, messageId: message.id, transport: 'fetch' };
}

// Only operator-configured Mastodon keys may sign pushes in this trial relay.
// No network key discovery, self-signed-key acceptance, or request-supplied URLs.
export async function verifyVapid(headers, origin, allowedKeys, now) {
  try {
    const authorization = headers.get('authorization') ?? '';
    check(authorization.length <= 4096, 401, 'invalid_vapid');
    let jwt, key;
    if (/^vapid /i.test(authorization)) {
      const params = new Map();
      for (const item of authorization.slice(6).split(',')) {
        const match = /^\s*([tk])\s*=\s*"?([A-Za-z0-9_.=-]+)"?\s*$/.exec(item);
        check(match && !params.has(match[1]), 401, 'invalid_vapid');
        params.set(match[1], match[2]);
      }
      jwt = params.get('t'); key = params.get('k');
    } else {
      // Older Mastodon/webpush implementations use the pre-RFC WebPush scheme.
      jwt = /^WebPush ([A-Za-z0-9_.-]+)$/i.exec(authorization)?.[1];
      const keys = [...(headers.get('crypto-key') ?? '').matchAll(/(?:^|[;,])\s*p256ecdsa=([A-Za-z0-9_-]+={0,2})(?=\s*(?:[;,]|$))/g)];
      check(keys.length === 1, 401, 'invalid_vapid'); key = keys[0][1];
    }
    const rawKey = unbase64url(key);
    const normalized = base64url(rawKey);
    check(rawKey.length === 65 && rawKey[0] === 4 && allowedKeys.includes(normalized), 403, 'vapid_key_not_allowed');
    const parts = jwt?.split('.');
    check(parts?.length === 3, 401, 'invalid_vapid');
    const header = JSON.parse(new TextDecoder().decode(unbase64url(parts[0])));
    const claims = JSON.parse(new TextDecoder().decode(unbase64url(parts[1])));
    check(header?.alg === 'ES256' && header.crit === undefined, 401, 'invalid_vapid');
    const seconds = Math.floor(now / 1000);
    check(claims?.aud === origin && Number.isInteger(claims.exp) && claims.exp > seconds && claims.exp <= seconds + 86400,
      401, 'invalid_vapid');
    const signature = unbase64url(parts[2]);
    check(signature.length === 64, 401, 'invalid_vapid');
    const publicKey = await crypto.subtle.importKey('raw', rawKey, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    check(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey, signature, bytesOf(`${parts[0]}.${parts[1]}`)),
      401, 'invalid_vapid');
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(401, 'invalid_vapid');
  }
}

export function parsePush(headers, bytes, now) {
  check(/^\d{1,10}$/.test(headers.get('ttl') ?? ''), 400, 'invalid_ttl');
  const ttl = Math.min(Number(headers.get('ttl')), 86400);
  const encoding = headers.get('content-encoding');
  check(['aes128gcm', 'aesgcm'].includes(encoding), 415, 'unsupported_encoding');
  check(bytes.length > 0 && bytes.length <= 65536, 413, 'invalid_payload_size');
  const cryptoHeaders = {};
  const encryption = headers.get('encryption');
  const cryptoKey = headers.get('crypto-key');
  check((encryption?.length ?? 0) <= 2048 && (cryptoKey?.length ?? 0) <= 2048, 400, 'invalid_encryption_headers');
  if (encryption) cryptoHeaders.encryption = encryption;
  // Forward only the ECDH key. Never forward VAPID authorization or p256ecdsa.
  if (cryptoKey) {
    const dh = /(?:^|[;,])\s*dh=([A-Za-z0-9_-]+={0,2})(?=\s*(?:[;,]|$))/.exec(cryptoKey)?.[1];
    if (dh) cryptoHeaders['crypto-key'] = `dh=${dh}`;
  }
  check(encoding !== 'aesgcm' || (cryptoHeaders.encryption && cryptoHeaders['crypto-key']), 400, 'missing_encryption_headers');
  return { ttl, encoding, headers: JSON.stringify(cryptoHeaders), body: base64url(bytes), expires_at: now + ttl * 1000 };
}
