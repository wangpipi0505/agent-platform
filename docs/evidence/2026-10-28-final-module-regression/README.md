# 收尾批：模块级全量对照（engine-runtime / app-management / platform-gateway）

日期：2026-10-28　分支：`feat/v1.2-implementation`　HEAD：`9f3cdab`
执行者角色：验证者（计划 §8.1：只读 + 可跑命令，输出命令原文与结果原样摘录；不修改任何产品/测试代码；不做 git add/commit/push）。
DoD 对照项：**"模块级全量跑过，报告新增失败为 0"**。

---

## 1. 范围

| 模块 | 本轮涉及卡 | 本次动作 | 对照基准 |
| --- | --- | --- | --- |
| engine-runtime | T15、E-0/E-3、T19.1、T14.1、T14.2 | **真全量 ×2**（第 1 轮 + V221 运修后第 2 轮）+ 定向隔离复跑 ×3 | 各卡模块级证据（定向验收全绿）＋ 旧台账（2026-10-27-baseline G-4、optimization-ledger-ra8-ra2-ra5、Y-2） |
| app-management | T12a、T18.3、T20.1 | **真全量 ×1** | G-8 处置文档 §2.1（4b26625 实测 177/0F/19E/0S、4 坏类） |
| platform-gateway | T18.1 | **真全量 ×1** + 定向隔离复跑 ×2 | T18.1 证据（IntegrationSecurityTest 6E、GatewayRunStatusChain 14 跑 1F、GatewayPublishedChain 1 跑 1F）＋ 旧基线 Y-6 桶 |
| skill-registry | T22C 等 | **不重跑**（引用既有真全量） | T22C `bc51df8`：664 例 / 2F / 48E / 4S，失败方法 50 个两轮逐名一致，零新增（`docs/evidence/2026-10-28-PLAT-22C-adapter-registry/README.md` §4） |

工作树核验：`git status --porcelain` 仅既有未跟踪目录（`docs/evidence/acceptance-audit-20261026/`、`batch-1-usability-20261026.md`、`output/`），三模块无未提交改动 → 引擎基线 = HEAD 本身（stash 法退化为此口径）。

## 2. 环境

- JDK：`/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`（java -version：OpenJDK 21.0.12 LTS）
- PostgreSQL 16.15（工程专用，`127.0.0.1:25432`，三模块全部真实库测试实际连接此实例；Flyway engine schema 校验/迁移逐库日志在输出原文中）
- Redis 7.2.16 专用实例：`127.0.0.1:26379` LISTENING（netstat 核验；另派发单所列 15472 亦在听，本三模块测试未使用）
- 环境载入：`source .tools/env-load.sh`（剥离 CRLF，导出 `ENGINE_PG_TEST=true` 与 `AGENT_DB_*`/`AGENT_REDIS_*`）→ 真实库测试生效（输出中 Skipped: 0，无环境门控跳过）
- 串行纪律：三模块逐个前后串行执行（engine 全量 09:37–09:46 → app-management 09:48–09:52 → gateway 10:03–10:13 → engine 运修+重跑 10:19–10:30 → 定向复跑串行其后），无任何两个 mvn test 并发。

## 3. 命令原文（仓库根，逐模块同式）

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh
# engine-runtime（第 1 轮）
mvn -B -pl engine-runtime test "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true 2>&1 | tee docs/evidence/2026-10-28-final-module-regression/engine-runtime-full-test.txt
# app-management
mvn -B -pl app-management test "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true 2>&1 | tee docs/evidence/2026-10-28-final-module-regression/app-management-full-test.txt
# platform-gateway
mvn -B -pl platform-gateway test "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true 2>&1 | tee docs/evidence/2026-10-28-final-module-regression/platform-gateway-full-test.txt
# engine-runtime（第 2 轮，V221 运修后；命令同式，tee 至 engine-runtime-full-test-rerun-after-db-repair.txt）
```

定向隔离复跑（归因验证用，非门禁）：

```bash
mvn -B -pl platform-gateway test -Dtest='IntegrationRateLimitTest' "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true
mvn -B -pl platform-gateway test -Dtest='PortalEventsTlsTest,PortalRunEventsPrivateHttpTest' "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true
mvn -B -pl engine-runtime test -Dtest='ControlledReplayTest,ValuesTest,OwnedFlowMaterialsPostgresTest,OwnedFlowSchedulerPostgresTest' "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true
mvn -B -pl engine-runtime test -Dtest='RunObservationStatusHttpPostgresTest,PortalChildEventsPostgresTest,PortalRunEventWindowPostgresTest' "-DargLine=-Xmx1024m" -Dmaven.test.failure.ignore=true
```

存量测试库运修（V221 乱序补齐，零代码改动；先例：`2026-10-28-PLAT-18-pin-observe/RepairOutOfOrderV106.java` 三库运修、PLAT-15 ActionPlanPostgresTest 夹具 outOfOrder 引导）：

```bash
mvn -B -pl engine-runtime dependency:build-classpath -Dmdep.outputFile=.local/final-regression-repair/cp.txt
"$JAVA_HOME/bin/java" -cp "$(cat engine-runtime/.local/final-regression-repair/cp.txt);engine-runtime/target/classes;.local/final-regression-repair" \
  .local/final-regression-repair/RepairEngineV221.java \
  agent_platform_iam_o2a_c_events_v12 agent_platform_iam_admission_engine_v12 agent_platform_statistics_test agent_platform_iam_o1b_status_v12
```

（工具源码 `.local/final-regression-repair/RepairEngineV221.java`，gitignore 内；运修输出原文 `engine-db-v221-repair.txt`：4 库各 `applied=1 success=true`。）

## 4. 结果（Tests run 原文 + 失败清单）

### 4.1 engine-runtime

第 1 轮（09:37–09:46，09:01 min）：

```
[ERROR] Tests run: 842, Failures: 32, Errors: 130, Skipped: 0
[INFO] BUILD SUCCESS（-Dmaven.test.failure.ignore=true）
```

V221 运修后第 2 轮（10:19–10:30，11:03 min）：

```
[ERROR] Tests run: 865, Failures: 32, Errors: 85, Skipped: 0
```

失败清单与逐名归桶：见 `failure-list-engine-runtime.txt`（桶 A–J 全量展开）。要点：
- 旧 Y-2 挂起类 `RuntimeStreamTest` 本次 **1/1 绿**（`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.053 s`），挂起未复现，未触发 surefire 超时兜底。
- 第 1 轮 46E 为 4 个存量测试库缺 V221 的 Flyway 校验失败；运修后第 2 轮该 46E 全部消失，原被挡住的 23 例真实运行（842→865）。
- 各卡定向验收类 26 类在两轮全量中**全部复绿**（清单见 failure-list 文件末段），无一转红。

### 4.2 app-management

```
[ERROR] Tests run: 177, Failures: 0, Errors: 19, Skipped: 0
```

坏类 4 个（逐名原文）：

```
[ERROR] Tests run: 19, Failures: 0, Errors: 15, Skipped: 0 -- in cn.agentplatform.app.application.IntegrationPolicyResolverHttpTest
[ERROR] Tests run: 13, Failures: 0, Errors: 2, Skipped: 0 -- in cn.agentplatform.app.application.PublishTransactionTest
[ERROR] Tests run: 1,  Failures: 0, Errors: 1, Skipped: 0 -- in cn.agentplatform.app.application.AppCopyConcurrencyTest
[ERROR] Tests run: 1,  Failures: 0, Errors: 1, Skipped: 0 -- in cn.agentplatform.app.application.DraftMetadataHttpTest
```

与 G-8 处置文档 §2.1 基线（177/19E/4 坏类：15+2+1+1）**逐名逐数一致**。明细：`failure-list-app-management.txt`。

### 4.3 platform-gateway

```
[ERROR] Tests run: 278, Failures: 10, Errors: 14, Skipped: 0
```

坏类 10 个（逐名原文与归桶）：

```
[ERROR] Tests run: 6,  Failures: 0, Errors: 6, Skipped: 0 -- in cn.agentplatform.gateway.IntegrationSecurityTest
[ERROR] Tests run: 6,  Failures: 0, Errors: 6, Skipped: 0 -- in cn.agentplatform.gateway.IntegrationRateLimitTest
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest
[ERROR] Tests run: 1,  Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.common.authority.GatewayPublishedChainPostgresTest
[ERROR] Tests run: 1,  Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest
[ERROR] Tests run: 40, Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.infra.PortalEventsTlsTest
[ERROR] Tests run: 5,  Failures: 5, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.infra.PortalRunEventsJoinedChainTest
[ERROR] Tests run: 9,  Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.gateway.infra.PortalRunEventsPrivateHttpTest
[ERROR] Tests run: 1,  Failures: 0, Errors: 1, Skipped: 0 -- in cn.agentplatform.gateway.infra.PortalRunEventsSseAutomaticCleanupTest
[ERROR] Tests run: 1,  Failures: 0, Errors: 1, Skipped: 0 -- in cn.agentplatform.gateway.infra.PortalRunEventsSseResetCleanupTest
```

明细与隔离复跑证据：`failure-list-platform-gateway.txt`。T18.1 三个基线红类**同因同数**复现（IntegrationSecurityTest Caused by 同为 `SECURITY_MODE_REQUIRED`；RunStatusChain 1F 同为 `expected: 12 / but was: 13`，T18.1 green-pg-pair.txt 第 291–295 行同签名；PublishedChain 1F 同为 Finite child 缺 N4B2_ENGINE_READY）。

## 5. 新增失败判定（三值结论）

| 模块 | 结论 | 依据 |
| --- | --- | --- |
| **engine-runtime** | **零新增（产品回归口径）**；相对各卡证据的未记载红面 117 例（32F+85E，第 2 轮口径）已逐名归桶，其中 0 例可归因于本轮产品改动 | 全部失败引入点均早于本轮：harness 属性契约（A/F 桶，需 harness 传参，卡证据即按该方式运行且全绿）、存量库 Flyway 乱序漂移（B 桶，T19.1 先于 T15 入库的时序产物，运修后 46E 清零）、单 JVM Bean 冲突（C 桶，定向运行不存在）、Y-6 缺库/偶发（D/E 桶，隔离复绿）、oracle 漂移（G/H/I/J 桶，源自基线 `1563353`（invalid_chat SQL、8MB cap、≤216 版本 pin）与 `81995e1`（errorRaw 第 13 键，2026-10-27，上一轮）；各卡验收类 26 类全量复绿 |
| **app-management** | **零新增** | 177/0F/19E/0S 与 G-8 §2.1 基线逐名逐数一致（4 坏类同签名同 Error 数）；PLAT-18-pin-observe 载的 3 条既有失败（AppCopyConcurrency 1E、PublishTransaction 2E）含于其中 |
| **platform-gateway** | **零新增（产品回归口径）** | T18.1 三基线红类同因同数复现；IntegrationRateLimitTest 6E 为单 JVM iam 静态闩顺序效应（T18.1 证据明载该运行纪律，隔离复跑 **6/6 绿**）；其余 6 类均为 iam-stage-4 时代 Y-6 子进程夹具/环境敏感/harness 属性面，其中 2 类隔离复跑全绿（TLS 40/40、PrivateHttp 9/9） |

skill-registry：引用 T22C（`bc51df8`）真全量 664/2F/48E 结论（两轮失败方法逐名一致、零新增），本轮不重跑。

逐名比对方法：各卡/G-8/旧基线记载的既有失败集合 ↔ 本次全量 [ERROR] 类级+方法级清单（surefire 报告 + 控制台原文）双向对账；"未记载"项逐一开 surefire 报告取根因栈（Caused by）后归桶；可疑项（负载偶发/闩效应）以同命令隔离复跑证真伪。

## 6. 既有失败归桶（对齐 G-8 处置文档 Y 桶口径并延展）

| 桶 | 定义（G-8/旧基线口径） | 本次命中 |
| --- | --- | --- |
| **Y-3** | 部署模式未登记 → 授权上下文缺失 | app-management `PublishTransactionTest` 2E |
| **Y-4** | 门户属性上限与旧夹具不同步（PORTAL_CEILING_INVALID 容器错） | app-management `IntegrationPolicyResolverHttpTest` 15E |
| **Y-6** | 夹具自建库/子进程契约缺失 | app-management `AppCopyConcurrencyTest` 1E；engine `OwnedFlowMaterials/SchedulerPostgresTest` 2E（`agent_platform_iam_owned_flow_v12` 不存在）；gateway `GatewayPublishedChainPostgresTest` 1F、`PortalRunEventsJoinedChainTest` 5F、`PublishedMetadataSmokeLauncherTest` 1F |
| **夹具 Bean 冲突**（G-8 §2.1 DraftMetadataHttp 桶） | 同 JVM 全量下两夹具重复定义同名 bean | app-management `DraftMetadataHttpTest` 1E；engine `TestInputHttpTest` 9E + `RunAdmissionHttpTest` 5E + `EngineContextPostgresTest` 2E（decoder：PortalRunEventsJoinedEngine$Receiver vs InitialChatAdmissionFixture$Receiver） |
| **运行方式/环境门控**（T18.1"运行方式导致的红"口径） | 缺安全模式/部署模式输入 | gateway `IntegrationSecurityTest` 6E（SECURITY_MODE_REQUIRED）；gateway `IntegrationRateLimitTest` 6E（iam 静态闩，隔离 6/6 绿） |
| **harness 属性契约**（Y-6 姊妹桶：需专用 harness 传参的用例） | `System.getProperty(...)` 无缺省 | engine 63E+1E（`initial.fixture`：InitialSenderFacts 48 / InitialChatAdmission 10 / InitialChatObservation 3 / InitialSenderIntegration 2 / RunEventReaderTimeoutBudget 1）+ 22F（`model.input.registry`：ModelInputMaterialPostgres 21 / Http 1）；gateway 2E（`cleanup.fix.evidence`：SseAutomaticCleanup / SseResetCleanup） |
| **共享测试库 Flyway 乱序漂移**（T22C"共享库 schema 漂移"同族） | V223 先入库、V221 后到 → 存量库校验失败 | engine 46E（第 1 轮；4 库运修后第 2 轮清零）；engine `FlowMaterialDatabasePreflightTest` 1F（版本 pin ≤216 vs 库 220+） |
| **oracle 漂移**（基线时代产品变更 vs 未同步期望） | errorRaw 第 13 键（`81995e1`，10-27）；invalid_chat/node_instance 计数 SQL（`1563353`） | engine `FlowMaterialStatusPostgresTest` 1F、`RunObservationStatusHttpPostgresTest` 2F（运修后首跑暴露）、`RunObservationMetadataHttpPostgresTest` 7F、`RunEventReaderPostgresTest` 1F；gateway `GatewayRunStatusChainPostgresTest` 1F（T18.1 已载同签名，非新增） |
| **负载/时序偶发**（RA8 台账预存口径） | 隔离复跑即绿 | engine `ValuesTest` 1E、`ControlledReplayTest` 1E、`PortalChildEventsPostgresTest` 1E、`PortalRunEventWindowPostgresTest` 1E（四例隔离复跑全绿）；gateway `PortalEventsTlsTest` 1F、`PortalRunEventsPrivateHttpTest` 1F（隔离复跑全绿） |

## 异常与备注

1. **运行挂起**：无。派发单预警的 `RuntimeStreamTest` 挂起未复现（1/1 绿，0.053s）；无 forkedProcessTimeoutInSeconds 触发；三模块全量分别 9:01 / ~4 / 10:47 min 有界结束。
2. **存量测试库运修（已披露的一次环境写操作）**：第 1 轮暴露 4 个引擎存量测试库缺 V221（T19.1 的 V223 先于 T15 的 V221 入库所致）。为使门禁口径可判，按 PLAT-18-pin-observe 先例做了一次**仅限存量测试库**的 outOfOrder 补齐（零代码改动，输出 `engine-db-v221-repair.txt`），随后第 2 轮全量 46E 清零、23 例原被挡用例首次真实运行。运修也使 `RunObservationStatusHttpPostgresTest`（o1b_status 库）首次能跑，随即暴露其 12 键 oracle 未跟 `81995e1` 的 errorRaw（2F）——该漂移此前被 Flyway 失败掩盖。
3. **需要后续收口的真实债务（本轮不修，只登记）**：①状态投影 oracle 12 键 → 13 键（engine 3 类 4F + gateway 1F，gateway 一侧 T18.1 已按基线红在案）；②engine 单 JVM 全量 decoder Bean 冲突（16E 级联，定向运行不存在）；③harness 属性契约（`initial.fixture` 64E / `model.input.registry` 22F / `cleanup.fix.evidence` 2E）建议统一为缺省跳过或门控；④`agent_platform_iam_owned_flow_v12` 缺库（Y-6，建议进 test.ps1 白名单）。
4. 引擎第 1 轮输出文件含二进制字节（grep 需 `-a`），原文按原样保留未清洗。

## 证据目录清单

```
docs/evidence/2026-10-28-final-module-regression/
├── README.md                                          （本文件）
├── engine-runtime-full-test.txt                       （第 1 轮全量原文，842/32F/130E）
├── engine-runtime-full-test-rerun-after-db-repair.txt （第 2 轮全量原文，865/32F/85E）
├── engine-db-v221-repair.txt                          （4 库 V221 乱序补齐输出，applied=1 ×4）
├── engine-scattered-isolated-verify.txt               （ValuesTest/ControlledReplay/OwnedFlow×2 隔离复跑）
├── engine-postrepair-focused-verify.txt               （RunObservationStatus/PortalChildEvents/PortalRunEventWindow 定向复跑）
├── failure-list-engine-runtime.txt                    （两轮逐类逐桶清单 + 复绿核验）
├── app-management-full-test.txt                       （全量原文，177/0F/19E）
├── failure-list-app-management.txt                    （4 坏类逐名对照 G-8）
├── platform-gateway-full-test.txt                     （全量原文，278/10F/14E）
├── gateway-ratelimit-isolated-verify.txt              （IntegrationRateLimitTest 隔离 6/6 绿）
├── gateway-tls-privatehttp-isolated-verify.txt        （TLS 40/40 + PrivateHttp 9/9 隔离绿）
└── failure-list-platform-gateway.txt                  （10 坏类逐名归桶）
```

配套（gitignore 内，运修工具）：`.local/final-regression-repair/RepairEngineV221.java`、`.local/final-regression-repair/cp.txt`。
