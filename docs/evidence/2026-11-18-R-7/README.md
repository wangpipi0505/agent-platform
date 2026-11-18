# R-7 McpConnectionTest 链尾断言修——证据补档（R-12）

- 台账归属：§7.6 R 系列收口补丁（预估 0.1 人日）
- 状态：**通过（2026-11-18，91948e45）**
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`91948e45` R-7(test): McpConnectionTest链尾断言修——V901种子后==322过期改>=322下限断言(表级在位断言不变)
- 复查命令：`git log --oneline --all --grep="R-7"`

## 验收口径（卡面摘录）

> 断言改 ≥322 下限（表级在位断言不变）；主线复跑 McpConnectionTest 7/7。缺陷背景：V901 种子合入后 skill 迁移链尾 322→901，==322 断言基底即失败 2 例（M5-1×A3-6 互作漏网）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl skill-registry test -Dtest=McpConnectionTest -DfailIfNoTests=false
grep -n ">= 322\|322" skill-registry/src/test/java/cn/agentplatform/skill/application/McpConnectionTest.java | head -5
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.skill.application.McpConnectionTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: ...
```

**7/7 全绿**——与卡面判据一致，实测复验通过（本环境 skill 库链尾=901，≥322 下限断言如设计放行）。

## Surefire 报告指针

- `skill-registry/target/surefire-reports/cn.agentplatform.skill.application.McpConnectionTest.txt`
- 报告 mtime：**2026-11-19 01:41:17**（本补档实跑产物）
- 关联发现登记：A3-1 的 WorkspaceManifestPostgresTest 存在同类未修链尾断言（==123，app 链已至 901），详见 `docs/evidence/2026-11-14-A3-1/README.md`——R-7 仅修 skill 域，app 域同类项已在本补档如实登记。
