# MNT-05 子步1（A 席）：运行快照准备抽取为 RunSnapshotService

- 日期：2026-11-04
- 席位：A
- 基线 HEAD：`49e6508b`（INT-05 宿主 API 子步）
- 范围：`engine-runtime` 快照准备协调拆分（INT-06 前置）
- 未触：Controller、数据库结构、`FlowDriver`/`AgentDriver`/`RunScheduler`/`TraceStore` 实现、`createInternal` 的事务与锁、SEM-05.1 语义位。

## 一、原责任 → 新责任

| 原方法（RunService） | 输入 | 输出 | Actor/授权 | 事务/幂等 | 回调所有者 | 新所有者 |
| --- | --- | --- | --- | --- | --- | --- |
| `verifyCandidate` | snapshot、req、code | 校验后 snapshot（否则 409 `CANDIDATE_MISMATCH`） | 无（纯函数） | 无事务；幂等 | 无 | `RunSnapshotService` |
| `versionRefs` | source、frozen、req | 运行版本引用 Map | 无 | 无事务；纯读 | 无 | `RunSnapshotService` |
| `freezeModelRefs` | snapshot、trigger、actor | 写 `dependencyVersions.modelRefs` | Actor；DEBUG 才允许冻结缺失模型引用 | 在 `store.atomic` 之外；网络不进长事务 | 模型绑定冻结 HTTP 调用 | `RunSnapshotService` |
| `prepareSnapshot`（2 参/3 参） | source、actor、controlledDraftId | 冻结后的 snapshot | Actor：guard 角色/属性校验（403 `APP_GUARD_DENIED`） | 无事务；同一输入幂等 | skill/prompt/ontology 读取 HTTP | `RunSnapshotService` |
| `capabilitySources`/`validateCapabilitySkill`/`freezeCapabilities`/`validateCapabilitySnapshot`/`capabilityConflict` | snapshot/config | 能力包来源、选择与校验 | 无额外授权 | 无事务；纯计算 | 无 | `RunSnapshotService`（收敛为私有/包可见） |
| `applyOntologyContext` | config、snapshot、actor | `config.ontologyContext`、`snapshot.contextPack` 审计 | Actor 透传给 context pack 解析 | 无事务；网络不进长事务 | 语义空间绑定/取包 HTTP | `RunSnapshotService` |
| `initialBudget` | config | 预算与 deadline | 无 | 读 `Clock` | 无 | 保留在 `RunService`（与 `createInternal` 同一预算边界） |
| `createInternal`/受理、调度、恢复 | 原样 | 原样 | 原样 | 原样（`store.atomic` 与 advisory lock 未动） | 原样 | `RunService`（兼容门面 + 调度入口） |

## 二、改动文件

| 文件 | 变更 |
| --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunSnapshotService.java` | 新增 `@Service`；承载上表快照方法；`RuntimeClients` + `Clock`（`ObjectProvider` 缺省 `Clock.systemUTC()`）；可选 `OntologyContextPacks`；`promptDigestCheck`/`pinResolutionEnabled`/`pinEnvironment` 受控 setter |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 3232 → 2690 行；新增 `final RunSnapshotService snapshots`；新增 `@Autowired` 6 参构造器注入 bean；旧构造器链保留（最深构造器自建默认快照服务）；快照 setter 改为委托；快照方法改为薄委托；新增包可见 `snapshots()` 供受控测试装配断言 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/PromptFreezeDigestTest.java` | 原反射私有字段 `promptDigestCheck` 改为调用装配 setter；断言未改 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/CapabilityRunSnapshotTest.java` | 新增 `ENGINE_PG_URL` 覆盖（缺省仍 `127.0.0.1:25432/agent_platform_engine_test`） |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineTestDatabase.java` | 同样新增 `ENGINE_PG_URL` 覆盖（缺省行为不变） |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunSnapshotAssemblyTest.java` | 新增：真实 Spring 装配（`AnnotationConfigApplicationContext`）断言 `RunService` 收到容器内 `RunSnapshotService` bean |

## 三、不变量

1. 公开 Controller、run/call/attempt 身份、`versionRefs` 字段名与取值规则不变。
2. `createInternal` 的受理顺序、幂等（同 requestId 不增 run）、`store.atomic` 事务与 `pg_advisory_xact_lock` 锁顺序不变。
3. 快照准备仍在 `store.atomic` 之外；skill/prompt/ontology/model-binding 网络调用不进入长事务。
4. `RunService` 旧构造器全部保留，直接 `new RunService(...)` 的既有夹具无需新增可空旁路。
5. 快照行为与错误码逐字保留（`CANDIDATE_MISMATCH`、`SKILL_*`、`PROMPT_TEMPLATE_*`、`CAPABILITY_*`、`CONTEXT_PACK_*`、`MODEL_BINDING_REQUIRED`、`APP_GUARD_DENIED`）。
6. 快照配置（contextPacks/promptDigestCheck/pin*）仍可从 `RunService` 的既有 setter 设置，内部委托到快照服务。
7. `RunSnapshotService` 作为 bean 由容器注入；`RunService` 不再自行持有 contextPacks/pin 字段。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `python .tmp-devdb/mnt05_split.py` | `ok 2690`；新增 `RunSnapshotService.java`，`RunService.java` 3232→2690 行 |
| 2 | `mvn -q -pl engine-runtime -am -DskipTests compile` | exit 0 |
| 3 | `mvn -q -pl engine-runtime -am -DskipTests test-compile` | exit 0；源码无 `new RunService(...,null)` 六参二义 |
| 4 | `-Dtest=PromptFreezeDigestTest` | 首轮 `NoSuchFieldException`（字段迁走）→ 改走 setter 后 4/4 通过 |
| 5 | `-Dtest=RunSnapshotAssemblyTest` | 1/1 通过（`snapshots` 字段与 `snapshots()` 均 sameAs 容器 bean） |
| 6 | `-Dtest='RunServiceContextPackTest,RunToolProfileTest,RunObservationDimensionsTest'` | 9/9、13/13、1/1 通过 |
| 7 | `ENGINE_PG_TEST=true ENGINE_PG_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_test -Dtest=CapabilityRunSnapshotTest` | 1/1 通过（真实 PG，Flyway `engine` schema 已 up to date；25432 为 35432 的本地转发） |
| 8 | 合并 7 类（含 `RunAdmissionHttpTest`） | `Tests run: 38, Failures: 0, Errors: 5`；5 个 error 全在 `RunAdmissionHttpTest`，根因既有 `BeanDefinitionOverrideException`（`InitialChatAdmissionFixture$Receiver.decoder` 与 `PortalRunEventsJoinedEngine$Receiver.decoder` 同名） |
| 9 | 基线对照：`git stash push -u` 后 HEAD 复跑 `PublishedAdmissionHttpPostgresTest`/`RunQuotaAdmissionPostgresTest` | 同样连接失败（`CannotGetJdbcConnection` / Flyway 连接失败），与本次改动无关；已 `git stash pop` 恢复 |
| 10 | PG 35432 再次探测 | `connection refused`（无 postgres 进程），合并复跑无法完成；第 7/8 轮 PG 证据保留 |

基线对照：`docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/method-summary/nonpass.csv` 已记录 `RunAdmissionHttpTest` 5 例 error、`InitialChatAdmissionHttpPostgresTest` 10 例 error（engine-runtime 共 117 条 nonpass）；`CapabilityRunSnapshotTest`/`RunServiceContextPackTest`/`PromptFreezeDigestTest`/`RunToolProfileTest`/`RunObservationDimensionsTest` 均不在 nonpass 中。

## 五、未覆盖

1. 受影响的 CHAT/FLOW 受理 HTTP 正负例未能在本环境复跑：`RunAdmissionHttpTest` 为基线既有 bean 冲突；PG 35432 目前不可用。
2. `RunQuotaAdmissionPostgresTest`、`PublishedAdmissionHttpPostgresTest` 的库连接在本环境不可用（验收要求依赖有效 PG 窗口）。
3. `RunSnapshotService` 内部新格式化由脚本生成，未做逐行人工风格整理（行为等价，编译与测试通过）。

## 六、契约缺口（只登记，未私改）

1. `engine` 测试库名 `agent_platform_engine_test` 在本机不存在，且测试用户无 CREATEDB；本次以 `ENGINE_PG_URL` 复用 `agent_platform_test` 的 `engine` schema。测试夹具是否需要独立的建库/迁移前置，属仓库级约定问题。
2. `RunAdmissionHttpTest` 的 bean 名冲突属既有测试装配缺陷（基线已记录），本卡未改其断言。
3. `RunQuotaAdmissionPostgresTest` 未识别 `ENGINE_PG_URL`（另有自己的连接方式），基线对照仍失败。

## 七、回退

`git revert <本卡提交>`；不涉及数据库结构变更，无数据回填；运行事实（run/versionRefs/authorization）保持不变。回退后 `RunService` 恢复为单一类，测试反射字段随之恢复。
