# PLAT-19 T19.1（E-1 派发单）：token 计量与三级配额（X-6 缩水版）

- 日期：2026-10-28
- 仓库：agent-platform，分支 `feat/v1.2-implementation`
- 开工基线：engine-runtime 最近提交 `0b704d9`（PLAT-05 引擎侧）；开工时 HEAD `13f7dd1`，收工时 HEAD 已被他泳道推进到 `bcd9184`（skill PLAT-06，未触及 engine-runtime）。工作树开工检查：无本单相关未提交改动（他泳道 skill-registry/contracts 无未提交修改，仅有其历史 untracked 证据文件，未触碰）。
- 派发依据：方案 §4.19（1076–1105 行）；计划 §6 规范、§2.2 PLAT-19 行；台账 §3 T19.1、§5 X-6。
- X-6 裁决遵守：**不引入模型价目与金额成本**。本单只做 token 计量聚合 + tenant/app/credential 三级滑动窗口配额 + 超限可读拒绝；金额列、成本估算、前端成本卡一律未做。

## 一、范围依据

方案 §4.19 实现项与本单的对应（缩水后）：

| 方案 §4.19 实现项 | 本单处理 |
| --- | --- |
| 1. `engine.run` 记录 token；运行中心按应用/用户/模型聚合 | token 落库**已有**（`TraceStore.responseInitial` 写 `usage_accounted`+`response.usage`，`ModelExchange.accountUsage` 汇总主轮并翻标志，`engine.run.budget.consumed.tokens` 同步累计）——本单**不改其写入语义**，补聚合查询：`RunQuota.aggregate(actor, by=app\|user\|model, windowSeconds)`，读路径 `GET /admin/usage/summary`（tenant 取自已验 JWT） |
| 2. 配额 tenant/app/credential 三级滑动窗口，超限 `QUOTA` | `RunQuota.requireRunAdmission`：`RunService.createInternal` 在 `engine.run` 插入前（protected/legacy 两路径共同点，受理事务外）只读判定；超限抛 `PlatformException(429,"QUOTA",userMessage)`，userMessage 说明**哪一级**（租户/应用/模型凭据）、**当前用量**、**上限**；新迁移 `V223__engine_quota_window.sql` 只增两个索引 |
| 3. 只读调用缓存（相同输入+技能版本+参数），写调用绝不缓存 | **未做**（见偏差 §六） |
| 4. 多轮会话改造（会话摘要/上下文裁剪等） | **不做**（派发单明确排除；`conversation_id`/会话已存在） |
| 5. 前端成本卡与超限告警 | **不做**（X-6：金额成本不做；web/ 不在白名单） |

### 实现时定死的决策（按派发要求写明）

1. **配额配置来源 = engine-runtime `application.yml`**（引擎配置，非应用配置/数据库表）：
   - 开关 `agent.engine.quota-enabled`，**默认 false**（环境变量 `AGENT_ENGINE_QUOTA_ENABLED`）；
   - `agent.engine.quota.window-seconds`（默认 3600，可配置）；
   - `agent.engine.quota.tenant-tokens` / `app-tokens` / `credential-tokens`（**默认 0 = 该级不限**；开启开关但未配上限时行为零变化）。
2. **credential 级 scope = 冻结模型引用的 `entry.providerId`**（供应商凭据主体；模型调用实际经 model-gateway 以 provider 的 secret_ref 出口）。快照无模型引用（providerId≤0）时该级跳过，不误伤无模型运行。
3. **判定口径**：准入检查"滑动窗口内**已计量**用量 ≥ 上限即拒绝"，不预估本次运行的未来 token——模型调用的实时节流仍由 model-gateway 供应商级 TPM/RPM（`ModelQuota.java`，本单未改动）负责；本配额是平台侧可读、跨供应商的三级边界。窗口按 `engine.model_invocation.updated_at`（响应落库≈计量时刻）滑动，与 `usage_accounted` 翻转存在秒级近似偏差（有意限制）。
4. **计量事实唯一来源不变**：只读 `engine.model_invocation.usage_accounted + response->'usage'->>'totalTokens'`（`usage_accounted` 保证初始调用与主轮不重复计数）。不新增表、不新增列，V223 只增索引。
5. **错误码**：`QUOTA` 为本次唯一新增错误码（计划 §6.2-2）。触发条件=对应级别滑动窗口内已计量用量达到上限。信封由既有 `PlatformErrors`→`ApiResponse(code,message,data,traceId)` 组装，traceId 由既有 `TraceContextFilter` 写入 MDC——admin-common 未改动。
6. 聚合读路径 `GET /admin/usage/summary?by=app|user|model&windowSeconds=<60..2592000>` 不受开关控制（纯只读、无持久化副作用）。

### 改动文件清单（全部在白名单内）

新增：
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunQuota.java`（聚合查询 + 三级配额闸门）
- `engine-runtime/src/main/java/cn/agentplatform/engine/api/UsageController.java`（`GET /admin/usage/summary`）
- `engine-runtime/src/main/resources/db/migration/V223__engine_quota_window.sql`
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunQuotaPostgresTest.java`（10 例）
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunQuotaAdmissionPostgresTest.java`（3 例）

修改：
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`（+15 行：可选 setter 注入 `RunQuota` + 插入前单点钩子，既有构造器不变）
- `engine-runtime/src/main/resources/application.yml`（+10 行：开关与配额默认值）

禁止项核对：skill-registry/**、contracts/**、admin-common/**、app-management/**、monitor/**、web/**、V221/V222 及其它既有迁移、.env —— 均未改动；未执行任何 git add/commit/push。

## 二、环境

- Windows 10 x64 / Git Bash；JDK 21：`/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`
- Maven `-pl engine-runtime`（admin-common test-jar 已在本地 `.tools/m2`）
- 专用 PG25432（`127.0.0.1:25432`，测试库 `agent_platform_engine_test`），环境经 `source .tools/env-load.sh` 加载（`ENGINE_PG_TEST=true`，CR 已剥）
- Flyway：classpath 共 24 个迁移，测试库 schema version **223**（V223 在**既有旧数据**上增量应用成功，见绿日志 flyway 行）
- 未启动 Redis/H2；引擎测试夹具模式与既有 `*PostgresTest` 一致

## 三、命令原文

```bash
# （0）开工基线核对
git status && git log -1 -- engine-runtime
ls engine-runtime/src/main/resources/db/migration/   # 最高 V220，V221/V222 被台账认领未用，V223 空闲

# （1）红（RunQuota 为零行为占位实现，仅保签名；测试先行）
source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -pl engine-runtime test -Dtest='RunQuotaPostgresTest,RunQuotaAdmissionPostgresTest' "-DargLine=-Xmx1024m"

# （2）绿（真实现）+（3）定向多类回归（同一命令、扩大 -Dtest 类列表）
mvn -B -pl engine-runtime test -Dtest='RunQuotaPostgresTest,RunQuotaAdmissionPostgresTest,TracePostgresTest,AgentLoopBudgetPostgresTest,RunAdmissionBoundaryPostgresTest,EngineMigrationsTest,ChatSessionsTest,ModelExchangeTest,EngineErrorSurfacePostgresTest,RunStatisticsPostgresTest' "-DargLine=-Xmx1024m"
```

输出原文：`red-stub-run.log`（红）、`green-regression-batch.log`（绿+回归全量输出）、`red-*.txt` / `green-*.txt`（surefire 报告原文）。

## 四、结果原文（摘要，全文见日志文件）

### 红 → 绿

红（占位实现，11 跑 7F/0E；占位下本就该绿的"开关关闭/零上限"与迁移索引断言保持绿）：

```
[ERROR] Failures:
[ERROR]   RunQuotaAdmissionPostgresTest.appLevelOverrunRejectsReadableBeforeRunInsert:255 expected: <QUOTA> but was: <AUTHORITY...>  →  修正夹具后为 expected PlatformException to be thrown（占位无拒绝）
[ERROR]   RunQuotaAdmissionPostgresTest.tenantOverrunRejectsBeforeRunInsertAndReleasesAdmissionSlot  （同上）
[ERROR]   RunQuotaPostgresTest.appLevelOverrunRejectsReadableQuotaEnvelope:165 Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.
[ERROR]   RunQuotaPostgresTest.credentialLevelOverrunRejectsReadableQuotaEnvelope:180 Expected ... but nothing was thrown.
[ERROR]   RunQuotaPostgresTest.slidingWindowRestoresAdmissionAfterUsageSlidesOut:200 Expected ... but nothing was thrown.
[ERROR]   RunQuotaPostgresTest.tenantLevelOverrunRejectsReadableQuotaEnvelope:149 Expected ... but nothing was thrown.
[ERROR]   RunQuotaPostgresTest.withinQuotaAdmitsAndSeedUsageAggregatesByAppUserModel:151 expected: <500> but was: <0>
[INFO] Tests run: 11, Failures: 7, Errors: 0, Skipped: 0
```

绿（真实现，新测试类 13/13）：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.infra.RunQuotaAdmissionPostgresTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.engine.infra.RunQuotaPostgresTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 定向多类同跑（回归对照）

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ...application.RunStatisticsPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.AgentLoopBudgetPostgresTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.ChatSessionsTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.EngineErrorSurfacePostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.EngineMigrationsTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.ModelExchangeTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.RunAdmissionBoundaryPostgresTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.RunQuotaAdmissionPostgresTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.RunQuotaPostgresTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- in ...infra.TracePostgresTest
[INFO] Tests run: 60, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**回归对照结论：既有类全部通过（含派发点名的 TracePostgresTest 24/24、AgentLoopBudgetPostgresTest 3/3），新增失败 = 0。** 未跑模块全量（按派发纪律"定向集合控制在 15 分钟内"，本次 44.7s）。

### 派发点名覆盖对照

1. ①配额内运行放行且 token 计量落库可聚合 → `RunQuotaPostgresTest.withinQuotaAdmitsAndSeedUsageAggregatesByAppUserModel`（三条已计量事实 → tenant/app/credential 窗口和 + by=app/user/model 三维聚合，`RunQuotaAdmissionPostgresTest.withinQuotaAdmitsRunThroughSameHook` 验证真实受理放行落库）✅
2. ②tenant 级超限拒绝 + 信封含哪级配额/用量/上限 → `tenantLevelOverrunRejectsReadableQuotaEnvelope` + `tenantOverrunRejectsBeforeRunInsertAndReleasesAdmissionSlot`（code=QUOTA、status=429、消息含"租户/15000/10000"、被拒运行不落库）✅
3. ③app 级、credential 级各一例 → `appLevelOverrunRejectsReadable*`（"应用/25/20"）、`credentialLevelOverrunRejectsReadableQuotaEnvelope`（"模型凭据/40/35"）✅
4. ④滑动窗口边界（滑出后恢复放行）→ `slidingWindowRestoresAdmissionAfterUsageSlidesOut`（窗口外用量不计入、恢复放行；窗口内边界仍拒绝）✅
5. ⑤开关关闭时配额不生效 → `switchOffDisablesQuotaEnforcement`（enabled=false + 三级超限种子 → 不拒绝）✅
6. ⑥缓存用例 → **不适用**（缓存未做，见偏差）✅
7. 迁移用真实 PG、V223 在旧数据上可跑 → 夹具 Flyway 对既有 `agent_platform_engine_test` 增量应用至 223；`migrationAddsWindowIndexes` 断言两个索引存在 ✅

补充（非点名）：`zeroLimitMeansUnlimitedAndUnknownModelSkipsCredentialLevel`（0=不限与无模型快照跳过 credential 级）、`usageSummaryEndpoint*`（聚合端点 MockMvc standalone：JWT tenant 隔离信封 + 非法维度 INVALID_INPUT）。

## 五、未覆盖与已知失败

1. **只读调用缓存未实施**（方案 §4.19 实现项 3）——见偏差 §六。因此⑥"写调用不命中缓存/只读同参数命中"无用例。
2. **`@SpringBootTest` 整容器 HTTP 测试模式在本模块不可用**：与既有 fixture `Receiver` 配置类的 bean 定义冲突（`RunAdmissionHttpTest` 为既有挂起失败类，2026-10-22 起报告同一 `BeanDefinitionOverrideException`，非本单引入）。准入端到端改为 `RunService` 直连（legacy 路径 + 本地 HttpServer 桩 + 手工构造 scheduler 绑空 worker），HTTP 信封的 `code/message/traceId` 组装由既有 `PlatformErrors`+`TraceContextFilter`（admin-common，未改动）负责，并以 MockMvc standalone（挂 `PlatformErrors` advice）验证信封形状。
3. **IAM（protectedAdmission）路径未在真实 IAM 链路上端到端验证**：protected/legacy 两路径共用同一插入前判定点（代码位置唯一），但端到端用例走 legacy。
4. **窗口时间近似**：以 `model_invocation.updated_at`（响应落库）近似计量时刻，主轮 `usage_accounted` 翻转不改 `updated_at`，存在秒级偏差；且准入判定不含在途运行的未来 token——本配额是有意设计为"已计量用量"边界，不是硬实时上限。
5. **运行中心（monitor）侧未接线**：聚合仅引擎侧端点 `GET /admin/usage/summary`，monitor/前端消费不在本单白名单。
6. **ModelQuota（model-gateway）供应商级 TPM/RPM 不在回归集**：`-pl engine-runtime` 不含 model-gateway 测试，且该模块本单零改动。
7. 未跑模块全量；`RuntimeStreamTest` 等挂起类未碰。
8. 单类隔离运行无已知假红（新测试类自身 `@BeforeAll` 幂等注册 dev-hmac 部署模式，见 `RunQuotaAdmissionPostgresTest.connect` 注释，规避 §6.4-3 进程级静态态问题）。

## 六、偏差

1. **只读调用缓存（可选项）未做，降级为"只做配额"**：三级配额 + 聚合查询 + 迁移 + 端到端准入用例的实际代价（含 `@SpringBootTest` 容器不可用需绕行、迁移与多类回归）已超预期；缓存是方案 §4.19 实现项 3 的独立增强，X-6 缩水后优先级低于配额。按派发单许可降级，如实记录。缓存缺席不影响"写调用绝不缓存"的验收命题（无缓存即无不正确缓存命中）。
2. **测试形态偏差**：派发要求的"超限返回 QUOTA 完整信封"未用整容器 HTTP 断言（原因见 §五.2），改为：PlatformException 字段级断言（code/status/userMessage 内容）+ 准入直连端到端 + MockMvc standalone 验证 advice 信封。traceId 断言因此未在 HTTP 层复核（由 admin-common 既有行为保证，未改动）。
3. **迁移内容比"建表"薄**：V223 只含两个索引（计量事实无需新表，聚合直接读既有 trace 结构，符合"不重建 TraceStore、不改既有 trace 写入语义"）。
4. 其余无偏差：开关命名（`agent.engine.quota-enabled`）、默认值入 `application.yml`、错误码 QUOTA、三级 scope 定义、窗口可配置均按派发/§6.2 执行。

## 回滚方式

1. **行为开关（主回滚面）**：`agent.engine.quota-enabled=false`（默认即 false；环境变量 `AGENT_ENGINE_QUOTA_ENABLED=false`）。关闭后 `requireRunAdmission` 首行返回，准入不做任何配额拒绝；聚合读路径为纯只读 SELECT，可独立保留。阈值/窗口同一处可调（0=不限）。
2. **V223 down（手工 SQL，迁移文件内含同样说明）**：
   ```sql
   DROP INDEX IF EXISTS engine.idx_model_invocation_usage_window;
   DROP INDEX IF EXISTS engine.idx_run_app_started;
   ```
   V223 未建表未加列，drop 索引不影响任何数据与行为（仅影响窗口求和扫描性能）。
3. **代码回滚**：删除新增三个主文件（RunQuota/UsageController/V223）+ revert `RunService.java`（+15 行）与 `application.yml`（+10 行）即可；无数据回填、无契约变更、无跨服务影响。
