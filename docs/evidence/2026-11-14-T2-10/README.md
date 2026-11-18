# T2-10 spill 消费+提示装配+A15b 子项——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 3 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `9a8f71b4` T2-10(engine): spill流式转存+PromptSection五段白名单+输出校验策略A15b
  - `c7ca960f` T2-10(engine): spill摘要单次提取修复（MessageDigest.digest 自复位）
  - `3b167915` T2-10(engine): SpillPromptTest 9例
  - `4e1e0ef4` merge: waveP2-1-T2-10（9 例+邻接回归 40/40 绿）
  - 复查命令：`git log --oneline --all --grep="T2-10"`

## 验收口径（卡面摘录）

> SpillPromptTest 9 例（spill 分块+落行+回填 2/白名单+replace 2/A15b 翻译+挂载+双分支 5）+邻接回归 40/40；DoD 14/14 绿；OutputValidationPolicy RETRY=retry.max+1 次同一预算/TERMINATE=首次终止，OUTPUT_SCHEMA_INVALID 落库。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=SpillPromptTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.harness.context.SpillPromptTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9.629 s
```

**9/9 全绿**——spill 分块/五段白名单/A15b 双分支判据实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.harness.context.SpillPromptTest.txt`
- 报告 mtime：**2026-11-19 01:41:06**（本补档实跑产物）
