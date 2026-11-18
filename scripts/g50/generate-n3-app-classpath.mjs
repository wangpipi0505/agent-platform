// R-11：重建 iam-n3 验收链的 app 子进程 classpath 工件。
// 生成 .local/iam-n3-admission-app-client/app-classpath.txt（PublishedAdmissionHttpPostgresTest:275 消费）。
// 约束（测试 :276-284）：条目 ROOT 相对后必须以 .tools/m2/、app-management/target/ 或 admin-common/target/ 开头。
// 用法：node scripts/g50/generate-n3-app-classpath.mjs（在仓库根执行）
import { execSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { relative, resolve, sep } from "node:path";

const ROOT = process.cwd();
const OUT_DIR = resolve(ROOT, ".local/iam-n3-admission-app-client");
const M2 = resolve(ROOT, ".tools/m2");

console.log("[1/3] mvn dependency:build-classpath（app-management，含 reactor 兄弟模块）…");
const depsFile = resolve(OUT_DIR, "deps-cp.txt");
mkdirSync(OUT_DIR, { recursive: true });
execSync(
  `mvn -B -ntp -pl app-management -am dependency:build-classpath -Dmaven.repo.local="${M2}" ` +
    `-DincludeScope=test -Dmdep.outputFile="${depsFile}" -DskipTests`,
  { stdio: ["ignore", "ignore", "inherit"], cwd: ROOT },
);

console.log("[2/3] 拼接 test-classes/classes/deps …");
const deps = readFileSync(depsFile, "utf8").trim();
const parts = [
  resolve(ROOT, "app-management/target/test-classes"),
  resolve(ROOT, "app-management/target/classes"),
  resolve(ROOT, "admin-common/target/classes"),
  deps,
];
const cp = parts.join(";");
writeFileSync(resolve(OUT_DIR, "app-classpath.txt"), cp, "utf8");

console.log("[3/3] 约束校验 …");
let bad = 0;
for (const entry of cp.split(";")) {
  const rel = relative(ROOT, entry).replaceAll(sep, "/");
  if (!(rel.startsWith(".tools/m2/") || rel.startsWith("app-management/target/") || rel.startsWith("admin-common/target/"))) {
    bad++;
    console.log("  违规条目:", rel);
  }
}
console.log(`条目数=${cp.split(";").length} 违规=${bad}`);
console.log(bad === 0 ? "工件已生成并校验通过: .local/iam-n3-admission-app-client/app-classpath.txt" : "存在违规条目");
process.exit(bad === 0 ? 0 : 1);
