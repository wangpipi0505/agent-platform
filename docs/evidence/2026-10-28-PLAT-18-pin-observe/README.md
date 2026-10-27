# T18.3 / PLAT-18：pin 结果可观测（落库，app V106）证据

## 1. 范围依据

- 台账 §3 T18.3（`4caf8af` 只把绑定结果并入发布响应副本，未持久化；反查"某应用当前 pinned 到哪个 release"需问本体绑定点或翻日志）。
- 方案 §4.20（平台侧优化方案与实施细则-20261027.md 1105–1133 行）不涉及本卡；本卡按计划 §6.2 后端规范执行（开关、迁移、事务边界、注释纪律）。
- 硬约束：只读复用 `ReleasePins` 状态机结果，禁止改 `ReleasePins.java` 本体；与 T12a 的 V107 同批分先后入库（迁移顺序警告）。

## 2. 环境

- 仓库 `G:\207\项目\算法重组工具\代码\agent-platform`，分支 `feat/v1.2-implementation`，开工基线 app-management HEAD `c94f2f1`（T12a V107），仓库 HEAD `bcd9184`。
- JDK 21（Eclipse Adoptium jdk-21.0.12.8-hotspot）、Maven、真实 PG 16.15 @ 127.0.0.1:25432（用户 agent_v12，口令走 `.env` `AGENT_DB_PASSWORD`，经 `.tools/env-load.sh` 载入，只导出不打印）。
- 本卡专用测试库 `agent_platform_pin_audit_v12`（不存在时由夹具创建，Fresh DB 顺序迁移）；另一次触达见第 6 节的三库 outOfOrder 一次性修复。

## 3. 命令原文

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh

# 红例（编译期红，T12a 同口径）：pinStatus/pinRecord 不存在
mvn -B -pl app-management test-compile -Dtest=PublishPinAuditPostgresTest "-DargLine=-Xmx1024m"
#   → 输出见 red-compile.txt（找不到符号: 方法 pinStatus(java.lang.String)/pinRecord(boolean)）

# 新类定向
mvn -B -pl app-management test -Dtest='PublishPinAuditPostgresTest' "-DargLine=-Xmx1024m"
#   → green-newclass-run1/2/3.txt（run1/run2 为夹具自身缺陷迭代，见第 5 节）

# 三库 outOfOrder 一次性修复（既有库已应用 V107，再遇 V106 会校验失败；仅限存量测试库运维修复）
mvn -B -pl app-management dependency:build-classpath -Dmdep.outputFile=app-management/target/cp.txt
"$JAVA_HOME/bin/java" -cp "$(cat app-management/target/cp.txt);app-management/target/classes;docs/evidence/2026-10-28-PLAT-18-pin-observe" \
  docs/evidence/2026-10-28-PLAT-18-pin-observe/RepairOutOfOrderV106.java \
  agent_platform_v12 agent_platform_iam_policy_v12 agent_platform_iam_identity_v12
#   → 各 applied=1 success=true；历史序见 repair-outoforder-history.txt（105,107,106 全 success）

# 基线对照（干净 worktree @ bcd9184，同清单同环境；修复先临时撤销，跑完重新补上）
mvn -B -pl app-management test -Dtest='ReleasePinsTest,PublishPinTest,PublishGateTest,PublishTransactionTest,IdentityMappingPostgresTest,AuditLinkPostgresTest,IntegrationPolicyDatabaseTest,AppCopyConcurrencyTest' "-DargLine=-Xmx1024m"
#   → baseline-head-multiclass.txt：Tests run: 54, Failures: 0, Errors: 3

# 最终多类同跑（新类 + 既有 pin/gate + 相邻 PostgresTest）
mvn -B -pl app-management test -Dtest='PublishPinAuditPostgresTest,DataHealthPostgresTest,DataHealthHttpTest,ReleasePinsTest,PublishPinTest,PublishGateTest,PublishTransactionTest,IdentityMappingPostgresTest,AuditLinkPostgresTest,IntegrationPolicyDatabaseTest,AppCopyConcurrencyTest' "-DargLine=-Xmx1024m"
#   → final-acceptance-combined.txt：Tests run: 72, Failures: 0, Errors: 3
```

## 4. 结果原文

- 红（编译期）：`PublishPinAuditPostgresTest.java:[333,30] 找不到符号 符号: 方法 pinStatus(java.lang.String)` 等 8 处（red-compile.txt）。
- 绿（新类 9 例，green-newclass-run3.txt）：

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
04:28:31.776 [main] INFO ... Current version of schema "app": 107
04:28:31.778 [main] INFO ... Schema "app" is up to date. No migration necessary.
```

- 三库修复（`applied=1 success=true` × 3）后历史序（repair-outoforder-history.txt）：`7|107|t`、`8|106|t`（105 之前），后续常规 migrate 校验通过（final-acceptance-combined.txt 中既有类全绿复证）。
- 最终验收（final-acceptance-combined.txt）：

```
[INFO] Tests run: 72, Failures: 0, Errors: 3, Skipped: 0
（Errors=基线既有 3 条，逐行同签名，见第 5 节）
```

- 回归对照：基线（干净 worktree @ `bcd9184`）54 例 3 Error，本卡 72 例同 3 Error、逐行同签名 → **新增失败 = 0**。三条既有失败：
  1. `AppCopyConcurrencyTest.database:40 ? FlywaySql ... database "agent_platform_app_copy_test" does not exist`（该测试期望外部预建库，环境缺库）；
  2. `PublishTransactionTest.globalCredentialLookupRequiresDedicatedGatewayIdentity:347 ? Platform 缺少当前授权上下文`；
  3. `PublishTransactionTest.integrationSecretOnlyShownOnceAndRevocationPersists:315 ? Platform 缺少当前授权上下文`。

## 5. 未覆盖与已知失败

- 上列 3 条既有失败原样保留（Y-4 口径），本卡不修。
- 新类夹具迭代说明（诚实记录）：green-newclass-run1/run2 是**测试自身缺陷**（静态共享 AppService 的开关状态跨用例泄漏；误把 state/environment 断言为可空；两例在未发布前就断言 pinStatus），实现代码未因这三轮改动；run3 全绿。
- 红例口径：编译期红（T12a 先例）。未先造运行期红：pinStatus/pinRecord 不存在时无法编译运行任何断言。
- 未覆盖：`/admin/**` HTTP 查询端点未加（任务允许"不强求"，application 层 `AppService.pinStatus(code)` 交付，管理面后续接线）；fail-closed(502) 路径不落库行为由既有 `PublishPinTest.failClosedTurnsResponseInto502` 与实现路径推证，未单独立红例；并发同应用发布对 UPSERT 行的争用未做压力测试（唯一约束保证单行，last_pinned_* 条件更新语义在库层幂等）。
- 运行方式敏感：全部真实库测试需 `AGENT_DB_PASSWORD`（env-load 载入），否则按 `@EnabledIfEnvironmentVariable` 静默跳过，不构成通过证据。
- 选型理由（方案二选一）：取 B 新表 `app.publish_pin`，不补 `publish_report.checks` JSON——pin 生命周期独立于单次发布报告（报告是 15 分钟预检证据；DISABLED/SKIPPED 与任何报告无关），应用粒度唯一行才能 O(1) 反查"当前绑定"。事务语义（定死）：落库在发布事务提交之后、与返回值组装同一处，单条 UPSERT 自动提交；落库失败仅 WARN，不破坏已提交发布与响应（fail-open 与 T-pin 同一取向）；`agent.pin.record-enabled=false` 只停写不改响应；fail-closed 时 pin() 先抛 502，不落库。

## 6. 回滚方式

- 代码/配置：`git checkout -- app-management/src/main/java/cn/agentplatform/app/application/AppService.java app-management/src/main/resources/application.yml`；删除 `V106__publish_pin.sql`、三个新测试类与本证据目录即可回到 `4caf8af` 行为（响应 pin 字段从未变更）。
- 运行开关：`AGENT_PIN_RECORD_ENABLED=false`（`agent.pin.record-enabled`，默认 true）只停写 `app.publish_pin`，发布与绑定行为不变。
- V106 down（手动执行；drop 前确认无在途发布写入窗口）：

```sql
DROP TABLE IF EXISTS app.publish_pin;
DELETE FROM app.flyway_schema_history WHERE version='106';
```

- 三库 outOfOrder 修复的回滚 = 上两条 SQL（agent_platform_v12 / agent_platform_iam_policy_v12 / agent_platform_iam_identity_v12 均为可弃测试库）。注意：回滚后若 V106 文件仍在 classpath，下次 migrate 会重新应用；彻底回滚须先删迁移文件。
