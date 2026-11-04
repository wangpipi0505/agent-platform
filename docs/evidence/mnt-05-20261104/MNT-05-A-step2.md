# MNT-05 子步2（A 席）：受理协调抽取为 RunAdmissionService

- 日期：2026-11-04
- 席位：A
- 基线 HEAD：`c057b848`（MNT-05 快照子步）
- 范围：engine-runtime 受理/重复请求判定抽取；`RunScheduler` 容量预约协作；`RunAuthority` 装配委托
- 未触：公开 Controller、网关、数据库 schema、`versionRefs` 形状、模型/业务 I/O 路径、调度与恢复循环

## 一、原责任 → 新责任

| 原责任（RunService） | 新责任（RunAdmissionService） | 输入/输出/Actor | 保留授权/事务/幂等 |
| --- | --- | --- | --- |
| `requireNoAmbientTransaction` 外置 | 同名方法 | `boolean protectedAdmission`；无返回 | 在任何 I/O 前抛 403 `AUTHORITY_TRANSACTION_FORBIDDEN` |
| `requirePublishedRequest` | 静态同名方法 | `req/trigger/trustedSnapshot/controlledDraftId/recordedReplay/suppliedTicket/acceptedReservation`；无 Actor | 仅 iam 保护受理；403 早于 source/channel HTTP |
| `admissionProof(code,actor)` | `opening(code,actor,protected)` | Actor、app code；返回 `RetainedAuthorization` | 校验 gateway/engine/action/portal 委托与 `code@rev`；缺装配 403 `IAM_ADMISSION_UNAVAILABLE` |
| `REPLAY_METADATA` 查询 | `existing(actor,req,protected)` | Actor；返回已受理 run 行或 null | 读，不写；无 requestId 直接 null |
| `admissionTransaction` | `bind(code,actor,protected)` / `begin(proof)` | 返回/接收 `RetainedAuthorization` | `bind` 在事务外；`begin` 仅事务内且设置 2s/1.5s 本地超时 |
| 幂等复核 + advisory lock | `lockAndReuse(actor,req,code,trigger,protected,proof)` | 返回已存在 run id 或 null | 事务内 `pg_advisory_xact_lock(tenant:principal:requestId)`；legacy 路径仍 `verifyReplay` |
| 容量预约竞态复核 | `reserve(...)` | 返回 `Reservation(slot,replay)` | 仅 `RunAdmissionRejected` 时短事务复核；`ENGINE_BUSY` 原样抛出 |
| `verifyReplay` / 摘要比对 | 同名方法 | prior 行、code、req、trigger | 409 `REQUEST_CONFLICT` 语义不变 |
| `requireInitialCoverage` | 同名方法 | `RetainedAuthorization` | 仍是事务前 + 事务内双重校验 |

留在 `RunService`：`createInternal` 编排、source 获取、`prepareSnapshot`、能力/提示词冻结、候选摘要与 DAG、`runQuota`、持久化插入、`scheduler.created`、`replayPublished`/`status`、调度与恢复入口。

## 二、改动文件

| 文件 | 行数 | 变化 |
| --- | --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 2521（原 2690） | 新增 `admissions` 字段与生产 7 参构造器；兼容 6 参构造器内建默认服务；10 参构造器委托 12 参；setter 同时委托；`createInternal` 改调受理服务；删除 6 个已迁移私有方法 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAdmissionService.java` | 323 | 新增；`@Service`，构造 `(TraceStore,RunScheduler)`，可选 setter 注入 `RunAuthority`/`RunAuthorityStore` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunAdmissionServiceTest.java` | 180 | 新增 8 例 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunSnapshotAssemblyTest.java` | 51 | 扩展为同时断言 `RunSnapshotService`/`RunAdmissionService` 同一 bean |

未暂存：`skill-registry/src/main/java/cn/agentplatform/skill/application/DefinitionLookupSkills.java`、`skill-registry/src/main/java/cn/agentplatform/skill/infra/SemanticDefinitionClient.java`、`skill-registry/src/main/resources/skills/`（O 席未跟踪文件，本卡不触碰）。

## 三、不变量

1. 公开 Controller 的路径、方法、请求体与响应字段不变；run/call/attempt 身份与 `versionRefs` 形状不变。
2. 受理事务边界不变：仍由 `store.atomic` 提供外层提交；`begin` 只在事务内执行。
3. 锁顺序不变：`pg_advisory_xact_lock(tenant:principal:requestId)` 仍在既有位置、同一 key。
4. HTTP 仍在事务外：`opening`、source 获取、`runQuota`、`bind` 均在 `store.atomic` 之前；事务内不新增网络调用。
5. 授权失败优先：保护受理在入参校验后立刻取 `opening`；缺 `RunAuthority` 装配返回 403 `IAM_ADMISSION_UNAVAILABLE`，早于模型/业务 I/O。
6. 幂等语义不变：相同 `(tenant,principal,requestId)` 先查后锁，命中返回既有 run，不新增行；legacy 路径仍做输入摘要比对。
7. 兼容门面：`RunService` 保留 `runAuthority`/`admissionStore` setter 字段并委托给受理服务，`RunAuthorityAssemblyTest` 反射断言仍成立；旧 `new RunService(...)` 夹具无需改构造。
8. 测试装配无旁路：新增 `admissions()` 为包可见受控入口；Spring 装配断言 `RunService` 收到的 `RunAdmissionService` 与容器 bean 为同一实例。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -q -pl engine-runtime -am -DskipTests compile` | exit 0（补 3 处 import 后） |
| 2 | `mvn -q -pl engine-runtime -am -DskipTests test-compile` | exit 0 |
| 3 | `mvn -q -pl engine-runtime -am -Dtest='RunAdmissionServiceTest,RunSnapshotAssemblyTest,RunAuthorityAssemblyTest,RunServiceContextPackTest,PromptFreezeDigestTest,RunToolProfileTest' -Dsurefire.failIfNoSpecifiedTests=false test` | exit 0；8/1/4/9/4/13 全绿（39 例） |
| 4 | 首轮 `RunAdmissionServiceTest` | 2 例失败（`existing`/`lockAndReuse` 返回 null）：Mockito 对 varargs `queryForList(String,Object...)` 的桩未命中；改为 `doReturn(List.of(row)).when(jdbc).queryForList(anyString(), any(Object[].class))` 后转绿（断言未改） |
| 5 | `mvn -q -pl engine-runtime -Dtest=RunObservationDimensionsTest test`（infra 包） | exit 0；`Tests run: 3, Skipped: 3`——类上 `@EnabledIfEnvironmentVariable(ENGINE_PG_TEST=true)`，PG 不可用故跳过，0 失败 |
| 6 | 真实 PG 定向（`AGENT_DB_*` + `ENGINE_PG_TEST=true`） | 无法执行：35432/25432 均 down；本机无 postgres 进程、无 `C:\Program Files\PostgreSQL`、无 docker 容器 |

## 五、未覆盖

- 真实 PG 上的受理正负例（含同 requestId 不增 run、越权先于 I/O）未跑；仅单测 + 装配测试。
- `RunObservationDimensionsTest` 因 `ENGINE_PG_TEST` 门控在无 PG 时整体跳过（3 skipped），未提供观测维度回归证据。
- `RunAdmissionHttpTest` 5 例仍受既有 `decoder` bean 同名冲突（`InitialChatAdmissionFixture$Receiver` 与 `PortalRunEventsJoinedEngine$Receiver`）阻塞；属基线问题（`docs/evidence/2026-10-28-EX-00/baseline/.../nonpass.csv`）。
- `RunQuotaAdmissionPostgresTest`、`PublishedAdmissionHttpPostgresTest` 需 PG，未跑。
- `requireInitialCoverage` 事务前/事务内双调用仅经代码走查确认，未由 HTTP 用例覆盖。

## 六、白名单外改动 / 契约缺口

- 白名单外仅改动测试（`RunSnapshotAssemblyTest`）与包可见 `admissions()` 访问器（沿用 `snapshots()` 既有受控模式）。
- O 席未跟踪的 skill-registry 文件保持原样，未暂存。
- 契约缺口：受理服务仍复用既有 `ENGINE_BUSY`/`REQUEST_CONFLICT`/`IAM_ADMISSION_UNAVAILABLE` 码，未新增错误码；`RunScheduler.Admission` 的 `persist/completeReservation/close` 仍由 `RunService` 持有，受理服务只借 `slot()` 与竞态复核。

## 七、回退

- `git revert <本子步提交>`；`RunService` 恢复内联受理逻辑，删除 `RunAdmissionService` 与两个测试断言扩展。
- 数据影响：无 schema/数据迁移；已受理 run 与授权事实不受影响。
