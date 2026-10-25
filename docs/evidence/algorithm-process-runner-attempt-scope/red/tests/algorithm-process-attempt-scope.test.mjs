import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import * as runner from './smoke-algorithm-processes.mjs';

const input = new URL('../docs/evidence/algorithm-process-runner-attempt-scope/inputs/', import.meta.url);
const read = async name => JSON.parse(await fs.readFile(new URL(name, input), 'utf8'));
const archived = await read('v4-ack-loss.json'), pg = (await read('algorithm-process-live-v4-readonly.json')).data;
const fixture = await read('v4-ack-fixture-final.json');
let validate = runner.assertAttemptScope;
if (!validate) {
  const old = await fs.readFile(new URL('./smoke-algorithm-processes.mjs', import.meta.url), 'utf8');
  const statement = old.match(/assert\.equal\(scenario\.after\.attempts, 1\);/)?.[0];
  assert(statement, 'Run the actual old total-count assertion, not an import failure');
  validate = (_mode, _scenario, view) => new Function('assert', 'scenario', statement)(assert, { after: view });
}

function sample() {
  const scenario = structuredClone(archived), call = scenario.after.call, node = pg.nodes.find(node => node.id === call.node_instance_id);
  const events = pg.events.filter(event => event.run_id === call.run_id), original = pg.attempts.find(attempt => attempt.id === scenario.before.call.attempt_id);
  const waiting = events.find(event => event.type === 'WAITING_EXTERNAL' && event.payload.nodeInstanceId === node.id);
  for (const [stage, view] of [['before', scenario.before], ['after', scenario.after]]) {
    view.run = { ...view.run, id: call.run_id, tenantId: view.fact.tenant_id, principalId: view.fact.principal_id };
    view.calls = [view.call]; view.facts = [view.fact]; view.bindings = [view.binding]; view.runBindings = 1;
    view.node = structuredClone(node);
    view.attemptRows = stage === 'before' ? [structuredClone(original)] : structuredClone(pg.attempts.filter(attempt => attempt.node_instance_id === node.id));
    view.terminalNotes = stage === 'before' ? [] : structuredClone(pg.notes.filter(note => note.node_instance_id === node.id && note.kind === 'TOOL_EXECUTION_FACT' && note.payload.accepted && ['SUCCEEDED', 'FAILED'].includes(note.payload.status)));
    view.events = structuredClone(stage === 'before' ? events.filter(event => event.seq <= waiting.seq) : events);
  }
  // v4 did not capture node-before or any afterRecheck. These extra fields are synthetic test
  // enrichment; after/node/attempt/note/event rows and the old attempts=2 failure are archived PG.
  Object.assign(scenario.before.node, { status: 'WAITING_EXTERNAL', committed_attempt_id: null, output: null, lease_owner: scenario.before.call.claimed_by });
  scenario.afterRecheck = structuredClone(scenario.after);
  scenario.syntheticAfterRecheck = true;
  return scenario;
}

test('actual v4 two local rows are accepted while the external call remains in its original generation', () => {
  const scenario = sample();
  assert.equal(scenario.after.attempts, 2);
  assert.doesNotThrow(() => validate('ack-loss', scenario, scenario.after, fixture));
  assert.doesNotThrow(() => validate('ack-loss', scenario, scenario.afterRecheck, fixture));
});
