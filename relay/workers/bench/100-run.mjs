import { readFile, writeFile, rename } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

const option = (name, fallback) => process.argv.find(v => v.startsWith(`--${name}=`))?.slice(name.length + 3) ?? fallback;
const local = process.argv.includes('--local'), smoke = process.argv.includes('--smoke');
const task = option('task', smoke ? 'smoke' : 'normal');
if (!['init', 'smoke', 'normal', 'inline', 'fetch', 'stats', 'stops', 'outage', 'observe', 'close', 'maintenance'].includes(task)) throw new Error('invalid_task');
const deliveryMode = option('mode', 'queued');
if (!['queued', 'hybrid'].includes(deliveryMode)) throw new Error('invalid_delivery_mode');
const hybrid = deliveryMode === 'hybrid';
const directory = new URL(hybrid ? '../.wrangler/bench-hybrid-20261007/' : '../.wrangler/bench-100-20261007/', import.meta.url);
const fixture = JSON.parse(await readFile(new URL('fixture.json', directory), 'utf8'));
const manifest = JSON.parse(await readFile(new URL('manifest.json', directory), 'utf8'));
if ((fixture.deliveryMode ?? 'queued') !== deliveryMode) throw new Error('invalid_delivery_mode');
if (!/^https:\/\/nagisa-relay-bench-[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev$/.test(fixture.origin) || Date.now() >= fixture.endsAt)
  throw new Error('invalid_or_expired_benchmark');
const output = new URL(`result-${local ? 'local' : 'cloud'}-${task}-${Date.now()}.json`, directory);
const report = { runId: fixture.runId, deliveryMode, artifactSha256: manifest.sha256, task, pid: process.pid, startedAt: new Date().toISOString(),
  environment: local ? 'local-workerd' : 'cloudflare-free', scope: 'synthetic FCM, 200 subscriptions, no real devices',
  expiresAt: new Date(fixture.endsAt).toISOString(), phases: [], observations: [] };
let saving = Promise.resolve();
const save = () => {
  const snapshot = JSON.stringify(report, null, 2);
  saving = saving.then(async () => { const temporary = new URL(output.href + '.tmp');
    await writeFile(temporary, snapshot); await rename(temporary, output); });
  return saving;
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, Math.max(0, ms)));
let mf;
const keys = await Promise.all(fixture.keys.map(pair => crypto.subtle.importKey('jwk', pair.privateJwk,
  { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])));
const ownerById = new Map(fixture.owners.map(owner => [owner.id, owner]));
async function request(path, method = 'GET', body, headers = {}) {
  if (Date.now() >= fixture.endsAt) throw new Error('benchmark_expired');
  const url = new URL(path, fixture.origin);
  if (url.origin !== fixture.origin) throw new Error('wrong_origin');
  const init = { method, body, headers: { 'X-Benchmark-Token': fixture.controlToken, ...headers },
    redirect: 'manual', signal: AbortSignal.timeout(30000) };
  return local ? mf.dispatchFetch(url.href, init) : fetch(url, init);
}
async function control(name, body) {
  const response = await request(`/__bench/${name}`, body === undefined ? 'GET' : 'POST',
    body === undefined ? undefined : JSON.stringify(body), { 'Content-Type': 'application/json' });
  if (!response.ok) throw new Error(`control_${name}_${response.status}`);
  const value = await response.json();
  if (value.error) throw new Error('benchmark_not_configured');
  return value;
}
async function authorization(index) {
  const b64 = v => Buffer.from(JSON.stringify(v)).toString('base64url');
  const data = `${b64({ alg: 'ES256', typ: 'JWT' })}.${b64({ aud: fixture.origin,
    exp: Math.floor(Date.now() / 1000) + 3600, sub: 'mailto:benchmark@example.test' })}`;
  const signature = Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, keys[index], Buffer.from(data))).toString('base64url');
  return `vapid t=${data}.${signature}, k=${fixture.keys[index].publicKey}`;
}
const percentiles = values => {
  const sorted = [...values].sort((a, b) => a - b), p = q => sorted[Math.max(0, Math.ceil(sorted.length*q)-1)] ?? 0;
  return { count: values.length, p50: p(.5), p95: p(.95), p99: p(.99), max: p(1) };
};
async function phase(name, count, endpoints, { pace = 120, mix = true, bytes = 4096, fetchBody = false, ttl = 86400 } = {}) {
  const result = { name, requested: count, paceMs: pace, mix: mix ? '1KiB90-4KiB9-64KiB1' : bytes,
    statuses: {}, errorCodes: {}, transportErrors: 0, fetchStatuses: {} }, durations = [], inflight = new Set();
  const headers = await Promise.all(keys.map((_, index) => authorization(index))), begin = performance.now();
  const before = await control('stats', { summary: true });
  const summaryBefore = before.summary;
  async function send(index) {
    const endpoint = endpoints[index % endpoints.length], owner = ownerById.get(endpoint.id), started = performance.now();
    try {
      const size = mix ? index % 100 < 90 ? 1024 : index % 100 < 99 ? 4096 : 65536 : bytes;
      const response = await request(`/push/${endpoint.delivery_id}`, 'POST', new Uint8Array(size),
        { Authorization: headers[owner.keyIndex], TTL: String(ttl), 'Content-Encoding': 'aes128gcm', 'X-Benchmark-Phase': name });
      result.statuses[response.status] = (result.statuses[response.status] ?? 0) + 1;
      const receipt = response.headers.get('Location')?.match(/\/receipts\/([A-Za-z0-9_-]{43})$/)?.[1];
      if (response.status === 201) await response.arrayBuffer();
      else {
        const body = await response.text();
        let code = 'unclassified_response';
        try { const value = JSON.parse(body).error; if (/^[a-z_]{1,80}$/.test(value)) code = value; } catch {}
        result.errorCodes[code] = (result.errorCodes[code] ?? 0) + 1;
      }
      if (fetchBody && response.status === 201 && receipt) {
        const fetched = await request(`/v1/registrations/${owner.id}/messages/${receipt}`, 'GET', undefined,
          { Authorization: `Bearer ${owner.management}`, 'X-Benchmark-Phase': `${name}-fetch` });
        result.fetchStatuses[fetched.status] = (result.fetchStatuses[fetched.status] ?? 0) + 1;
        await fetched.arrayBuffer();
      }
    } catch { result.transportErrors++; }
    durations.push(performance.now() - started);
  }
  for (let index = 0; index < count; index++) {
    if (inflight.size >= 16) await Promise.race(inflight);
    await sleep(begin + index * pace - performance.now());
    const work = send(index); inflight.add(work); work.finally(() => inflight.delete(work));
  }
  await Promise.all(inflight);
  result.elapsedMs = performance.now() - begin; result.latencyMs = percentiles(durations);
  // waitUntil completes after the HTTP response. Wait for the phase's own sample completion.
  for (let attempt = 0; attempt < 30; attempt++) {
    const current = await control('stats', { summary: true });
    const sample = current.summary.filter(row => row.phase === name || row.phase === `${name}-fetch`);
    const prior = summaryBefore.filter(row => row.phase === name || row.phase === `${name}-fetch`);
    result.d1 = sample.map(row => { const old = prior.find(item => item.kind === row.kind && item.phase === row.phase) ?? {};
      return Object.fromEntries(Object.entries(row).map(([key, value]) => [key, typeof value === 'number' ? value - (old[key] ?? 0) : value])); });
    result.pendingAfter = current.pending; result.messagesAfter = current.messages;
    const expected = Object.values(result.statuses).reduce((a, b) => a + b, 0);
    if (result.d1.every(row => row.incomplete === 0) && result.d1.some(row => row.phase === name && row.samples === expected)) break;
    await sleep(1000);
  }
  report.phases.push(result); await save(); console.log(JSON.stringify(result));
}
async function observe(minutes) {
  const begin = Date.now();
  while (Date.now() - begin < minutes * 60000) {
    const current = await control('stats', { summary: false });
    report.observations.push(current); await save();
    console.log(JSON.stringify({ pending: current.pending, delivered: current.delivered, messages: current.messages,
      completedCron: current.samples.filter(s => s.kind === 'cron' && s.elapsed >= 0).length }));
    if (!current.pending) break;
    await sleep(60000);
  }
}
try {
  await save();
  if (local) mf = new Miniflare(convertV4MiniflareOptions({ modules: true,
    script: await readFile(new URL('worker.js', directory), 'utf8'), compatibilityDate: '2026-09-21', d1Databases: ['DB'] }));
  if (task === 'init' || local) {
    report.initialized = await control('init', {});
    for (let index = 0; index < (smoke ? 2 : 200); index++) await control('seed', { index });
    report.seeded = (await control('stats', {})).registrations;
    console.log(JSON.stringify({ initialized: true, ...report.seeded }));
  }
  if (task === 'close') {
    await control('flags', { relay: false, registration: false, push: false, delivery: false });
    report.closed = true;
  }
  if (task === 'maintenance') report.maintenance = await control('cron', {});
  if (!['init', 'stats', 'close', 'maintenance'].includes(task)) {
    const endpoints = await control('endpoints');
    if (!endpoints.length) throw new Error('empty_benchmark');
    if (task === 'smoke') {
      await phase('smoke-inline', 5, endpoints, { mix: false, bytes: 1024 });
      await phase(hybrid ? 'smoke-sync' : 'smoke-fetch', 5, endpoints, { mix: false, bytes: 4096, fetchBody: !hybrid });
    }
    if (task === 'normal') {
      await phase('normal-mix', 1000, endpoints, { pace: 600 });
      await phase('burst-mix', 500, endpoints);
    }
    if (task === 'fetch' || task === 'inline') {
      const count = Number(option('count', '1000'));
      if (!Number.isSafeInteger(count) || count < 1 || count > 6500) throw new Error('invalid_count');
      const pace = Number(option('pace', '600'));
      if (!Number.isFinite(pace) || pace < 100 || pace > 60000) throw new Error('invalid_pace');
      await phase(task === 'inline' ? 'all-inline-1k' : hybrid ? 'all-sync-4k' : 'all-fetch-4k', count, endpoints,
        { pace, mix: false, bytes: task === 'inline' ? 1024 : 4096, fetchBody: task === 'fetch' && !hybrid });
    }
    if (task === 'observe') await observe(60);
    if (task === 'outage') {
      await control('mode', { mode: 'outage' }); report.outageStartedAt = new Date().toISOString(); await save();
      if (hybrid) {
        try {
          await phase('hybrid-outage', 10, endpoints, { mix: false });
          report.backlogAtRecovery = await control('stats', { summary: true });
        } finally { await control('mode', { mode: 'success' }); }
        report.recoveryStartedAt = new Date().toISOString();
        await phase('hybrid-after-recovery', 10, endpoints, { mix: false });
      } else {
      await Promise.all([
        phase('outage-continuous', 500, endpoints, { pace: 14400, mix: false }),
        phase('outage-burst', 500, endpoints, { mix: false }),
      ]);
      // Complete the full two hours before restoring. No timestamp/backoff manipulation.
      await sleep(Date.parse(report.outageStartedAt) + 7200000 - Date.now());
      report.backlogAtRecovery = await control('stats', { summary: true });
      await control('mode', { mode: 'success' }); report.recoveryStartedAt = new Date().toISOString(); await save();
      await Promise.all([phase('recovery-continuous', 250, endpoints, { pace: 14400, mix: false }), observe(60)]);
      }
    }
    if (task === 'stops') {
      report.stops = [];
      for (const disabled of ['registration', 'push', 'delivery', 'relay']) {
        const flags = { relay: true, registration: true, push: true, delivery: true, [disabled]: false };
        await control('flags', flags);
        const owner = fixture.owners[0];
        const health = await request('/health');
        const response = await request(`/push/${endpoints[0].delivery_id}`, 'POST', new Uint8Array(1024),
          { Authorization: await authorization(ownerById.get(endpoints[0].id).keyIndex), TTL: '3600',
            'Content-Encoding': 'aes128gcm', 'X-Benchmark-Phase': `stop-${disabled}` });
        await response.arrayBuffer();
        const updated = await request(`/v2/registrations/${owner.id}`, 'PUT', JSON.stringify({
          fcmToken: `synthetic-device-${owner.keyIndex}`, serverKey: fixture.keys[owner.keyIndex].publicKey, revision: 1 }),
          { Authorization: `Bearer ${owner.management}`, 'Content-Type': 'application/json', 'X-Benchmark-Phase': `stop-${disabled}-update` });
        await updated.arrayBuffer();
        report.stops.push({ disabled, health: health.status, push: response.status, update: updated.status }); await save();
      }
      await control('flags', { relay: true, registration: true, push: true, delivery: true });
    }
  }
  report.final = await control('stats', { summary: true });
  report.allPushRequestsAccepted = report.phases.every(phase => !phase.transportErrors && phase.statuses[201] === phase.requested);
  report.measurementComplete = report.phases.every(phase => phase.d1.some(row => row.phase === phase.name &&
    row.samples === Object.values(phase.statuses).reduce((a, b) => a + b, 0)) && phase.d1.every(row => row.incomplete === 0));
  if (hybrid) report.hybridChecks = {
    noStoredBodies: report.final.messages === 0 && report.final.stored_text_bytes === 0 && report.final.pending === 0,
    oneSendPerAcceptedPush: report.phases.filter(phase => !phase.name.includes('outage')).every(phase =>
      phase.d1.find(row => row.phase === phase.name)?.fcm_success === phase.statuses[201]),
    failedPushesReturn503: task === 'outage' ? report.phases.find(phase => phase.name === 'hybrid-outage')?.statuses[503] === 10 : null,
  };
  const fetchedPhases = report.phases.filter(phase => Object.keys(phase.fetchStatuses).length);
  report.allFetchRequestsSucceeded = fetchedPhases.length ? fetchedPhases.every(phase =>
    phase.fetchStatuses[200] === phase.statuses[201] && Object.keys(phase.fetchStatuses).length === 1) : null;
  report.finishedAt = new Date().toISOString(); await save();
  console.log(JSON.stringify({ finished: true, task, pending: report.final.pending, delivered: report.final.delivered,
    messages: report.final.messages, ...(task === 'stats' ? { summary: report.final.summary } : {}) }));
} catch (error) {
  report.failed = true; report.failure = /^(control_[a-z]+_[0-9]+|benchmark_[a-z_]+|invalid_[a-z_]+|empty_benchmark)$/.test(error.message) ? error.message : 'benchmark_failed';
  await save(); console.error(report.failure); process.exitCode = 1;
} finally { await mf?.dispose(); }
