import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { assertScenarioTerminal } from './smoke-algorithm-processes.mjs';

test('the actual PowerShell helper round-trips a Chinese reviewed JAR path without inspecting any PID', async () => {
  const source = await fs.readFile(new URL('./smoke-algorithm-processes.mjs', import.meta.url), 'utf8');
  const start = source.indexOf('function powershell('), end = source.indexOf('export const ownershipScripts', start);
  assert(start >= 0 && end > start, 'Extract the actual bounded PowerShell helper');
  const powershell = new Function('spawnSync', 'assert', source.slice(start, end) + '\nreturn powershell;')(spawnSync, assert);
  const expected = 'G:\\测试目录\\算法重组工具\\审查制品\\skill-registry.jar';
  const result = powershell('$env:ALGORITHM_TEST_UNICODE | ConvertTo-Json -Compress', { ALGORITHM_TEST_UNICODE: expected });
  assert.equal(JSON.parse(result), expected);
});

function originalScenario() {
  const fact = { attempt_id: 7, execution_owner: 'original-owner', call_kind: 'DEBUG', status: 'SUCCEEDED', result: { structured: { value: 'ok' } } };
  const binding = { external_job_uid: 'original-job-uid', status: 'DONE', metadata: { terminalEvidence: { condition: 'Complete' } } };
  return { before: { fact: structuredClone(fact), binding: structuredClone(binding) }, after: { fact, binding, toolCommits: 1 }, recheck: { ok: true } };
}

for (const [field, replacement] of [['attempt_id', 8], ['execution_owner', 'replacement-owner'], ['call_kind', 'FORMAL']]) {
  test('final fact cannot change original ' + field, () => {
    const scenario = originalScenario();
    assert.doesNotThrow(() => assertScenarioTerminal('complete', scenario, { jobUid: 'original-job-uid' }, '{"value":"ok"}'));
    scenario.after.fact[field] = replacement;
    assert.throws(() => assertScenarioTerminal('complete', scenario, { jobUid: 'original-job-uid' }, '{"value":"ok"}'),
      /Original fact changed/, 'An otherwise valid terminal result cannot hide fact replacement');
  });
}
