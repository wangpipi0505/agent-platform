import assert from 'node:assert/strict'
import { readFile, writeFile, mkdtemp, unlink, rmdir } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../../../', import.meta.url))
const contract = await readFile(path.join(root, 'contracts/skill-invocation.md'))
const freeze = JSON.parse(await readFile(path.join(root, 'contracts/freeze.json'), 'utf8'))
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
assert.equal(hash(contract.subarray(0, 63602)), freeze.contracts.find(c => c.name === 'skill-invocation').sha256)
const marker = '\r\n## 16. 工程增补（EX-05）：本体manifest消费映射\r\n'
const text = contract.toString('utf8')
const cases = [
  ['valid', contract, 0, 'EX-05消费映射'],
  ['original-byte-drift', Buffer.concat([Buffer.from('!'), contract.subarray(1)]), 1, '冻结契约原文被改'],
  ['missing-addendum', contract.subarray(0, 63602), 1, '增补缺失'],
  ['duplicate-marker', Buffer.from(text + marker), 1, '增补缺失、重复或边界漂移'],
  ['shifted-boundary', Buffer.concat([contract.subarray(0, 63602), Buffer.from(' '), contract.subarray(63602)]), 1, '边界漂移'],
  ['unframed-tail', Buffer.from(text + 'untracked tail'), 1, '结束边界无效'],
]
const mapping = JSON.parse(contract.subarray(63602).toString('utf8').match(/```json\r\n([\s\S]*?)\r\n```\r\n<!-- EX-05-CONSUMPTION-END -->/)[1])
for (const key of Object.keys(mapping)) {
  const bad = structuredClone(mapping)
  delete bad[key]
  cases.push([`missing-${key}`, Buffer.from(text.replace(JSON.stringify(mapping, null, 2).replaceAll('\n', '\r\n'), JSON.stringify(bad, null, 2).replaceAll('\n', '\r\n'))), 1, `漂移：${key}`])
}
for (const [name, key, field, value] of [
  ['bearer-fallback', 'authentication', 'authorizationHeader', true],
  ['offset-as-page', 'pagination', 'exposure.pagination.offsetParam', 'pagination.request.page'],
  ['automatic-paging-claim', 'pagination', 'automaticPaging', true],
  ['risk-downgrade', 'exposure', 'riskDowngrade', 'ALLOW'],
]) {
  const bad = structuredClone(mapping)
  bad[key][field] = value
  cases.push([name, Buffer.from(text.replace(JSON.stringify(mapping, null, 2).replaceAll('\n', '\r\n'), JSON.stringify(bad, null, 2).replaceAll('\n', '\r\n'))), 1, `漂移：${key}`])
}
const scratch = await mkdtemp(path.join(root, '.local/ex05-consumption-'))
const input = path.join(scratch, 'skill.md')
const results = []
try {
  for (const [name, bytes, expectedExit, diagnostic] of cases) {
    await writeFile(input, bytes)
    const run = spawnSync(process.execPath, ['scripts/verify-contracts.mjs', '--skill-contract', input], { cwd: root, encoding: 'utf8', windowsHide: true })
    assert.ifError(run.error)
    assert.equal(run.status, expectedExit, `${name}: ${run.stdout}${run.stderr}`)
    assert.ok((run.stdout + run.stderr).includes(diagnostic), `${name}: missing diagnostic`)
    results.push({ name, inputSha256: hash(bytes), nativeExit: run.status, diagnostic, pass: true })
  }
  for (const [name, diagnostic] of [
    ['missing-tool-binding', '能力包.tools[0].binding'],
    ['identity-plaintext', '能力包.identity.secrets[0].ref'],
    ['unknown-kind', '能力包.interfaces[0].kind'],
  ]) {
    const fixture = `docs/evidence/2026-10-29-EX-09/fixtures/invalid-${name}.json`
    const run = spawnSync(process.execPath, ['scripts/verify-contracts.mjs', '--file', fixture], { cwd: root, encoding: 'utf8', windowsHide: true })
    assert.ifError(run.error)
    assert.equal(run.status, 1, `${name}: old EX-09 negative must remain rejected`)
    assert.ok((run.stdout + run.stderr).includes(diagnostic), `${name}: EX-09 diagnostic changed`)
    results.push({ name: `EX09-${name}`, inputSha256: hash(await readFile(path.join(root, fixture))), nativeExit: run.status, diagnostic, pass: true })
  }
  await writeFile(new URL('consumption-checks.json', import.meta.url), JSON.stringify(results, null, 2) + '\n')
  console.log(`Consumption contract checks: ${results.length} passed; frozen prefix unchanged`)
} finally {
  await unlink(input)
  await rmdir(scratch)
}
