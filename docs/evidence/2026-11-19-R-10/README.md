# R-10 RunObservation 投影演进对账（基线 9F 清零）——证据补档（R-12）

- 台账归属：§7.6 R 系列收口补丁（预估 0.5 人日）
- 状态：**通过（2026-11-19，6e2d9a31）**
- 交付日期：2026-11-19；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`6e2d9a31` R-10(test): RunObservation投影演进对账——Status白名单补invalid_chat派生列/256KB截断distinct-on/model_input_snapshot元数据查询+13键含paused；Metadata禁用词收窄+404同形比较剥离随机traceId
- 复查命令：`git log --oneline --all --grep="R-10"`

## 验收口径（卡面摘录）

> 观测投影演进与旧断言对账修复。**两类基线复跑绿**；卡面：**Metadata 16/16+Status 19/19=35/35 全绿（基线 9F 清零）**。修复点：Status 白名单补 invalid_chat 派生列（CHAT 形状校验=存在性/计数元数据判定不读 payload）、distinct-on 按 X2-4 裁决 256KB 截断口径、model_input_snapshot 标识查询、状态投影 13 键含 paused；Metadata 禁用词清单收窄（node_instance 元数据形态放行）+404 同形比较剥离随机 traceId（断言自身缺陷）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest='RunObservationMetadataHttpPostgresTest,RunObservationStatusHttpPostgresTest' -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.83 s -- in ...RunObservationMetadataHttpPostgresTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 20.61 s -- in ...RunObservationStatusHttpPostgresTest
```

**16+19=35/35 全绿**——与卡面判据逐字一致（基线 9F 清零复验通过），本环境 PG 25432 实测。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.RunObservationMetadataHttpPostgresTest.txt`（mtime 2026-11-19 01:42）
- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.RunObservationStatusHttpPostgresTest.txt`（mtime 2026-11-19 01:42）
- 均为本补档实跑产物。
