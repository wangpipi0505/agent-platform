# R-11 PublishedAdmissionHttpPostgresTest 死遗产复活——证据补档（R-12）

- 台账归属：§7.6 R 系列收口补丁（预估 0.5–1 人日）
- 状态：**通过（2026-11-19，5bde1c30）——处置=完整修复可跑（推翻"移除"方向）**
- 交付日期：2026-11-19；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`5bde1c30` R-11(fix): 死遗产测试复活——n3工件生成脚本(build-classpath到.tools/m2+约束校验116条)+FixtureMain补AppAuthorityTransport/OnboardingApplicationSources两bean+READY等待30s→120s
- 复查命令：`git log --oneline --all --grep="R-11"`

## 验收口径（卡面摘录）

> 核对修正卡面——PublishedAdmissionFixtureMain 并未删除（在 app-management 测试树），真实缺口=①`.local` app-classpath 工件无生成机制（scripts/g50/generate-n3-app-classpath.mjs：build-classpath 到 .tools/m2+约束校验 116 条目全合规）②FixtureMain 落后于 app 应用演进（补三 bean）③child READY 等待 30s→120s（首跑含全链迁移）；**PublishedAdmissionHttpPostgresTest 20/20 全绿（engine 库 V200→V901 全链+app 库 V100→V104 双库自举在案）**。

## 复现命令（两步：先重建工件，再跑测试——本补档按此路径完整复现）

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"   # 生成脚本内 mvn 需 JDK21
# 步骤 1：重建工件（本工作树 .tools/m2 为空仓，需先 install admin-common 进该仓再生成）
mvn -B -ntp -pl admin-common install -Dmaven.repo.local="$PWD/.tools/m2" -DskipTests
node scripts/g50/generate-n3-app-classpath.mjs          # 输出：条目数=116 违规=0 工件已生成并校验通过
# 步骤 2：跑测试（依赖 .local/iam-n3-admission-app-client/app-classpath.txt，缺工件即 fail-fast NoSuchFile）
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=PublishedAdmissionHttpPostgresTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
（步骤1）条目数=116 违规=0 —— 工件已生成并校验通过: .local/iam-n3-admission-app-client/app-classpath.txt
（步骤2）N3_APP_REPLY={"versionId":235} / N3_APP_STOPPED=29416   ← n3 子进程真实拉起并优雅退出
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 70.62 s -- in ...PublishedAdmissionHttpPostgresTest
```

**20/20 全绿**——与卡面判据一致（engine V200→V901 全链+app 双库自举+n3 验收链经子进程真实走通）。诚实登记：本补档首次尝试（batch4）在该工作树因 `.local` 工件缺失 fail-fast（`NoSuchFileException ...app-classpath.txt`，测试 :274 防呆设计如预期工作）——这恰好证明 R-11①"工件无生成机制即不可跑"的缺陷定位成立；按 R-11 交付的两步复现路径重建工件后 20/20 全绿。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.PublishedAdmissionHttpPostgresTest.txt`
- 报告 mtime：**2026-11-19 01:52:02**（本补档实跑产物）
- 注：`.local/` 与 `.tools/m2` 均不入 Git（生成机制已由 `scripts/g50/generate-n3-app-classpath.mjs` 固化，任何环境可重建）。
