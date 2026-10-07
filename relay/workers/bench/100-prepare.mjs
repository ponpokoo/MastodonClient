import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { createHash, randomBytes, generateKeyPairSync } from 'node:crypto';
import { bundleBenchmark } from './100-build.mjs';
import { LIMITS } from '../src/store.mjs';

const option = (name, fallback) => process.argv.find(value => value.startsWith(`--${name}=`))?.slice(name.length + 3) ?? fallback;
const durationHours = Number(option('duration-hours', '30'));
const deliveryMode = option('mode', 'queued');
if (!['queued', 'hybrid'].includes(deliveryMode)) throw new Error('invalid_delivery_mode');
const origin = option('origin', `https://nagisa-relay-bench-20261007-${deliveryMode === 'hybrid' ? 'hybrid' : '100'}.ponta3921.workers.dev`);
const parsed = new URL(origin);
if (parsed.origin !== origin || !/^nagisa-relay-bench-[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev$/.test(parsed.hostname) ||
    !Number.isFinite(durationHours) || durationHours < 1 || durationHours > 48) throw new Error('invalid_benchmark_scope');
const directory = new URL(deliveryMode === 'hybrid' ? '../.wrangler/bench-hybrid-20261007/' : '../.wrangler/bench-100-20261007/', import.meta.url);
// Never silently rotate a previously deployed benchmark's control credentials.
try { await readFile(new URL('fixture.json', directory)); throw new Error('benchmark_fixture_exists'); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const randomId = () => randomBytes(32).toString('base64url');
const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 });
const keys = await Promise.all(Array.from({ length: 200 }, async () => {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  return { publicKey: Buffer.from(await crypto.subtle.exportKey('raw', pair.publicKey)).toString('base64url'),
    privateJwk: await crypto.subtle.exportKey('jwk', pair.privateKey) };
}));
const fixture = { origin, deliveryMode, runId: `20261007-${deliveryMode === 'hybrid' ? 'hybrid' : '100'}`, controlToken: randomId(),
  endsAt: Date.now() + durationHours * 3600000,
  limits: { ...LIMITS, registrations: 300, pending: 50, messages: 8000 },
  rsaPem: rsa.privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(), keys,
  owners: Array.from({ length: 200 }, (_, index) => ({ id: randomId(), management: randomId(), keyIndex: index })),
};
await mkdir(directory, { recursive: true });
await writeFile(new URL('fixture.json', directory), JSON.stringify(fixture));
const serverFixture = { ...fixture, keys: keys.map(({ publicKey }) => ({ publicKey })),
  owners: fixture.owners.map(({ management, ...owner }) => ({ ...owner,
    managementHash: createHash('sha256').update(management).digest('base64url') })) };
await writeFile(new URL('fixture-worker.json', directory), JSON.stringify(serverFixture));
const artifact = await bundleBenchmark(directory, deliveryMode);
const manifest = { runId: fixture.runId, deliveryMode, ...artifact, subscriptions: fixture.owners.length,
  distinctServerKeys: keys.length, limits: fixture.limits, expiresAt: new Date(fixture.endsAt).toISOString(),
  scope: 'synthetic credentials and mock Google only; production limits unchanged' };
await writeFile(new URL('manifest.json', directory), JSON.stringify(manifest, null, 2));
console.log(JSON.stringify(manifest));
