import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

const directory = import.meta.dirname, root = path.resolve(directory, '../../..');
const hash = async file => crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
const originalDirectory = path.join(root, 'docs/evidence/algorithm-process-runner');
const original = JSON.parse(await fs.readFile(path.join(root, 'docs/evidence/algorithm-process-runner-sha256.json'), 'utf8'));
let originalChecks = 0;
for (const [group, prefix] of [['frozen', 'frozen'], ['replay', 'replay'], ['evidence', '']]) {
  for (const [file, expected] of Object.entries(original[group])) {
    assert.equal(await hash(path.join(originalDirectory, prefix, file)), expected, 'Original evidence changed: ' + file); originalChecks++;
  }
}
for (const [file, expected] of Object.entries(original.inputs)) {
  assert.equal(await hash(path.join(root, file)), expected); originalChecks++;
}
for (const record of [original.patch, original.historicalPausedReport]) {
  assert.equal(await hash(path.join(root, record.file)), record.sha256); originalChecks++;
}
const files = ['tests/smoke-algorithm-processes.mjs', 'tests/algorithm-process-runner.test.mjs', 'tests/algorithm-process-runner-fixes.test.mjs', 'docs/evidence/algorithm-process-runner-fixes.md'];
for (const file of files.slice(0, 2)) assert.equal(await hash(path.join(directory, 'baseline', file)), original.sources[file]);
await fs.writeFile(path.join(directory, 'baseline-absence.json'), JSON.stringify({ absentBeforeFix: files.slice(2) }, null, 2));
for (const file of files) {
  const target = path.join(directory, 'frozen', file); await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.copyFile(path.join(root, file), target);
}
const diff = spawnSync('git', ['-c', 'core.autocrlf=false', 'diff', '--no-index', '--binary', '--no-renames', '--', 'baseline', 'frozen'], { cwd: directory, encoding: 'utf8' });
assert.equal(diff.status, 1, diff.stderr);
const patch = diff.stdout.replaceAll('a/baseline/', 'a/').replaceAll('a/frozen/', 'a/').replaceAll('b/frozen/', 'b/');
const patchFile = path.join(root, 'docs/evidence/algorithm-process-runner-fixes.patch'); await fs.writeFile(patchFile, patch);
const replay = path.join(directory, 'replay'), prefix = path.relative(root, replay).replaceAll('\\', '/');
await fs.mkdir(path.join(replay, 'tests'), { recursive: true });
await fs.mkdir(path.join(replay, 'docs/evidence/algorithm-process-runner'), { recursive: true });
for (const file of files.slice(0, 2)) await fs.copyFile(path.join(directory, 'baseline', file), path.join(replay, file));
for (const flag of ['--check', null]) {
  const args = ['-c', 'core.autocrlf=false', 'apply', ...(flag ? [flag] : []), '--directory=' + prefix, patchFile];
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  await fs.appendFile(path.join(directory, 'replay.log'), JSON.stringify({ command: 'git ' + args.join(' '), exit: result.status, stdout: result.stdout, stderr: result.stderr }) + '\n');
  assert.equal(result.status, 0, result.stderr);
}
for (const file of files) assert.equal(await hash(path.join(replay, file)), await hash(path.join(directory, 'frozen', file)));
await fs.copyFile(path.join(root, 'tests/smoke-algorithm-protocol.mjs'), path.join(replay, 'tests/smoke-algorithm-protocol.mjs'));
for (const args of [['--check', files[0]], ['--check', files[1]], ['--check', files[2]], ['--test', files[1], files[2]]]) {
  const result = spawnSync(process.execPath, args, { cwd: replay, encoding: 'utf8', timeout: 20000 });
  await fs.appendFile(path.join(directory, 'replay.log'), JSON.stringify({ command: 'node ' + args.join(' '), exit: result.status }) + '\n' + result.stdout + result.stderr);
  assert.equal(result.status, 0, result.stderr);
}
const manifest = { generatedAt: new Date().toISOString(), actualLiveRerun: false, originalChecks, baseline: {}, sources: {}, frozen: {}, replay: {}, evidence: {},
  originalManifestSha256: await hash(path.join(root, 'docs/evidence/algorithm-process-runner-sha256.json')) };
for (const file of files.slice(0, 2)) manifest.baseline[file] = await hash(path.join(directory, 'baseline', file));
for (const file of files) {
  manifest.sources[file] = await hash(path.join(root, file)); manifest.frozen[file] = await hash(path.join(directory, 'frozen', file)); manifest.replay[file] = await hash(path.join(replay, file));
}
async function list(relative = '') {
  const files = [];
  for (const entry of await fs.readdir(path.join(directory, relative), { withFileTypes: true })) {
    if (!relative && ['frozen', 'replay'].includes(entry.name)) continue;
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) files.push(...await list(file)); else files.push(file);
  }
  return files;
}
for (const file of await list()) manifest.evidence[file.replaceAll('\\', '/')] = await hash(path.join(directory, file));
manifest.patch = { file: 'docs/evidence/algorithm-process-runner-fixes.patch', sha256: await hash(patchFile) };
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-runner-fixes-sha256.json'), JSON.stringify(manifest, null, 2));
process.stdout.write(JSON.stringify({ originalChecks, replaySHA: '4/4', nodeTests: '16/16', actualLiveRerun: false }) + '\n');
