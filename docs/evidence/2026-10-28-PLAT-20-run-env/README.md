# E-3 / PLAT-20 T20.1（run 环境标注半段）证据 — 2026-10-28

分支 `feat/v1.2-implementation`，基线 HEAD `75d9d8c`（engine-runtime 最近提交 `71ca122`，PLAT-19）。
本卡只做 T20.1 的"运行结果标注 environment 和 dataset"半段；健康数据面主体已在 `ca46e55` 入库。

## 1. 改动文件清单

主代码（3 个文件，均在白名单 `engine-runtime/src/main/java/**`）：

- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`
  - `demoMode` 字段 + `@Autowired demoMode(@Value("${AGENT_DEMO_MODE:false}"))` setter（取值与 app-management `DataHealthService` 逐字对齐；字段初始 false，保持既有构造器夹具不变）。
  - `annotateRuntime(ObjectNode, boolean, JsonNode)`（包级静态纯函数）：冻结前往快照顶层写 `runtime = {environment: DEMO|REAL, demoMode, datasetVersion?}`。
  - `createInternal`：在 `final JsonNode frozen = ...` 之前统一标注——放在此处（而非 prepareSnapshot）是为了覆盖录制重放与首发 CHAT 这两条不走 prepareSnapshot 的创建路径。
  - `surfaceRuntimeAnnotation(Map, JsonNode)`（公开静态，同 `effectiveError` 先例）：把标注铺到运行详情顶层。
  - `composeStatusBody`：读冻结快照后调用 `surfaceRuntimeAnnotation`。
- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java`
  - `putRuntimeAnnotation(Map, Object)`（公开静态）：往 `RUN_STARTED` 事件载荷追加 `environment`/`datasetVersion`（SSE 数据源与详情 events 同源）。
  - 两处保护路径发车点（`initialDispatch`、`startOwnedFlow`）的 `RUN_STARTED` 载荷接线（`flowRun(view)`/`run` 已携带 snapshot 值，零额外查询）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunScheduleRepository.java`
  - 遗留路径 `claim` 的 `RUN_STARTED` 载荷接线：快照列不随 claim 选择（扫描热路径不加宽行），仅在真正发车这一次按主键补读 `select snapshot from engine.run where id=?`。

测试（2 个新类，白名单 `engine-runtime/src/test/java/**`）：

- `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunEnvironmentAnnotationTest.java`（11 例，无 PG 依赖）：DEMO 标注、REAL 缺省、setter 解析、dataset 透传/空白不落键、既有键不被触碰（含 `contextPack.environment` 发布绑定语义不受影响）、详情铺键、旧记录读取不补键、RUN_STARTED 载荷携带/缺省。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunEnvironmentPostgresTest.java`（3 例，`ENGINE_PG_TEST` 门控）：真库走真实 `createInternal`（EVALUATION 冻结候选路径，与 monitor → `/internal/evaluations/run` 载荷同形）：快照落 `runtime`、详情顶层可见、`RUN_STARTED` 载荷可见；无 dataset 引用不落键；旧记录（直接 SQL 插入、无标注）`service.status` 读取不报错不补键。

证据文件（命令输出原文）：`red-unit.txt`、`green-new-classes.txt`、`regression-multi.txt`、`regression-bonus.txt`、`ownedflow-standalone.txt`、`baseline-observation-preexisting.txt`。

## 2. 红→绿输出摘要

先红（占位实现：三个函数 no-op，测试真实编译执行）：

```
$ mvn -B -pl engine-runtime test -Dtest='RunEnvironmentAnnotationTest' "-DargLine=-Xmx1024m"
[ERROR]   RunEnvironmentAnnotationTest.annotationPreservesExistingSnapshotKeys:119 expected: <true> but was: <false>
[ERROR]   RunEnvironmentAnnotationTest.datasetVersionIsPassedThroughVerbatim:96 expected: <v7> but was: <>
[ERROR]   RunEnvironmentAnnotationTest.demoRunSnapshotCarriesEnvironmentDemo:59 演示运行快照必须带 runtime 标注 ==> expected: <true> but was: <false>
[ERROR]   RunEnvironmentAnnotationTest.nonDemoRunDefaultsToReal:72 expected: <REAL> but was: <>
[ERROR]   RunEnvironmentAnnotationTest.partialAnnotationSurfacesOnlyPresentKeys:154 expected: <REAL> but was: <null>
[ERROR]   RunEnvironmentAnnotationTest.runDetailSurfacesAnnotationAtTopLevel:130 expected: <DEMO> but was: <null>
[ERROR]   RunEnvironmentAnnotationTest.runStartedPayloadCarriesAnnotation:169 expected: <REAL> but was: <null>
[ERROR] Tests run: 11, Failures: 7, Errors: 0, Skipped: 0   （全文见 red-unit.txt）
```

后绿（真实实现后，新两类同跑）：

```
$ mvn -B -pl engine-runtime test -Dtest='RunEnvironmentAnnotationTest,RunEnvironmentPostgresTest' "-DargLine=-Xmx1024m"
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in c.a.e.application.RunEnvironmentAnnotationTest
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- in c.a.e.infra.RunEnvironmentPostgresTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS    （全文见 green-new-classes.txt）
```

环境：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`（JDK 21）；`source .tools/env-load.sh`（导出 `ENGINE_PG_TEST=true` 与真库坐标，PostgreSQL 16.15 @ 127.0.0.1:25432，schema engine Flyway v223）。

## 3. 回归对照（新增失败 = 0）

九类同跑（派发指定 4 类 + 新 2 类 + RUN_STARTED 敏感邻类 3 类），`regression-multi.txt`：

| 类 | 结果 | 与基线对照 |
| --- | --- | --- |
| RunDiagnosticsTest | 7/7 绿 | 派发指定 |
| RunEnvironmentAnnotationTest | 11/11 绿 | 新增 |
| RunServiceContextPackTest | 9/9 绿 | 派发指定 |
| EngineErrorSurfacePostgresTest | 4/4 绿 | 派发指定 |
| TracePostgresTest | 24/24 绿 | 派发指定（含真实 EVALUATION createInternal 全链路） |
| RunSchedulerPostgresTest | 24/24 绿 | RUN_STARTED 遗留发车路径 |
| RunEnvironmentPostgresTest | 3/3 绿 | 新增 |
| RunObservationStatusHttpPostgresTest | 19 例 2F | **基线即失败，非本卡引入**（见下） |
| OwnedFlowMaterialsPostgresTest | 1 例 1E | **环境缺预置库，非本卡引入**（见下） |

合计 102 例，2F/1E；两类失败在"暂存本卡全部改动后的基线"上完全复现，新增失败 = 0：

- `RunObservationStatusHttpPostgresTest` 2F（`freshStatusDoesNotResumeExecutionGrant`、`publicRecordsCannotMint...`）：断言运行详情键数 `isEqualTo(12)`，实际 13（`runId…paused`，无 environment——本卡对无标注快照不铺键）。`git stash push -- engine-runtime/src/main/java` 并移出两个新测试类后在基线 HEAD 重跑同类，同样 `Tests run: 19, Failures: 2`（原文 `baseline-observation-preexisting.txt`），为基线既有漂移，不属本卡修复范围（改他人断言需另行走卡确认预期形状）。
- `OwnedFlowMaterialsPostgresTest` 1E：`Caused by: org.postgresql.util.PSQLException: FATAL: database "agent_platform_iam_owned_flow_v12" does not exist`（原文 `ownedflow-standalone.txt`）——setup 阶段连接未预置的隔离库，环境限制，与代码无关。

补充回归（保护路径 RUN_STARTED 与 SSE 帧完整性），`regression-bonus.txt`：
`PublishedAdmissionHttpPostgresTest` 20/20 绿、`PortalRunEventsHttpTest` 6/6 绿。

## 4. 未覆盖风险

- 两处保护路径（`TraceStore.initialDispatch`/`startOwnedFlow`）的 `RUN_STARTED` 载荷标注由单元级 `putRuntimeAnnotation` 用例与 `PublishedAdmissionHttpPostgresTest`（发车事件类型断言）间接覆盖，没有像遗留路径那样断言载荷内 `environment` 键值；若保护路径载荷构造被重构，回归网可能漏检。
- `AGENT_DEMO_MODE=true` 下的真库 e2e 未跑（本环境 .env 为 REAL 语义），DEMO 取值只在单元层验证；`environment` 值域（DEMO/REAL）与 `demoMode` 自洽在 e2e 中是二值断言，不绑定具体值。
- 前端展示标识（web 侧）与发布预检拒绝"演示数据评测冒充真实评测"（app-management 侧）按派发不做；详情 API 已有 `environment`/`demoMode`/`datasetVersion` 顶层键可后续直连。
- 未跑模块全量（按派发纪律），`RuntimeStreamTest` 未触碰。

## 5. 回滚方式

单提交回滚即可（本卡未提交；若入库后回滚）：

```
git revert <本卡提交>
```

或手工还原 3 个主代码文件（`RunService.java`、`TraceStore.java`、`RunScheduleRepository.java`，`git checkout 75d9d8c -- engine-runtime/src/main/java`）并删除 2 个新测试类。运行时行为面还有一个更细的退路：`demoMode` setter 未被任何部署配置引用时，标注恒为 `REAL`（只写值，不改变任何执行行为）；已落库的 `runtime` 标注是纯附加 JSON 键，旧版本读取方按未知键忽略，无迁移、无破坏性。

## 6. 偏差与定死决策

1. **缺省语义定死为 REAL（二选一之"建议 REAL"）**：新运行一律在快照落 `runtime.environment ∈ {DEMO, REAL}`——`AGENT_DEMO_MODE` 缺失/false → `REAL`，true → `DEMO`。取值与 `/health/data`（`DataHealthService`）完全同源，跨服务可用同一等式判断"演示运行"；旧 run 记录一律不回填，读取侧对无 `runtime` 对象的快照整体缺省（详情不补键、不报错）。
2. **标注结构**：单键 `snapshot.runtime = {environment, demoMode, datasetVersion?}`，与 T13 的 `snapshot.contextPack` 同层级（快照顶层对象），不新建表/列/并行结构；无迁移。引擎对 `runtime` 键有所有权：应用快照若带同名键会被有意覆盖（发布快照白名单无此键，正常不可达）。
3. **dataset 透传口径**：透传运行入参的 `datasetVersion`（monitor → `/internal/evaluations/run` 携带的评测数据集版本引用，与 `TestInputService.promote` 同名同义），键名保持 `datasetVersion`；入参未带或空白时该键缺省，不伪造。`evalReportId`/`caseId` 不在标注内（属评测报告维度，另卡需要时再透传）。
4. **SSE 载体**：不新增事件类型（避免 event_seq/事件流形状漂移），把标注追加进既有 `RUN_STARTED` 载荷（`environment` + 有则 `datasetVersion`；`demoMode` 是 environment 的冗余布尔，事件载荷不重复携带）。旧运行事件载荷无键，SSE 消费者按缺省省略。
5. **录制重放语义**：重放产生的是新 run 记录，按当前进程环境标注（而非沿用被重放运行的旧标注）——重放在演示环境执行即是演示运行。
6. **遗留路径补读**：`RunScheduleRepository.claim` 的发车点按主键补读 `snapshot` 列（每次运行生命周期仅一次），claim 扫描选择列不加宽，避免热路径行宽退化。
