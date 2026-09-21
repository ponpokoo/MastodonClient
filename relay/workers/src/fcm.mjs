import { base64url, bytesOf } from './protocol.mjs';

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

export class FcmSender {
  constructor({ projectId, clientEmail, privateKey }, { fetcher = (url, init) => fetch(url, init), now = Date.now } = {}) {
    this.projectId = projectId; this.clientEmail = clientEmail; this.privateKey = privateKey;
    this.fetcher = fetcher; this.now = now;
    this.cachedToken = undefined; this.pendingToken = undefined;
  }
  async request(url, init) {
    const abort = new AbortController();
    const timer = setTimeout(() => abort.abort(), 7000);
    try {
      // workerd supports manual/follow, not the browser's redirect: 'error'.
      // Never follow redirects carrying Google credentials to another destination.
      const response = await this.fetcher(url, { ...init, redirect: 'manual', signal: abort.signal });
      // Consume the response within the timeout, including a stalled response body.
      const payload = await response.json().catch(() => null);
      return { status: response.status, headers: response.headers, payload };
    } finally { clearTimeout(timer); }
  }
  async accessToken() {
    if (this.cachedToken && this.cachedToken.expiresAt > this.now() + 60000) return this.cachedToken.value;
    if (!this.pendingToken) {
      this.pendingToken = this.mintToken().finally(() => { this.pendingToken = undefined; });
    }
    return this.pendingToken;
  }
  async mintToken() {
    const issuedAt = Math.floor(this.now() / 1000);
    const header = base64url(bytesOf(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
    const claims = base64url(bytesOf(JSON.stringify({ iss: this.clientEmail, scope: SCOPE,
      aud: TOKEN_URL, iat: issuedAt, exp: issuedAt + 3600 })));
    const pem = this.privateKey.replaceAll('\\n', '\n').trim();
    if (!/^-----BEGIN PRIVATE KEY-----\s+[A-Za-z0-9+/=\s]+\s+-----END PRIVATE KEY-----$/.test(pem)) throw new Error('fcm_configuration');
    const der = Uint8Array.from(atob(pem.replace(/-----[^-]+-----/g, '').replace(/\s/g, '')), c => c.charCodeAt(0));
    const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, bytesOf(`${header}.${claims}`));
    const response = await this.request(TOKEN_URL, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: `${header}.${claims}.${base64url(new Uint8Array(signature))}` }).toString(),
    });
    const payload = response.payload;
    if (response.status !== 200 || typeof payload?.access_token !== 'string' || !payload.access_token ||
      !Number.isFinite(payload.expires_in) || payload.expires_in <= 60) throw new Error('fcm_authentication');
    this.cachedToken = { value: payload.access_token,
      expiresAt: issuedAt * 1000 + Math.min(payload.expires_in, 3600) * 1000 };
    return this.cachedToken.value;
  }
  async send(token, data, expiresAt) {
    try {
      const accessToken = await this.accessToken();
      const ttl = Math.floor((expiresAt - this.now()) / 1000);
      if (ttl <= 0) return { kind: 'expired' };
      const response = await this.request(`https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`, {
        method: 'POST', headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: { token, data, android: { priority: 'HIGH', ttl: `${Math.min(ttl, 86400)}s` } } }),
      });
      if (response.status === 200 && typeof response.payload?.name === 'string') return { kind: 'success' };
      if (response.status === 401) this.cachedToken = undefined;
      const details = response.payload?.error?.details;
      const unregistered = Array.isArray(details) && details.some(detail =>
        detail['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError' && detail.errorCode === 'UNREGISTERED');
      // A generic 400/404 can mean invalid payload/project. Never retire the device for it.
      if (response.status === 404 && unregistered) return { kind: 'invalid' };
      const retryAfter = response.headers.get('retry-after');
      let delay = /^\d+$/.test(retryAfter ?? '') ? Number(retryAfter) : (Date.parse(retryAfter) - this.now()) / 1000;
      if (!Number.isFinite(delay)) delay = 60;
      return { kind: 'retry', delaySeconds: Math.max(60, Math.min(86400, Math.ceil(delay))) };
    } catch {
      // Do not surface Google error bodies, credentials, JWTs, tokens, or request URLs.
      return { kind: 'retry', delaySeconds: 60 };
    }
  }
}
