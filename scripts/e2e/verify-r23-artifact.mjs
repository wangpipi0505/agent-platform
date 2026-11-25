import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const {
  R23_GATEWAY_URL: gateway, R23_API_KEY: apiKey, R23_TICKET_REQUEST_FILE: requestFile,
  R23_PUBLISHED_DIGEST: publishedDigest, R23_DOWNLOADED_FILE: output,
  R23_TASK_GOAL: goal = 'first', R23_ORIGIN: origin,
} = process.env;
assert(gateway && apiKey && requestFile && output && /^[0-9a-f]{64}$/.test(publishedDigest || ''),
  'Set R23_GATEWAY_URL, R23_API_KEY, R23_TICKET_REQUEST_FILE, R23_PUBLISHED_DIGEST and R23_DOWNLOADED_FILE');
const base = new URL(gateway);
assert(['http:', 'https:'].includes(base.protocol) && base.href === base.origin + '/',
  'R23_GATEWAY_URL must be an HTTP(S) origin');
const claims = JSON.parse(await readFile(requestFile, 'utf8'));
assert(/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(claims.appCode || ''), 'Ticket request must bind an application');

function request(path, authorization, body) {
  return fetch(new URL(path, base), {
    method: body ? 'POST' : 'GET',
    headers: {
      Authorization: authorization, ...(origin && { Origin: origin }),
      ...(body && { 'Content-Type': 'application/json' }),
    },
    body: body ? JSON.stringify(body) : undefined,
    redirect: 'error',
    signal: AbortSignal.timeout(15_000),
  });
}

async function json(path, authorization, body) {
  const response = await request(path, authorization, body);
  const envelope = await response.json();
  assert(response.status === 200 && envelope.code === 'OK', `${path} returned ${response.status} ${envelope.code}`);
  return envelope.data;
}

const mint = await json('/embed/ticket', 'ApiKey ' + apiKey, claims);
assert(typeof mint.ticket === 'string' && mint.ticket.length > 0, 'Expected an integration ticket');
const authorization = 'Bearer ' + mint.ticket;
const task = await json('/v1/apps/' + claims.appCode + '/tasks', authorization, {
  requestId: 'r23-file-' + randomUUID(), mode: 'AGENT', goal,
});
assert(/^task-[0-9a-f]{20}$/.test(task.taskId), 'Expected a public task ID');
const taskPath = '/v1/tasks/' + task.taskId;
const deadline = Date.now() + 30_000;
let state;
do {
  state = (await json(taskPath, authorization)).state;
  if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(state)) break;
  await delay(500);
} while (Date.now() < deadline);
assert.equal(state, 'SUCCEEDED', 'The task must reach SUCCEEDED before download');
const events = await json(taskPath + '/events', authorization);
assert(events.events.some(event => event.type === 'TASK_FINISHED'), 'Expected a committed terminal event');
const result = await json(taskPath + '/result', authorization);
assert.equal(result.state, 'SUCCEEDED', 'The result must be committed');
assert.equal(result.artifacts.length, 1, 'Expected one task result file');
const [receipt] = result.artifacts;
assert(/^r_[0-9a-f]{32}$/.test(receipt.runId), 'Expected a public run ID');
assert.equal(receipt.artifactId, 'task_result_' + receipt.runId.slice(2), 'Artifact must belong to the run');
const downloadPath = `/v1/runs/${receipt.runId}/artifacts/${receipt.artifactId}/download`;
assert.equal(receipt.downloadPath, downloadPath, 'Download must use the declared run and artifact');
assert.equal(receipt.publishedDigest, publishedDigest, 'Receipt must bind the independently recorded publication');
assert.equal(receipt.mediaType, 'application/json', 'Expected a JSON result receipt');

const download = await request(downloadPath, authorization);
assert.equal(download.status, 200, 'Attachment GET');
assert.equal(download.headers.get('content-type'), 'application/json', 'Attachment media type');
assert.equal(download.headers.get('content-disposition'), `attachment; filename="${receipt.artifactId}.json"`);
const bytes = Buffer.from(await download.arrayBuffer());
const digest = createHash('sha256').update(bytes).digest('hex');
assert.equal(bytes.length, receipt.bytes, 'Downloaded byte count differs from receipt');
assert.equal(digest, receipt.digest, 'Downloaded SHA-256 differs from receipt');
assert.equal(download.headers.get('etag'), `"${digest}"`, 'Attachment ETag differs from receipt');
assert.deepEqual(JSON.parse(bytes.toString('utf8')), result.result, 'File differs from the committed task result');
await writeFile(output, bytes, { flag: 'wx' });

console.log(JSON.stringify({
  taskId: task.taskId, artifactId: receipt.artifactId, runId: receipt.runId, downloadPath,
  mediaType: receipt.mediaType, bytes: bytes.length, digest, publishedDigest,
  http: download.status, etag: download.headers.get('etag'),
}));
