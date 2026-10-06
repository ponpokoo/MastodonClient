// Resume observation of an existing benchmark without initialization or new Push traffic.
import { readFile, writeFile } from 'node:fs/promises';

const directory = new URL('../.wrangler/bench/', import.meta.url);
const fixture = JSON.parse(await readFile(new URL('fixture.json', directory), 'utf8'));
const original = JSON.parse(await readFile(new URL('result-cloud.json', directory), 'utf8'));
const manual = new Set(process.argv.slice(2).map(value => {
  if (!/^--manual-sample=\d+$/.test(value)) throw new Error('invalid_observer_argument');
  return Number(value.slice('--manual-sample='.length));
}));
const report = { startedAt: new Date().toISOString(), recoveryStartedAt: original.recoveryStartedAt,
  scope: 'synthetic FCM, automatic Cron observed separately from manual diagnostics',
  manualSampleTimes: [...manual], observations: [] };
const output = new URL('result-cron-investigation.json', directory);
const save = () => writeFile(output, JSON.stringify(report, null, 2));
try {
  while (Date.now() < fixture.endsAt) {
    const response = await fetch(new URL('/__bench/stats', fixture.origin), {
      headers: { 'X-Benchmark-Token': fixture.controlToken }, redirect: 'manual',
      signal: AbortSignal.timeout(30000),
    });
    if (!response.ok) throw new Error('benchmark_observation_failed');
    const current = await response.json();
    report.remaining = current.pending;
    report.deliveredFetch = current.delivered;
    report.automaticCronSamples = current.samples.filter(s => !manual.has(s.at) && s.elapsed >= 0);
    report.incompleteAutomaticAttempts = current.samples.filter(s => !manual.has(s.at) && s.elapsed < 0);
    report.manualSamples = current.samples.filter(s => manual.has(s.at));
    const observation = { at: new Date().toISOString(), pending: current.pending,
      completedAutomaticRuns: report.automaticCronSamples.length,
      incompleteAutomaticAttempts: report.incompleteAutomaticAttempts.length };
    report.observations.push(observation);
    await save(); console.log(JSON.stringify(observation));
    if (current.pending === 0) {
      report.recoveredAt = observation.at;
      report.recoveryElapsedMs = Date.parse(observation.at) - Date.parse(report.recoveryStartedAt);
      report.recoveredWithin60Minutes = report.recoveryElapsedMs <= 60 * 60000;
      break;
    }
    await new Promise(resolve => setTimeout(resolve, Math.min(60000, fixture.endsAt - Date.now())));
  }
  report.finishedAt = new Date().toISOString(); await save();
  if (report.remaining !== 0) process.exitCode = 1;
} catch {
  // Transport errors may contain credentials or endpoint URLs; keep only a fixed diagnostic.
  report.failed = true; await save(); console.error('benchmark_observation_failed'); process.exitCode = 1;
}
