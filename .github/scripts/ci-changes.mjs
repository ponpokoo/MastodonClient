import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const flags = ['android', 'android_test', 'release_tests', 'prototype', 'workers', 'relay'];

export function classifyPaths(paths, forceAll = false) {
  const selected = Object.fromEntries(flags.map(flag => [flag, forceAll]));
  for (const path of paths) {
    if (/\.md$/i.test(path) || path.startsWith('app/release/') ||
        path.startsWith('relay/workers/bench/results/')) continue;
    if (path.startsWith('.github/workflows/') || path.startsWith('.github/scripts/')) {
      flags.forEach(flag => { selected[flag] = true; });
    } else if (path.startsWith('gradle/') || path === 'gradlew' || path === 'gradlew.bat' ||
        /^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties)$/.test(path)) {
      for (const flag of ['android', 'android_test', 'release_tests', 'prototype']) selected[flag] = true;
    } else if (path.startsWith('tools/')) {
      selected.android = true;
      selected.android_test = true;
    } else if (path.startsWith('app/')) {
      selected.android = true;
      if (path.startsWith('app/src/androidTest/') || path === 'app/build.gradle.kts') selected.android_test = true;
    } else if (path.startsWith('release-tests/')) {
      selected.release_tests = true;
    } else if (path.startsWith('transition-prototype/')) {
      selected.prototype = true;
    } else if (path.startsWith('relay/workers/')) {
      selected.workers = true;
    } else if (path.startsWith('relay/')) {
      selected.relay = true;
    }
  }
  return selected;
}

function git(cwd, args, input) {
  return execFileSync('git', args, { cwd, input, encoding: 'utf8', stdio: 'pipe' });
}

function commit(cwd, sha) {
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i.test(sha ?? '')) throw new Error('Invalid event commit SHA');
  return git(cwd, ['rev-parse', '--verify', `${sha}^{commit}`]).trim();
}

export function analyzeChanges(eventName, event, cwd = process.cwd()) {
  let range;
  let forceAll = eventName === 'workflow_dispatch';
  if (eventName === 'pull_request') {
    const base = commit(cwd, event.pull_request?.base?.sha);
    const head = commit(cwd, event.pull_request?.head?.sha);
    range = `${base}...${head}`;
  } else if (eventName === 'push' || eventName === 'workflow_dispatch') {
    const head = eventName === 'push' ? commit(cwd, event.after) : git(cwd, ['rev-parse', 'HEAD']).trim();
    let base;
    try {
      base = eventName === 'push' ? commit(cwd, event.before) : git(cwd, ['rev-parse', '--verify', 'HEAD^']).trim();
    } catch {
      // Initial pushes and unavailable force-push bases must not silently skip validation.
      base = git(cwd, ['hash-object', '-t', 'tree', '--stdin'], '').trim();
      forceAll = true;
    }
    range = `${base}..${head}`;
  } else {
    throw new Error(`Unsupported CI event: ${eventName}`);
  }
  git(cwd, ['diff', '--check', range, '--']);
  // Disable rename detection so moving a file validates both its old and new scopes.
  const paths = git(cwd, ['diff', '--name-only', '--no-renames', '-z', range, '--']).split('\0').filter(Boolean);
  return { range, paths, selected: classifyPaths(paths, forceAll) };
}

export function checkResults(needs) {
  if (needs.changes?.result !== 'success') throw new Error('Changes and diff did not succeed');
  const output = needs.changes.outputs ?? {};
  if (flags.some(flag => !['true', 'false'].includes(output[flag]))) {
    throw new Error('CI selection outputs are missing or invalid');
  }
  const required = {
    android: ['android', 'release_tests', 'prototype'].some(flag => output[flag] === 'true'),
    workers: output.workers === 'true',
    relay: output.relay === 'true',
  };
  for (const [job, selected] of Object.entries(required)) {
    const result = needs[job]?.result;
    if (result !== 'success' && !(result === 'skipped' && !selected)) {
      throw new Error(`${job}: ${result ?? 'missing'} (required: ${selected})`);
    }
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  if (process.argv.includes('--check-results')) {
    checkResults(JSON.parse(process.env.CI_NEEDS ?? '{}'));
    console.log('All selected CI jobs succeeded.');
  } else {
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    const { range, paths, selected } = analyzeChanges(process.env.GITHUB_EVENT_NAME, event);
    appendFileSync(process.env.GITHUB_OUTPUT, flags.map(flag => `${flag}=${selected[flag]}\n`).join(''));
    const summary = `## CI selection\n\nCommitted range: ${range}\n\nChanged files: ${paths.length}\n\n` +
      flags.map(flag => `- ${flag}: ${selected[flag] ? 'selected' : 'skipped'}\n`).join('');
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
    console.log(summary);
  }
}
