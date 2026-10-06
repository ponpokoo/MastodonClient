// Test-only entry point. The production entry point never imports this module.
import { createWorker } from '../src/worker.mjs';
import { D1Store } from '../src/store.mjs';
import { digest } from '../src/protocol.mjs';
import fixture from '../.wrangler/bench/fixture-worker.json';
import initial from '../migrations/0001_initial.sql';
import bound from '../migrations/0002_bound_registrations.sql';

const json = value => Response.json(value, { headers: { 'Cache-Control': 'no-store' } });
const config = DB => ({ DB, PUBLIC_ORIGIN: fixture.origin, RELAY_ENABLED: 'true',
  REGISTRATION_ENABLED: 'true', PUSH_ENABLED: 'true', DELIVERY_ENABLED: 'true',
  VAPID_PUBLIC_KEYS: '[]', FCM_PROJECT_ID: 'test-project',
  FCM_CLIENT_EMAIL: 'relay@test-project.iam.gserviceaccount.com', FCM_PRIVATE_KEY: fixture.rsaPem });
let mode = 'success';
const app = createWorker({ report() {}, fetcher: async url => {
  // No real FCM/OAuth request is possible, including on an unexpected URL.
  if (url === 'https://oauth2.googleapis.com/token') return json({ access_token: 'synthetic', expires_in: 3600 });
  if (url !== 'https://fcm.googleapis.com/v1/projects/test-project/messages:send') throw new Error('mock_destination');
  return mode === 'outage' ? Response.json({}, { status: 503, headers: { 'Retry-After': '60' } }) :
    json({ name: 'projects/test-project/messages/synthetic' });
} });

function measured(db) {
  const totals = { statements: 0, rowsRead: 0, rowsWritten: 0 };
  const record = result => {
    totals.rowsRead += result.meta?.rows_read ?? 0;
    totals.rowsWritten += result.meta?.rows_written ?? 0;
    return result;
  };
  const statement = raw => ({ raw,
    bind(...args) { return statement(raw.bind(...args)); },
    async first(column) {
      totals.statements++; const row = record(await raw.all()).results[0];
      return row ? column ? row[column] : row : null;
    },
    async all() { totals.statements++; return record(await raw.all()); },
    async run() { totals.statements++; return record(await raw.run()); },
  });
  return { totals, DB: { prepare(sql) { return statement(db.prepare(sql)); },
    async batch(statements) {
      totals.statements += statements.length;
      return (await db.batch(statements.map(s => s.raw))).map(record);
    } } };
}
async function settings(DB) {
  mode = (await DB.prepare('SELECT mode FROM bench_control WHERE id=1').first())?.mode ?? 'success';
}
async function stats(DB) {
  return {
    messages: await DB.prepare('SELECT count(*) AS n FROM messages').first('n'),
    pending: await DB.prepare('SELECT count(*) AS n FROM messages WHERE delivered=0').first('n'),
    delivered: await DB.prepare('SELECT count(*) AS n FROM messages WHERE delivered=1').first('n'),
    registrations: await DB.prepare('SELECT count(*) AS n FROM registrations WHERE deleted=0').first('n'),
    samples: (await DB.prepare('SELECT * FROM bench_samples ORDER BY at').all()).results,
  };
}
async function cron(DB) {
  await settings(DB);
  const trace = measured(DB), start = Date.now();
  // Persist entry before running the job: a CPU termination cannot reach the final update.
  // elapsed=-1 means incomplete; -2 means a caught job failure. Neither is a successful sample.
  await DB.prepare('INSERT INTO bench_samples(at,elapsed,statements,rows_read,rows_written) VALUES(?,-1,0,0,0)')
    .bind(start).run();
  let elapsed;
  try {
    await app.scheduled({}, config(trace.DB), {});
    elapsed = Date.now() - start;
  } catch {
    await DB.prepare('UPDATE bench_samples SET elapsed=-2 WHERE at=?').bind(start).run();
    throw new Error('benchmark_cron_failed');
  }
  // Control/sample queries are outside the production counters, but still billed by Cloudflare.
  await DB.prepare('UPDATE bench_samples SET elapsed=?,statements=?,rows_read=?,rows_written=? WHERE at=?')
    .bind(elapsed, trace.totals.statements, trace.totals.rowsRead, trace.totals.rowsWritten, start).run();
  return trace.totals;
}
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    // Prevent this artifact from being used at the production hostname.
    if (Date.now() > fixture.endsAt || url.origin !== fixture.origin || !url.hostname.startsWith('nagisa-relay-bench-') ||
      request.headers.get('X-Benchmark-Token') !== fixture.controlToken) return new Response(null, { status: 404 });
    const DB = env.DB;
    if (url.pathname === '/__bench/init' && request.method === 'POST') {
      const exists = await DB.prepare("SELECT count(*) AS n FROM sqlite_master WHERE type='table' AND name='registrations'").first('n');
      if (exists) return new Response(null, { status: 409 });
      const sql = `${initial}\n${bound}`.replace(/--[^\n]*/g, '').split(';').filter(s => s.trim());
      await DB.batch(sql.map(s => DB.prepare(s)));
      await DB.batch([
        DB.prepare('CREATE TABLE bench_control(id INTEGER PRIMARY KEY, mode TEXT NOT NULL)'),
        DB.prepare("INSERT INTO bench_control VALUES(1,'success')"),
        DB.prepare('CREATE TABLE bench_samples(at INTEGER,elapsed INTEGER,statements INTEGER,rows_read INTEGER,rows_written INTEGER)'),
      ]);
      return json({ initialized: true });
    }
    if (url.pathname === '/__bench/seed' && request.method === 'POST') {
      // Seed bypasses the HTTP enrollment rate limit; it is not an enrollment measurement.
      const index = Number((await request.json()).index);
      if (!Number.isInteger(index) || index < 0 || index >= fixture.owners.length) return new Response(null, { status: 400 });
      const owner = fixture.owners[index], token = `synthetic-device-${index}`;
      const store = new D1Store(DB);
      await store.registerBound(owner.id, owner.managementHash, token,
        fixture.publicKey, 1, await digest(JSON.stringify([token, fixture.publicKey, 1])), Date.now(), true);
      return json({ seeded: true });
    }
    if (url.pathname === '/__bench/endpoints' && request.method === 'GET') {
      // Returned only to the private driver, never printed or saved in the public report.
      return json((await DB.prepare('SELECT id,delivery_id FROM registrations WHERE deleted=0').all()).results);
    }
    if (url.pathname === '/__bench/mode' && request.method === 'POST') {
      const next = (await request.json()).mode;
      if (!['success', 'outage'].includes(next)) return new Response(null, { status: 400 });
      await DB.prepare('UPDATE bench_control SET mode=? WHERE id=1').bind(next).run();
      return json({ mode: next });
    }
    if (url.pathname === '/__bench/cron' && request.method === 'POST') return json(await cron(DB));
    if (url.pathname === '/__bench/stats' && request.method === 'GET') return json(await stats(DB));
    await settings(DB);
    const trace = measured(DB);
    const response = await app.fetch(request, config(trace.DB), ctx);
    const headers = new Headers(response.headers);
    headers.set('X-Benchmark-D1', JSON.stringify(trace.totals));
    return new Response(response.body, { status: response.status, headers });
  },
  async scheduled(controller, env) {
    if (env.DB && Date.now() <= fixture.endsAt) await cron(env.DB);
  },
};
