import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

export async function bundleBenchmark(directory, deliveryMode) {
  const built = await build({ entryPoints: [fileURLToPath(new URL('100-worker.mjs', import.meta.url))],
    bundle: true, format: 'esm', platform: 'browser', loader: { '.sql': 'text' }, write: false,
    plugins: [{ name: 'benchmark-instrumentation', setup(builder) {
      builder.onResolve({ filter: /^benchmark-fixture$/ }, () => ({ path: fileURLToPath(new URL('fixture-worker.json', directory)) }));
      if (deliveryMode === 'hybrid') builder.onLoad({ filter: /[\\/]src[\\/]worker\.mjs$/ }, async args => {
        const source = await readFile(args.path, 'utf8');
        const original = 'await getSender(config).send(current.fcm_token, data, payload.expires_at)';
        if (source.split(original).length !== 2) throw new Error('benchmark_instrumentation_mismatch');
        return { contents: source.replace(original,
          'await env.DB.benchmarkSend(() => getSender(config).send(current.fcm_token, data, payload.expires_at), data.transport)'), loader: 'js' };
      });
    } }] });
  const artifact = built.outputFiles[0].contents;
  await writeFile(new URL('worker.js', directory), artifact);
  return { artifactBytes: artifact.length, sha256: createHash('sha256').update(artifact).digest('hex') };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const mode = process.argv.find(value => value.startsWith('--mode='))?.slice(7) ?? 'queued';
  if (!['queued', 'hybrid'].includes(mode)) throw new Error('invalid_delivery_mode');
  const directory = new URL(mode === 'hybrid' ? '../.wrangler/bench-hybrid-20261007/' : '../.wrangler/bench-100-20261007/', import.meta.url);
  const fixture = JSON.parse(await readFile(new URL('fixture-worker.json', directory), 'utf8'));
  if ((fixture.deliveryMode ?? 'queued') !== mode || Date.now() >= fixture.endsAt ||
    !/^https:\/\/nagisa-relay-bench-[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev$/.test(fixture.origin)) throw new Error('invalid_benchmark_scope');
  const manifest = JSON.parse(await readFile(new URL('manifest.json', directory), 'utf8'));
  const metadata = await bundleBenchmark(directory, mode);
  const history = [...(manifest.previousArtifacts ?? []), { sha256: manifest.sha256, rebuiltAt: new Date().toISOString() }];
  await writeFile(new URL('manifest.json', directory), JSON.stringify({ ...manifest, ...metadata, previousArtifacts: history }, null, 2));
  console.log(JSON.stringify({ rebuilt: true, deliveryMode: mode, ...metadata }));
}
