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
