# R-6 返工证据：插件发现执行器接线（controller 走 trigger + pulse 装配 @Scheduled + API 级验收）

日期：2026-11-18。卡片：台账 §7.6 **R-6 插件发现执行器接线**。分支 `r6-plugin-wiring`（基线 `5a457849`）。
范围：只动 `skill-registry` + 本证据目录；本地提交不推送。

## 0 断点说明（验收发现的原状）

A3-5 交付的 `PluginDiscoveryExecutor`（`@Service`，skill-registry）单测 11/11 通过，但**无任何生产调用点**：

- `POST /admin/plugin-sources/{id}/discover` 经 `PluginGovernanceController.discover` → `PluginGovernance.triggerDiscovery`
  只插 `QUEUED` 行，无人消费；
- 执行器 `trigger()`/`pulse()` 无调用者、无 `@Scheduled`（当时 skill-registry 内仅有
  `ServiceLifecycle:291`、`SkillOutbox:24`、`ArtifactStore:882` 三处调度）；
- 结论：经 API 触发的发现任务永不被消费，执行器核心逻辑成为孤岛。

本卡为**纯接线卡**：不改执行器核心逻辑（认领/接管/批次单调/租约/五门发布语义零变化）。

## 1 接线点（文件:行）

| 接线点 | 位置 | 内容 |
| --- | --- | --- |
| controller 触发改走执行器 | `skill-registry/src/main/java/cn/agentplatform/skill/api/PluginGovernanceController.java:4,17-20,25,27,45` | 构造注入 `PluginDiscoveryExecutor`；`discover` 改调 `discoveryExecutor.trigger(id)`；HTTP 契约不变（202 + `{discoveryId,status:'QUEUED',batchNo}`——`trigger` 内部复用 `governance.triggerDiscovery` 同一受理投影，ADMIN 校验/停用 409/批次单调原样） |
| pulse 调度装配 | `skill-registry/src/main/java/cn/agentplatform/skill/application/PluginDiscoveryExecutor.java:345` | `@Scheduled(fixedDelayString = "${agent.discovery.pulse-ms:5000}")`——逐字照 `SkillOutbox.java:24` 的写法（`fixedDelayString` + 属性占位 + 默认值；`@EnableScheduling` 已由 `SkillOutbox.java:13` 在全量上下文开启，无需重复） |
| javadoc 同步 | `PluginDiscoveryExecutor.java:46-48,339-343` | "本卡不装配"的旧说明更新为 R-6 已装配（文档对齐，不改行为） |

**选型说明（最小侵入）**：未把执行器注入 `PluginGovernance`——执行器构造已注入 governance
（executor→governance），反向注入构成构造环；controller 层注入是零环、零数据面改动的最小接法。
执行器 diff 仅 import + javadoc + `@Scheduled` 注解三处，`pulse()` 返回类型/逻辑、
`complete/fail/claim/renew/publish`、发布门五门、批次单调、租约语义全部原样。

## 2 DoD 逐条命令与结果

### DoD-1 生产调用点核验

```
grep -rn "PluginDiscoveryExecutor" skill-registry/src/main
```

出现 controller 注入与调用（非仅执行器自身）：

```
PluginGovernanceController.java:4:  import cn.agentplatform.skill.application.PluginDiscoveryExecutor;
PluginGovernanceController.java:17: * <p>R-6 接线：发现触发改走 {@link PluginDiscoveryExecutor#trigger(long)}——受理复用
PluginGovernanceController.java:25:     private final PluginDiscoveryExecutor discoveryExecutor;
PluginGovernanceController.java:27:     public PluginGovernanceController(PluginGovernance governance, PluginDiscoveryExecutor discoveryExecutor) {
PluginGovernance.java:24: * ...由 {@link PluginDiscoveryExecutor} 承担，           （既有 javadoc）
PluginDiscoveryExecutor.java:56,57,81: ...                                                （执行器自身）
```

`@Scheduled` pulse 在位：

```
PluginDiscoveryExecutor.java:345:    @Scheduled(fixedDelayString = "${agent.discovery.pulse-ms:5000}")
SkillOutbox.java:24:                 @Scheduled(fixedDelayString = "${agent.outbox.delay-ms:2000}")   ← 照此写法
```

### DoD-2 API 级验收测试（不靠测试直调执行器）

新增 `skill-registry/src/test/java/cn/agentplatform/skill/api/PluginDiscoveryWiringApiTest.java`。

**口径**：`@SpringBootTest(classes=SkillApplication.class, RANDOM_PORT)` **全量真实上下文**（真实安全链
dev-hmac + `ServiceTokens` HS256 用户票据 + 真实 Tomcat），`@Scheduled` 生效；专用库
`agent_platform_r6_discovery_test`（drop/create，不触 dev 库与其他验证库）；`agent.discovery.pulse-ms=2000`
（生产默认 5000，测试下调短）。触发只走 `POST /admin/plugin-sources/{id}/discover`；状态查询只走
`GET /admin/plugin-sources/{id}/discoveries/{discoveryId}`；测试零次调用执行器方法。

4/4 绿，调度证据（surefire stdout 原样）：

```
[R-6 API] discovery=disc_92fe6ca2... statuses=RUNNING→SUCCEEDED            elapsedMs=53    ← HTTP 触发（异步工作线程）
[R-6 API] discovery=disc_417727ec... statuses=QUEUED→RUNNING→SUCCEEDED     elapsedMs=959   ← 遗弃 QUEUED 行被真实 @Scheduled pulse 消费
[R-6 API] discovery=disc_5867e439... statuses=RUNNING→SUCCEEDED            elapsedMs=3973  ← ghost 遗弃 RUNNING：确定性观测 RUNNING→pulse 接管（代次≥2）
```

- 用例 1 `httpTriggeredDiscoveryRunsFromQueuedToSucceeded`：202 受理体断言 QUEUED/批次 1 → 轮询至
  SUCCEEDED；`startedAt` 非空断言（QUEUED→RUNNING 认领腿的机制性证明）；`changes.added` 含 demo 插件
  （目录合并消费）；`lastDiscoveredAt` 回写可查询。
- 用例 2 `scheduledPulseConsumesOrphanQueuedRowWithoutAnyTestDrivenExecution`：遗弃 QUEUED 行仅经
  数据面受理（`governance.triggerDiscovery`，不派发）——**唯一消费者只能是真实 `@Scheduled` pulse**，
  959ms 内 QUEUED→RUNNING→SUCCEEDED 全程观测。这是调度装配在位的直接证据。
- 用例 3 `scheduledPulseTakeoverObservesRunningThenSucceeds`：ghost 认领 + 2s 租约 → 轮询**确定性观测
  RUNNING** → 租约失效后 pulse 接管至 SUCCEEDED，`claim_generation≥2`（接管代次推进）。
- 用例 4 `disabledSourceContractPreservedThroughWiredEntrypoint`：停用来源经新接线入口原样透传数据面
  409 `PLUGIN_SOURCE_DISABLED`（HTTP 契约保持）。

### DoD-3 既有回归（本人实跑，命令）

```
set -a; source .env; set +a; mvn -B -ntp test -pl skill-registry -Dtest=PluginDiscoveryExecutorTest
mvn -B -ntp test -pl skill-registry -Dtest=...（四类定向合跑）
mvn -B -ntp test -pl skill-registry（全模块）
```

| 测试类 | 本卡后 | 基底 `5a457849` | 结论 |
| --- | --- | --- | --- |
| PluginDiscoveryExecutorTest | **11/11 绿** | 11/11 | 执行器核心逻辑零变化 |
| PluginGovernanceControllerTest | **3/3 绿** | 3/3 | 适配新构造签名（mock executor），202/错误码透传契约断言原样保留 |
| PluginDiscoveryWiringApiTest | **4/4 绿**（新增） | — | 见 DoD-2 |
| McpConnectionTest | **5/7**（2 失败） | **5/7**（同 2 失败） | 基底既有失败，**零新增失败**（见诚实登记 4） |
| 全模块（skill-registry 全部 *Test 类） | 见 §3 汇总 | — | 零新增失败（基底同名失败逐类核对） |

### DoD-4 证据归档

本文件（`docs/evidence/2026-11-18-R-6/README.md`）。

## 3 全模块回归统计（skill-registry，`mvn test` 全量，逐类对照）

同机同库同命令跑了两轮全量（先本卡后 `94cdffd7`+`24065cb0`，后基底 `5a457849`）：

| 轮次 | 代码 | 类数 | Tests run | F | E | S |
| --- | --- | --- | --- | --- | --- | --- |
| 本卡后 | `94cdffd7`+`24065cb0` | 119 | 918 | **15** | 67 | 38 |
| 基底对照 | `5a457849`（生产代码基底；含本卡新测试类，见下注） | 119 | 918 | 17 | 67 | 38 |

逐类 F/E/S diff **仅两处**（其余 117 类完全一致，含全部失败/错误类逐个同值）：

| 类 | 本卡后 | 基底 | 判定 |
| --- | --- | --- | --- |
| PluginDiscoveryWiringApiTest（本卡新增） | **4/4 绿** | 3 失败（触发/调度/接管三用例超时，停用 409 用例过） | **未接线缺陷在基底复现、接线后修复**——本卡新测试在基底生产代码上按预期红 |
| AlgorithmExecutionPostgresTest | 28/29（`lateTerminalObservationIsRetainedWithoutRevivingCancelledAuthority` 1 失败） | 29/29 绿 | **环境漂移，与本卡无关**：随后基底代码隔离复跑同一类同样 2F+25E（与本卡代码隔离复跑结果逐字节相同），即基底自身在本环境随时间退化（PG 专用实例状态类 flake，与插件链路无共享代码路径）；详见诚实登记 7 |

基底轮的失败/错误类清单与本卡轮逐类同值：CallbackHttpTest 32E、McpManagementTest 7E、IdentityOutboundTest 6E、ReconciliationProofTest 1F6E、ApprovedConnectionDispatchTest 1F5E、SkillCallbackWakeTest 3E、OntologyManifestImportsTest 3F、OntologyProjectionImportTest 3F、McpConnectionTest 2F、SpaceGroundOpenApiImportTest 2F、AlgorithmMigrationPostgresTest 1F、AlgorithmProtocolTest 1F1E 及 8 个单用例错误类——全部为**基底既有**（多为部分上下文缺 `agent.security.mode` 触发 `SECURITY_MODE_REQUIRED`，或基底迁移链断言 vs V901 种子），零新增。

逐类完整清单归档：`regression-mine.txt` / `regression-base.txt`（本目录）。

## 4 诚实登记（未覆盖项）

1. **部署栈未覆盖**：本环境无部署栈，未做多副本真实部署下的跨实例接管实测；租约接管语义由
   `PluginDiscoveryExecutorTest`（双执行器实例 + SQL 拨租约）机制级覆盖，单实例真实上下文中以 ghost
   行模拟崩溃实例（API 级用例 3）。
2. **调度间隔口径**：生产默认 `agent.discovery.pulse-ms=5000`；API 级验收在测试 property 下调短为
   2000ms 以缩短观测等待。生产间隔下调度装配同一（同一注解同一属性键），仅等待时长不同。
3. **瞬时 RUNNING 的可观测性**：demo 扫描桩毫秒级完成，HTTP 触发路径的 QUEUED→RUNNING 转换以
   `started_at` 非空 + 后续终态机制性证明；轮询是否恰好撞见 RUNNING 属机会性（本跑撞见，
   `statuses=RUNNING→SUCCEEDED`）。遗弃 RUNNING 路径的 RUNNING 为 2s 租约窗口确定性观测。
4. **McpConnectionTest 5/7 为基底既有失败**（`expected: <322> but was: <901>`×2）：两用例硬断言迁移链
   尾到达 V322，M5-1 交付 V901 演示种子后链尾延长所致；已在基底 `5a457849` 复跑确认同失败
   （与本卡无关，本卡未触碰迁移链）。修复归口应在 A3-6/M5-1 卡，不在 R-6 范围。
5. **真实来源扫描器未覆盖**：REGISTRY/GIT/DIRECTORY/CAPABILITY_PACKAGE 的真实扫描随来源适配卡交付；
   本卡接线与验收经 `DemoPluginSourceScanner`（manifest.demo=true 明示演示数据）。
6. **不触库纪律**：API 级验收用专用库 `agent_platform_r6_discovery_test`（drop/create 全链 migrate），
   dev 库 / 共享库 / 其他验证库零触碰；密钥仅从 `.env` 环境变量读取，不入命令参数/日志/Git。
7. **全量回归的环境本底**：本环境全模块 `mvn test` 本底即非全绿（基底 `5a457849` 同跑 F17/E67——
   主要是部分上下文测试缺 `agent.security.mode` 的 `SECURITY_MODE_REQUIRED`、McpConnectionTest 的
   V322 链尾断言 vs V901 种子、以及 AlgorithmExecutionPostgresTest 在本环境随时间的 flake 漂移）。
   本卡以**逐类对照**口径证明零新增失败（117/119 类逐值相同，两处差异均见 §3 判定）；这些基底
   本底失败不属 R-6 修复范围（各自归口 EX-43 部分上下文装配/迁移链断言/算法执行链）。
   四类 DoD 定向合跑在收尾时复跑一遍仍稳定：Executor 11/11、Controller 3/3、API 4/4、
   McpConnectionTest 5/7（同基底 2 失败）。

## 5 本地提交（不推送）

| hash | 内容 |
| --- | --- |
| `94cdffd7` | R-6(fix): 插件发现执行器接线——controller发现触发改走executor.trigger+pulse装配@Scheduled兜底（照SkillOutbox写法） |
| `24065cb0` | R-6(test): API级验收测试——@SpringBootTest真实上下文走HTTP入口触发+真实@Scheduled pulse兜底闭环（专用库agent_platform_r6_discovery_test） |
| 本提交 | R-6(docs): 证据归档（本 README + 逐类回归清单两份） |
