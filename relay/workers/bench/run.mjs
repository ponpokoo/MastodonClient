import { readFile, writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

const local = process.argv.includes('--local'), smoke = process.argv.includes('--smoke');
const liveOnly = process.argv.includes('--live-only');
const fixture = JSON.parse(await readFile(new URL('../.wrangler/bench/fixture.json', import.meta.url), 'utf8'));
const key = await crypto.subtle.importKey('jwk', fixture.privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
let mf;
const report = { startedAt: new Date().toISOString(), environment: local ? 'local-workerd' : 'cloudflare-free',
  scope: 'synthetic FCM, no real devices', phases: [], remaining: null };
const output = new URL(`../.wrangler/bench/result-${liveOnly ? 'live' : local ? 'local' : 'cloud'}.json`, import.meta.url);
const save = () => writeFile(output, JSON.stringify(report, null, 2));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(path, method = 'GET', body, headers = {}) {
  const options = { method, headers: { 'X-Benchmark-Token': fixture.controlToken, ...headers }, body,
    redirect: 'manual', signal: AbortSignal.timeout(30000) };
  return local ? mf.dispatchFetch(new URL(path, fixture.origin).href, options) : fetch(new URL(path, fixture.origin), options);
}
async function control(path, body) {
  const response = await request(`/__bench/${path}`, body === undefined ? 'GET' : 'POST',
    body === undefined ? undefined : JSON.stringify(body), { 'Content-Type': 'application/json' });
  if (!response.ok) throw new Error(`benchmark_control_${path}_${response.status}`);
  return response.json();
}
async function authorization() {
  const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const payload = `${b64({ alg: 'ES256', typ: 'JWT' })}.${b64({ aud: fixture.origin,
    exp: Math.floor(Date.now() / 1000) + 3600, sub: 'mailto:benchmark@example.test' })}`;
  const signature = Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, Buffer.from(payload))).toString('base64url');
  return `vapid t=${payload}.${signature}, k=${fixture.publicKey}`;
}
function summarize(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const p = q => sorted[Math.max(0, Math.ceil(sorted.length * q) - 1)] ?? 0;
  return { count: values.length, p50: p(.5), p95: p(.95), p99: p(.99), max: p(1) };
}
async function phase(name, count, endpoints, { pace = 0, large = false } = {}) {
  const durations = [], statuses = {}, d1 = { statements: 0, rowsRead: 0, rowsWritten: 0 };
  const header = await authorization(), begin = performance.now();
  let transportErrors = 0;
  for (let index = 0; index < count; index++) {
    if (pace) await sleep(Math.max(0, begin + index * pace - performance.now()));
    const started = performance.now();
    try {
      const response = await request(`/push/${endpoints[index % endpoints.length].delivery_id}`, 'POST',
        new Uint8Array(large ? 4000 : 256), { Authorization: header, TTL: '86400', 'Content-Encoding': 'aes128gcm' });
      statuses[response.status] = (statuses[response.status] ?? 0) + 1;
      const meta = JSON.parse(response.headers.get('X-Benchmark-D1') ?? '{}');
      for (const field of Object.keys(d1)) d1[field] += meta[field] ?? 0;
      // Consume the body without logging token-bearing headers or endpoint URLs.
      await response.arrayBuffer();
    } catch { transportErrors++; }
    durations.push(performance.now() - started);
  }
  const result = { name, requested: count, statuses, transportErrors,
    elapsedMs: performance.now() - begin, latencyMs: summarize(durations), d1,
    pacingMs: pace, ciphertextBytes: large ? 4000 : 256 };
  report.phases.push(result); await save(); console.log(JSON.stringify(result));
}
try {
  if (liveOnly) {
    if (local) throw new Error('live_requires_cloud');
    const endpoints = await control('endpoints');
    let minute = 0;
    while (Date.now() < fixture.endsAt) {
      const checkpoint = JSON.parse(await readFile(new URL('../.wrangler/bench/result-cloud.json', import.meta.url), 'utf8'));
      if (checkpoint.finishedAt || checkpoint.failed) break;
      if (!checkpoint.recoveryStartedAt) { await sleep(10000); continue; }
      await phase(`recovery-new-arrivals-${++minute}`, 3, endpoints);
      await sleep(60000);
    }
    report.finishedAt = new Date().toISOString(); await save();
  } else {
  if (local) mf = new Miniflare(convertV4MiniflareOptions({ modules: true,
    script: await readFile(new URL('../.wrangler/bench/worker.mjs', import.meta.url), 'utf8'),
    compatibilityDate: '2026-09-21', d1Databases: ['DB'] }));
  await control('init', {});
  for (let index = 0; index < (smoke ? 2 : 120); index++) await control('seed', { index });
  const endpoints = await control('endpoints');
  await phase('inline-normal', smoke ? 5 : 100, endpoints);
  await phase('inline-burst', smoke ? 5 : 300, endpoints, { pace: local ? 0 : 200 });
  await control('mode', { mode: 'outage' });
  await phase('outage-inline', smoke ? 5 : 300, endpoints);
  await phase('outage-fetch', smoke ? 5 : 300, endpoints, { large: true });
  const before = await control('stats');
  report.backlogAtRecovery = before.pending;
  report.recoveryStartedAt = new Date().toISOString();
  await control('mode', { mode: 'success' });
  console.log(JSON.stringify({ recoveryStarted: true, backlog: before.pending,
    recoveryMode: local ? 'manual Cron invocations, accelerated interval' : 'real every-minute Cron' }));
  // Backoff is at least 60s with jitter. Do not alter persisted timestamps to pretend recovery is immediate.
  await sleep(80000);
  const deadline = Date.parse(report.recoveryStartedAt) + (local ? 10 : 60) * 60000;
  while (Date.now() < deadline) {
    if (local) await control('cron', {});
    const current = await control('stats');
    report.remaining = current.pending; report.deliveredFetch = current.delivered;
    report.cronSamples = current.samples.filter(sample => sample.elapsed >= 0);
    report.incompleteCronAttempts = current.samples.filter(sample => sample.elapsed < 0); await save();
    console.log(JSON.stringify({ pending: current.pending, deliveredFetch: current.delivered,
      cronRuns: report.cronSamples.length, incompleteCronAttempts: report.incompleteCronAttempts.length }));
    if (!current.pending) break;
    await sleep(local ? 10 : 60000);
  }
  report.finishedAt = new Date().toISOString(); await save();
  if (report.remaining || report.phases.some(p => p.transportErrors || p.statuses[201] !== p.requested)) process.exitCode = 1;
  }
} catch (error) {
  // Never print transport error objects: they may contain request URLs or headers.
  report.failed = true; await save(); console.error('benchmark_failed'); process.exitCode = 1;
} finally { await mf?.dispose(); }
