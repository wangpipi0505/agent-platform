import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
const directory = import.meta.dirname, root = path.resolve(directory, '../../..');
const digest = data => crypto.createHash('sha256').update(data).digest('hex');
const preserved = {};
async function visit(relative) {
  for (const entry of await fs.readdir(path.join(root, relative), { withFileTypes: true })) {
    const file = path.posix.join(relative, entry.name);
    if (entry.isDirectory()) await visit(file);
    else preserved[file] = digest(await fs.readFile(path.join(root, file)));
  }
}
for (const entry of await fs.readdir(path.join(root, 'docs/evidence'), { withFileTypes: true })) {
  if (!/^algorithm-(process|live)/.test(entry.name) || entry.name.startsWith('algorithm-process-runner-time-precision-fix')) continue;
  const file = 'docs/evidence/' + entry.name;
  if (entry.isDirectory()) await visit(file); else preserved[file] = digest(await fs.readFile(path.join(root, file)));
}
await fs.writeFile(path.join(directory, 'preserved-history.json'), JSON.stringify(preserved, null, 2));
const tests = ['tests/algorithm-process-runner.test.mjs', 'tests/algorithm-process-runner-fixes.test.mjs', 'tests/algorithm-process-runner-redis.test.mjs', 'tests/algorithm-process-runner-async.test.mjs', 'tests/algorithm-process-runner-async-cleanup.test.mjs', 'tests/algorithm-process-attempt-scope.test.mjs', 'tests/algorithm-process-attempt-time.test.mjs'];
const commands = [
  { command: 'node --test tests/algorithm-process-attempt-time.test.mjs', phase: 'old oracle red', oracleSha256: 'f6a78f20554d75994abfc16a4e49ac72def2ab86bd28ff254a35eddc0b4fe9cb', exit: 1, tests: 11, pass: 7, fail: 4, log: 'red.log' },
  { command: 'node --test tests/algorithm-process-attempt-time.test.mjs', phase: 'fixed oracle green', exit: 0, tests: 11, pass: 11, fail: 0, log: 'green-time.log' },
  { command: 'node --test ' + tests.join(' '), phase: 'full coverage green', exit: 0, tests: 135, pass: 135, fail: 0, log: 'green-all.log' },
];
for (const command of commands) command.logSha256 = digest(await fs.readFile(path.join(directory, command.log)));
await fs.writeFile(path.join(directory, 'commands.json'), JSON.stringify({ cwd: root, commands, fifthLiveExecution: false }, null, 2));
console.log(JSON.stringify({ priorFilesPreserved: Object.keys(preserved).length }));
