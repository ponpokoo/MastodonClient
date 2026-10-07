// Dedicated synthetic benchmark. Never deploy this entry point to the live Relay.
import { createWorker } from '../src/worker.mjs';
import { D1Store } from '../src/store.mjs';
import { digest } from '../src/protocol.mjs';
import fixture from 'benchmark-fixture';
import initial from '../migrations/0001_initial.sql';
import bound from '../migrations/0002_bound_registrations.sql';
import { persistSample } from './sample.mjs';

const json = value => Response.json(value, { headers: { 'Cache-Control': 'no-store' } });
let mode = 'success';
const claimedTraces = new Map();
const app = createWorker({ limits: fixture.limits, deliveryMode: fixture.deliveryMode ?? 'queued', report() {}, fetcher: async (url, init) => {
  if (url === 'https://oauth2.googleapis.com/token') return json({ access_token: 'synthetic', expires_in: 3600 });
  if (url !== 'https://fcm.googleapis.com/v1/projects/test-project/messages:send') throw new Error('mock_destination');
  const message = JSON.parse(init.body).message;
  const trace = claimedTraces.get(message.data.messageId);
  if (trace) {
    trace.fcmCalls++;
    if (mode === 'success') {
      trace.fcmSuccess++;
      if (trace.kind === 'http' && Date.now() - trace.startedAt <= 60000) trace.within60++;
    } else trace.fcmRetry++;
  }
  return mode === 'outage' ? Response.json({}, { status: 503, headers: { 'Retry-After': '60' } }) :
    json({ name: 'projects/test-project/messages/synthetic' });
} });
const config = (DB, flags) => ({ DB, PUBLIC_ORIGIN: fixture.origin, RELAY_ENABLED: String(flags.relay),
  REGISTRATION_ENABLED: String(flags.registration), PUSH_ENABLED: String(flags.push), DELIVERY_ENABLED: String(flags.delivery),
  VAPID_PUBLIC_KEYS: '[]', FCM_PROJECT_ID: 'test-project',
  FCM_CLIENT_EMAIL: 'relay@test-project.iam.gserviceaccount.com', FCM_PRIVATE_KEY: fixture.rsaPem });
async function settings(DB) {
  const row = await DB.prepare('SELECT * FROM bench_control WHERE id=1').first();
  mode = row.mode;
  return { relay: !!row.relay, registration: !!row.registration, push: !!row.push, delivery: !!row.delivery };
}
function measured(db, kind, phase) {
  const trace = { id: crypto.randomUUID(), kind, phase, startedAt: Date.now(), statements: 0,
    rowsRead: 0, rowsWritten: 0, fcmCalls: 0, fcmSuccess: 0, fcmRetry: 0, within60: 0,
    inline: 0, syncRequired: 0, messageIds: [] };
  function record(result, sql) {
    trace.rowsRead += result.meta?.rows_read ?? 0;
    trace.rowsWritten += result.meta?.rows_written ?? 0;
    if (sql.startsWith('UPDATE messages SET lease_id')) {
      for (const row of result.results ?? []) {
        claimedTraces.set(row.id, trace); trace.messageIds.push(row.id);
      }
    }
    return result;
  }
  const statement = (raw, sql) => ({ raw,
    bind(...args) { return statement(raw.bind(...args), sql); },
    async first(column) {
      trace.statements++; const row = record(await raw.all(), sql).results[0];
      return row ? column ? row[column] : row : null;
    },
    async all() { trace.statements++; return record(await raw.all(), sql); },
    async run() { trace.statements++; return record(await raw.run(), sql); },
  });
  return { trace, DB: { prepare(sql) { return statement(db.prepare(sql), sql); },
    async benchmarkSend(send, transport) {
      const result = await send();
      // Mock OAuth always succeeds. Non-expired sends make exactly one mock FCM request.
      if (result.kind !== 'expired') {
        trace.fcmCalls++;
        if (transport === 'inline') trace.inline++; else if (transport === 'sync_required') trace.syncRequired++;
        if (result.kind === 'success') {
          trace.fcmSuccess++;
          if (Date.now() - trace.startedAt <= 60000) trace.within60++;
        } else if (result.kind === 'retry') trace.fcmRetry++;
      }
      return result;
    },
    async batch(items) {
      trace.statements += items.length;
      return (await db.batch(items.map(item => item.raw))).map(result => record(result, 'batch'));
    } } };
}
async function startSample(DB, trace) {
  await DB.prepare('INSERT INTO bench_samples(id,kind,phase,at,elapsed) VALUES(?,?,?,?,-1)')
    .bind(trace.id, trace.kind, trace.phase, trace.startedAt).run();
}
async function finishSample(DB, trace, status, elapsed = Date.now() - trace.startedAt) {
  for (const id of trace.messageIds) claimedTraces.delete(id);
  await persistSample(DB, trace, status, elapsed);
}
async function cron(DB) {
  const flags = await settings(DB), traced = measured(DB, 'cron', 'scheduled');
  await startSample(DB, traced.trace);
  try {
    await app.scheduled({}, config(traced.DB, flags), {});
    await finishSample(DB, traced.trace, 200);
  } catch {
    await finishSample(DB, traced.trace, 500, -2);
    throw new Error('benchmark_cron_failed');
  }
}
async function stats(DB, summary) {
  const state = await DB.prepare(`SELECT count(*) AS messages,coalesce(sum(delivered=0),0) AS pending,
    coalesce(sum(delivered=1),0) AS delivered,coalesce(sum(length(body)+length(headers)),0) AS stored_text_bytes,
    min(CASE WHEN delivered=0 THEN next_attempt END) AS oldest_due FROM messages`).first();
  const registrations = await DB.prepare('SELECT count(*) AS total,coalesce(sum(deleted=0),0) AS active FROM registrations').first();
  const samples = (await DB.prepare(`SELECT kind,phase,at,elapsed,status,statements,rows_read,rows_written,fcm_calls,
    fcm_success,fcm_retry,within60,inline_count,sync_count FROM bench_samples ORDER BY at DESC LIMIT 20`).all()).results;
  const result = { ...state, registrations, samples, serverTime: Date.now(), expiresAt: fixture.endsAt };
  if (summary) {
    result.summary = (await DB.prepare(`SELECT kind,phase,count(*) AS samples,coalesce(sum(elapsed<0),0) AS incomplete,
      sum(statements) AS statements,sum(rows_read) AS rows_read,sum(rows_written) AS rows_written,
      sum(fcm_calls) AS fcm_calls,sum(fcm_success) AS fcm_success,sum(fcm_retry) AS fcm_retry,sum(within60) AS within60,
      sum(inline_count) AS inline_count,sum(sync_count) AS sync_count
      FROM bench_samples GROUP BY kind,phase`).all()).results;
  }
  return result;
}
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (Date.now() > fixture.endsAt || url.origin !== fixture.origin ||
      !url.hostname.startsWith('nagisa-relay-bench-') || request.headers.get('X-Benchmark-Token') !== fixture.controlToken)
      return new Response(null, { status: 404 });
    const DB = env.DB;
    if (!DB) return json({ error: 'benchmark_db_missing' });
    if (url.pathname === '/__bench/init' && request.method === 'POST') {
      const exists = await DB.prepare("SELECT count(*) AS n FROM sqlite_master WHERE type='table' AND name='registrations'").first('n');
      if (exists) return new Response(null, { status: 409 });
      const sql = `${initial}\n${bound}`.replace(/--[^\n]*/g, '').split(';').filter(item => item.trim());
      await DB.batch(sql.map(item => DB.prepare(item)));
      await DB.batch([
        DB.prepare('CREATE TABLE bench_control(id INTEGER PRIMARY KEY,mode TEXT NOT NULL,relay INTEGER,registration INTEGER,push INTEGER,delivery INTEGER)'),
        DB.prepare("INSERT INTO bench_control VALUES(1,'success',1,1,1,1)"),
        DB.prepare(`CREATE TABLE bench_samples(id TEXT PRIMARY KEY,kind TEXT,phase TEXT,at INTEGER,elapsed INTEGER,status INTEGER,
          statements INTEGER DEFAULT 0,rows_read INTEGER DEFAULT 0,rows_written INTEGER DEFAULT 0,fcm_calls INTEGER DEFAULT 0,
          fcm_success INTEGER DEFAULT 0,fcm_retry INTEGER DEFAULT 0,within60 INTEGER DEFAULT 0,
          inline_count INTEGER DEFAULT 0,sync_count INTEGER DEFAULT 0)`),
        DB.prepare('CREATE INDEX bench_samples_at ON bench_samples(at)'),
      ]);
      return json({ initialized: true, runId: fixture.runId, limits: fixture.limits });
    }
    if (url.pathname === '/__bench/seed' && request.method === 'POST') {
      const index = Number((await request.json()).index);
      if (!Number.isInteger(index) || index < 0 || index >= fixture.owners.length) return new Response(null, { status: 400 });
      const owner = fixture.owners[index], token = `synthetic-device-${index}`, publicKey = fixture.keys[owner.keyIndex].publicKey;
      const store = new D1Store(DB, fixture.limits);
      await store.registerBound(owner.id, owner.managementHash, token, publicKey, 1,
        await digest(JSON.stringify([token, publicKey, 1])), Date.now(), true);
      return json({ seeded: true });
    }
    if (url.pathname === '/__bench/endpoints' && request.method === 'GET') {
      return json((await DB.prepare('SELECT id,delivery_id FROM registrations WHERE deleted=0').all()).results);
    }
    if (url.pathname === '/__bench/mode' && request.method === 'POST') {
      const next = (await request.json()).mode;
      if (!['success', 'outage'].includes(next)) return new Response(null, { status: 400 });
      await DB.prepare('UPDATE bench_control SET mode=? WHERE id=1').bind(next).run();
      return json({ mode: next });
    }
    if (url.pathname === '/__bench/flags' && request.method === 'POST') {
      const flags = await request.json();
      if (!['relay','registration','push','delivery'].every(key => typeof flags[key] === 'boolean'))
        return new Response(null, { status: 400 });
      await DB.prepare('UPDATE bench_control SET relay=?,registration=?,push=?,delivery=? WHERE id=1')
        .bind(+flags.relay, +flags.registration, +flags.push, +flags.delivery).run();
      return json({ configured: true });
    }
    if (url.pathname === '/__bench/cron' && request.method === 'POST') { await cron(DB); return json({ ran: true }); }
    if (url.pathname === '/__bench/stats' && request.method === 'POST') return json(await stats(DB, !!(await request.json()).summary));
    const flags = await settings(DB);
    const phase = request.headers.get('X-Benchmark-Phase') ?? 'unlabelled';
    if (!/^[a-z0-9-]{1,64}$/.test(phase)) return new Response(null, { status: 400 });
    const traced = measured(DB, 'http', phase), work = [];
    await startSample(DB, traced.trace);
    let response;
    try {
      response = await app.fetch(request, config(traced.DB, flags), { waitUntil(task) { work.push(task); ctx.waitUntil(task); } });
    } catch {
      await finishSample(DB, traced.trace, 500, -2); throw new Error('benchmark_http_failed');
    }
    if (fixture.deliveryMode === 'hybrid') {
      // Hybrid has no background relay delivery. Persist measurement before finishing the request.
      await finishSample(DB, traced.trace, response.status);
    } else ctx.waitUntil(Promise.allSettled(work).then(() => finishSample(DB, traced.trace, response.status)));
    return response;
  },
  async scheduled(_controller, env) {
    if (env.DB && Date.now() <= fixture.endsAt) await cron(env.DB);
  },
};
