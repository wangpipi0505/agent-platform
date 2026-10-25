import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

const directory = import.meta.dirname, root = path.resolve(directory, '../../..');
const files = ['tests/smoke-algorithm-processes.mjs', 'tests/algorithm-process-runner.test.mjs', 'docs/evidence/algorithm-process-runner-ready.md'];
const hash = async file => crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
for (const name of ['baseline', 'frozen', 'inputs/tests', 'replay/docs/evidence/algorithm-process-runner'])
  await fs.mkdir(path.join(directory, name), { recursive: true });
await fs.writeFile(path.join(directory, 'baseline/absence.json'), JSON.stringify({ kind: 'new-files-absent-before-this-task', files }, null, 2));
// Git's empty source baseline lives separately from the absence metadata.
await fs.mkdir(path.join(directory, 'empty-source-baseline'), { recursive: true });
for (const file of files) {
  const target = path.join(directory, 'frozen', file);
  await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.copyFile(path.join(root, file), target);
}
const fixture = 'tests/smoke-algorithm-protocol.mjs';
await fs.copyFile(path.join(root, fixture), path.join(directory, 'inputs', fixture));
const diff = spawnSync('git', ['-c', 'core.autocrlf=false', 'diff', '--no-index', '--binary', '--no-renames', '--', 'empty-source-baseline', 'frozen'], { cwd: directory, encoding: 'utf8' });
assert.equal(diff.status, 1, diff.stderr);
const patch = diff.stdout.replaceAll('a/frozen/', 'a/').replaceAll('b/frozen/', 'b/').replaceAll('a/empty-source-baseline/', 'a/');
const patchFile = path.join(root, 'docs/evidence/algorithm-process-runner.patch');
await fs.writeFile(patchFile, patch);
const replay = path.join(directory, 'replay'), prefix = path.relative(root, replay).replaceAll('\\', '/');
for (const flag of ['--check', null]) {
  const args = ['-c', 'core.autocrlf=false', 'apply', ...(flag ? [flag] : []), '--directory=' + prefix, patchFile];
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  await fs.appendFile(path.join(directory, 'replay.log'), JSON.stringify({ command: 'git ' + args.join(' '), exit: result.status, output: result.stdout, stderr: result.stderr }) + '\n');
}
for (const file of files) assert.equal(await hash(path.join(replay, file)), await hash(path.join(directory, 'frozen', file)));
await fs.copyFile(path.join(directory, 'inputs', fixture), path.join(replay, fixture));
const checks = [['--check', 'tests/smoke-algorithm-processes.mjs'], ['--check', 'tests/algorithm-process-runner.test.mjs'], ['--test', 'tests/algorithm-process-runner.test.mjs']];
for (const args of checks) {
  const result = spawnSync(process.execPath, args, { cwd: replay, encoding: 'utf8', timeout: 20000 });
  await fs.appendFile(path.join(directory, 'replay.log'), JSON.stringify({ command: 'node ' + args.join(' '), exit: result.status }) + '\n' + result.stdout + result.stderr);
  assert.equal(result.status, 0, result.stderr);
}
const manifest = { generatedAt: new Date().toISOString(), realJvmOrDatabaseRun: false, sourceBaseline: 'new files absent',
  sources: {}, frozen: {}, replay: {}, inputs: { [fixture]: await hash(path.join(root, fixture)) }, evidence: {} };
for (const file of files) {
  manifest.sources[file] = await hash(path.join(root, file));
  manifest.frozen[file] = await hash(path.join(directory, 'frozen', file));
  manifest.replay[file] = await hash(path.join(replay, file));
}
async function list(base, relative = '') {
  const found = [];
  for (const entry of await fs.readdir(path.join(base, relative), { withFileTypes: true })) {
    if (['frozen', 'replay', 'inputs'].includes(entry.name) && relative === '') continue;
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) found.push(...await list(base, file)); else found.push(file);
  }
  return found;
}
for (const file of await list(directory)) manifest.evidence[file.replaceAll('\\', '/')] = await hash(path.join(directory, file));
manifest.patch = { file: 'docs/evidence/algorithm-process-runner.patch', sha256: await hash(patchFile) };
manifest.historicalPausedReport = { file: 'docs/evidence/algorithm-process-runner-report.md', sha256: await hash(path.join(root, 'docs/evidence/algorithm-process-runner-report.md')) };
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-runner-sha256.json'), JSON.stringify(manifest, null, 2));
process.stdout.write('Frozen source 3/3; git apply check/apply passed; replay SHA 3/3; replay finite Node 12/12. No JVM/PG/MinIO run.\n');
