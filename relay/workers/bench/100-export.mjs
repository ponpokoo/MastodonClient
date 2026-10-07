// Export only anonymous results. Never copy benchmark fixture credentials or endpoints.
import { readFile, readdir, writeFile, mkdir } from 'node:fs/promises';
const mode = process.argv.find(value => value.startsWith('--mode='))?.slice(7) ?? 'queued';
if (!['queued', 'hybrid'].includes(mode)) throw new Error('invalid_delivery_mode');
const run = mode === 'hybrid' ? 'hybrid' : '100';
const directory = new URL(`../.wrangler/bench-${run}-20261007/`, import.meta.url);
const files = (await readdir(directory)).filter(name => /^result-cloud-[a-z]+-[0-9]+\.json$/.test(name)).sort();
const results = new Map();
const attempts = [];
for (const name of files) {
  const report = JSON.parse(await readFile(new URL(name, directory), 'utf8'));
  if (report.task === 'stats' || !report.finishedAt || report.failed) continue;
  const { task, artifactSha256, startedAt, finishedAt, phases, seeded, initialized, maintenance, stops, closed,
    measurementComplete, hybridChecks, allPushRequestsAccepted, allFetchRequestsSucceeded } = report;
  const { messages, pending, delivered, stored_text_bytes, registrations, summary } = report.final;
  const result = { task, artifactSha256, startedAt, finishedAt, phases, seeded, initialized, maintenance, stops, closed,
    measurementComplete, hybridChecks, allPushRequestsAccepted, allFetchRequestsSucceeded,
    final: { messages, pending, delivered, stored_text_bytes, registrations, summary } };
  results.set(task, result); attempts.push(result);
}
const manifest = JSON.parse(await readFile(new URL('manifest.json', directory), 'utf8'));
for (const attempt of attempts) if (!attempt.artifactSha256) {
  attempt.artifactSha256 = manifest.previousArtifacts?.[0]?.sha256 ?? manifest.sha256;
  attempt.artifactReference = 'inferred from initial artifact; early runner did not record the hash';
}
const output = { runId: manifest.runId, deliveryMode: mode, artifactSha256: manifest.sha256,
  scope: 'synthetic payloads and mock FCM; 200 subscriptions; no real Android or Mastodon API measurement',
  artifacts: manifest.previousArtifacts ?? [], results: [...results.values()], attempts };
const target = new URL(`results/20261007-${run}.json`, import.meta.url);
await mkdir(new URL('results/', import.meta.url), { recursive: true });
await writeFile(target, JSON.stringify(output, null, 2) + '\n');
console.log(JSON.stringify({ exported: true, tasks: output.results.map(result => result.task) }));
