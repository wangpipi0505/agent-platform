// R-21⑤：里程碑 tag 本地/远端一致性校验——一条命令，差异为 0 即通过。
// 用法：node scripts/verify-tags.mjs [远端名，默认 origin]
// 规则：milestone/* 前缀 tag 全集一致（本地有远端缺=未推送；远端有本地缺=漂移）；
//       双侧同名 tag 的 peel 指向（commit）必须相同。
import { execSync } from "node:child_process";

const remote = process.argv[2] || "origin";

// Windows cmd 会展开 %(...)——逐 tag 用 rev-list 自动 peel（annotated→commit，轻量→自身）。
const local = new Map(
  execSync('git tag -l "milestone/*"', { encoding: "utf8" })
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((name) => [
      name.trim(),
      execSync(`git rev-list -1 ${name.trim()}`, { encoding: "utf8" }).trim(),
    ]),
);

const lsRemote = execSync(`git ls-remote --tags ${remote} "refs/tags/milestone/*"`, { encoding: "utf8" });
const remoteTags = new Map();
for (const line of lsRemote.trim().split("\n").filter(Boolean)) {
  const [hash, ref] = line.split(/\s+/);
  if (ref.endsWith("^{}")) {
    const name = ref.replace("refs/tags/", "").replace("^{}", "");
    remoteTags.set(name, hash); // peel 行覆盖 tag 对象行
  } else if (!remoteTags.has(ref.replace("refs/tags/", ""))) {
    remoteTags.set(ref.replace("refs/tags/", ""), hash);
  }
}

const missingRemote = [...local.keys()].filter((t) => !remoteTags.has(t));
const missingLocal = [...remoteTags.keys()].filter((t) => !local.has(t));
const diverged = [...local.keys()].filter((t) => remoteTags.has(t) && remoteTags.get(t) !== local.get(t));

console.log(`本地 milestone tag=${local.size} 远端=${remoteTags.size}`);
for (const t of missingRemote) console.log(`  未推送: ${t}`);
for (const t of missingLocal) console.log(`  本地缺失: ${t}`);
for (const t of diverged) console.log(`  指向漂移: ${t} 本地=${local.get(t).slice(0, 12)} 远端=${remoteTags.get(t).slice(0, 12)}`);

const diff = missingRemote.length + missingLocal.length + diverged.length;
console.log(diff === 0 ? "PASS：本地/远端 milestone tag 完全一致（差异=0）" : `FAIL：差异=${diff}`);
process.exit(diff === 0 ? 0 : 1);
