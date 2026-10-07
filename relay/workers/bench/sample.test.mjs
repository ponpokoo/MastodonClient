import test from 'node:test';
import assert from 'node:assert/strict';
import { persistSample } from './sample.mjs';
const trace = { id: 'synthetic', statements: 4, rowsRead: 5, rowsWritten: 3,
  fcmCalls: 1, fcmSuccess: 1, fcmRetry: 0, within60: 1, inline: 1, syncRequired: 0 };
test('sample retry writes identical counters even when a committed response was lost', async () => {
  const values = []; let attempts = 0;
  const DB = { prepare(sql) {
    assert.match(sql, /^UPDATE bench_samples SET/);
    return { bind(...args) { values.push(args); return { async run() { if (++attempts === 1) throw Error('synthetic'); } }; } };
  } };
  await persistSample(DB, trace, 201, 50);
  assert.equal(attempts, 2); assert.deepEqual(values[0], values[1]); assert.equal(values[1][6], 1);
});
test('record failure is bounded and reports a fixed error without private details', async () => {
  let attempts = 0;
  const DB = { prepare() { return { bind() { return { async run() { attempts++; throw Error('private failure'); } }; } }; } };
  await assert.rejects(persistSample(DB, trace, 201, 50), { message: 'benchmark_record_failed' });
  assert.equal(attempts, 3);
});
