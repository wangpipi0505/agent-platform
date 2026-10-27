# PLAT-12（A-1 平台侧半段）：主体属性统一与身份映射 — 实施证据

日期：2026-10-28 ｜ 仓库：agent-platform ｜ 分支：`feat/v1.2-implementation` ｜ 基线 HEAD：`bdc663f` ｜ 迁移号：app-management `V107`（X-5 认领）

## 1. 范围与依据

依据：
- 方案 §4.12（`平台侧优化方案与实施细则-20261027.md` 819–868 行）：`Actor` 增加 `subject()` 快照；审计记录 `runId ↔ principalId ↔ credentialId`；
- 台账 §3 T12.1/T12.2 与 §5 X-5（已裁决：**建身份映射表**，认领 `V107`；表存 `tenant_id + principal_id → subject_snapshot/映射键`，与本体侧 O-07 `platform_actor_credential`（V24）对齐）；
- 计划 §6 代码规范（迁移须在旧数据上可跑、先红后绿、真实 PG、不新增依赖）。

本卡实施（白名单内）：
1. **`Actor.subject()` 快照**：`admin-common` 新增 `SubjectSnapshot` 记录（不可变、拷贝语义、`mappingKey()` 摘要键）与 `Actor.subject()`；不改既有 claims 校验行为。
2. **身份映射表 V107**：`app.identity_mapping`（tenant+principal 唯一，mapping_key 唯一，snapshot jsonb，revision 单调）；配套 `IdentityMapping`（domain）/`IdentityMappingRepository`（infra）/`IdentityMappingService`（application，`put/find/require`）。
3. **审计关联**：V107 给既有 `app.lifecycle_log` 补可空列 `run_id`/`credential_id`（不新建重复表）；`IntegrationService` 凭据生命周期审计带上 credential_id；新增 `AuditLinkService`（记录运行关联 + 从业务用户/凭据反查运行）。

写入语义（X-5 表，定死）：**UPSERT**——同一 `(tenant_id, principal_id)` 只保留一行当前映射；快照未变（mapping_key 相同）重放为无操作（revision 不推进）；快照变化整行替换且 revision 单调 +1；读取不存在的映射显式 404 `IDENTITY_MAPPING_NOT_FOUND`，禁止静默造数据。库层 `UNIQUE(tenant_id, principal_id)` 兜住绕过服务的裸写。

不在范围：`X-User-*` 出站注入（技能侧后续卡 S-3）、桥 18095 身份映射（外部仓 X-3）、JWT 中期改造（4.13）、guard 调用点改写（authority 包在本卡白名单外；本卡提供快照源，调用点改造归后续卡）。

## 2. 运行环境

- JDK：Temurin `21.0.12+8`（`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`）
- PG：PostgreSQL 16.15，`127.0.0.1:25432`，用户 `agent_v12`，密码经 `source .tools/env-load.sh` 注入（不落盘）
- 测试库：专用库 `agent_platform_iam_identity_v12`（测试自建，仅本卡使用）；另触碰既有专用库 `agent_platform_iam_policy_v12`（V105→V107 旧数据迁移验证，与既有 `IntegrationPolicyDatabaseTest` 同库）
- admin-common 先 `install -DskipTests`（app-management 依赖其 test-jar 与新 API）；install 前 `git status` 确认 admin-common 只有本卡改动

## 3. 执行的命令原文

```bash
# 基线（改动前，app-management 全量）
source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -pl app-management test "-DargLine=-Xmx1024m"

# 红（新测试先行，两模块分别跑出编译红）
mvn -B -pl admin-common test -Dtest='ActorSubjectSnapshotTest'
mvn -B -pl app-management test -Dtest='IdentityMappingPostgresTest,AuditLinkPostgresTest' "-DargLine=-Xmx1024m"

# install admin-common（app-management 需要新 Actor API）
mvn -B -pl admin-common install -DskipTests

# 定向验收（两模块多类同跑；含既有 IntegrationPolicyDatabaseTest 验证 V107 在旧库上迁移）
mvn -B -pl admin-common,app-management test \
  -Dtest='ActorSubjectSnapshotTest,CommonBoundaryTest,IdentityMappingPostgresTest,AuditLinkPostgresTest,IntegrationPolicyDatabaseTest' \
  "-DargLine=-Xmx1024m"

# 回归对照（改动后 app-management 全量）
mvn -B -pl app-management test "-DargLine=-Xmx1024m"
```

原始输出：`baseline-app-management-full.log`、`red-admin-common-compile.log`、`red-app-management-compile.log`、`green-admin-common.log`、`green-app-management-first-run.log`（含 jsonb 转换/种子列两处修正前的失败）、`acceptance-targeted.log`、`regression-app-management-full.log`。

## 4. 结果（原文摘录）

红（先红后绿，编译期红，全文见 red-*.log）：

```
[ERROR] .../ActorSubjectSnapshotTest.java:[26,9] 找不到符号
[ERROR]   符号:   类 SubjectSnapshot
[ERROR] .../ActorSubjectSnapshotTest.java:[26,41] 找不到符号
[ERROR]   符号:   方法 subject()
[ERROR]   位置: 类型为cn.agentplatform.common.Actor的变量 actor
[ERROR] .../AuditLinkPostgresTest.java:[154,20] 无法将类 ...IntegrationService中的方法 audit应用到给定类型;
[ERROR]   需要: java.lang.String,java.lang.String,java.lang.String
[ERROR]   找到:    java.lang.String,java.lang.String,java.lang.String,java.lang.String
[ERROR] .../IdentityMappingPostgresTest.java:[50,57] 找不到符号  符号: 类 IdentityMappingService
```

绿（定向验收，`acceptance-targeted.log`）：

```
[INFO] Running cn.agentplatform.common.ActorSubjectSnapshotTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running cn.agentplatform.common.CommonBoundaryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0        (admin-common)
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ...IdentityMappingPostgresTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ...AuditLinkPostgresTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ...IntegrationPolicyDatabaseTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0       (app-management)
[INFO] BUILD SUCCESS   （合计 19 项，0 失败 0 错误）
```

V107 在旧数据上可跑（既有库 `agent_platform_iam_policy_v12` 带旧数据从 V105 直接迁到 V107，`acceptance-targeted.log`）：

```
03:13:31.572 [main] INFO ...DbMigrate -- Current version of schema "app": 105
03:13:31.586 [main] INFO ...DbMigrate -- Migrating schema "app" to version "107 - identity mapping"
03:13:31.791 [INFO] ...DbMigrate -- Successfully applied 1 migration to schema "app", now at version v107
```

回归对照（`regression-app-management-full.log` vs `baseline-app-management-full.log`）：

```
基线：  Tests run: 149, Failures: 0, Errors: 19, Skipped: 0   BUILD FAILURE（Y-4 夹具债务）
改动后：Tests run: 159, Failures: 0, Errors: 19, Skipped: 0   BUILD FAILURE（同一批）
新增失败 = 0（+10 全为本卡新测试：IdentityMapping 6 + AuditLink 4）
```

既有 19 Error 明细（与基线逐条同签名，Y-4 夹具债务，本卡未触碰）：
- `AppCopyConcurrencyTest.database` ×1（`agent_platform_app_copy_test` 库不存在）
- `DraftMetadataHttpTest.realDraftIdentityIsTenantScopedAndRequiresServiceAndEditor` ×1（ApplicationContext 加载失败）
- `IntegrationPolicyResolverHttpTest` ×15（`PORTAL_CEILING_INVALID`）
- `PublishTransactionTest` ×2（`缺少当前授权上下文`）

## 5. 未覆盖与已知失败

- **既有 19 Error 未修复**（Y-4 夹具债务，X-7 规则不修），对照口径为不新增，见上表。
- **guard/出站调用点未改写**：`Actor.subject()` 已提供快照源，但 `common/authority`（StreamAuthorityGuard 等）与各模块 guard 调用点不在本卡白名单，guard 改用快照属后续卡；`X-User-*` 出站注入（技能侧 S-3）未做。
- **`IdentityMappingService`/`AuditLinkService` 无 HTTP 出口**：按派发只落 domain/application/repository 层服务；HTTP 暴露与授权路由归消费方卡片，当前不存在未授权暴露面。
- **`recordRunLink` 不反查引擎运行存在性**：run 引用是跨服务事实（engine.run.public_id），本卡只做形状与租户归属校验；跨服务存在性校验需引擎侧配合，未覆盖。
- **并发语义**：UPSERT 以唯一约束 + 行级 update 串行化；同主体两笔并发写入且快照不同时，后提交者胜出（最新验签 claims 语义），无分布式锁，属定死语义的一部分。
- **IntegrationService 审计关联**只覆盖 issue/revoke/policy 三条凭据生命周期审计行（直接列校验测试）；HTTP 层端到端（issue→反查）被既有 Y-4 夹具债务阻断，未跑通。
- 既有失败单列：见 §4 回归对照。

## 6. 回滚方式

- 代码回滚：还原 `Actor.java`、删除 `SubjectSnapshot.java` 与测试；还原 `IntegrationService.java`；删除 `IdentityMapping*`、`AuditLink*` 与两个 PG 测试类（本卡未提交，未做 git add/commit）。
- V107 down（需手动执行，Flyway 社区版无自动 down；drop 前确认无在途写入窗口）：

```sql
DROP INDEX IF EXISTS app.lifecycle_log_credential;
DROP INDEX IF EXISTS app.lifecycle_log_principal_run;
ALTER TABLE app.lifecycle_log DROP COLUMN IF EXISTS credential_id;
ALTER TABLE app.lifecycle_log DROP COLUMN IF EXISTS run_id;
DROP INDEX IF EXISTS app.identity_mapping_key_unique;
DROP TABLE IF EXISTS app.identity_mapping;
```

- V107 为纯增量（新表 + lifecycle_log 两个可空列 + 两个部分索引），down 不删除任何既有数据；`agent_platform_iam_identity_v12` 为本卡专用测试库，可整体 `DROP DATABASE agent_platform_iam_identity_v12` 清理；`agent_platform_iam_policy_v12` 已迁至 V107，如需回到 V105：先执行上述 down，再 `DELETE FROM app."flyway_schema_history" WHERE version='107';`，或直接重建该测试库（测试自建，无持久资产）。
