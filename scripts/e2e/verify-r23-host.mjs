import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const host = process.env.R23_HOST_URL;
const expectedText = process.env.R23_EXPECTED_RESULT_TEXT;
assert(host && expectedText, 'Set R23_HOST_URL and R23_EXPECTED_RESULT_TEXT');
const base = new URL(host);
assert(['https:', 'http:'].includes(base.protocol) && !base.username && !base.password,
  'R23_HOST_URL must be an HTTP(S) origin');
assert.equal(base.href, base.origin + '/', 'R23_HOST_URL must not contain a path or query');

async function call(path, body) {
  const response = await fetch(new URL('/agent-platform/' + path, base), {
    method: body ? 'POST' : 'GET',
    ...(body && { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
    redirect: 'error',
    signal: AbortSignal.timeout(45_000),
  });
  return { status: response.status, data: await response.json() };
}

const health = await call('health');
assert(health.status === 200 && health.data.integration === 'READY' && health.data.ticketMode === true,
  'The independent host must have its short-ticket integration configured');
const downloadArtifacts = process.env.R23_HOST_DOWNLOAD === 'true';
const request = { requestId: 'r23-' + randomUUID(), goal: process.env.R23_TASK_GOAL || 'first', downloadArtifacts };
const result = await call('dispatch', request);
assert.equal(result.status, 200, 'Host dispatch HTTP status');
assert.equal(result.data.state, 'SUCCEEDED', 'Host task status');
assert.equal(result.data.resultState, 'SUCCEEDED', 'Platform result status');
assert(typeof result.data.taskId === 'string' && /^task-[a-z0-9-]+$/.test(result.data.taskId),
  'Platform must return a business task ID');
assert(result.data.result?.text === expectedText, 'Host must preserve the expected structured result');
assert(Array.isArray(result.data.events) && result.data.events.length > 0, 'Host must deliver task events');
assert(Array.isArray(result.data.artifacts), 'Host must preserve the artifact list');
if (downloadArtifacts) {
  assert(result.data.artifacts.length > 0, 'Expected a file receipt from the released application');
  assert(Array.isArray(result.data.downloads), 'Host must report verified downloads');
  assert.equal(result.data.downloads.length, result.data.artifacts.length, 'Host must verify every file');
  for (const [index, receipt] of result.data.artifacts.entries()) {
    assert.equal(receipt.publishedDigest, process.env.R23_PUBLISHED_DIGEST, 'Expected the recorded publication digest');
    assert.deepEqual(result.data.downloads[index], {
      artifactId: receipt.artifactId, bytes: receipt.bytes, digest: receipt.digest, etag: `"${receipt.digest}"`,
    }, 'Host must verify the downloaded bytes and ETag against the receipt');
  }
}
const retry = await call('dispatch', request);
assert(retry.status === 200 && retry.data.taskId === result.data.taskId, 'A retry must reuse the task ID');
const conflict = await call('dispatch', { ...request, goal: request.goal + ' (changed)' });
assert(conflict.status === 409 && conflict.data.code === 'REQUEST_REPLAY',
  'A retry with a different goal must be rejected');

console.log(JSON.stringify({
  taskId: result.data.taskId,
  state: result.data.state,
  eventCount: result.data.events.length,
  artifactCount: result.data.artifacts.length,
  ...(downloadArtifacts && { downloads: result.data.downloads }),
  replayStatus: retry.status,
  conflictStatus: conflict.status,
}));
