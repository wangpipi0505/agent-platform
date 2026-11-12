#!/usr/bin/env node
/**
 * dsh 对照 oracle 骨架（C0-4，台账#C0-4）。
 *
 * 用法：
 *   node scripts/oracle/run-oracle.mjs --scenario scripts/oracle/fixtures/example-recover-tool-call.json
 *   node scripts/oracle/run-oracle.mjs --scenario <fixture> \
 *        --ref-driver "<cmd>" --subject-driver "<cmd>" [--out <report.json>]
 *
 * 契约：contracts/oracle-replay-adapter.md（驱动器命令行契约 / 事件映射 / 行为清单 B1-B4）。
 * 骨架模式（默认，无 --ref-driver/--subject-driver）：内置 stub 驱动按 fixture 确定性产出
 * 归一化事件流，仅证明对照管线可运行，不构成任何行为通过结论。
 * 真实驱动（dsh 参考侧 / java 回放被测侧）按契约 §1 替换，随 H1-x 卡交付。
 * 全程无真实出站（写操作走测试适配器，仅开发环境使用）。
 */
import { readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) args[a.slice(2)] = argv[++i];
    else args._.push(a);
  }
  return args;
}

const mapping = JSON.parse(readFileSync(join(here, "event-mapping.json"), "utf8"));
const dshToSession = new Map(mapping.mappings.map((m) => [m.from, m.to]));

/** 归一化：原始（dsh 侧）事件 → session_event 类型；已是归一化形状则原样通过。 */
function normalizeEvent(line) {
  const ev = JSON.parse(line);
  if (ev.control) return ev; // {"control":"restart"} 边界标记
  if (ev.type && ev.type.includes("/")) return ev; // 已归一化
  const mapped = dshToSession.get(ev.type);
  if (!mapped) throw new Error(`event-mapping 缺少映射: ${ev.type}`);
  return { seq: ev.seq, type: mapped, time: null, data: ev.data ?? {}, surfaceOp: ev.surfaceOp ?? null };
}

function loadStream(source) {
  return source.trim().split(/\r?\n/).filter(Boolean).map(normalizeEvent);
}

/** callId 等易变标识按出现顺序对齐（映射表粒度注记：两侧各自生成，按序对齐）。 */
function canonicalize(events) {
  const callIds = new Map();
  let callSeq = 0;
  return events.map((ev) => {
    if (ev.control) return ev;
    const data = { ...ev.data };
    if (ev.type === "tool/call" && data.callId) data.callId = `call#${++callSeq}`;
    if (ev.type === "tool/result" && data.callId) {
      if (!callIds.has(data.callId)) callIds.set(data.callId, `call#${callIds.size + 1}`);
      data.callId = callIds.get(data.callId);
    }
    return { ...ev, data };
  });
}

// ---------- 行为清单 B1-B4（契约 §3） ----------

function checkB1(events) {
  // 工具可见性与实际执行一致：每步 request/header.tools ⊇ 该步 tool/call 的 name
  const issues = [];
  let currentTools = null;
  for (const ev of events) {
    if (ev.control) continue;
    if (ev.type === "request/header") currentTools = (ev.data.tools ?? []).map((t) => t.name ?? t.toolId ?? t);
    if (ev.type === "tool/call") {
      const declared = currentTools ?? [];
      if (!declared.includes(ev.data.name)) issues.push(`工具 ${ev.data.name} 未在该步采纳集声明`);
    }
  }
  return { id: "B1", pass: issues.length === 0, issues };
}

function checkB2(events) {
  // 工具调用与结果成对：每个 callId 恰一个 result；补写结果必须 synthetic+UNKNOWN
  const issues = [];
  const calls = new Map();
  const results = new Map();
  for (const ev of events) {
    if (ev.control) continue;
    if (ev.type === "tool/call") {
      if (calls.has(ev.data.callId)) issues.push(`tool/call 重复: ${ev.data.callId}`);
      calls.set(ev.data.callId, ev);
    }
    if (ev.type === "tool/result") {
      if (results.has(ev.data.callId)) issues.push(`tool/result 重复: ${ev.data.callId}`);
      results.set(ev.data.callId, ev);
      if (ev.data.synthetic === true && ev.data.semantic !== "UNKNOWN") {
        issues.push(`补写结果 semantic 非法: ${ev.data.callId}=${ev.data.semantic}`);
      }
    }
  }
  for (const id of calls.keys()) if (!results.has(id)) issues.push(`tool/call 无配对结果: ${id}`);
  for (const id of results.keys()) if (!calls.has(id)) issues.push(`tool/result 无配对调用: ${id}`);
  return { id: "B2", pass: issues.length === 0, issues };
}

function checkB3(events, fixture) {
  // 已完成产物复用：恢复后不得重复已完成步骤的同参 tool/call（受理次数恒 1）
  const issues = [];
  const accepted = new Set();
  let restarted = false;
  for (const ev of events) {
    if (ev.control === "restart") { restarted = true; continue; }
    if (ev.control) continue;
    if (ev.type === "tool/call") {
      const key = `${ev.data.name}:${stableJson(ev.data.args ?? {})}`;
      if (restarted && accepted.has(key)) issues.push(`恢复后重复调用已完成步骤: ${key}`);
      accepted.add(key);
    }
  }
  const expected = fixture?.expected?.externalAcceptCount;
  const externalAccepts = events.filter((e) => !e.control && e.type === "tool/call" && !e.data.synthetic).length;
  if (expected != null && externalAccepts > expected) issues.push(`外部受理次数 ${externalAccepts} > 预期 ${expected}`);
  return { id: "B3", pass: issues.length === 0, issues };
}

function checkB4(refEvents, subjEvents) {
  // 恢复后事件序列等价：按序对齐比较（控制边界各自剥离后逐事件比对类型+关键键）
  const strip = (events) => events.filter((e) => !e.control);
  const a = strip(refEvents), b = strip(subjEvents);
  const issues = [];
  if (a.length !== b.length) issues.push(`事件数不等: ref=${a.length} subject=${b.length}`);
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    if (a[i].type !== b[i].type) { issues.push(`#${i + 1} 类型不等: ${a[i].type} vs ${b[i].type}`); continue; }
    const ka = keyOf(a[i]), kb = keyOf(b[i]);
    if (ka !== kb) issues.push(`#${i + 1} 关键键不等: ${a[i].type} ${ka} vs ${kb}`);
  }
  return { id: "B4", pass: issues.length === 0, issues };
}

const VOLATILE = new Set(["callId", "usage", "summary", "time"]);
function keyOf(ev) {
  const keys = Object.keys(ev.data ?? {}).filter((k) => !VOLATILE.has(k) && ev.data[k] !== undefined).sort();
  return keys.map((k) => `${k}=${stableJson(ev.data[k])}`).join(",");
}
function stableJson(v) {
  if (v == null || typeof v !== "object") return JSON.stringify(v);
  if (Array.isArray(v)) return `[${v.map(stableJson).join(",")}]`;
  return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${stableJson(v[k])}`).join(",")}}`;
}

// ---------- stub 驱动（骨架自证，确定性产出，不代表真实行为） ----------

function stubStream(fixture) {
  const out = [];
  let seq = 0;
  const push = (type, data, surfaceOp = null) => out.push({ seq: ++seq, type, time: null, data, surfaceOp });
  const toolsOf = (step) => (fixture.stubs.modelResponses.find((m) => m.step === step)?.toolCalls ?? [])
    .map((t) => ({ toolId: t.name, versionRef: `${t.name}@1`, schemaDigest: "0".repeat(64) }));
  const msgOf = (turn) => fixture.inputs.find((i) => i.turn === turn)?.message ?? "";

  // turn 1：纯回复步
  push("turn/start", { turn: 1 });
  push("step/start", { step: 1 });
  push("request/header", { config: { model: "stub-model" }, tools: [] });
  push("request/context", { provider: "stub", model: "stub-model", contextWindow: 60000 });
  push("system/message", { content: "You are a helpful assistant." });
  push("user/message", { content: msgOf(1) });
  push("assistant/message", { stream: [{ t: "delta", text: fixture.stubs.modelResponses[0].content }], usage: fixture.stubs.modelResponses[0].usage });
  push("step/end", { step: 1 });
  push("turn/end", { turn: 1, reason: "completed" });

  // turn 2：工具调用步 + 故障注入 + 恢复
  push("turn/start", { turn: 2 });
  push("step/start", { step: 2 });
  push("request/header", { config: { model: "stub-model" }, tools: toolsOf(2) });
  push("request/context", { provider: "stub", model: "stub-model", contextWindow: 60000 });
  push("user/message", { content: msgOf(2) });
  push("tool/call", { callId: "stub-call-1", name: fixture.stubs.modelResponses[1].toolCalls[0].name, args: fixture.stubs.modelResponses[1].toolCalls[0].args });
  out.push({ control: "restart" }); // 故障注入点：外部受理后、回执落库前崩溃
  // 恢复：从事件日志重建 → 查询原外部任务（只查询不重发）→ 权威回执确认
  // 补写结果如实标注 synthetic+UNKNOWN（契约四条④：结果未确认，禁止表述失败）
  const receipt = fixture.stubs.toolReceipts[0];
  push("tool/result", { callId: "stub-call-1", meta: { externalTaskId: receipt.externalTaskId, recoverySource: "AUTO", semantic: "UNKNOWN", synthetic: true } });
  push("step/end", { step: 2 });
  push("turn/end", { turn: 2, reason: "completed" });
  return out;
}

function runExternalDriver(cmd, scenarioPath, label, workDir) {
  const outFile = join(workDir, `${label}.jsonl`);
  const full = `${cmd} --scenario "${scenarioPath}" --out "${outFile}"`;
  const res = spawnSync(full, { shell: true, encoding: "utf8" });
  if (res.status !== 0) {
    throw new Error(`${label} 驱动失败（exit=${res.status}）：${(res.stderr || res.stdout || "").slice(0, 800)}`);
  }
  return loadStream(readFileSync(outFile, "utf8"));
}

// ---------- 主流程 ----------

const args = parseArgs(process.argv.slice(2));
if (!args.scenario) {
  console.error("用法: run-oracle.mjs --scenario <fixture.json> [--ref-driver <cmd>] [--subject-driver <cmd>] [--out <report.json>]");
  process.exit(2);
}
const fixturePath = resolve(args.scenario);
const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));

let refEvents, subjEvents;
const workDir = mkdtempSync(join(tmpdir(), "oracle-"));
if (args["ref-driver"] || args["subject-driver"]) {
  refEvents = runExternalDriver(args["ref-driver"], fixturePath, "ref", workDir);
  subjEvents = runExternalDriver(args["subject-driver"], fixturePath, "subject", workDir);
} else {
  const stub = stubStream(fixture);
  refEvents = stub; subjEvents = stub.map((e) => JSON.parse(JSON.stringify(e))); // 骨架自证：同一确定性流
}

const refC = canonicalize(structuredClone(refEvents));
const subjC = canonicalize(structuredClone(subjEvents));

const behaviors = [checkB1(refC), checkB2(refC), checkB3(refC, fixture), checkB4(refC, subjC)];
const report = {
  scenarioId: fixture.scenarioId,
  mode: args["ref-driver"] ? "external-drivers" : "stub-skeleton",
  refDriver: args["ref-driver"] ?? "stub",
  subjectDriver: args["subject-driver"] ?? "stub",
  behaviors,
  sequenceDiff: diffSequences(refC, subjC),
  pass: behaviors.every((b) => b.pass),
  disclaimer: "stub 模式仅证明对照管线可运行，不构成任何行为通过结论（contracts/oracle-replay-adapter.md §5）",
};

function diffSequences(a, b) {
  const sa = a.filter((e) => !e.control), sb = b.filter((e) => !e.control);
  const diffs = [];
  const n = Math.max(sa.length, sb.length);
  for (let i = 0; i < n; i++) {
    const ea = sa[i], eb = sb[i];
    if (!ea || !eb || ea.type !== eb.type || stableJson(ea.data) !== stableJson(eb.data)) {
      diffs.push({ index: i + 1, ref: ea ? { type: ea.type } : null, subject: eb ? { type: eb.type } : null });
    }
    if (diffs.length >= 20) { diffs.push({ note: "截断：仅显示前 20 条差异" }); break; }
  }
  return diffs;
}

const reportText = JSON.stringify(report, null, 2);
if (args.out) writeFileSync(resolve(args.out), reportText, "utf8");
console.log(reportText);
process.exit(report.pass ? 0 : 1);
