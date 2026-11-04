# 夹具规范化批次（A 席）：夹具数据库坐标统一走环境约定

- 日期：2026-11-04
- 席位：A（夹具规范化单列批次，纯测试基建）
- 基线 HEAD：`451031e1`（INT-09 接入包）
- 范围：app-management 的 `PublishPinAuditPostgresTest`、`PublishedMetadataHttpTest`、`PublishTransactionTest`；engine-runtime 的 `CapabilityRunSnapshotTest`、`RunAuthorityLiveResumeTest`
- 未触：任何产品代码、断言、用例语义；其他席位的并行改动（`web/tests/onboarding-templates.test.ts`、`docs/evidence/int-07-20261104/assistant-sdk-structure.json`）

## 一、原责任 → 新责任

| 类 | 原坐标获取 | 现坐标获取 |
| --- | --- | --- |
| `PublishPinAuditPostgresTest` | admin 硬编码 `127.0.0.1:25432/postgres` + `agent_v12`，自建 `agent_platform_pin_audit_v12` | `TestDatabase.url()/user()/password()`，不建库 |
| `PublishedMetadataHttpTest` | admin 同上；业务库硬编码 `25432/agent_platform_iam_metadata_v12` | `TestDatabase.url()/user()/password()`；`current_database()` 断言改 `TestDatabase.database()` |
| `PublishTransactionTest` | 已使用 `TestDatabase`（本次核对确认，无需改） | 不变 |
| `EngineTestDatabase` | 包可见、未文档化 | `public` + 私有构造；`ENGINE_PG_URL` 优先，未显式给 URL 时才保留 25432 守卫与自动建库 |
| `CapabilityRunSnapshotTest` | `ENGINE_PG_URL` 为空时硬编码 `25432/agent_platform_engine_test` | `EngineTestDatabase.url()` |
| `RunAuthorityLiveResumeTest` | `EX26_LIVE_DB_URL` 静态默认硬编码 `25432/...`，`USER` 默认 `agent_v12` | 静态仅解析 `USER = EngineTestDatabase.user()`；`@BeforeAll` 用 `value("EX26_LIVE_DB_URL", EngineTestDatabase.url())` 延迟解析 |

## 二、不变量（未改断言 / 用例语义）

1. 每一处改动只替换“连接 URL / 用户名 / 口令 / 库名断言”的来源，不改任何 `assert*`、响应形状、租户隔离、迁移顺序检查。
2. `PublishPinAuditPostgresTest` 仍以随机租户、随机应用码隔离，仍验证四态落库与 T-pin 兼容；迁移仍在同一事务/同一实例上按 105→106→107 存在性检查。
3. `PublishedMetadataHttpTest` 仍验证发布元数据 HTTP 投影；`current_database()` 从硬编码库名改为 `TestDatabase.database()`，断言目标从“连到某个专用库”变为“连到 `AGENT_DB_URL` 约定的库”。
4. engine 侧保留 25432 专用库守卫语义：仅当 `ENGINE_PG_URL` 未显式提供时才回退 `AGENT_DB_URL`/默认值并校验 `:25432/`；显式 URL 时不改写、不建库。
5. 未新增任何授权旁路、未放宽安全模式、未 mock 产品校验；仍由真实 PG + 真实迁移驱动。

## 三、命令与结果

环境：`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`、`AGENT_DB_USER=$PG_AGENT_PLATFORM_TEST_USER`、`AGENT_DB_PASSWORD=$PG_AGENT_PLATFORM_TEST_PWD`、`ENGINE_PG_TEST=true`、`ENGINE_PG_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test`、JDK21；口令只从私有准备根读入，未回显。

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1（改造前） | `mvn -q -pl app-management -am -Dtest='PublishPinAuditPostgresTest,PublishedMetadataHttpTest,PublishTransactionTest' ... test` | `PublishPinAudit` 0/9（`databaseAndSecrets:65` CannotGetJdbcConnection：25432/postgres + agent_v12）；`PublishedMetadataHttp` 0/9（`start:52` 同因）；`PublishTransaction` 0/13（同因） |
| 2（改造后） | 同上 | `PublishPinAudit` **9/9**、`PublishedMetadataHttp` **9/9**、`PublishTransaction` **10/13**（3 errors，见四） |
| 3（HEAD 对照） | `git stash push -- <本批 6 文件>` 后仅跑 `PublishTransactionTest`（同环境） | **13 run / 3 errors，与轮次 2 完全一致** → 本批改动未影响该类，3 红为存量 |
| 4 | `mvn -q -pl engine-runtime -am -Dtest='CapabilityRunSnapshotTest,RunAuthorityLiveResumeTest' ... test` | `CapabilityRunSnapshotTest` **3/3**；`RunAuthorityLiveResumeTest` **3 skipped**（`EX26_LIVE_RESUME` 未置 true） |

## 四、仍红项（如实记录，未改断言）

1. `PublishTransactionTest.globalCredentialLookupRequiresDedicatedGatewayIdentity`：`PlatformException 缺少当前授权上下文`（`IntegrationService.requirePublishedVersion` → `AuthorityContext.requireFor` 无 frame）。`docs/evidence/2026-10-28-EX-00/baseline/.../nonpass.csv` 已列该例为存量红项。
2. `PublishTransactionTest.integrationSecretOnlyShownOnceAndRevocationPersists`：同因，同属存量红项。
3. `PublishTransactionTest.candidateAndPublishedVersionUseIdenticalFrozenModels`：stub 返回 `bindingId` 整数与 `sha256:"freeze-1"`（非 64hex），被当前产品侧模型冻结摘要校验拒绝（`请求校验失败: /internal/model-bindings/freeze`）。属夹具与现行产品契约不一致，非坐标问题；按“仅改连接获取”约束未动。
4. `<RunAuthorityLiveResumeTest>` 未真跑：需 `EX26_LIVE_RESUME=true` 且 18086 authority-service（当前 down）。本轮只验证“坐标可延迟解析、默认跳过”。

## 五、未覆盖

- `EX26_LIVE_RESUME` 真跑（依赖 18086 authority-service 与 IAM client secret）。
- 35432 上 `dev_ap_test` 无 CREATEDB、无 `postgres` 库 CONNECT 权限，故“自建专用库”路线不可行；本轮改为复用 `AGENT_DB_URL` 已准备库，若未来要恢复 fresh-DB 语义需 DBA 预建库或授予 CREATEDB。
- `PublishTransactionTest` 的 3 个红项属产品/契约缺口，未在本批修复。

## 六、数据影响

- 35432/`agent_platform_test` 的 `app` schema 由 17 条 / max 115 推进到 18 条 / max 116（Flyway 应用 V116），engine schema 保持 28 条 / 226。
- 25432/`agent_platform_engine_test` 与 35432/`agent_platform_engine_test` 均保持 30 条 / 228，无新增迁移。
- 测试写入均按随机租户/应用码隔离，未删除、未改动既有业务数据。

## 七、回退

`git revert <本次提交>` 即可恢复原夹具；不涉及数据库结构回退（仅测试运行时应用既有 V116）。回退后 app 夹具重新依赖 25432/postgres + 专用库创建权限。
