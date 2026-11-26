// Run against an isolated application whose model returns a controlled 503.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { setTimeout as wait } from "node:timers/promises";

const { TASK_GATEWAY, TASK_APP, TASK_BEARER } = process.env;
if (!TASK_GATEWAY || !TASK_APP || !TASK_BEARER) {
  throw new Error("Set TASK_GATEWAY, TASK_APP and TASK_BEARER for the test application");
}
async function request(path, body) {
  const response = await fetch(new URL(path, TASK_GATEWAY), {
    method: body ? "POST" : "GET",
    headers: { Authorization: `Bearer ${TASK_BEARER}`, "Content-Type": "application/json" },
    body: body && JSON.stringify(body),
    redirect: "error",
    signal: AbortSignal.timeout(20000),
  });
  assert.equal(response.status, 200, path);
  return (await response.json()).data;
}
const path = `/v1/apps/${encodeURIComponent(TASK_APP)}/tasks`;
const body = { mode: "AGENT", requestId: randomUUID(), goal: "model failure regression",
  inputs: { message: "upstream-unavailable" } };
const [first, replay] = await Promise.all([request(path, body), request(path, body)]);
assert.ok(first.taskId);
assert.equal(first.taskId, replay.taskId);
const task = `/v1/tasks/${encodeURIComponent(first.taskId)}`;
let detail, result;
for (let poll = 1; poll <= 3; poll++) {
  await wait(6000);
  [detail, result] = await Promise.all([request(task), request(task + "/result")]);
  console.log(JSON.stringify({ poll, at: new Date().toISOString(), detail, result }));
}
for (const view of [detail, result]) {
  assert.equal(view.state, "FAILED");
  assert.equal(view.reason, "SYS-MODEL-503");
  assert.equal(view.retryable, false);
}
