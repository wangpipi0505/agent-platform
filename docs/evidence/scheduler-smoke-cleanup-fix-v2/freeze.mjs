import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../../..');
const phase = 'docs/evidence/scheduler-smoke-cleanup-fix-v2';
const at = relative => path.join(root, relative);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const previousPath = 'docs/evidence/scheduler-smoke-cleanup-fix-sha256.json';
const previousBytes = await fs.readFile(at(previousPath));
assert.equal(sha(previousBytes), '46868c40c6f31d073b9c89dd70c52a5ec6afa5d3ca592c35fe5cfab6e2f74884');
const previous = JSON.parse(previousBytes);
for (const old of [previous.patch, ...previous.evidence]) assert.equal(sha(await fs.readFile(at(old.path))), old.sha256, old.path);
for (const old of previous.files) {
  assert.equal(sha(await fs.readFile(at(old.frozen))), old.afterSha256, old.frozen);
  if (old.baseline) assert.equal(sha(await fs.readFile(at(old.baseline))), old.beforeSha256, old.baseline);
}
const baseline = JSON.parse(await fs.readFile(at(phase + '/baseline-sha256.json'), 'utf8'));
const files = [];
let patch = '';
for (const entry of [...baseline, {path: 'tests/scheduler-smoke-cleanup-async.test.mjs', beforeSha256: null}]) {
  const beforePath = phase + '/baseline/' + entry.path;
  const frozenPath = phase + '/frozen/' + entry.path;
  const current = await fs.readFile(at(entry.path));
  if (entry.beforeSha256) {
    assert.equal(sha(await fs.readFile(at(beforePath))), entry.beforeSha256);
    assert.equal(previous.files.find(old => old.path === entry.path).afterSha256, entry.beforeSha256);
  }
  await fs.mkdir(path.dirname(at(frozenPath)), {recursive: true});
  await fs.writeFile(at(frozenPath), current);
  const afterSha256 = sha(current);
  const status = entry.beforeSha256 === afterSha256 ? 'unchanged' : entry.beforeSha256 ? 'modified' : 'added';
  files.push({path: entry.path, status, beforeSha256: entry.beforeSha256, afterSha256,
    baseline: entry.beforeSha256 ? beforePath : null, frozen: frozenPath});
  if (status === 'unchanged') continue;
  const diff = spawnSync('git', ['-c', 'core.autocrlf=false', 'diff', '--no-index', '--',
    entry.beforeSha256 ? beforePath : '/dev/null', frozenPath], {cwd: root, encoding: 'utf8', windowsHide: true});
  assert.equal(diff.status, 1, diff.stderr);
  patch += diff.stdout.replaceAll('a/' + phase + '/baseline/', 'a/').replaceAll('a/' + phase + '/frozen/', 'a/')
    .replaceAll('b/' + phase + '/frozen/', 'b/');
}
assert.deepEqual(files.filter(file => file.status === 'modified').map(file => file.path),
  ['tests/scheduler-smoke-cleanup.mjs', 'tests/scheduler-smoke-cleanup.test.mjs']);
const stopBody = text => text.slice(text.indexOf('async function stop(engine) {'), text.indexOf('\nfunction seed('));
const oldStop = stopBody(await fs.readFile(at('docs/evidence/scheduler-smoke-cleanup-fix/baseline/tests/smoke-run-scheduler-replicas.mjs'), 'utf8'));
assert.equal(stopBody(await fs.readFile(at('tests/smoke-run-scheduler-replicas.mjs'), 'utf8')), oldStop);
await fs.writeFile(at(phase + '.patch'), patch);
const evidencePaths = [phase + '/baseline-sha256.json', phase + '/red.log', phase + '/green.log', phase + '/syntax.log',
  ...['scheduler-smoke-cleanup.mjs', 'scheduler-smoke-cleanup.test.mjs', 'scheduler-smoke-cleanup-async.test.mjs', 'smoke-run-scheduler-replicas.mjs'].map(file => phase + '/red/tests/' + file),
  phase + '/freeze.mjs', phase + '.md', previousPath];
if (process.argv.includes('--with-replay')) evidencePaths.push(phase + '/replay.log');
const evidence = await Promise.all(evidencePaths.map(async file => ({path: file, sha256: sha(await fs.readFile(at(file)))})));
const manifest = {createdAt: new Date().toISOString(), node: process.version, gitHead: 'unborn; full v1 baseline and v2 frozen copies',
  previousManifest: {path: previousPath, sha256: sha(previousBytes)}, files, unchangedStopBodySha256: sha(oldStop),
  patch: {path: phase + '.patch', sha256: sha(patch)}, evidence};
await fs.writeFile(at(phase + '-sha256.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(JSON.stringify({files: files.map(({path, status, afterSha256}) => ({path, status, afterSha256})),
  unchangedStopBodySha256: manifest.unchangedStopBodySha256, patch: manifest.patch, previousEvidenceUnchanged: true}, null, 2));
