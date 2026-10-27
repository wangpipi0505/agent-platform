# E-4 / PLAT-14 T14.2（分页与大响应策略半段）证据 — 2026-10-28

分支 `feat/v1.2-implementation`，基线 HEAD `c38ea4c`（engine-runtime 最近提交同为准线）。
本卡只做 T14.2 的"分页/`OUTPUT_TOO_LARGE` 策略"半段；T14.1（工具档案 + toolProfile 选择）不在本卡（门控 T22A，另行派发）。
方案依据：平台侧优化方案 §4.14"实现"第 2/3 条（技能契约 `pagination` 声明；适配器对超限响应返回 `OUTPUT_TOO_LARGE` + 分页建议，不静默截断）。
台账验收锚点：T14.2 大响应用例（对 P2-11 的 550KB 场景）。

## 1. 改动文件清单

主代码（2 个修改 + 1 个新增，均在白名单 `engine-runtime/src/main/java/**` 与 `application.yml`）：

- `engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java`（新增）
  - 引擎级大响应策略（`@Component`，构造器 `@Value("${agent.tool.output-max-bytes:262144}")`）。
  - `enforce(tool, result, traceId)`：按 UTF-8 序列化字节度量；阈值内返回**同一节点**（零变化锚点）；超限返回替换后的模型可见内容——`{error: 七字段信封}` 或 `{error: 信封, pagination: 分页提示}`。
  - 错误信封与 contracts/skill-invocation.md §11.1 七字段同构：`semantic/code/category/retryable/userMessage/detail{tool,actualBytes,maxBytes}/traceId` + `reason` 兼容键，含实际大小与上限。
  - `declaredPagination(tool)`：消费 `config.tools[].contract.pagination`（技能契约上的 `pagination` 键，方案 §4.14 形状：`request.page/size` + `response.itemsPath/totalPath` + `maxPageSize`）；形状非法一律视为未声明。
  - `paginationHint(declaration, result)`：声明原样透传 + 按声明路径从原响应提取 `observed.total`/`itemCount` + 续拉指引文本；**不切片原响应**。
  - `resolve(node, path)`：最小点路径解析（仅 `$.a.b` / `a.b`，方案示例形状）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java`
  - `outputPolicy` 字段（初始 `ToolOutputPolicy.defaults()`，手工构造的测试夹具零 Spring 依赖也有正确缺省）+ `@Autowired(required = false)` setter（与既有 interceptors 链同一装配先例）。
  - `drive()`：工具结果在进入 `history`（模型上下文）**之前**经 `outputPolicy.enforce(...)`，traceId 取 `run.public_id:nodeInstanceId:roundN:callId`。这是工具输出进入模型上下文的唯一咽喉点；`skill()` 与 FlowDriver SKILL/RETRIEVE 节点输出不进模型上下文，不受影响（见偏差 ⑥-3）。
- `engine-runtime/src/main/resources/application.yml`
  - 新增 `agent.tool.output-max-bytes: ${AGENT_TOOL_OUTPUT_MAX_BYTES:262144}`（256KiB，含调参说明注释）。

测试（1 个新类 + 1 个集成类，白名单 `engine-runtime/src/test/java/**`）：

- `engine-runtime/src/test/java/cn/agentplatform/engine/application/ToolOutputPolicyTest.java`（10 例，纯单元无 PG）：阈值内同实例放行、默认阈值 < 550KB、无声明超限信封七字段+实际大小与上限+大负载不外泄、声明分页提示（request/response/maxPageSize/observed.total/itemCount/hint）、声明路径未命中不伪造观测值、形状非法声明视为未声明（非对象/缺 size）、阈值可配置（4KB 负载在 1024 下被拒、默认下放行）、非正阈值拒绝、UTF-8 字节口径（211 字符 vs 611 字节辨析）、traceId 原样透传。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolOutputLargeResponsePostgresTest.java`（4 例，`ENGINE_PG_TEST` 门控，真库 + 脚本化 RuntimeClients 驱动真实 `AgentDriver.drive`）：
  - ① **550KB 锚点**：5414 条星表条目（≥550KB，991 号带唯一标记）→ 运行不失败、模型可见内容为 `OUTPUT_TOO_LARGE` 信封（含 actualBytes=实测序列化字节数、maxBytes=262144、traceId 含 run public_id），标记串与大负载本体绝不进入模型请求；
  - ② 阈值内零变化：小输出逐字原样（`{"value":1}`）进入模型上下文；
  - ③ 声明分页（`contract.pagination`）：第一轮超限 → 错误 + 分页元信息（request.page/size、maxPageSize=200、observed.total=5414、hint）；模型第二轮带 `page=1,size=200` 续拉 → 小页逐字进入模型上下文——"大响应可分页消费"端到端得证；
  - ④ 驱动级阈值可配置：`agent.outputPolicy(new ToolOutputPolicy(1024))` 后 4KB 负载被拒且上报 maxBytes=1024。

证据文件（命令输出原文）：`red-behavioral.txt`、`red-compile.txt`、`green-new-classes.txt`、`revert-check-disabled.txt`、`regression-multi.txt`。

## 2. 红→绿输出摘要

红阶段 ①（行为红，实现落地前，集成类当时 3 例）：

```
$ mvn -B -pl engine-runtime test -Dtest='ToolOutputLargeResponsePostgresTest' "-DargLine=-Xmx1024m"
[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0   （全文见 red-behavioral.txt）
  oversizedOutputBecomesReadableErrorNeverModelBlob:193   expected: "OUTPUT_TOO_LARGE" but was: ""
  declaredPaginationLetsModelRepullByPage:275             expected: "OUTPUT_TOO_LARGE" but was: ""
```

即：实现前 556KB 工具输出被逐字塞进模型请求、无错误信封、无分页提示。

红阶段 ②（编译红，单测类 + 集成 ④⑤ 引用新类后）：

```
$ mvn -B -pl engine-runtime test-compile -DskipTests
[ERROR] ... 找不到符号  符号: 变量 ToolOutputPolicy   （14 处，全文见 red-compile.txt）
[ERROR] BUILD FAILURE
```

后绿（真实实现后，新两类同跑）：

```
$ mvn -B -pl engine-runtime test -Dtest='ToolOutputLargeResponsePostgresTest,ToolOutputPolicyTest' "-DargLine=-Xmx1024m"
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in c.a.e.application.ToolOutputPolicyTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- in c.a.e.infra.ToolOutputLargeResponsePostgresTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS    （全文见 green-new-classes.txt）
```

实现后回退验证（证伪绿状态非偶然）：临时注释 `drive()` 中的 `enforce` 调用 →
14 例中 3 例回红（①③⑤，全文见 `revert-check-disabled.txt`）→ 恢复后复绿。

环境：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`（JDK 21）；`source .tools/env-load.sh`（导出 `ENGINE_PG_TEST=true` 与真库坐标，PostgreSQL 16.15 @ 127.0.0.1:25432，schema engine Flyway v223）。

## 3. 回归对照（新增失败 = 0）

派发指定多类同跑（新 2 类 + `EngineErrorSurfacePostgresTest`/`RunEnvironmentAnnotationTest`/`RunDiagnosticsTest`/`OutputPolicyPostgresTest`/`AgentLoopBudgetPostgresTest`），`regression-multi.txt`（2026-10-28 06:11 最终复跑）：

```
RunDiagnosticsTest               Tests run: 7,  Failures: 0, Errors: 0
RunEnvironmentAnnotationTest     Tests run: 11, Failures: 0, Errors: 0
ToolOutputPolicyTest             Tests run: 10, Failures: 0, Errors: 0  （新增）
AgentLoopBudgetPostgresTest      Tests run: 3,  Failures: 0, Errors: 0
EngineErrorSurfacePostgresTest   Tests run: 4,  Failures: 0, Errors: 0
OutputPolicyPostgresTest         Tests run: 24, Failures: 0, Errors: 0  （输出策略既有类回归）
ToolOutputLargeResponsePostgresTest Tests run: 4, Failures: 0, Errors: 0  （新增）
合计                             Tests run: 63, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

新增失败 = 0；本轮无既有失败（OutputPolicyPostgresTest 24 例为输出策略域既有主类，全绿）。
说明：本卡不触碰 `RuntimeStreamTest`，未跑模块全量（按派发纪律）。

## 4. 未覆盖风险

- **真实技能未声明分页**：`contract.pagination` 载体已在引擎侧消费并有端到端用例，但当前仓库无已发布技能携带该键（O-02 落在 ontorepo 投影，引擎工具不经其到达）；声明从技能注册页到契约落库的**录入链路**（skill-registry 表单/校验）不在本卡白名单，未覆盖——首个真实声明需在技能侧补录后走一遍 550KB 演练。
- **MCP 工具**：引擎侧 MCP 工具与技能工具同走 `config.tools[]` → `drive()` 咽喉点，策略自动生效；但未为本卡专门构造 MCP 适配器用例（fixture 为技能路径）。
- **FlowDriver SKILL/RETRIEVE 节点输出**：不进模型上下文，本卡定死语义不适用（见偏差 ⑥-3）；节点输出本身超大时的存储/事实面行为与此前一致，由 T09.1（流式读写/artifact 引用）承接。
- **多工具并发超限**：策略为逐调用纯函数，无共享状态；`corrections` 预算（≤2）与 NO_PROGRESS 指纹熔断对"模型反复撞大输出"的兜底未写专项用例（既有 AgentLoopBudgetPostgresTest 覆盖熔断机制本身）。
- **阈值极小值演练**（如置 1 字节使全部工具输出被拒）仅在单元级验证语义正确，未做全链路演练。

## 5. 回滚方式

- 整卡回滚：还原 `AgentDriver.java`（两处：outputPolicy 字段/setter、drive() 中 enforce 调用）与 `application.yml`（`agent.tool` 段），删除 `ToolOutputPolicy.java` 与两个测试类——无表/列/迁移改动，无跨服务契约变更，纯引擎内行为。
- 行为级回滚（不改代码）：`AGENT_TOOL_OUTPUT_MAX_BYTES` 置极大值（如 `1073741824`）等价关闭检查，工具输出恢复整块透传；配置回滚即生效（应用重启）。
- 定位影响面：仅 AGENT/LLM 驱动循环中"工具结果 → 模型消息"一处替换；`engine.tool_call`/技能侧事实/trace 均未改动，回滚后无残留状态。

## 6. 偏差（定死决策单列）

1. **与 O-02 分页声明的对齐方式**：O-02（ontorepo `fa6f298`）把 exposure 分页声明落在 `service_endpoint.exposure`；引擎侧探明工具声明到达路径为应用冻结快照 `config.tools[]`，其 `contract` 键即技能契约全文（`RunService.prepareSnapshot` 原样透传，约 1252-1284 行），**不经 ontorepo 投影**。故引擎按**同一声明语义**（方案 §4.14 形状的 pagination 参数/上限）消费技能契约上的 `contract.pagination` 键，载体位置以代码现状为准——两侧"声明了分页就遵循"的语义一致，载体不同，属方案 §4.14 第 2 条在技能侧契约的落点。
2. **阈值默认值 262144（256KiB）**：低于 P2-11 场景 550KB（必须触发），高于常规只读工具输出；可经 `agent.tool.output-max-bytes` / 环境变量 `AGENT_TOOL_OUTPUT_MAX_BYTES` 调整。度量口径定死为 UTF-8 序列化**字节**（有 211 字符 vs 611 字节辨析用例钉住）。
3. **作用域收窄到模型咽喉点**：方案 §4.14 第 3 条说"适配器对超限响应返回 OUTPUT_TOO_LARGE"，实现落点为 `AgentDriver.drive()` 的"工具结果 → 模型上下文"咽喉点而非 skill HTTP 适配器内部（适配器在 skill-registry，非本卡白名单）。由此 FlowDriver SKILL/RETRIEVE 节点直连技能的超大输出不触发本策略（不进模型上下文，替换反而破坏节点输出契约）；引擎级兜底（ContextProjection 四段投影）不变。
4. **超限不失败运行**：错误以工具结果交给模型（与 PLAT-05 可解释拒绝同构），run 不直接 FAILED；"大响应不再触发 schema 预算失败"由源头替换保证。模型无进展循环由既有 NO_PROGRESS 指纹（连续 3 轮）熔断兜底，本卡不新增计账。
5. **形状非法的分页声明视为未声明**（防御读取，不抛错）：注册期校验属 skill-registry（另一泳道在改，白名单禁止）；引擎侧宁可不启用分页提示也不误伤既有工具。
6. **Replay（受控重放）模式下模型可见内容不受本策略影响**：`ControlledReplay.modelToolContent` 取录制材料原文，enforce 结果在该分支不被消费——重放保真优先，策略只作用于实时运行。
7. **⚠ 工作区内发现他人未提交改动（如实上报，未触碰未提交）**：`AgentDriver.java` 中 `interceptors` setter 的注解在基线 HEAD 为 `@Autowired(required = false)`，本卡开工期间工作区内已被改为 `@Autowired`（本卡首次编辑前已存在——本卡 Edit 的精确匹配与开工后首读均见改后形态；基线 `git show HEAD` 与当前 diff 证实此 1 行非本卡所改）。本卡按派发纪律未回退、未提交、未基于其做任何依赖（测试全部手工构造 AgentDriver，不启动 Spring 上下文；`ToolInterceptorChain` 本身是 `@Component`，应用上下文中两种写法装配等价）。该行随工作区留给协调者裁决；本卡严格遵守"禁止 git add/commit/push"。另：本卡运行期间泳道 T21d 提交 `8ada745`（仅 skill-registry/docs），与本卡文件零交集。
