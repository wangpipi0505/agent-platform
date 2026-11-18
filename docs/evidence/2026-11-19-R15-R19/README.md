# R-15/R-18/R-19 收尾三卡证据（2026-11-19）

基线：`5bde1c30`（upgrade/20261103），分支 `close-wt-r15`，worktree `wt-r15`。engine-runtime 串行执行，每卡一提交，只动 engine-runtime + contracts/error-codes.md + 本 evidence 目录。回归纪律：每卡定向回归零新增失败；三卡完成后合并回归一次。

环境：PG 25432 独立实例（ENGINE_PG_TEST=true），JDK 21（Temurin 21.0.12+8），Maven 3.9.16 离线仓。测试命令统一形如：
`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot && set -a; source .env; set +a; export ENGINE_PG_TEST=true && mvn -pl engine-runtime test -o -Dtest=...`

---

## R-15 spill 接入工具环

### 改动文件（文件:行）

| 文件 | 改动 |
| --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/harness/context/SpillStore.java:77-84,196-234` | 新增读侧：`StorageSource` 接口（生产=MinIO GET，与写侧 StorageSink 对偶）与 `read(sessionId, spillId, source)` 取回原文——spill_id+session_id 联合定位（归属核验，跨会话 404 不区分泄露存在性）、读回后 sha256 复核（摘要不符 500 SPILL_DIGEST_MISMATCH，不回填疑似损坏内容） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java:75-107,131-149` | `enforce` 增加带外溢引用的四参重载（三参原语义零变化，委托 null）：超限且引用非空时模型可见信封并入 `spill` 节点（spilled/spillId/locator/preview/bytes/sha256），userMessage 补"全文已外溢落盘（见 spill 引用，可按引用取回）"；分页提示保持既有语义 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java:57-83,383-443` | 工具环超限分支接线：会话关联运行且外溢组件已装配时先经 `SpillStore.spill` 流式落盘（阈值内零变化短路、sink 工厂懒触发），回填节点并入 `outputPolicy.enforce` 信封；未装配/无会话/落盘异常（RuntimeException）降级为既有报错口径，不阻塞运行 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/harness/context/PromptAssembler.java:53-62,88-155,177-205` | 组装时按引用回填：`SPILL_REF` token（`{{ spill:<spillId> }}`，与 {{var}} 插值互不干扰）、`render(sessionId, ...)`（插值后回填、回填产物不再二次插值；sessionId=null 与三参 render 完全同口径）、`retrieveSpill`/`backfill`（协作端未装配时结构化拒绝 SPILL_RETRIEVAL_UNAVAILABLE，不伪造原文） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/harness/context/SpillWiringConfiguration.java`（新增） | `SpillStore` 入 Spring 容器（@Bean），供 AgentDriver `@Autowired(required=false)` 注入；sink/source 生产实现（MinIO）未提供时如实降级，接线点已就绪 |
| `contracts/error-codes.md:43` | §2 全表补登记 `OUTPUT_TOO_LARGE`（语义/触发/归属卡 R-15；该码 PLAT-14 T14.2 引入已在用但未登记，一并补） |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolOutputSpillPostgresTest.java`（新增 4 用例） | 见下 |

### DoD 命令与结果

1. **300KB 工具输出三段验收**（`ToolOutputSpillPostgresTest.oversizedToolOutputSpillsToStoreAndModelSeesReferenceNotFullBody` + `promptAssemblerBackfillsOriginalContentByReferenceInLaterRound`）：
   - 5500 条目负载序列化 313,912 字节（>300KB）→ `spill_artifact` 行落库（kind=TOOL_RESULT、bytes/sha256/preview/storage_ref 与索引一致，对象存储正文与原始输出逐字节一致）；
   - 第二轮模型请求的工具结果消息=错误信封+`spill` 引用节点（spillId/locator/preview/bytes/sha256），全文与唯一标记 LEO-SPILL-MARKER 不在模型可见面（content<4096 字节）；
   - 后续轮次 `PromptAssembler.render(sessionId, ...)` 的 `{{ spill:s_... }}` 引用回填原文——组装产物与落盘序列化产物**逐字节一致**；`retrieveSpill` 直接取回同样一致；跨会话取回 404 `SPILL_REF_NOT_FOUND`；摘要损坏 500 `SPILL_DIGEST_MISMATCH`。
2. **接线证据 grep**：`grep -rl SpillStore engine-runtime/src/main` → 5 文件命中，除自身外 4 个：`AgentDriver.java`、`ToolOutputPolicy.java`、`PromptAssembler.java`、`SpillWiringConfiguration.java`。
3. **定向回归**：`mvn -pl engine-runtime test -o -Dtest='ToolOutputPolicyTest,ToolOutputSpillPostgresTest,ToolOutputLargeResponsePostgresTest,SpillPromptTest'` → **Tests run: 51, Failures: 0, Errors: 0**（ToolOutputPolicyTest 34/34、SpillPromptTest 9/9、ToolOutputLargeResponsePostgresTest 4/4——既有 T14.2 口径零变化、新 4/4）。

### 测试统计

| 类 | 用例 | 结果 |
| --- | --- | --- |
| ToolOutputSpillPostgresTest（新） | 4/4 | 落盘+引用响应 / 回填一致+归属+摘要复核 / 无会话降级 / 阈值内零变化 |
| ToolOutputPolicyTest（既有） | 34/34 | 零改动全绿 |
| ToolOutputLargeResponsePostgresTest（既有） | 4/4 | 零改动全绿（未装配外溢组件=既有口径锚点） |
| SpillPromptTest（既有） | 9/9 | 零改动全绿 |

### 诚实登记项

1. **产品决策口径**：卡面给定的推荐方向"超限=截断+落盘引用"按**报错路径升级**实现——超限分支不是"直接报错"或"静默截断"，而是保留 T14.2 可读错误信封（actualBytes/maxBytes/traceId+分页提示），并升级为"信封+外溢引用"：全文经 SpillStore 流式落盘（主方案 §5.4 接收侧累计字节+有界窗口，未物化全量），模型可见面只给 locator/preview/长度/sha256 引用；运行不失败。未装配外溢组件的部署形态自动保持原报错口径（零行为漂移）。
2. **生产 sink 未到位**：主方案 §5.4"沿用 MinIO"——引擎模块暂无对象存储 SDK 依赖，`StorageSink/StorageSource` 生产实现（MinIO ArtifactStore）尚未提供。Spring 已装配 SpillStore Bean 与两个注入点（AgentDriver.spillSinkFactory、PromptAssembler 构造），MinIO 实现到位即激活，无需再改工具环；当前生产运行口径=既有报错信封（降级如实登记）。测试以 BufferedMemorySink/内存映射按接口契约等价验证。
3. **独立运行（无会话关联）不落盘**：spill_artifact.session_id 为 NOT NULL FK（V231），独立运行无会话归属可挂——保持既有报错信封口径（零落盘、信封无 spill 引用），已用例定死（unlinkedRunKeepsLegacyEnvelopeWithoutSpillRow）。
4. **call_id 未关联**：外溢落盘时 spill_artifact.call_id 暂传 null（callId 在 skill() 登记内部，未上抛到工具环调用点；tool_call 行自身保留完整结果事实，可经 trace 对账）；列为后续顺手项。

---

## R-18 engine 侧 GUIDE 目录注册 + memoryPolicy 接 manifest

### 改动文件（文件:行）

| 文件 | 改动 |
| --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/GuideDirectory.java`（新增） | GUIDE 目录注册：启动（ApplicationReadyEvent）/按需经 RuntimeClients 拉 skill 服务 `GET /admin/skills?kind=GUIDE`（V321 双轨 kind 过滤）本地缓存（保序只读）；不可达=N/A 留痕（异常码入 detail）保留最后已知缓存、绝不阻塞启动；`catalogSection()` 五字段摘要投影（提示装配 SKILL_CATALOG 段正文来源，N/A/空目录=空串不伪造） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/GuideDirectoryController.java`（新增） | `GET /v1/skills?kind=GUIDE` 读本地缓存（engine 侧有数据可查）；kind 白名单收口（TOOL 走工具环 400 GUIDE_KIND_ONLY） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/ResourceManifestService.java:37-51,139-163` | guideRefs 消费路径升级：目录缓存可用=实核（命中 OK/未命中 MISSING 阻断装载），N/A（未注入/不可达）保持既有 N/A 留痕口径零漂移 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/Memories.java:53-77` | memoryPolicy 接 manifest：`MemoryPolicy.from` 三级回退补第三级 `config.memoryPolicy`（chat_session 钉住发布快照与 /internal/apps/{code}/published 均把 manifest 放 config 下——此前 manifest memoryPolicy 不进策略链的缺口接通，与 collectMemory 的 MEMORY_POLICY 条目同源） |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/GuideDirectoryTest.java`（新增 4 用例） | 见下 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/MemoryManifestPolicyTest.java`（新增 1 用例） | 见下 |

### DoD 命令与结果

1. **engine 侧 GUIDE 目录可查有数据**（`GuideDirectoryTest.refreshCachesGuideDirectoryAndEngineSideEndpointServesIt`）：refresh 经 RuntimeClients 出站（stub 断言拉取路径带 kind=GUIDE）→ `GET /v1/skills?kind=GUIDE` 返回缓存 2 条（code/definition.description 在位）；kind=TOOL 400。
2. **不可达 N/A 留痕不阻塞**（`unreachableSkillServiceLeavesNaTrailAndKeepsLastKnownCache`）：refresh 吞 502 为 N/A（detail 含 SKILL_UNAVAILABLE）、不抛异常；先 OK 后故障保留最后已知缓存（2 条）且 available()=false（消费路径回落留痕口径）。
3. **guideRefs 消费路径实核**（`manifestGuideRefsResolvedAgainstEngineSideDirectory`）：缓存可用→已注册引用 OK、未注册 ghost-guide@1 MISSING（loadable=false 定位对象在位）；目录 N/A→既有 N/A 留痕口径不变（ResourceManifestTest 6/6 零改动全绿）。
4. **memoryPolicy 变化用例在案**（`MemoryManifestPolicyTest.manifestMemoryPolicyChangeFlipsWriteAndReadPathBehavior`）：chat_session 钉住发布形状快照（config.memoryPolicy）——writeScopes [PRIVATE]→ADMIN 写 SHARED 403 SCOPE_WRITE_DENIED，改 manifest 放开 [PRIVATE,SHARED]→200；readScopes 同链 USER 读共享 403→200；USER 写 SHARED 恒 403（角色红线不受策略放行影响）。
5. **定向回归**：`mvn -pl engine-runtime test -o -Dtest='GuideDirectoryTest,MemoryManifestPolicyTest,MemoryApiTest,ResourceManifestTest'` → **Tests run: 20, Failures: 0, Errors: 0**（GuideDirectoryTest 4/4、MemoryManifestPolicyTest 1/1 新增；MemoryApiTest 9/9、ResourceManifestTest 6/6 零改动全绿）。

### 测试统计

| 类 | 用例 | 结果 |
| --- | --- | --- |
| GuideDirectoryTest（新） | 4/4 | 拉取+端点可查 / N-A 留痕+缓存保留 / manifest 实核+留痕口径 / catalogSection 投影 |
| MemoryManifestPolicyTest（新） | 1/1 | manifest memoryPolicy 变化翻转读写路径（含角色红线） |
| MemoryApiTest（既有） | 9/9 | 零改动全绿（顶层 memoryPolicy 形状口径不受影响） |
| ResourceManifestTest（既有） | 6/6 | 零改动全绿（无目录注入=N/A 留痕口径锚点） |

### 诚实登记项

1. **`GET /v1/skills?kind=GUIDE` 实现口径（等价口径）**：网关未登记 /v1/skills 前缀（且本卡约束不动网关），engine 侧端点为 engine 直连只读面（:18082，standalone MockMvc 用例覆盖）；经网关的技能目录面仍是 skill 服务 `GET /admin/skills?kind=GUIDE`（已可达、kind 过滤现成）——与卡面"若网关把 skills 面指到 skill 服务已可达，则 engine 侧注册=本地缓存+装配到 guideRefs 消费路径"的分支一致，engine 侧注册=本地缓存（GuideDirectory）+两条消费路径（ResourceManifestService 实核 / catalogSection 提示装配段正文）。
2. **拉取通道与鉴权形态**：照既有跨服务预检口径走 RuntimeClients（ServiceTicket 面）；skill 服务 `/admin/**` 在 legacy（dev-hmac/test）模式接受服务票，IAM（trusted）模式下 /admin/** 要求门户域票据——服务票拉取会被拒→N/A 留痕（目录缓存为尽力而为注册面，不影响运行）；生产 IAM 部署形态如需目录实核，后续由 skill 服务开 `/internal` 目录端点或网关面承接（本卡不动他服务的约束下如实登记）。
3. **memoryPolicy 读取链核验结论**：A3-4 现状=MemoryPolicy.from 只读顶层/manifest 两形状，发布快照形状（config 下 manifest）的 memoryPolicy 实际不进策略链——本卡补第三级回退接通（新增链自查断言）；顶层/manifest 两既有形状行为零变化（MemoryApiTest 9/9 零改动全绿）。ResourceManifestService.collectMemory 的 MEMORY_POLICY 条目（读 manifest.memoryPolicy）与策略链自此同源。
4. **"USER 写 SHARED 不再 403"卡面括注与现状契约不符**：requireWriteScope 的角色红线（USER 恒不写 SHARED，403）为接口稿明文不变量，策略放行不越角色门——用例按"writeScopes 变化→ADMIN 写 SHARED 403↔200 + readScopes 变化→USER 读共享 403↔200"覆盖写入路径行为变化，并显式断言 USER 写 SHARED 恒 403（红线保持）。

---

## R-19 T2-2 config_release 诚实占位改真实快照

### 改动文件（文件:行）

| 文件 | 改动 |
| --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java:43-77` | 新增 `RuntimeClients clients` 依赖（@Autowired 三参主构造器 + `Tasks(store)` 测试/降级构造器，照 Memories 双构造器先例） |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java:145-171` | `create` 受理钉住改真实快照：`pinRelease` 非空时 `config_release_id=appver-<app_version 行 id>`（发布链返回的真实快照行身份）、`config_digest=candidateSha256`（发布时内容摘要，64-hex 形状校验）；钉住值随受理事务写入 task 行 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java:437-462` | `ReleasePin` record + `pinRelease`：经 RuntimeClients `get("app","/internal/apps/{code}/published")` 取发布快照（卡面指定来源）；candidateSha256 缺失/越形按发布快照规范 JSON 现算（同版本确定一致）；发布不可得/无来源/越形响应返回 null → 回退 T2-2 占位降级口径 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/TaskConfigReleasePinTest.java`（新增 3 用例） | 见下 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolOutputSpillPostgresTest.java:70` 与 `ToolOutputLargeResponsePostgresTest.java:35` | 回归纪律修复：清理改为 @BeforeEach+@AfterEach 双向（照 SpillPromptTest 口径）——此前 BeforeEach-only 的类尾残留 run/model_invocation 行会以 FK 违例阻断共享库其他 engine-test 夹具清理（本次合并回归中实测暴露并修复） |

### DoD 命令与结果

1. **同一任务 requestId 重放读取到同一快照**（`replayReadsSameSnapshotAndNewReleasePinsNewSnapshot`）：首受理钉住 `appver-101`+`sha256(release-1-body)`；发布推进到 v2 后同 requestId 重放→原任务、行内快照仍 `appver-101`/原摘要（重放分支先行返回，不重复取快照、不被新发布翻新——受理即钉住时点语义）。
2. **发布新版本后新任务拿到新快照**：同用例第二段——新任务钉住 `appver-102`+`sha256(release-2-body)`，与 v1 摘要不同。
3. **占位字面量清零（主路径）**：`grep -rn "unpinned-\|release-pin" engine-runtime/src/main` 仅命中 `Tasks.java` 降级分支（发布不可得/无来源组装时按 T2-2 占位降级口径落库，卡面"发布不可达时保持降级口径"）与注释留痕——真实受理主路径零占位。
4. **定向回归**：`mvn -pl engine-runtime test -o -Dtest='TaskConfigReleasePinTest,TasksApiTest,PlansApiTest,TaskDispatchTest,TaskReconciliationTest,ConfirmationCenterTest,StepExecutionMappingTest,SubagentPlanningTest,TaskEventsSseTest,ExternalIntegrationTest'` → **Tests run: 61, Failures: 0, Errors: 0**（TaskConfigReleasePinTest 3/3 新增；TasksApiTest 7/7、PlansApiTest 6/6 等既有类零改动全绿）。

### 测试统计

| 类 | 用例 | 结果 |
| --- | --- | --- |
| TaskConfigReleasePinTest（新） | 3/3 | 重放同快照+新发布新快照 / 不可得与无来源降级 / candidateSha 缺失现算摘要 |
| TasksApiTest（既有） | 7/7 | 零改动全绿（无来源构造=占位降级口径锚点） |
| PlansApiTest / TaskDispatchTest / TaskReconciliationTest / ConfirmationCenterTest / StepExecutionMappingTest / SubagentPlanningTest / TaskEventsSseTest / ExternalIntegrationTest（既有） | 6+7+5+9+5+9+5+5=51/51 | 零改动全绿 |

### 诚实登记项

1. **真实快照 id 的来源与形状**：卡面指定两条来源路径，取 RuntimeClients `get("app","/internal/apps/{code}/published")`（发布链只读接口）；该响应的真实快照行身份=数值 `appVersionId`（app.app_version 行 id，version() 读回时覆写为数值），releaseId 落库形状 `appver-<id>`（varchar(64) 内、跨版本唯一）。摘要优先发布时内容摘要 `candidateSha256`（发布固化的 64-hex），缺失/越形按响应规范 JSON 现算（同版本确定一致，跨版本必然不同——已用例覆盖两种形状）。
2. **降级口径如实登记**：发布不可得（未发布/停用/409 APP_UNAVAILABLE/服务不可达）、无来源组装（`new Tasks(store)` 测试构造，10 个既有测试类零改动依赖此路径）、越形响应（缺 appVersionId）三种情况均回退 T2-2 占位降级口径（`unpinned-*`+`release-pin:*` 摘要）——占位字面量仅存于该降级分支（grep 留痕），受理不因发布面不可达阻塞。
3. **"事务内钉住"实现口径**：钉住值（releaseId/digest）随受理事务写入 task 行；发布快照的跨服务只读读取在事务外完成（避免事务内持锁等跨服务往返）——重放分支在读取之前先行返回，重放不产生第二次快照读取（用例断言）。
4. **跨类清理污染修复（回归纪律）**：合并回归暴露 ToolOutputLargeResponsePostgresTest（既有）与 ToolOutputSpillPostgresTest（R-15 新增）清理为 BeforeEach-only，类尾残留 engine-test 租户 run/model_invocation 行，导致后续共享库夹具（StepExecutionMappingTest/TaskReconciliationTest）清理 FK 违例——两类清理补齐 @AfterEach 双向后 61/61 全绿；修复属测试基建，不触生产代码。

---

## 合并回归（三卡完成后，2026-11-19）

命令（两批，同一离线仓+PG 25432 `agent_platform_engine_test` 库）：
- 批一（AgentDriver 消费面，drive 循环被 R-15 改动）：`mvn -pl engine-runtime test -o -Dtest='AgentLoopBudgetPostgresTest,AgentD2RequestPersistTest,ChatTurnEventLinkageTest,TracePostgresTest,ControlledReplayTest,DebugControlsTest,EngineErrorSurfacePostgresTest,AgentEngineShadowTest,AgentReceiptLostRecoveryGateTest,ControlledDraftSkillTest,ApprovalPolicyEnforcementTest,BusinessIntentMissingValueTest'` → **Tests run: 56, Failures: 0, Errors: 0**。
- 批二（三卡新类+台账点名回归类）：`-Dtest='ToolOutputPolicyTest,ToolOutputSpillPostgresTest,ToolOutputLargeResponsePostgresTest,SpillPromptTest,GuideDirectoryTest,MemoryManifestPolicyTest,MemoryApiTest,ResourceManifestTest,TaskConfigReleasePinTest,TasksApiTest,PlansApiTest,CompactionStoreTest,ChatSessionConfigPinTest,RunConfigReferenceTest,ConfirmationCenterTest,TaskDispatchTest,TaskReconciliationTest,StepExecutionMappingTest,SubagentPlanningTest,TaskEventsSseTest,ExternalIntegrationTest'` → **Tests run: 150, Failures: 0, Errors: 5**——5 个错误全部来自 `ChatSessionConfigPinTest`（构造期 NPE：`new ChatSessions(null,null,null)` 早于 H1-2 的 SessionStore(trace) 空指针），**基线既有失败已证**：同一用例在基线 worktree（wt-r14@5bde1c30）同样 5/5 报错，非本批引入（零新增失败）。其余 145/145 全绿。
- 合计：**201 用例，196 绿，5 错（全部=基线既有 ChatSessionConfigPinTest 构造 NPE）**，三卡改动零新增失败。

合并回归中修复的两处共享库夹具卫生问题（测试基建，不触生产代码，均已入库）：
1. `ToolOutputLargeResponsePostgresTest`（既有）与 `ToolOutputSpillPostgresTest`（R-15）清理由 BeforeEach-only 补齐为 @BeforeEach+@AfterEach 双向——类尾残留 engine-test 租户 run/model_invocation 行会以 FK 违例阻断后续共享库夹具（StepExecutionMappingTest/TaskReconciliationTest 实测暴露）。
2. `BusinessIdempotencyAcrossCallsTest`（既有）补 action_plan 双向清理（ex25-* 租户行带 call_id FK，阻断 ChatTurnEventLinkageTest 的无界 tool_call 清理，实测暴露）。
