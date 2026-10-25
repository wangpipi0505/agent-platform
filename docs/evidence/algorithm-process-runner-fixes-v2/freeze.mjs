import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

const directory = import.meta.dirname, root = path.resolve(directory, '../../..');
const hash = async file => crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
const previousDirectory = path.join(root, 'docs/evidence/algorithm-process-runner-fixes');
const previousFile = path.join(root, 'docs/evidence/algorithm-process-runner-fixes-sha256.json');
const previous = JSON.parse(await fs.readFile(previousFile, 'utf8'));
let historicalChecks = 0;
for (const [group, prefix] of [['frozen', 'frozen'], ['replay', 'replay'], ['evidence', '']]) {
  for (const [file, expected] of Object.entries(previous[group])) {
    assert.equal(await hash(path.join(previousDirectory, prefix, file)), expected, 'Historical fix changed: ' + file); historicalChecks++;
  }
}
assert.equal(await hash(path.join(root, previous.patch.file)), previous.patch.sha256); historicalChecks++;
assert.equal(await hash(path.join(root, 'docs/evidence/algorithm-process-runner-sha256.json')), previous.originalManifestSha256); historicalChecks++;
const files = [...Object.keys(previous.sources), 'tests/algorithm-process-runner-redis.test.mjs', 'docs/evidence/algorithm-process-runner-fixes-v2.md'];
for (const [file, expected] of Object.entries(previous.sources)) assert.equal(await hash(path.join(directory, 'baseline', file)), expected);
await fs.writeFile(path.join(directory, 'baseline-absence.json'), JSON.stringify({ absentBeforeFix: files.slice(-2) }, null, 2));
for (const file of files) {
  const target = path.join(directory, 'frozen', file); await fs.mkdir(path.dirname(target), { recursive: true }); await fs.copyFile(path.join(root, file), target);
}
const diff = spawnSync('git', ['-c', 'core.autocrlf=false', 'diff', '--no-index', '--binary', '--no-renames', '--', 'baseline', 'frozen'], { cwd: directory, encoding: 'utf8' });
assert.equal(diff.status, 1, diff.stderr);
const patch = diff.stdout.replaceAll('a/baseline/', 'a/').replaceAll('a/frozen/', 'a/').replaceAll('b/frozen/', 'b/');
const patchFile = path.join(root, 'docs/evidence/algorithm-process-runner-fixes-v2.patch'); await fs.writeFile(patchFile, patch);
const replay = path.join(directory, 'replay'), prefix = path.relative(root, replay).replaceAll('\\', '/');
await fs.mkdir(path.join(replay, 'docs/evidence/algorithm-process-runner'), { recursive: true });
for (const file of Object.keys(previous.sources)) {
  const target = path.join(replay, file); await fs.mkdir(path.dirname(target), { recursive: true }); await fs.copyFile(path.join(directory, 'baseline', file), target);
}
for (const flag of ['--check', null]) {
  const args = ['-c', 'core.autocrlf=false', 'apply', ...(flag ? [flag] : []), '--directory=' + prefix, patchFile];
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  await fs.appendFile(path.join(directory, 'replay.log'), JSON.stringify({ command: 'git ' + args.join(' '), exit: result.status, stdout: result.stdout, stderr: result.stderr }) + '\n');
  assert.equal(result.status, 0, result.stderr);
}
for (const file of files) assert.equal(await hash(path.join(replay, file)), await hash(path.join(directory, 'frozen', file)));
await fs.copyFile(path.join(root, 'tests/smoke-algorithm-protocol.mjs'), path.join(replay, 'tests/smoke-algorithm-protocol.mjs'));
for (const args of [...files.filter(file => file.endsWith('.mjs')).map(file => ['--check', file]), ['--test', 'tests/algorithm-process-runner.test.mjs', 'tests/algorithm-process-runner-fixes.test.mjs', 'tests/algorithm-process-runner-redis.test.mjs']]) {
  const result = spawnSync(process.execPath, args, { cwd: replay, encoding: 'utf8', timeout: 20000 });
  await fs.appendFile(path.join(directory, 'replay.log'), JSON.stringify({ command: 'node ' + args.join(' '), exit: result.status }) + '\n' + result.stdout + result.stderr);
  assert.equal(result.status, 0, result.stderr);
}
const manifest = { generatedAt: new Date().toISOString(), thirdLiveExecution: false, historicalChecks, previousManifestSha256: await hash(previousFile),
  baseline: {}, sources: {}, frozen: {}, replay: {}, evidence: {}, preservedLiveEvidence: {} };
for (const file of Object.keys(previous.sources)) manifest.baseline[file] = await hash(path.join(directory, 'baseline', file));
for (const file of files) {
  manifest.sources[file] = await hash(path.join(root, file)); manifest.frozen[file] = await hash(path.join(directory, 'frozen', file)); manifest.replay[file] = await hash(path.join(replay, file));
}
async function list(base, relative = '', skip = []) {
  const found = [];
  for (const entry of await fs.readdir(path.join(base, relative), { withFileTypes: true })) {
    if (!relative && skip.includes(entry.name)) continue;
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) found.push(...await list(base, file)); else found.push(file);
  }
  return found;
}
for (const file of await list(directory, '', ['frozen', 'replay'])) manifest.evidence[file.replaceAll('\\', '/')] = await hash(path.join(directory, file));
for (const name of ['live-bcc9285991eb', 'live-49498fe27b72']) {
  const base = path.join(root, 'docs/evidence/algorithm-process-runner', name);
  for (const file of await list(base)) manifest.preservedLiveEvidence['docs/evidence/algorithm-process-runner/' + name + '/' + file.replaceAll('\\', '/')] = await hash(path.join(base, file));
}
for (const name of ['algorithm-process-live.md', 'algorithm-process-live-summary.json', 'algorithm-process-live-execution.log', 'algorithm-process-live-encoding-diagnostic.json', 'algorithm-process-live-first-pg-readonly.json',
  'algorithm-process-live-v2.md', 'algorithm-process-live-v2-summary.json', 'algorithm-process-live-v2-execution.log', 'algorithm-process-live-v2-readonly.json'])
  manifest.preservedLiveEvidence['docs/evidence/' + name] = await hash(path.join(root, 'docs/evidence', name));
manifest.patch = { file: 'docs/evidence/algorithm-process-runner-fixes-v2.patch', sha256: await hash(patchFile) };
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-runner-fixes-v2-sha256.json'), JSON.stringify(manifest, null, 2));
process.stdout.write(JSON.stringify({ historicalChecks, replaySHA: '6/6', finiteTests: '21/21', thirdLiveExecution: false }) + '\n');
