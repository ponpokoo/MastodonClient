import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import test from 'node:test';
import { analyzeChanges, checkResults, classifyPaths } from './ci-changes.mjs';

function repository(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'nagisa-ci-test-')));
  t.after(() => {
    assert.equal(dirname(root), realpathSync(tmpdir()));
    assert.ok(basename(root).startsWith('nagisa-ci-test-'));
    rmSync(root, { recursive: true, force: true });
  });
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: 'pipe' }).trim();
  git('init', '--quiet', '--initial-branch=main');
  git('config', 'user.name', 'CI test');
  git('config', 'user.email', 'ci-test@example.test');
  git('config', 'core.autocrlf', 'false');
  const write = (path, content) => {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), content);
  };
  const commit = files => {
    for (const [path, content] of Object.entries(files)) write(path, content);
    git('add', '--all');
    git('-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'CI fixture');
    return git('rev-parse', 'HEAD');
  };
  return { root, git, write, commit };
}

test('documentation, release outputs and measurement records skip build jobs', () => {
  assert.ok(Object.values(classifyPaths([
    'docs/project-setup.md', 'relay/workers/README.md', 'app/release/google-play/icon.png',
    'relay/workers/bench/results/measurement.json',
  ])).every(value => value === false));
});

test('Android device tests and shared Gradle changes select the relevant compilation', () => {
  const device = classifyPaths(['app/src/androidTest/java/RegressionTest.kt']);
  assert.equal(device.android, true);
  assert.equal(device.android_test, true);
  assert.equal(device.workers, false);
  const tool = classifyPaths(['tools/export-license-artifacts.gradle']);
  assert.equal(tool.android, true);
  assert.equal(tool.android_test, true);
  const shared = classifyPaths(['gradle/libs.versions.toml']);
  for (const flag of ['android', 'android_test', 'release_tests', 'prototype']) assert.equal(shared[flag], true);
  assert.equal(shared.relay, false);
});

test('Relay implementations remain independent and mixed changes select both', () => {
  const workers = classifyPaths(['relay/workers/package-lock.json']);
  assert.equal(workers.workers, true);
  assert.equal(workers.relay, false);
  const both = classifyPaths(['relay/workers/src/protocol.mjs', 'relay/test/relay.test.mjs']);
  assert.equal(both.workers, true);
  assert.equal(both.relay, true);
  assert.equal(both.android, false);
});

test('CI changes and manual dispatch select every job', () => {
  assert.ok(Object.values(classifyPaths(['.github/workflows/ci.yml'])).every(Boolean));
  assert.ok(Object.values(classifyPaths([], true)).every(Boolean));
});

test('push checks committed changes rather than an uncommitted working tree', t => {
  const repo = repository(t);
  const before = repo.commit({ 'README.md': 'Initial\n' });
  const after = repo.commit({ 'app/src/main/Example.kt': 'class Example\n' });
  repo.write('README.md', 'Uncommitted trailing space \n');
  const analysis = analyzeChanges('push', { before, after }, repo.root);
  assert.deepEqual(analysis.paths, ['app/src/main/Example.kt']);
  assert.equal(analysis.selected.android, true);
});

test('PR uses the merge base and validates both sides of a rename', t => {
  const repo = repository(t);
  repo.commit({ 'relay/workers/src/example.mjs': 'export const value = 1;\n' });
  repo.git('checkout', '--quiet', '-b', 'feature');
  mkdirSync(join(repo.root, 'relay/tools'), { recursive: true });
  renameSync(join(repo.root, 'relay/workers/src/example.mjs'), join(repo.root, 'relay/tools/example.mjs'));
  const head = repo.commit({});
  repo.git('checkout', '--quiet', 'main');
  const base = repo.commit({ 'app/src/main/Unrelated.kt': 'class Unrelated\n' });
  const analysis = analyzeChanges('pull_request', { pull_request: { base: { sha: base }, head: { sha: head } } }, repo.root);
  assert.equal(analysis.selected.workers, true);
  assert.equal(analysis.selected.relay, true);
  assert.equal(analysis.selected.android, false);
  assert.equal(analysis.paths.length, 2);
});

test('initial push and unavailable old history conservatively select every job', t => {
  const repo = repository(t);
  const after = repo.commit({ 'README.md': 'Initial\n' });
  for (const before of ['0'.repeat(40), '1'.repeat(40)]) {
    assert.ok(Object.values(analyzeChanges('push', { before, after }, repo.root).selected).every(Boolean));
  }
  assert.ok(Object.values(analyzeChanges('workflow_dispatch', {}, repo.root).selected).every(Boolean));
});

test('whitespace failures in committed changes fail the common check', t => {
  const repo = repository(t);
  const before = repo.commit({ 'README.md': 'Initial\n' });
  const after = repo.commit({ 'README.md': 'Trailing space \n' });
  assert.throws(() => analyzeChanges('push', { before, after }, repo.root), error => error.status === 2);
});

test('result accepts intentional skips but rejects selected skips, failures and cancellations', () => {
  const outputs = Object.fromEntries(Object.entries(classifyPaths([])).map(([flag]) => [flag, 'false']));
  const needs = {
    changes: { result: 'success', outputs: { ...outputs, workers: 'true' } },
    android: { result: 'skipped' }, workers: { result: 'success' }, relay: { result: 'skipped' },
  };
  assert.doesNotThrow(() => checkResults(needs));
  for (const result of ['skipped', 'failure', 'cancelled']) {
    assert.throws(() => checkResults({ ...needs, workers: { result } }));
  }
  assert.throws(() => checkResults({ ...needs, changes: { result: 'failure' } }));
  assert.throws(() => checkResults({ ...needs, changes: { result: 'success', outputs: {} } }));
  assert.throws(() => checkResults({ ...needs, changes: { result: 'success', outputs: { ...outputs, release_tests: 'true' } } }));
});
