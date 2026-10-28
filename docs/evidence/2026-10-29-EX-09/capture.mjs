import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const evidence = path.dirname(fileURLToPath(import.meta.url));
const phase = process.argv[2];
if (!['before', 'after'].includes(phase)) throw new Error('phase must be before or after');
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fixtureDir = path.join(evidence, 'fixtures');
mkdirSync(fixtureDir, {recursive: true});
const sample = JSON.parse(readFileSync(path.join(root, 'contracts/capability-packages/route1-visibility.json'), 'utf8'));
const variants = [
    ['missing-tool-binding', (pkg) => { delete pkg.tools[0].binding; }, '能力包.tools[0].binding'],
    ['identity-plaintext', (pkg) => { pkg.identity.secrets = [{ref: 'EX09_SYNTHETIC_NOT_A_CREDENTIAL', purpose: 'EX-09 controlled negative'}]; }, '能力包.identity.secrets[0].ref'],
    ['unknown-kind', (pkg) => { pkg.interfaces[0].kind = 'EX09_UNKNOWN_KIND'; }, '能力包.interfaces[0].kind'],
];
for (const [name, mutate] of variants) {
    const pkg = structuredClone(sample);
    mutate(pkg);
    writeFileSync(path.join(fixtureDir, `invalid-${name}.json`), `${JSON.stringify(pkg, null, 2)}\n`);
}
const outputDir = path.join(evidence, phase);
mkdirSync(outputDir, {recursive: true});
const freeze = JSON.parse(readFileSync(path.join(root, 'contracts/freeze.json'), 'utf8'));
const files = ['scripts/verify-contracts.mjs', 'scripts/verify-capability-package.mjs',
    'contracts/capability-package.schema.json', 'contracts/freeze.json',
    'contracts/snapshot/detailed-design-v1.2.md', 'contracts/capability-packages/route1-visibility.json',
    ...freeze.contracts.map((item) => item.file)];
const snapshot = {
    capturedAt: new Date().toISOString(), node: process.version, cwd: root,
    head: spawnSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8', windowsHide: true}).stdout.trim(),
    dirty: spawnSync('git', ['-c', 'core.quotepath=false', 'status', '--short'], {cwd: root, encoding: 'utf8', windowsHide: true}).stdout,
    hashes: Object.fromEntries(files.map((file) => [file, sha256(readFileSync(path.join(root, file)))])),
};
writeFileSync(path.join(outputDir, 'snapshot.json'), `${JSON.stringify(snapshot, null, 2)}\n`);
const cases = [];
for (const [prefix, script] of [['standalone', 'scripts/verify-capability-package.mjs'], ['gate', 'scripts/verify-contracts.mjs']]) {
    cases.push({name: `${prefix}-default`, args: [script], expectedExit: 0});
    for (const [name, , fieldPath] of variants) cases.push({name: `${prefix}-${name}`, args: [script, '--file', `docs/evidence/2026-10-29-EX-09/fixtures/invalid-${name}.json`], expectedExit: 1, fieldPath});
}
if (phase === 'after') for (const script of files.slice(0, 2)) cases.push({name: `syntax-${path.basename(script, '.mjs')}`, args: ['--check', script], expectedExit: 0});
const results = cases.map(({name, args, expectedExit, fieldPath}) => {
    const run = spawnSync(process.execPath, args, {cwd: root, encoding: 'utf8', windowsHide: true});
    writeFileSync(path.join(outputDir, `${name}.stdout.txt`), run.stdout || '');
    writeFileSync(path.join(outputDir, `${name}.stderr.txt`), run.stderr || '');
    const fieldPathFound = fieldPath ? `${run.stdout || ''}${run.stderr || ''}`.includes(fieldPath) : null;
    const result = {name, command: `node ${args.join(' ')}`, nativeExit: run.status, signal: run.signal,
        expectedExit, fieldPath, fieldPathFound, error: run.error?.code,
        stdoutSha256: sha256(run.stdout || ''), stderrSha256: sha256(run.stderr || '')};
    console.log(`${name}: nativeExit=${run.status}, expected=${expectedExit}, fieldPathFound=${fieldPathFound}`);
    return result;
});
writeFileSync(path.join(outputDir, 'commands.json'), `${JSON.stringify(results, null, 2)}\n`);
if (phase === 'after' && results.some((item) => item.nativeExit !== item.expectedExit || item.fieldPathFound === false)) process.exitCode = 1;
