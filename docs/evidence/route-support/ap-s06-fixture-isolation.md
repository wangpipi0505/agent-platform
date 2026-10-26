# AP-S06：fixture 与演示数据隔离（实施报告）

- 日期：2026-10-26
- 分支：feat/v1.2-implementation
- 范围：model-gateway、skill-registry、admin-common（authority 目录与测试）、web/admin（来源标识）
- 约束遵守：未触碰 engine-runtime / app-management；未改动任何 M 状态文件（ModelController、ModelStore、ModelCalls、ModelCallsPostgresTest、Routes.java、SystemStatusController 等均只读分析，未编辑）；未 git commit。

## 1. 现状机制（先摸清，后动手）

### 1.1 注入机制结论：无代码级 seed，注入路径是"管理面 API + dev-token"

| 排查点 | 结论 |
| --- | --- |
| Flyway 迁移（model V300–V305 / skill V300–V311） | 仅建表与索引，无任何演示数据 INSERT（grep insert=0） |
| ModelStore / SkillCatalog 启动 seed 逻辑 | 无。两服务均无 ApplicationRunner/CommandLineRunner/静态 seed |
| scripts/（bootstrap、dev-up、seed-sample-agent 等） | 无 provider/技能 seed；seed-sample-agent.ps1 只创建一个示例智能体并复用既有 ENABLED 条目 |
| 运行库实况（agent_platform_dev_v12，只读查询） | `model.provider` 13 行：12 行 fixture/smoke 命名（base_url 指向 127.0.0.1:28400–28414 的本地 fixture 端口），1 行 `deepseek_cloud`（经本地 model-proxy.mjs 的真实链路）；`model.entry` 13 行；`skill.skill` 40 行（36 个 DRAFT，其中 35 个 `smoke_*` 前缀 + 2 个 `openapi_ui_*`） |
| 数据来源 | `tests/smoke-*.mjs/.ps1` 通过 POST /admin/model-providers、POST /admin/skills（AGENT_DEV_AUTH dev-token）创建后残留 |

因此"演示数据注入"的唯一闸门必须落在：**新建入口 + 真实调用出口 + 状态语义** 三处，而非迁移或 seed 代码。

### 1.2 识别约定（事实上已存在，本次固化为显式不变量）

- provider：`code` 含 `fixture`，或以 `smoke_model_` 开头（覆盖全部 12 个演示供应商；`deepseek_cloud` 明确排除，是本地代理的真实链路）。
- skill：`code` 含 `fixture`，或以 `smoke_` 开头（覆盖 35 个演示技能；`openapi_ui_*` 与 `space_visibility` 明确排除，前者是用户经 UI 导入的草稿，可用既有 DELETE 清理）。
- 此后新增演示数据必须遵守该命名约定，否则会被当作托管数据对待。

## 2. 隔离设计

- 开关：`agent.demo-mode`（默认 **false**；env `AGENT_DEMO_MODE`）。默认（生产）模式：禁止注入、禁止 fixture 参与真实调用、状态可辨识；演示模式：smoke/本地联调全流程放行。`.env`（gitignored 本地文件）已加 `AGENT_DEMO_MODE=true` 保持本地现有流程可用。
- 出口守卫（model-gateway `FixtureEgressGuard`，servlet filter，显式注册、排在 Security 之后）：
  - `POST /internal/model/chat`、`/internal/model/chat/stream`、`/internal/model/measure`、`/internal/model-bindings/freeze|validate`：请求体携带 `modelEntryId` 或 `modelBindingRef` 时解析 entry→provider，命中 fixture 且非演示模式 → 409 `SYS-MODEL-FIXTURE`；
  - `POST /admin/model-providers`、`/admin/model-entries`：非演示模式禁止再注入 fixture 命名行 → 409；
  - 有意限制：读取端点与连接测试（`/{id}/test`）不拦截——管理员应能验证 fixture 端点连通性但不用于运行；未识别的请求形状放行，由下游既有校验兜底（守卫只做确定性拒绝，避免误拦未来合法形态）。
- 创建守卫（skill-registry `SkillController.create` → `FixtureSkills.assertCreatable`）：非演示模式注册 `smoke_*`/`fixture` 编码 → 409 `SKILL-FIXTURE`。
- 状态语义：
  - model-gateway `FixtureViewMarker`（ResponseBodyAdvice，仅作用于 ModelController）：`/admin/model-providers*`、`/admin/model-entries*` 回包注入 `source=FIXTURE|MANAGED`（条目在自身与 provider 子对象双层标注）；
  - skill-registry `SkillController.list/get/create` 回包经 `FixtureSkills.withSource` 注入同语义字段；
  - 前端：ProvidersPage / ModelEntriesPage / SkillsPage 对 `source==='FIXTURE'` 行显示"演示 fixture"警示标签；SystemStatus 页新增独立"演示数据（fixture）"卡（灰/橙语义，任何分支都不使用绿色"正常"徽标；汇总读取失败显示"来源未知"）；
  - `/admin/system/status` 本体（app-management）按任务约束不可修改：状态页的 fixture 语义由 SystemStatus.vue 追加读取两个服务的隔离汇总实现，不改变既有健康检查行。
- 清理路径（两服务对称，路径沿用既有 admin 前缀风格）：
  - `GET /admin/model-providers/fixture`、`GET /admin/skills/fixture`：汇总（demoMode、计数、明细、最近 10 条审计）；
  - `POST /admin/model-providers/fixture/purge`、`POST /admin/skills/fixture/purge`：ADMIN 专用。provider 软删（deleted=true + DISABLED）；entry 无 call_log/binding_snapshot 引用则删除、有引用则保留并 DISABLED（历史调用与冻结快照永不物理删除）；skill 纯草稿删除、有发布历史停用、被应用引用（引用探测失败也按有引用处理，default refuse）跳过；动作写审计。
- 审计：新增迁移 `V306__fixture_audit.sql`（model）、`V312__fixture_audit.sql`（skill），每次 purge 一条记录（actor/action/detail），汇总接口可读。
- 启动守卫：两服务各新增 `FixtureStartupGuard`（ApplicationRunner），启动盘点 fixture 行数；非演示模式且 >0 时输出 WARN，指明"已隔离、不参与真实调用"与清理入口。
- authority 目录：`agent-service-authority.json` 追加 4 行（gateway→model/skill 的新 admin 路由，与既有 proxy.action 命名一致）；`AuthorityPolicyTest` 未改动（见 §5 存量问题）。

## 3. 改动清单（文件:要点）

新增（model-gateway）：
- `model-gateway/src/main/java/cn/agentplatform/model/application/FixturePolicy.java`：demo-mode 开关、命名约定判定、创建拒绝（409）。
- `model-gateway/src/main/java/cn/agentplatform/model/application/ModelFixtureService.java`：汇总/purge/审计（事务内，PG 权威源）。
- `model-gateway/src/main/java/cn/agentplatform/model/application/FixtureStartupGuard.java`：启动盘点日志（WARN/INFO 双语义）。
- `model-gateway/src/main/java/cn/agentplatform/model/api/FixtureAdminController.java`：GET `/admin/model-providers/fixture`、POST `/admin/model-providers/fixture/purge`。
- `model-gateway/src/main/java/cn/agentplatform/model/infra/FixtureEgressGuard.java`：出口守卫（缓存请求体、409 统一响应形状）。
- `model-gateway/src/main/java/cn/agentplatform/model/infra/FixtureGuardConfiguration.java`：filter 显式注册（order 0，Security 之后）与路径收窄。
- `model-gateway/src/main/java/cn/agentplatform/model/infra/FixtureViewMarker.java`：回包 source 标注（advice 仅挂 ModelController）。
- `model-gateway/src/main/resources/db/migration/V306__fixture_audit.sql`。
- `model-gateway/src/test/java/cn/agentplatform/model/application/FixtureIsolationTest.java`：6 项失败路径测试。

新增（skill-registry）：
- `skill-registry/src/main/java/cn/agentplatform/skill/application/FixtureSkills.java`：判定、创建守卫、汇总/purge/审计、withSource。
- `skill-registry/src/main/java/cn/agentplatform/skill/application/FixtureStartupGuard.java`。
- `skill-registry/src/main/java/cn/agentplatform/skill/api/FixtureAdminController.java`：GET `/admin/skills/fixture`、POST `/admin/skills/fixture/purge`。
- `skill-registry/src/main/resources/db/migration/V312__fixture_audit.sql`。
- `skill-registry/src/test/java/cn/agentplatform/skill/application/FixtureSkillsTest.java`：5 项失败路径测试。

修改（最小化）：
- `skill-registry/src/main/java/cn/agentplatform/skill/api/SkillController.java:12-38,46-70`：新增 4 参构造（3 参兼容既有测试，生产装配注入 FixtureSkills）；list/get/create 回包标注 source；create 前置创建守卫。
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmMigrationPostgresTest.java:220-235`：迁移版本钉扎 311→312（AP-S06 新增 V312 的必要回归同步）。
- `model-gateway/src/main/resources/application.yml:28-31`、`skill-registry/src/main/resources/application.yml:21-24`：`agent.demo-mode: ${AGENT_DEMO_MODE:false}`。
- `admin-common/src/main/resources/agent-service-authority.json`：末尾追加 4 行路由（+48 行 diff，未重排既有内容）。
- `.env`（gitignored）：`AGENT_DEMO_MODE=true`（本地演示库现状需要；生产无此变量即默认隔离）。

前端（web/admin，均为最小追加）：
- `web/admin/src/pages/ProvidersPage.vue`：名称/编码列加"演示 fixture"标签。
- `web/admin/src/pages/ModelEntriesPage.vue`：条目随 provider.source 加标签。
- `web/admin/src/pages/SkillsPage.vue`：技能行加标签。
- `web/admin/src/SystemStatus.vue`：新增"演示数据（fixture）"卡（加载两个隔离汇总；灰=已隔离、橙=演示模式放行、读取失败=来源未知；不用绿色）。

## 4. 测试（失败路径先行）与结果

先写测试并确认 RED（实现类缺失，test-compile 失败），再实现转 GREEN：

| 命令（JAVA_HOME=jdk-21；AGENT_DB_PASSWORD/AGENT_REDIS_PASSWORD 取自 .env） | 结果 |
| --- | --- |
| `mvn -pl model-gateway,skill-registry -am package -DskipTests` | 第一次因 skill-registry jar 被运行中的后端 JVM 锁定（repackage rename 失败，环境问题）；`-Dspring-boot.repackage.skip=true` 后 **BUILD SUCCESS**（编译+jar+测试字节码全通过） |
| `mvn -pl model-gateway test -Dtest=FixtureIsolationTest` | **Tests run: 6, Failures: 0, Errors: 0** |
| `mvn -pl skill-registry test -Dtest=FixtureSkillsTest` | **Tests run: 5, Failures: 0, Errors: 0** |
| `mvn -pl model-gateway test`（全量） | 见下方"全量回归"小节 |
| `mvn -pl skill-registry test`（全量） | 见下方"全量回归"小节 |

覆盖的失败路径（先 RED 后 GREEN）：
1. 非演示模式注入拒绝：POST /admin/model-providers（fixture 编码）→ 409 + `SYS-MODEL-FIXTURE`；demo 模式与托管编码放行。
2. 非演示模式真实调用拒绝：chat/measure/freeze 按 entryId → 409；`modelBindingRef` 冻结引用路径 → 409；托管条目、读取端点、demo 模式放行。
3. 清理范围与门禁：EDITOR purge → 403；purge 仅命中 fixture 行（软删/删条目/留引用停用），托管行不可触碰；审计写 1 条；purge 后汇总归零。
4. 状态语义：provider/entry 回包 source=FIXTURE/MANAGED；无 code+baseUrl/provider 形状的回包（创建结果、统计）不被改写；advice 只挂 ModelController。
5. 技能侧：非演示模式注册 `smoke_*` → 409 `SKILL-FIXTURE`；purge 对 纯草稿 REMOVED / 有发布历史 DISABLED / 被引用 SKIPPED_REFERENCED 分流；列表 source 标注；汇总含 demoMode。

测试期间发现并遵守的平台不变量：`skill_version` 触发器禁止删除/改写已发布版本（V302），purge 对有发布历史的技能只能停用；测试清理也按此留证。

## 5. 未覆盖范围与存量问题

1. **`AuthorityPolicyTest.catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards` 存量红**：改动前即失败（line 160 扫描器不识别 `public final class`，PortalSessionController 属基线遗留），本次未修（不属 AP-S06，且修复会连带要求为其未入目录的模板补目录）。本任务的 4 个新路由已按规范补入 `agent-service-authority.json`；该测试修复后应将目录断言计数 159/160 相应上调（catalog +4，actual +4 + 协作者 SystemStatusController 的 1 个模板）。
2. **`/admin/system/status` 聚合端点本身未改**：属 app-management 且为协作者未提交新文件（SystemStatusController.java、SystemStatus.vue 均未跟踪）。状态页的 fixture 语义由前端追加汇总卡实现；若主控后续愿意把 fixture 计数并入该控制器响应，可把 `GET /admin/model-providers/fixture` 与 `GET /admin/skills/fixture` 的结果合并进 checks。
3. **命名约定是运行时唯一识别手段**：历史数据按约定回溯标注，不写库；若未来有绕过命名约定的演示数据（如手工 INSERT 且不守约定），守卫无法识别——有意接受，避免引入脆弱的启发式（如 loopback 地址判定会误伤 deepseek_cloud 的本地代理链路）。
4. **purge 不清历史**：call_log、binding_snapshot、已发布技能契约与执行事实按设计保留（审计可溯源），purge 仅隔离未来使用。
5. **全量回归**：见下节。

## 6. 全量回归（两模块）与前端

### 6.1 失败路径以外的两处必要回归同步
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmMigrationPostgresTest.java:220,227-235`：该用例把最新迁移版本钉在 311；AP-S06 新增 V312 后同步为 312（`completeMigrationDatabaseIsAtV311` 更名 `completeMigrationDatabaseIsAtV312`）。**重跑：Tests run: 2, Failures: 0**。这是全量回归中发现的本任务唯一真实影响点。
- model-gateway 无迁移版本钉扎（grep flyway_schema_history 无命中）。

### 6.2 全量单测结果与环境定性

| 命令 | 结果 |
| --- | --- |
| `mvn -pl model-gateway test`（补齐 AGENT_MASTER_KEY/AGENT_SECRET_DIR/JWT 密钥/consumer.tls 等后重跑） | 171 项：`FixtureIsolationTest 6/6` 绿；其余通过类含 ModelPolicyTest、ModelStoreCapabilitiesTest、ModelQuota*、ModelStatistics、ModelNoSend* 等 |
| `mvn -pl skill-registry test`（同上环境） | 404 项：`FixtureSkillsTest 5/5`、`AlgorithmMigrationPostgresTest 2/2` 绿；其余通过类含 OpenApi*、SkillStatistics、McpAdapterTest、SignedCallback、ArtifactReference 等 |
| `npx vue-tsc --noEmit`（web 全工作区） | 零错误 |
| `npx vitest run`（web） | 31 个文件 / 235 项测试全部通过 |

全量中仍有失败的类（model 66 err / skill 120 err 量级）逐一归类为**协作者任务的私有运行环境缺失，与本任务无关**：
- model-gateway 的 Frozen*/InitialStream/Manifest 套件：fixture 需要 `-Dbinding.fixture`、`-Dbinding.registry`、`-Dconsumer.tls` 等私有 .local 路径（`Path.of(System.getProperty(...))` 直接 NPE），这些属性只存在于各任务自己的 runner 命令行（见 docs/evidence/iam-model-input-consumer-report.md:65）；
- skill-registry 的 CallbackHttpTest/ReconciliationProofTest/Mcp* 等：需要 `AGENT_SECRET_DIR` 私有密钥库目录与 IAM 客户端密钥等，`SecretVault.directory()` 缺配置即抛"必须显式配置本工程独立密钥目录"（SecretVault.java:24-27）；
- `ModelCallsPostgresTest`（协作者 M 文件）3 项错误为该在途改动自身状态，不属本任务。

判定依据：失败栈全部落在上述环境缺配/缺失属性；AP-S06 新增代码路径（FixtureSkills/FixtureAdminController/FixturePolicy/FixtureEgressGuard/ViewMarker/迁移 V306/V312）不进入这些类的调用链，且两个隔离套件在完整全量运行中保持全绿。

测试环境准备记录：为补齐缺失的 scratch 测试库，以 agent_v12 创建了 `agent_platform_model_test`、`agent_platform_skill_async_test`、`agent_platform_statistics_test`（其余 *_test 库已存在）；为 SecretVault 生成了仅测试用主密钥（不入 .env、不入库）。

## 7. 遗留风险

- skill-registry jar 的 repackage 在"本机后端仍在运行"时必然失败，属环境约束：重启后端后直接跑原命令即可。
- SystemStatus.vue / ProvidersPage.vue 等页面位于协作者未跟踪目录（web/admin/src/pages/ 为未跟踪新文件），本次改动为纯追加；合并时与 batch-1 可用性工作按行合并即可。
- 若协作者全量跑 admin-common 测试，会命中 §5.1 的存量红项，与本任务无关。
