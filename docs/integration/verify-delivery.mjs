#!/usr/bin/env node
// 交付包自检：checksum、Schema/事件枚举、示例接线与 SDK 自检脚本对齐。
// 用法：node docs/integration/verify-delivery.mjs
// 退出码：0 全通过；1 有失败项。

import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../..");
const failures = [];
const notes = [];

function fail(message) {
  failures.push(message);
  console.error("FAIL " + message);
}

function pass(message) {
  console.log("PASS " + message);
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function read(path) {
  return readFileSync(join(root, path), "utf8");
}

function exists(path) {
  try {
    return statSync(join(root, path)).isFile();
  } catch {
    return false;
  }
}

// 1) checksum
const checksumLines = read("docs/integration/checksums.txt")
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith("#"));

for (const line of checksumLines) {
  const match = /^([0-9a-f]{64})\s{2}(.+)$/.exec(line);
  if (!match) {
    fail("checksums.txt 行格式不合法: " + line);
    continue;
  }
  const [, expected, path] = match;
  if (!exists(path)) {
    fail("checksum 目标不存在: " + path);
    continue;
  }
  const actual = sha256(join(root, path));
  if (actual !== expected) {
    fail(`checksum 不符: ${path}\n  期望 ${expected}\n  实际 ${actual}`);
  } else {
    pass("checksum " + path);
  }
}

// 2) 事件枚举一致性：TS 常量 == JSON Schema enum == 契约 §4 表格
const hostEvents = read("web/shared/assistant-host-events.ts");
const tsMatch = /export const HOST_EVENT_NAMES = \[([\s\S]*?)\] as const;/.exec(hostEvents);
if (!tsMatch) {
  fail("无法从 web/shared/assistant-host-events.ts 读取 HOST_EVENT_NAMES");
}
const tsNames = tsMatch
  ? [...tsMatch[1].matchAll(/"([a-z-]+)"/g)].map((m) => m[1])
  : [];
if (new Set(tsNames).size !== tsNames.length) fail("HOST_EVENT_NAMES 有重复项");

const schema = JSON.parse(read("web/shared/assistant-host-events.schema.json"));
const schemaNames = schema?.properties?.event?.enum;
if (!Array.isArray(schemaNames)) {
  fail("Schema 缺少 properties.event.enum");
} else {
  const a = [...tsNames].sort().join(",");
  const b = [...schemaNames].sort().join(",");
  if (a !== b) fail(`事件枚举不一致: TS=[${a}] Schema=[${b}]`);
  else pass("事件枚举 TS/Schema 一致（" + tsNames.length + " 项）");
}

const contract = read("contracts/assistant-integration.md");
for (const name of tsNames) {
  if (!contract.includes("`" + name + "`") && !contract.includes(name)) {
    fail("契约 §4 未声明事件: " + name);
  }
}
if (tsNames.length !== 7) fail("事件应为 7 类，实际 " + tsNames.length);

// 3) SDK 自检脚本与示例对齐：自检脚本必须仍覆盖 onHostEvent 与终态一次回传
const sdkCheck = read("web/tests/assistant-sdk-split-check.mjs");
for (const token of ["onHostEvent", "hostResultEventDelivery", "window.AIAssistant"]) {
  if (!sdkCheck.includes(token)) fail("SDK 自检脚本缺少对齐标记: " + token);
}
if (failures.length === 0) pass("SDK 自检脚本与示例 API 对齐");

// 4) 示例检查
const examplesDir = join(root, "docs/integration/examples");
function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...walk(path));
    else out.push(path);
  }
  return out;
}
const exampleFiles = walk(examplesDir);
const htmlFiles = exampleFiles.filter((p) => p.endsWith(".html"));
const codeFiles = exampleFiles.filter((p) => /\.(html|vue|ts|mjs|js)$/.test(p));

for (const file of htmlFiles) {
  const text = readFileSync(file, "utf8");
  // 未知事件不得被示例当作可执行分支之外的实现；只检查引用的事件名都在白名单内。
  const used = [...text.matchAll(/event\.event === "([a-z-]+)"/g)].map((m) => m[1]);
  for (const name of used) {
    if (!tsNames.includes(name)) fail(`${relative(root, file)} 引用了未登记事件 ${name}`);
  }
  // targetOrigin 不得为 "*"
  if (/postMessage\([\s\S]{0,200}?,\s*"\*"\s*\)/.test(text)) {
    fail(`${relative(root, file)} 使用 "*" 作为 targetOrigin`);
  }
  // 不得把票据写进 URL/localStorage
  for (const banned of ["localStorage", "sessionStorage", "access_token="]) {
    if (text.includes(banned)) fail(`${relative(root, file)} 出现禁止用法: ${banned}`);
  }
}

const blankHost = readFileSync(join(examplesDir, "blank-host.html"), "utf8");
for (const token of ["AgentPlatformAssistant", "autoLauncher: false", "onHostEvent", "businessRecordRefs", "updateContext", "textContent"]) {
  if (!blankHost.includes(token)) fail("blank-host.html 缺少接线要素: " + token);
}
pass("示例接线要素检查完成");

// 空白宿主页不得以三条业务路线命名（示例命名为 blank-host，正文不出现路线名）
for (const banned of ["审批路线", "采购路线", "报表路线"]) {
  for (const file of codeFiles.concat([join(root, "docs/integration/README.md")])) {
    if (readFileSync(file, "utf8").includes(banned)) fail(`${relative(root, file)} 出现业务路线名: ${banned}`);
  }
}

// 5) versions.md 与产物一致（sha/字节）
const versions = read("docs/integration/versions.md");
const artifactPath = join(root, "web/assistant.js/dist/assistant.js");
if (!exists("web/assistant.js/dist/assistant.js")) {
  fail("SDK 产物不存在，请先构建 web/assistant.js");
} else {
  const digest = sha256(artifactPath);
  const bytes = statSync(artifactPath).size;
  // 从构建产物那一行取摘要与字节，避免把校验绑死在某一版产物上。
  const artifactRow = versions
    .split(/\r?\n/)
    .find((line) => line.includes("web/assistant.js/dist/assistant.js"));
  const artifactCells = artifactRow?.match(/`([^`]+)`/g)?.map((cell) => cell.slice(1, -1)) ?? [];
  const declaredDigest = artifactCells.find((cell) => /^[0-9a-f]{64}$/.test(cell));
  const declaredBytes = artifactCells.find((cell) => /^\d+$/.test(cell));
  if (!declaredDigest || declaredDigest !== digest) {
    fail(`versions.md 的 SDK sha256 与产物不一致（${digest}）`);
  }
  if (!declaredBytes || Number(declaredBytes) !== bytes) {
    fail(`versions.md 的字节数与产物不一致（${bytes}）`);
  }
  if (declaredDigest === digest && Number(declaredBytes) === bytes) {
    pass(`SDK 产物与 versions.md 一致（${digest.slice(0, 12)}…, ${bytes} bytes）`);
  }
}

// 6) 必备文档存在
for (const path of [
  "docs/integration/README.md",
  "docs/integration/l0-l3.md",
  "docs/integration/host-checklist.md",
  "docs/integration/versions.md",
  "docs/integration/error-codes.md",
  "docs/integration/upgrade-rollback.md",
  "docs/integration/templates/experience-template.json",
  "docs/integration/templates/prompt-template.md",
  "docs/integration/examples/iframe-host.html",
  "docs/integration/examples/vanilla-host.html",
  "docs/integration/examples/vue-host/App.vue",
  "docs/integration/examples/bff/ticket-server.mjs",
]) {
  if (!exists(path)) fail("缺少交付文件: " + path);
}

if (notes.length) for (const note of notes) console.log("NOTE " + note);

if (failures.length) {
  console.error(`\n交付包自检失败：${failures.length} 项`);
  process.exit(1);
}
console.log("\n交付包自检通过。");
