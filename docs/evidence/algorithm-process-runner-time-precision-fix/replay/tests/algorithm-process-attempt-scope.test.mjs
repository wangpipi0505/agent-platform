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
    view.tenantRuns = pg.runs.map(run => ({ id: run.id, publicId: run.runId }));
    view.calls = [view.call]; view.facts = [view.fact]; view.bindings = [view.binding]; view.runBindings = 1;
    view.node = structuredClone(node);
    view.attemptRows = stage === 'before' ? [structuredClone(original)] : structuredClone(pg.attempts.filter(attempt => attempt.node_instance_id === node.id));
    view.terminalNotes = stage === 'before' ? [] : structuredClone(pg.notes.filter(note => note.node_instance_id === node.id && note.kind === 'TOOL_EXECUTION_FACT' && note.payload.accepted && ['SUCCEEDED', 'FAILED'].includes(note.payload.status)));
    view.events = structuredClone(stage === 'before' ? events.filter(event => event.seq <= waiting.seq) : events);
  }
  // v4 did not capture node-before or any afterRecheck. These extra fields are synthetic test
  // enrichment; after/node/attempt/note/event rows and the old attempts=2 failure are archived PG.
  Object.assign(scenario.before.node, { status: 'WAITING_EXTERNAL', committed_attempt_id: null, committed_output_ref: null, output_digest: null,
    output: null, ended_at: null, lease_owner: scenario.before.call.claimed_by, lease_until: scenario.before.call.claimed_lease });
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

const rejected = (name, mutate, recheckOnly = false) => test(name + (recheckOnly ? ' (synthetic afterRecheck only)' : ''), () => {
  const scenario = sample(), currentFixture = structuredClone(fixture);
  assert.doesNotThrow(() => validate('ack-loss', scenario, scenario.after, currentFixture));
  const view = recheckOnly ? scenario.afterRecheck : scenario.after;
  mutate(scenario, view, currentFixture);
  assert.throws(() => validate('ack-loss', scenario, view, currentFixture));
});

const third = (_scenario, view) => { view.attemptRows.push({ ...view.attemptRows[1], id: 999, attempt_no: 3 }); view.attempts++; };
const duplicateCommit = (_scenario, view) => { view.events.push({ ...view.events.find(event => event.type === 'LOGIC_RESULT_COMMITTED' && event.payload.nodeInstanceId === view.node.id), id: 999, seq: 999 }); view.toolCommits++; };
const duplicateNote = (_scenario, view) => { view.terminalNotes.push(structuredClone(view.terminalNotes[0])); view.acceptedTerminalFacts++; };
const newCall = (_scenario, view) => { view.calls.push({ ...view.call, id: 999 }); view.runCalls++; };
const newBinding = (_scenario, view) => { view.bindings.push({ ...view.binding, call_id: 999 }); view.runBindings++; };

rejected('third local attempt is not a permitted recovery', third);
rejected('wrong committed attempt is rejected', (_s, view) => { view.node.committed_attempt_id = view.call.attempt_id; });
rejected('node attempt key cannot be replaced by the external call key', (_s, view) => { view.attemptRows[1].idempotency_key = view.call.idempotency_key; });
rejected('the original UNKNOWN row must not be rewritten to success', (_s, view) => { view.attemptRows[0].status = 'SUCCEEDED'; });
rejected('EXTERNAL_READY must precede the second NODE_STARTED', (_s, view) => {
  view.events.find(event => event.type === 'EXTERNAL_READY').seq = view.events.find(event => event.type === 'NODE_STARTED' && event.payload.attemptNo === 2).seq + 1;
});
rejected('terminal note time cannot follow the local recovery claim', (_s, view) => { view.terminalNotes[0].created_at = '2099-01-01T00:00:00Z'; });
rejected('logic commit cannot reference the external original attempt', (_s, view) => {
  view.events.find(event => event.type === 'LOGIC_RESULT_COMMITTED' && event.payload.nodeInstanceId === view.node.id).payload.attemptId = view.call.attempt_id;
});
rejected('recovery node output must consume original structured data', (_s, view) => { view.node.output = { value: 'different' }; });

for (const [group, fields] of Object.entries({
  call: ['id', 'run_id', 'node_instance_id', 'call_seq', 'skill_version_id', 'skill_code', 'args_digest', 'skill_contract_sha256', 'idempotency_key', 'claimed_by', 'attempt_id'],
  fact: ['tenant_id', 'principal_id', 'run_id', 'call_id', 'skill_version_id', 'attempt_id', 'execution_owner', 'args_digest', 'skill_contract_sha256', 'call_kind'],
  binding: ['tenant_id', 'principal_id', 'run_id', 'call_id', 'skill_version_id', 'attempt_id', 'idempotency_key', 'external_api_sha256', 'external_namespace', 'external_job_id', 'external_identity_sha256'],
})) for (const field of fields) rejected(group + '.' + field + ' cannot drift', (_s, view) => {
  view[group][field] = typeof view[group][field] === 'number' ? 999 : 'replacement';
});
for (const group of ['fact', 'binding']) for (const field of ['attemptId', 'leaseOwner', 'runId', 'callId', 'nodeInstanceId', 'callSeq', 'skillVersionId', 'idempotencyKey', 'skillCode', 'arguments'])
  rejected(group + '.request_snapshot.' + field + ' cannot drift', (_s, view) => {
    view[group].request_snapshot[field] = typeof view[group].request_snapshot[field] === 'number' ? 999 : field === 'arguments' ? { other: true } : 'replacement';
  });
for (const field of ['externalJobId', 'externalJobUid', 'namespace', 'apiSha256', 'identitySha256', 'condition'])
  rejected('terminalEvidence.' + field + ' must match the original binding', (_s, view) => { view.binding.metadata.terminalEvidence[field] = 'replacement'; });
rejected('first UID adoption cannot use a different Job even when post snapshots agree', (_s, view, current) => { view.binding.external_job_uid = current.jobUid = 'replacement-uid'; });
rejected('a second external call is rejected', newCall);
rejected('a second binding is rejected', newBinding);
rejected('a second fact is rejected', (_s, view) => { view.facts.push({ ...view.fact, call_id: 999 }); view.runFacts++; });
rejected('a new run without a POST is still rejected', (_s, view) => { view.tenantRuns.push({ id: 999, publicId: 'r_other' }); });
rejected('business POST2 is rejected', (_s, _view, current) => { current.posts = 2; });
rejected('duplicate accepted terminal note is rejected', duplicateNote);
rejected('duplicate skill logical commit is rejected', duplicateCommit);
rejected('terminal note must retain original attempt', (_s, view) => { view.terminalNotes[0].attempt_id = view.node.committed_attempt_id; });
rejected('terminal note payload owner must retain original lease', (_s, view) => { view.terminalNotes[0].payload.owner = 'local-new-owner'; });
rejected('result.owner cannot drift to the local recovery attempt', (_s, view) => { view.fact.result.owner.attemptId = view.node.committed_attempt_id; });
rejected('an arbitrary snapshot field cannot be silently rewritten', (_s, view) => { view.binding.request_snapshot.newField = true; });

for (const [name, mutate] of [
  ['third attempt', third], ['new call', newCall], ['new binding', newBinding], ['duplicate note', duplicateNote], ['duplicate commit', duplicateCommit],
  ['owner drift', (_s, view) => { view.fact.execution_owner = 'replacement'; }],
  ['UID drift', (_s, view) => { view.binding.external_job_uid = 'replacement'; }],
  ['binding downgrade', (_s, view) => { view.binding.status = 'SENDING'; }],
  ['submission reference drift', (_s, view) => { view.node.committed_output_ref = 'other-reference'; }],
  ['output digest drift', (_s, view) => { view.node.output_digest = 'other-digest'; }],
]) rejected('recheck detects ' + name, mutate, true);

function syntheticNonRecovery(mode) {
  const scenario = sample(), view = scenario.after, success = ['complete', 'offload'].includes(mode);
  const status = mode === 'running' ? 'UNKNOWN' : mode === 'failed' ? 'FAILED' : 'SUCCEEDED';
  view.attemptRows = [structuredClone(view.attemptRows[0])]; view.attemptRows[0].status = status; view.attempts = 1;
  view.node.status = mode === 'running' ? 'CANCELLED' : status; view.node.committed_attempt_id = success ? view.call.attempt_id : null;
  if (!success) { view.node.committed_output_ref = null; view.node.output_digest = null; }
  view.run.status = mode === 'running' ? 'CANCELLED' : status; view.call.status = view.fact.status = status;
  view.fact.result.status = status;
  if (!success) { view.fact.result.structured = null; view.fact.result.error = { semantic: mode === 'failed' ? 'NON_RETRYABLE_FAILURE' : 'RESULT_UNKNOWN' }; }
  if (mode === 'offload') { view.fact.result.structured = null; view.fact.result.fileRefs = [{ artifactId: 'synthetic-only-artifact' }]; }
  view.call.result = structuredClone(view.fact.result); view.node.output = structuredClone(view.fact.result.structured);
  view.binding.status = mode === 'running' ? 'SUBMITTED' : 'DONE';
  if (mode === 'running') delete view.binding.metadata.terminalEvidence;
  else view.binding.metadata.terminalEvidence.condition = mode === 'failed' ? 'Failed' : 'Complete';
  view.toolCommits = success ? 1 : 0;
  view.terminalNotes = mode === 'running' ? [] : [structuredClone(view.terminalNotes[0])]; view.acceptedTerminalFacts = view.terminalNotes.length;
  if (view.terminalNotes.length) { view.terminalNotes[0].payload.status = status; view.terminalNotes[0].payload.result = structuredClone(view.fact.result); }
  const originalStart = view.events.find(event => event.type === 'NODE_STARTED' && event.payload.attemptId === view.call.attempt_id);
  const oldCommit = view.events.find(event => event.type === 'LOGIC_RESULT_COMMITTED' && event.payload.nodeInstanceId === view.node.id);
  view.events = [originalStart];
  if (success) view.events.push({ ...oldCommit, payload: { nodeInstanceId: view.node.id, attemptId: view.call.attempt_id } });
  scenario.before = structuredClone(view);
  if (mode === 'running') { scenario.before.run.status = scenario.before.node.status = 'WAITING_EXTERNAL'; }
  scenario.fixtureBefore = { ...structuredClone(fixture), routes: [...fixture.routes] };
  scenario.afterRecheck = structuredClone(view);
  return scenario;
}

for (const mode of ['complete', 'failed', 'running', 'offload']) {
  test('synthetic ' + mode + ' retains the non-recovery single-attempt rule', () => {
    const scenario = syntheticNonRecovery(mode);
    assert.doesNotThrow(() => validate(mode, scenario, scenario.after, fixture));
    assert.doesNotThrow(() => validate(mode, scenario, scenario.afterRecheck, fixture));
    const extra = { ...scenario.after.attemptRows[0], id: 999, attempt_no: 2 };
    scenario.after.attemptRows.push(extra); scenario.after.attempts++;
    assert.throws(() => validate(mode, scenario, scenario.after, fixture));
  });
}
