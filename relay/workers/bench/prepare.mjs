import { mkdir, writeFile } from 'node:fs/promises';
import { createHash, randomBytes, generateKeyPairSync } from 'node:crypto';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const origin = 'https://nagisa-relay-bench-20261006.ponta3921.workers.dev';
const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 });
const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const randomId = () => randomBytes(32).toString('base64url');
const fixture = { origin, controlToken: randomId(), endsAt: Date.now() + 90 * 60000,
  publicKey: Buffer.from(await crypto.subtle.exportKey('raw', pair.publicKey)).toString('base64url'),
  privateJwk: await crypto.subtle.exportKey('jwk', pair.privateKey),
  rsaPem: rsa.privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
  owners: Array.from({ length: 120 }, () => ({ id: randomId(), management: randomId() })),
};
await mkdir(new URL('../.wrangler/bench/', import.meta.url), { recursive: true });
await writeFile(new URL('../.wrangler/bench/fixture.json', import.meta.url), JSON.stringify(fixture));
const { privateJwk, owners, ...publicFixture } = fixture;
await writeFile(new URL('../.wrangler/bench/fixture-worker.json', import.meta.url), JSON.stringify({ ...publicFixture,
  owners: owners.map(owner => ({ id: owner.id, managementHash: createHash('sha256').update(owner.management).digest('base64url') })) }));
const result = await build({ entryPoints: [fileURLToPath(new URL('worker.mjs', import.meta.url))],
  bundle: true, format: 'esm', platform: 'browser', loader: { '.sql': 'text' }, write: false });
const bytes = result.outputFiles[0].contents;
await writeFile(new URL('../.wrangler/bench/worker.mjs', import.meta.url), bytes);
console.log(JSON.stringify({ artifactBytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'),
  syntheticRegistrations: fixture.owners.length }));
