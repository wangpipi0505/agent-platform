import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

const digest = text => createHash('sha256').update(text).digest('hex');
const timestamp = value => {
  const parts = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  assert(parts, 'Missing PG evidence time');
  // Parse only whole seconds through Date; PG fractional microseconds must never be rounded to milliseconds.
  const seconds = Date.parse(parts[1] + parts[3]);
  assert(Number.isFinite(seconds), 'Missing PG evidence time');
  return BigInt(seconds) * 1000n + BigInt((parts[2] || '').padEnd(6, '0'));
};

// Oracle for these five finite scenarios only: a node re-claim never authorizes another business call.
export function assertAttemptScope(mode, scenario, observed, fixture) {
  const before = scenario.before, original = before.call, externalAttempt = original.attempt_id;
  const owner = original.claimed_by, nodeId = original.node_instance_id;
  assert.equal(original.id, scenario.callId); assert.equal(before.run.publicId, scenario.runId);
  const expectedOwner = { tenantId: before.fact.tenant_id, principalId: before.fact.principal_id,
    runId: before.fact.run_id, nodeInstanceId: nodeId, attemptId: externalAttempt, callId: original.id,
    skillVersionId: original.skill_version_id, skillCode: original.skill_code };
  const nodeKey = digest('node:' + nodeId);
  assert.equal(original.idempotency_key, digest([original.run_id, nodeId, original.call_seq, original.skill_version_id].join(':')), 'Original external call key');
  assert.equal(before.attemptRows.length, 1, 'Exactly one original node claim');
  assert.equal(before.attemptRows[0].id, externalAttempt); assert.equal(before.attemptRows[0].attempt_no, 1);
  assert.equal(before.attemptRows[0].idempotency_key, nodeKey, 'Node key is independent of external call key');
  for (const view of [before, observed]) {
    for (const [plural, singular, count] of [['calls', 'call', 'runCalls'], ['facts', 'fact', 'runFacts'], ['bindings', 'binding', 'runBindings']]) {
      assert.equal(view[plural].length, 1, 'Exactly one original ' + singular);
      assert.equal(view[count], 1); assert.deepEqual(view[singular], view[plural][0], 'Scalar alias must match captured set');
    }
    assert.deepEqual(view.tenantRuns, before.tenantRuns, 'No new run during observation/recheck');
    assert.equal(view.tenantRuns.filter(run => run.id === original.run_id && run.publicId === before.run.publicId).length, 1);
    for (const field of ['id', 'publicId', 'tenantId', 'principalId']) assert.equal(view.run[field], before.run[field], 'Original run changed: ' + field);
    for (const field of ['id', 'run_id', 'node_instance_id', 'call_seq', 'skill_version_id', 'skill_code', 'args_digest', 'skill_contract_sha256', 'idempotency_key', 'claimed_by', 'attempt_id', 'args'])
      assert.deepEqual(view.call[field], original[field], 'Original call changed: ' + field);
    for (const field of ['tenant_id', 'principal_id', 'run_id', 'call_id', 'skill_version_id', 'attempt_id', 'execution_owner', 'args_digest', 'skill_contract_sha256', 'call_kind', 'is_mock', 'request_snapshot'])
      assert.deepEqual(view.fact[field], before.fact[field], 'Original fact changed: ' + field);
    for (const field of ['tenant_id', 'principal_id', 'run_id', 'call_id', 'skill_version_id', 'attempt_id', 'idempotency_key', 'external_api_sha256', 'external_namespace', 'external_job_id', 'external_identity_sha256', 'request_snapshot'])
      assert.deepEqual(view.binding[field], before.binding[field], 'Original binding changed: ' + field);
    for (const field of ['protocol', 'image', 'serviceAccount', 'contractSha256'])
      assert.equal(view.binding.metadata[field], before.binding.metadata[field], 'Original binding metadata changed: ' + field);
    assert.equal(view.fact.call_kind, 'DEBUG'); assert.equal(view.fact.is_mock, false); assert.equal(view.call.is_mock, false);
    assert.equal(view.call.readonly_retry_count, 0); assert.equal(view.binding.send_started, true);
    assert.equal(view.run.id, original.run_id); assert.equal(view.run.tenantId, view.fact.tenant_id); assert.equal(view.run.principalId, view.fact.principal_id);
    assert.equal(view.fact.call_id, original.id); assert.equal(view.fact.run_id, view.run.publicId);
    assert.equal(view.fact.skill_version_id, original.skill_version_id); assert.equal(view.fact.attempt_id, externalAttempt); assert.equal(view.fact.execution_owner, owner);
    assert.equal(view.fact.args_digest, original.args_digest); assert.equal(view.fact.skill_contract_sha256, original.skill_contract_sha256);
    assert.equal(view.binding.tenant_id, view.fact.tenant_id); assert.equal(view.binding.principal_id, view.fact.principal_id);
    assert.equal(view.binding.run_id, view.run.publicId); assert.equal(view.binding.call_id, original.id); assert.equal(view.binding.skill_version_id, original.skill_version_id);
    assert.equal(view.binding.attempt_id, externalAttempt); assert.equal(view.binding.idempotency_key, original.idempotency_key);
    assert.equal(view.binding.metadata.contractSha256, original.skill_contract_sha256);
    assert.equal(view.binding.external_namespace, scenario.namespace); assert.equal(view.binding.external_job_id, scenario.fixtureBefore.jobName);
    for (const snapshot of [view.fact.request_snapshot, view.binding.request_snapshot]) {
      for (const [field, expected] of Object.entries({ attemptId: externalAttempt, leaseOwner: owner, runId: view.run.publicId,
        callId: original.id, nodeInstanceId: nodeId, callSeq: original.call_seq, skillVersionId: original.skill_version_id,
        idempotencyKey: original.idempotency_key, skillCode: original.skill_code })) assert.equal(snapshot[field], expected, 'Original snapshot identity: ' + field);
      assert.deepEqual(snapshot.arguments, original.args);
    }
    assert.deepEqual(view.fact.result.owner, expectedOwner); assert.deepEqual(view.call.result.owner, expectedOwner);
    assert.equal(view.node.id, nodeId); assert.equal(view.node.run_id, original.run_id); assert.equal(view.node.node_type, 'SKILL');
    assert.equal(view.node.node_def_id, before.node.node_def_id);
    assert.equal(view.attempts, view.attemptRows.length);
    assert.equal(view.attemptRows.filter(attempt => attempt.id === externalAttempt).length, 1, 'Original external attempt must bind exactly one node row');
    view.attemptRows.forEach((attempt, index) => {
      assert.equal(attempt.node_instance_id, nodeId); assert.equal(attempt.attempt_no, index + 1);
      assert.equal(attempt.idempotency_key, nodeKey, 'Every local row retains its node key');
    });
    assert.equal(view.acceptedTerminalFacts, view.terminalNotes.length);
    for (const note of view.terminalNotes) {
      assert.equal(note.kind, 'TOOL_EXECUTION_FACT'); assert.equal(note.node_instance_id, nodeId); assert.equal(note.attempt_id, externalAttempt);
      for (const [field, expected] of Object.entries({ accepted: true, callId: original.id, attemptId: externalAttempt, owner, isMock: false, status: view.fact.status }))
        assert.equal(note.payload[field], expected, 'Original terminal note: ' + field);
      assert.deepEqual(note.payload.result, view.fact.result); assert.deepEqual(note.payload.result.owner, expectedOwner); timestamp(note.created_at);
    }
    view.events.forEach(event => { assert.equal(event.run_id, original.run_id); timestamp(event.created_at); });
  }
  assert.deepEqual(observed.attemptRows[0], before.attemptRows[0], 'Original node attempt remains unchanged');
  assert.deepEqual(observed.events.slice(0, before.events.length), before.events, 'Original event history is retained');
  const events = observed.events.filter(event => event.payload.nodeInstanceId === nodeId);
  const starts = events.filter(event => event.type === 'NODE_STARTED'), commits = events.filter(event => event.type === 'LOGIC_RESULT_COMMITTED');
  const ready = events.filter(event => event.type === 'EXTERNAL_READY');
  assert.equal(starts.length, observed.attemptRows.length, 'NODE_STARTED exactly maps every local row');
  starts.forEach((event, index) => {
    assert.equal(event.payload.attemptId, observed.attemptRows[index].id); assert.equal(event.payload.attemptNo, index + 1);
  });
  const success = !['running', 'failed'].includes(mode), expectedStatus = mode === 'failed' ? 'FAILED' : mode === 'running' ? 'UNKNOWN' : 'SUCCEEDED';
  assert.equal(observed.fact.status, expectedStatus); assert.equal(observed.call.status, expectedStatus);
  assert.equal(observed.run.status, mode === 'running' ? 'CANCELLED' : expectedStatus);
  assert.equal(observed.node.status, mode === 'running' ? 'CANCELLED' : expectedStatus);
  assert.equal(observed.binding.status, mode === 'running' ? 'SUBMITTED' : 'DONE');
  assert.equal(observed.toolCommits, success ? 1 : 0); assert.equal(commits.length, observed.toolCommits);
  assert.equal(observed.terminalNotes.length, mode === 'running' ? 0 : 1);
  if (mode === 'ack-loss') {
    assert.equal(before.run.status, 'WAITING_EXTERNAL'); assert.equal(before.call.status, 'UNKNOWN'); assert.equal(before.fact.status, 'UNKNOWN');
    assert.equal(before.attemptRows[0].status, 'UNKNOWN'); assert.equal(before.toolCommits, 0); assert.equal(before.terminalNotes.length, 0);
    assert.equal(before.node.committed_attempt_id, null);
    assert.equal(observed.attemptRows.length, 2, 'Only one local recovery claim is permitted');
    const local = observed.attemptRows[1]; assert.equal(local.status, 'SUCCEEDED'); assert.notEqual(local.id, externalAttempt);
    assert.equal(observed.node.committed_attempt_id, local.id); assert.equal(commits[0].payload.attemptId, local.id);
    assert.deepEqual(observed.node.output, observed.fact.result.structured, 'Local recovery consumes original stored structured output');
    assert.equal(ready.length, 1);
    assert(ready[0].seq < starts[1].seq && starts[1].seq < commits[0].seq, 'Local recovery event order');
    const times = [observed.terminalNotes[0].created_at, ready[0].created_at, starts[1].created_at, commits[0].created_at].map(timestamp);
    assert(times.every((time, index) => index === 0 || time >= times[index - 1]), 'Terminal note precedes local recovery');
  } else {
    assert.equal(observed.attemptRows.length, 1, 'Non-recovery scenarios retain one node attempt'); assert.equal(ready.length, 0);
    if (success) { assert.equal(observed.node.committed_attempt_id, externalAttempt); assert.equal(commits[0].payload.attemptId, externalAttempt); }
    else assert.equal(observed.node.committed_attempt_id, null);
  }
  const originalFixture = scenario.fixtureBefore;
  assert.equal(typeof originalFixture.jobUid, 'string'); assert(originalFixture.jobUid.length > 0);
  assert.equal(fixture.jobUid, originalFixture.jobUid); assert.equal(fixture.jobName, originalFixture.jobName);
  assert.equal(originalFixture.posts, 1); assert.equal(fixture.posts, 1); assert.equal(fixture.denied, 0); assert.equal(fixture.authRejected, 0);
  const jobPath = '/apis/batch/v1/namespaces/' + scenario.namespace + '/jobs';
  const podPath = '/api/v1/namespaces/' + scenario.namespace + '/pods';
  assert.equal(originalFixture.routes[0], 'GET ' + jobPath + '/' + originalFixture.jobName);
  assert.equal(originalFixture.routes[1], 'POST ' + jobPath);
  const allowed = new Set(['GET ' + jobPath + '/' + originalFixture.jobName, 'POST ' + jobPath, 'GET ' + podPath, 'GET ' + podPath + '/algorithm-proof-pod/log']);
  assert.deepEqual(fixture.routes.slice(0, originalFixture.routes.length), originalFixture.routes);
  assert.equal(fixture.routes.filter(route => route === 'POST ' + jobPath).length, 1); assert(fixture.routes.every(route => allowed.has(route)), 'Only original Job routes');
  if (before.binding.external_job_uid != null) assert.equal(before.binding.external_job_uid, originalFixture.jobUid);
  assert.equal(observed.binding.external_job_uid, originalFixture.jobUid, 'First UID adoption is pinned to fixture-before');
  if (mode !== 'running') {
    const terminal = observed.binding.metadata.terminalEvidence;
    for (const [field, expected] of Object.entries({ externalJobId: observed.binding.external_job_id, externalJobUid: originalFixture.jobUid,
      namespace: observed.binding.external_namespace, apiSha256: observed.binding.external_api_sha256, identitySha256: observed.binding.external_identity_sha256,
      condition: mode === 'failed' ? 'Failed' : 'Complete' })) assert.equal(terminal[field], expected, 'Terminal binding evidence: ' + field);
  }
  if (scenario.after && observed !== scenario.after) {
    assert.deepEqual(observed.attemptRows, scenario.after.attemptRows, 'Recheck changed local attempts');
    assert.deepEqual(observed.terminalNotes, scenario.after.terminalNotes, 'Recheck changed terminal adoption');
    assert.deepEqual(observed.fact.result, scenario.after.fact.result, 'Recheck changed original result');
    for (const field of ['committed_attempt_id', 'committed_output_ref', 'output_digest', 'output'])
      assert.deepEqual(observed.node[field], scenario.after.node[field], 'Recheck changed node submission: ' + field);
  }
}
