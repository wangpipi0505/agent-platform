import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';

const directory = import.meta.dirname, root = path.resolve(directory, '../../../..');
const read = async file => JSON.parse(await fs.readFile(path.join(root, file), 'utf8'));
const relative = path.relative(root, directory).replaceAll('\\', '/');
const hash = async file => crypto.createHash('sha256').update(await fs.readFile(path.join(root, file))).digest('hex');
const result = await read(relative + '/result.json'), records = await read(relative + '/processes.json');
const readonly = await read('docs/evidence/algorithm-process-live-v5-readonly.json');
const cleanup = await read('docs/evidence/algorithm-process-live-v5-cleanup-readonly.json');
const manifest = await read('docs/evidence/algorithm-process-runner-time-precision-fix-sha256.json');
assert.equal(result.passed, true); assert.equal(result.scenarios.length, 5);
assert.equal(readonly.exit, 0); assert.equal(cleanup.allAbsent, true);
for (const rows of [readonly.data.runs, readonly.data.calls, readonly.data.facts, readonly.data.bindings]) assert.equal(rows.length, 5);
for (const scenario of result.scenarios) {
  const view = scenario.afterRecheck ?? scenario.after;
  const run = readonly.data.runs.find(row => row.public_id === scenario.runId);
  const call = readonly.data.calls.find(row => row.id === scenario.callId);
  const fact = readonly.data.facts.find(row => row.call_id === scenario.callId);
  const binding = readonly.data.bindings.find(row => row.call_id === scenario.callId);
  assert.equal(run.status, view.run.status);
  for (const [row, captured, keys] of [
    [call, view.call, ['id', 'run_id', 'node_instance_id', 'attempt_id', 'claimed_by', 'idempotency_key', 'readonly_retry_count', 'status']],
    [fact, view.fact, ['run_id', 'call_id', 'attempt_id', 'execution_owner', 'is_mock', 'call_kind', 'status']],
    [binding, view.binding, ['run_id', 'call_id', 'attempt_id', 'idempotency_key', 'external_job_uid', 'external_job_id', 'external_namespace', 'status']],
  ]) for (const key of keys) assert.deepEqual(row[key], captured[key], scenario.mode + ' PG field ' + key);
  assert.deepEqual(fact.request_snapshot, view.fact.request_snapshot);
  assert.deepEqual(binding.request_snapshot, view.binding.request_snapshot);
  const attempts = readonly.data.attempts.filter(row => row.node_instance_id === call.node_instance_id);
  assert.deepEqual(attempts, view.attemptRows, scenario.mode + ' complete local attempt rows');
  const node = readonly.data.nodes.find(row => row.id === call.node_instance_id);
  assert.equal(node.committed_attempt_id, view.node.committed_attempt_id);
  const notes = readonly.data.notes.filter(row => row.node_instance_id === node.id && row.kind === 'TOOL_EXECUTION_FACT' && row.payload.accepted && ['SUCCEEDED', 'FAILED'].includes(row.payload.status));
  assert.deepEqual(notes, view.terminalNotes);
  const commits = readonly.data.events.filter(row => row.run_id === run.id && row.type === 'LOGIC_RESULT_COMMITTED' && row.payload.nodeInstanceId === node.id);
  assert.equal(commits.length, view.toolCommits);
}
const storedArtifact = readonly.data.artifacts[0], capturedArtifact = result.scenarios.find(s => s.mode === 'offload').artifact;
assert.equal(readonly.data.artifacts.length, 1);
for (const key of ['artifact_id', 'state', 'size_bytes', 'sha256', 'run_id', 'call_id', 'tenant_id', 'principal_id']) assert.deepEqual(storedArtifact[key], capturedArtifact[key]);
for (const [file, expected] of Object.entries(manifest.sources)) assert.equal(await hash(file), expected, 'Frozen source changed: ' + file);
const preserved = await read('docs/evidence/algorithm-process-runner-time-precision-fix/preserved-history.json');
for (const [file, expected] of Object.entries(preserved)) assert.equal(await hash(file), expected, 'Historical evidence changed: ' + file);
const scenarios = result.scenarios.map(scenario => ({ mode: scenario.mode, phase: scenario.phase, runId: scenario.runId, callId: scenario.callId,
  originalAttemptId: scenario.before?.call.attempt_id, executionOwner: scenario.before?.fact.execution_owner,
  nodeId: scenario.after?.node.id, localAttemptIds: scenario.after?.attemptRows.map(row => row.id),
  localAttemptNos: scenario.after?.attemptRows.map(row => row.attempt_no), localAttemptStatuses: scenario.after?.attemptRows.map(row => row.status),
  committedAttemptId: scenario.after?.node.committed_attempt_id, externalJobUid: scenario.after?.binding.external_job_uid,
  posts: scenario.fixtureAfter?.posts, jobGets: scenario.fixtureAfter?.jobGets, runStatus: scenario.after?.run.status,
  factStatus: scenario.after?.fact.status, bindingStatus: scenario.after?.binding.status, logicalCommits: scenario.after?.toolCommits,
  acceptedTerminalFacts: scenario.after?.acceptedTerminalFacts, afterRecheckCaptured: Boolean(scenario.afterRecheck),
  afterRecheckAttemptIds: scenario.afterRecheck?.attemptRows.map(row => row.id),
  cancellationMeaning: scenario.cancellationMeaning, artifactState: scenario.artifact?.state, artifactId: scenario.artifact?.artifact_id,
  artifactSize: scenario.artifact?.size_bytes, downloadSha256: scenario.downloadSha256 }));
const summary = { status: result.passed ? 'PASSED_PENDING_INDEPENDENT_POSTACTUAL_REVIEW' : 'FAILED', runnerExit: 0,
  runnerSha256: await hash('tests/smoke-algorithm-processes.mjs'), oracleSha256: await hash('tests/algorithm-process-attempt-scope.mjs'),
  fixedArtifacts: result.artifacts, database: result.database, databaseRetained: true, rawDirectory: relative,
  startedAt: result.startedAt, finishedAt: result.finishedAt, scenarios, passedScenarios: scenarios.filter(s => s.phase === 'passed').length,
  rejectedUpstreamRequests: result.rejectedUpstreamRequests, businessInitialSubmissionRetried: false, codeOrBudgetChanged: false,
  readonlyPG: { exit: readonly.exit, runs: readonly.data.runs.length, calls: readonly.data.calls.length, facts: readonly.data.facts.length,
    bindings: readonly.data.bindings.length, artifacts: readonly.data.artifacts.map(row => ({ artifactId: row.artifact_id, state: row.state, size: row.size_bytes, sha256: row.sha256 })) },
  finallyCleanupSucceeded: result.passed && records.every(record => record.final?.pid === null && record.final.listenerPids.length === 0 && !record.finalInspectionError),
  privateChildren: records.map(record => ({ pid: record.pid, port: record.port, module: record.module, stopped: record.stopped, final: record.final })),
  postCleanupAllAbsent: cleanup.allAbsent, postactualPgMatchesFinalCaptures: true, frozenSourceChecks: Object.keys(manifest.sources).length, historicalEvidenceChecks: Object.keys(preserved).length,
  exclusions: result.exclusions, evidenceSha256: {} };
const offload = scenarios.find(scenario => scenario.mode === 'offload');
const report = `# 算法真实进程纵切 V5

本轮固定 runner/oracle 与 root 移交的 962 engine/skill 制品实际执行，五个有限场景均通过，runner exit=0。结果仍待 root 与原独立 reviewer 事后复核。没有修改源码、请求、原 4 秒期限（offload 15 秒）、环境、fixture 或清理预算；未重试任何初次业务提交。

命令：\
\
\`node tests/smoke-algorithm-processes.mjs --execute-reviewed .local/algorithm-reviewed-artifact-lock.json\`

数据库：\`${result.database}\`，为本轮唯一新库，保留追溯。原始目录：\`${relative}\`。真实服务为每场景新建的一对私有 engine/skill；K8s 使用独立 namespace 的有限 HTTP fixture。

| 场景 | run / call | 原外部 attempt | 本地 attempts / 提交 attempt | Job UID | POST / 逻辑提交 / 终态 note | 终态 |
|---|---|---|---|---|---|---|
${scenarios.map(s => `| ${s.mode} | ${s.runId} / ${s.callId} | ${s.originalAttemptId} | ${s.localAttemptIds.join(',')} / ${s.committedAttemptId ?? '无'} | ${s.externalJobUid} | ${s.posts} / ${s.logicalCommits} / ${s.acceptedTerminalFacts} | ${s.runStatus}; fact ${s.factStatus}; binding ${s.bindingStatus} |`).join('\n')}

Complete 记录先 GET 404、单次创建、可信 Pod/controller/algorithm 容器与完整 JSON；ACK 丢失仅对账原 Job，恰一次本地 no2 消费原成功事实，原 call/fact/binding/snapshot/结果 owner 均保持原外部 attempt。可信 Failed 以 NON_RETRYABLE_FAILURE 交付且不构成 NOT_APPLIED。以上与 offload 都执行 controlledCallId 重检并重新 capture；逐行集合、原身份、UID、终态 note 和逻辑提交仍满足冻结门禁。

Running 对账仍 UNKNOWN，随后只取消本地 run 为 CANCELLED，原 fact/call 仍 UNKNOWN、binding SUBMITTED，逻辑提交与终态 note 为 0。取消不表示删除 Job、停止集群执行或撤销效果。该场景没有额外添加重检请求，保留原测试序列。

Offload 实际输出 ${offload.artifactSize} 字节（outputMaxBytes=1024），真实 MinIO 上传/回读后 artifact=${offload.artifactId}、state=${offload.artifactState}；受控下载内容逐字匹配且 SHA=${offload.downloadSha256}，fact structured=null、唯一 fileRef 指向该 artifact。事后 PG artifact 行另存只读快照。

全部场景 fixture POST=1；未用上游拒绝端点请求数=${result.rejectedUpstreamRequests}。事后仅对本轮库做 BEGIN READ ONLY + default_transaction_read_only、statement_timeout=5s、连接/进程有界的完整行快照，得到 ${readonly.data.runs.length} runs / ${readonly.data.calls.length} calls / ${readonly.data.facts.length} facts / ${readonly.data.bindings.length} bindings / ${readonly.data.artifacts.length} artifact。原始 SQL、stdout、stderr、命令参数边界与执行状态均保存；真实凭据仅由本工程 .env 注入环境且脱敏。

10 个私有 JVM 每次 HTTP 前都通过 exact PID/creation/JAR/TCP 检查，逐项 finally 停止；最终 records 及另一次只读 PID/TCP 核验均无本轮进程或端口残留。fixture 均按原预算关闭；没有接管六个主服务，没有 drop 新库。清理详情见 processes.json 和 algorithm-process-live-v5-cleanup-readonly.json。没有业务错误或清理错误被省略。

runner SHA=${summary.runnerSha256}；oracle SHA=${summary.oracleSha256}。固定 engine SHA=d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7，skill SHA=2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196。冻结 16 source SHA 与此前 305 份历史证据 SHA 均复核未变，V1–V4 原失败事实保持。

本结果证明隔离的真实 JVM + PG + MinIO 与有限 K8s HTTP fixture 路径；不证明实际集群/容器/RBAC/生产 IAM、容量、多副本恢复或全部 G2/功能验收。
`;
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-live-v5.md'), report, { flag: 'wx' });
for (const entry of await fs.readdir(directory, { withFileTypes: true })) if (entry.isFile()) summary.evidenceSha256[relative + '/' + entry.name] = await hash(relative + '/' + entry.name);
for (const file of ['docs/evidence/algorithm-process-live-v5.md', 'docs/evidence/algorithm-process-live-v5-execution.log', 'docs/evidence/algorithm-process-live-v5-readonly.json', 'docs/evidence/algorithm-process-live-v5-cleanup-readonly.json']) summary.evidenceSha256[file] = await hash(file);
await fs.writeFile(path.join(root, 'docs/evidence/algorithm-process-live-v5-summary.json'), JSON.stringify(summary, null, 2), { flag: 'wx' });
console.log(JSON.stringify({ status: summary.status, passedScenarios: summary.passedScenarios, evidenceFiles: Object.keys(summary.evidenceSha256).length, finallyCleanupSucceeded: summary.finallyCleanupSucceeded, postCleanupAllAbsent: cleanup.allAbsent }));
