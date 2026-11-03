# MNT-06 AP 发布子步（AppPublicationService 抽取）实施记录

- 卡：MNT-06 / 发布子步（`AppLifecycleService` 生命周期子步之后）
- 席位：A（AP 后端）
- 时间：2026-11-03
- 基线 HEAD：`878ce093`（含 MNT-08 `fd73758d`、生命周期子步 `e2b12d3f`、INT-02 `de21f1d5`、INT-03 子步1 `0ca032f4`）
- 环境：真实 PostgreSQL `jdbc:postgresql://127.0.0.1:35432/agent_platform_test`，用户 `dev_ap_test`（口令从私有准备根读入，未写入仓库、未打印）

## 1. 原责任 → 新责任

| 原位置 | 新位置 | 说明 |
| --- | --- | --- |
| `AppService.precheck` | `AppPublicationService.precheck` | 预检规则、评测门禁、环境证据、豁免判定逐行搬运 |
| `AppService.commit` | `AppPublicationService.commit` | 事务内复核/固化/发版、事务外 pin，逐行搬运 |
| `AppService.createWaiver` / `revokeWaiver` | 同名 | 豁免增删与撤销窗口 |
| `AppService.pinStatus` | 同名 | pin 观测读取（V106 前旧发布返回空 Map 的语义保持） |
| `AppService.skillRefs` | 同名 | 由 `referenceChecks` 与 `attachModels` 共用 |
| `AppService` 私有：`validPublicationEvidence`、`requirePublicationEnvironment`、`publishGate`、`waiverActive`、`waivers`、`check`、`parameterChecks`、`typeChecks`、`resolveSkill`、`referenceChecks`、`validateSchema`、`recordPin`、`ontologyReleaseId`、`pin`、`releasedCandidate`、`publication` | 同名私有方法 | 只随发布簇一起搬家 |
| `AppService` 的 `publicationTarget` / `waiverEnabled` / `configuredGateMode` / `pinRecordEnabled` / `pinEnvironment` / `pins` / `publicationStore` / 远端地址 | `AppPublicationService.Port` 读取 | 字段仍归 `AppService`，setter 语义不变；Port 在每次调用时读取，因此测试用 setter 改完即时生效 |

`AppService`（1769 → 969 行）保留：草稿生命周期、版本读面、发布读面、候选冻结、模型快照、远端客户端与端到端装配。

## 2. 依赖方向与不变量

1. **单向依赖**：`AppService` → `AppPublicationService`；发布簇不反向持有应用服务实例，只经 `Port` 取读取、远端调用、审计日志与写事务。
2. **同一个写事务**：`Port.tx()` 返回 `AppService` 构造出的那一个 `TransactionTemplate`（同一个 `PlatformTransactionManager`），提交仍在原事务内完成，不存在自调用代理失效或第二个事务。
3. **HTTP 不进事务**：事务外先复核 pin/既有绑定，事务内只读已持久报告；`pin` 与 `recordPin` 仍在事务提交之后各调用一次。
4. **请求与错误码不变**：`idempotencyRequestNo`/`precheckId`/`candidateSha256`/`draftRev` 读取方式、`IDEMPOTENCY_CONFLICT`/`CANDIDATE_CHANGED`/`PRECHECK_INVALID`/`MODEL_SNAPSHOT_MISMATCH`/`APP_STATE`/`EVAL_GATE_BLOCKED`/`EVALUATION_ENVIRONMENT_BLOCKED`/`EVALUATION_ENVIRONMENT_REQUIRED`/`VERSION_NOT_FOUND` 全部原样。
5. **摘要与快照不变**：`AppDigest.semantic`、`AppService.sha`、`candidate`、`attachModels`、`collectModelIds` 仍是同一实现（Port 委托回原方法），版本 `snapshot` 字段集合与写入顺序不变。
6. **pin 失败独立记录**：失败仍只告警、不影响已提交版本与响应；`pinRecordEnabled` 关闭时仍不落库。
7. **无新迁移**：本子步没有新增或修改 `db/migration`。
8. **内存语义**：`released` 仍用 `AtomicReference` 把候选带出事务；重放分支仍读已固化快照。

## 3. 命令与实际结果（真实 PG）

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -o -pl app-management -Dtest='PublishTransactionTest,PublishPinTest,ApplicationLifecycleTest,PublishGateTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 43 跑：25 绿 18 错——全部为 `NullPointerException: this.tx is null`，根因：发布簇在 `this.tx` 赋值之前装配 |
| 2 | 同上（把装配移到构造器末尾后） | 43 跑：21 绿 22 错——`requirePublicationEnvironment` 报"环境证据…不一致"；根因：机械替换把字符串字面量 `"publicationTarget"` 改成了 `"port.publicationTarget()"`，Map 键与证据键对不上 |
| 3 | 同上 + 修正五处字符串字面量 | 43 跑：41 绿 2 错，剩余两项是基线既有失败 |
| 4 | `-Dtest='PublishTransactionTest,PublishPinTest,PublishPinAuditPostgresTest,ApplicationLifecycleTest,PublishGateTest,EvaluationEnvironmentGateTest,PublishedPayloadHttpTest,PublishedMetadataHttpTest,AppCopyConcurrencyTest,AppPinStatusTest,AppSchemaDialectTest'` | 60 跑：54 绿 6 错（详见下） |

第 4 轮逐类：`ApplicationLifecycleTest` 11/11、`PublishGateTest` 11/11、`PublishPinTest` 8/8、`PublishTransactionTest` 13 跑 11 绿 2 错、`AppPinStatusTest` 2/2、`AppSchemaDialectTest` 10/10、`AppCopyConcurrencyTest` 1/1。

6 个错误全部**不是本子步引入**：

- `PublishTransactionTest.globalCredentialLookupRequiresDedicatedGatewayIdentity:350`、`integrationSecretOnlyShownOnceAndRevocationPersists:318`：`Platform 缺少当前授权上下文`。已在基线 worktree（`fd73758d`，`D:/tmp/mnt06-base`）用同一命令逐字复现，属该环境的既有失败。
- `PublishPinAuditPostgresTest`、`PublishedMetadataHttpTest`、`PublishedPayloadHttpTest`、`EvaluationEnvironmentGateTest`：`Connection to 127.0.0.1:25432 refused`。这些用例把库地址写死为 25432 且需要先 `create database` 自建库；本环境 DEV 账号 `createdb=false`，基线同样无法运行。**未改动**：改坐标会让它们在没有建库权限的账号上必然失败，属于环境能力缺口而不是夹具风格问题（记入"未覆盖"并建议由 P 的窗口提供可建库账号后统一处理）。

## 4. 未覆盖

1. 全量回归与真实启动（Spring 装配、`RouteCoverageVerifier`）：本席只跑定向用例，不据此声称通过，留给 P 的串行窗口。
2. 上述四个需要建库权限的 HTTP/审计用例（25432 + `createdb`）。
3. 真实远端 skill/model/monitor/engine 的联调：本子步只保证调用点未变，仍由既有测试的桩与集成验收覆盖。
4. 前端与生成客户端：与本子步无关。

## 5. 提交与回退

- 提交：`refactor(mnt-06): 发布预检与提交抽到独立发布服务，AppService 保留兼容门面`
- 回退：`git revert <sha>`（两个文件整体回退，无迁移、无配置、无权限目录变更）。

## 6. 给复用方的说明

- 新增 `AppPublicationService` 不是第二个发布权威：所有状态仍写在 `app.publish_report`/`app.publish_commit`/`app.app_version`/`app.application`，读写都由 `AppService` 的 `JdbcTemplate` 经 Port 提供。
- 若后续要给它加新的发布规则，改 `AppPublicationService`；读者入口仍是 `AppService.precheck`/`commit`，兼容面在一处。
