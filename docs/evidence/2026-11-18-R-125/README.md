# R-125 三卡返工证据（R-1 / R-2 / R-5）——engine-runtime

日期：2026-11-18　分支：`r125-engine-wiring`（基线 5a457849）　工作树：`_worktrees/upgrade-20261103/wt-r125`
执行环境：PG 专用实例 127.0.0.1:25432（`EngineTestDatabase` 自动用 `agent_platform_engine_test`，`ENGINE_PG_TEST=true` 守卫）；JDK 21（Eclipse Adoptium 21.0.12.8）。
提交（本地未推送，每卡一提交）：

- R-1：`9250274d` — `R-1(fix): Plans.appendRevision接PlanValidator——非法计划落INVALID+diagnostics+确认中心过滤`
- R-2：`2346c4b4` — `R-2(fix): Tasks.create受理事务接startPlannerSubagents——规划子代理真实受理路径派发+A03幂等复验+目录可查`
- R-5：`d20f542a` — `R-5(fix): engine注册UTF-8 StringHttpMessageConverter——真实端口SSE字节级验证（中文/四字节emoji逐字节与源一致）+MockMvc注释更正`

最终合并回归（最终提交状态全量复跑，命令见各节）：**8 个测试类 49/49 全绿、零失败零跳过**。

```
ConfirmationCenterTest 9/9, PlansApiTest 6/6, SubagentPlanningTest 9/9,
TaskEventsSseRealPortTest 2/2, TaskEventsSseTest 5/5, TasksApiTest 6/6,
RunEventsHttpTest 1/1, SessionEventsEndpointTest 11/11 —— Tests run: 49, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
```

回归命令样板（worktree 根）：

```
set -a; source .env; set +a; export ENGINE_PG_TEST=true
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp test -pl engine-runtime -Dtest='<各类>'
```

---

## R-1：计划校验器接线（T2-4 PlanValidator 无调用点——Plans.appendRevision 硬编码 VALID）

### 改动文件（文件:行）

| 文件 | 位置 | 内容 |
|---|---|---|
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/Plans.java` | :80-81 | 固化前调用 `PlanValidator.validate(goalSpec, allowedSkills(task))`（读两边签名取最小侵入：JSON 入口 + 任务 tool_policy 能力范围） |
| 同上 | :86-107 | INSERT 带实际 `validation_status` + `diagnostics`（V230 既有 jsonb 列，NULL=未产生——空诊断写 NULL）；INVALID 修订不落 plan_step（goal_spec+diagnostics 已在不可变修订行留痕，javadoc 如实登记）；响应键形状不变（planId/revision/digest/validationStatus/diagnostics/pendingInputs） |
| 同上 | :113-128 | 新增 `allowedSkills(task)`：tool_policy.allowedToolRefs 的 `skill:code` 引用集合；NULL/空=继承发布范围，校验器跳过能力检查（PlanValidator 自身契约） |
| 同上 | :275 | `revisionDetail.canConfirm` 对 INVALID 修订投影 false（与 confirm 409 PLAN_NOT_VALID 同口径） |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/PlansApiTest.java` | :86-89 / :139-207 / :209-296 | appendReq 样例补 inputBindings（校验器接线后必填）；新增环依赖/未知依赖/非法 shape/能力范围四类 INVALID 样例断言（带 path 定位）+ VALID 行为不变断言 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/ConfirmationCenterTest.java` | :469-557 | 新增真实路径测试：环依赖经 `plans.appendRevision` 落 INVALID → confirmableBy=me 不列、计数投影不含；补合法修订（baseRevision=1）→VALID 头修订回流待办 |

B4-1 过滤说明：`Tasks.confirmableRevisions` 的 SQL 本就带 `pr.validation_status='VALID'` 头修订过滤（Tasks.java:331）——此前 appendRevision 恒 VALID 使过滤形同虚设；本卡接线后经真实路径复验（ConfirmationCenterTest 新用例）。确认中心计数共用该投影（`ConfirmationCenterService.counts` → `tasks.confirmableRevisions`），无需另改服务代码。

### DoD 命令与结果

```
mvn -B -ntp test -pl engine-runtime -Dtest='PlansApiTest,TasksApiTest,ConfirmationCenterTest'
→ ConfirmationCenterTest 9/9, PlansApiTest 6/6, TasksApiTest 6/6 —— Tests run: 21, Failures: 0, BUILD SUCCESS
```

### 测试统计

- PlansApiTest：6/6（4 既有 + 2 新增：环依赖、未知依赖+非法 shape+能力范围）
- TasksApiTest：6/6（零改动既有用例）
- ConfirmationCenterTest：9/9（8 既有 + 1 新增真实路径 INVALID 复验）
- 三类非法样例均判 INVALID 且带定位：DEPENDENCY_CYCLE→`/steps/b/dependsOn`、DEPENDENCY_UNKNOWN→`/steps/s1/dependsOn`+message 含 ghost、TARGET_REF_SHAPE/INPUT_BINDINGS_REQUIRED→`/steps/sk1/...`、CAPABILITY_NOT_ALLOWED→message 含 rogue。

---

## R-2：规划子代理触发点（T2-7 startPlannerSubagents 无调用点——A03 幂等仅测试内成立）

### 改动文件（文件:行）

| 文件 | 位置 | 内容 |
|---|---|---|
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java` | :40-52 | Tasks 内持有 `SubagentPlanning planning`（无状态仅依赖 TraceStore，与 9 处 `new Tasks(store)` 测试构造零破坏——不引 Spring 构造变更） |
| 同上 | :116-122 | 受理路径捕获：`plannerAcceptance = mode=PLAN && subagentPolicy.enabled && 未传 sessionId`（与 TASK_INTERNAL 根会话创建同口径，SubagentPlanning javadoc §5.5"无用户会话 PLAN 任务"语义） |
| 同上 | :172-184 | **受理事务内**（`store.atomic` 块中，TASK_ACCEPTED 事件后）调用 `startPlannerSubagents(taskId, role, count, outputSchema)`；缺省 `PLANNER_ALGO×2`，subagentPolicy.role/count/outputSchema 可覆盖；锁序说明：父任务行是本事务新建行，先锁后插子行，与 Plans.execute 的执行任务插入（PLANNED_EXEC 19 列，只触达既有父任务）无锁序倒置——Plans.execute 未动 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/SubagentPlanningTest.java` | :199-247 | 新增真实路径 DoD 测试：经 `Tasks.create`（公共入口，不直调 SubagentPlanning）产生 PLANNER_ALGO×2 子任务+目录行；目录接口 `GET /v1/sessions/{id}/subagents` 可查到；同 requestId 重放→原任务、根会话不另建、子代理不重复、登记事件仍 2 条 |
| 同上 | :216-247 | 既有 `startPlannerSubagentsIsIdempotentAndRegistersCatalogAndSubtasks` 前提更正为"受理即派发后直调=重放键重放（created=false）"——幂等语义不变，断言适配真实受理路径 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/TasksApiTest.java` | :53-70 / :129-180 | 清理扩展（sa-% 子会话/subagent_catalog/session_event 触发器窗口，FK 反序照 SubagentPlanningTest 先例）；`planTaskWithSubagentPolicyCreatesTaskInternalRootSession` 扩展为受理派发+详情 subtasks 投影+重放不重复派发断言 |

### DoD 命令与结果

```
mvn -B -ntp test -pl engine-runtime -Dtest='SubagentPlanningTest,PlansApiTest,TasksApiTest'
→ PlansApiTest 6/6, SubagentPlanningTest 9/9, TasksApiTest 6/6 —— Tests run: 21, Failures: 0, BUILD SUCCESS
```

### 测试统计

- SubagentPlanningTest：9/9（8 既有 + 1 新增真实路径；A03 幂等经真实路径复验：重放不另建根会话、不重复派发）
- PlansApiTest 6/6、TasksApiTest 6/6（后者扩展，计数不变）
- 子代理目录接口可查到：新用例经 MockMvc 调 `GET /v1/sessions/{root}/subagents`（提交人本人视图）返回 2 children 且带 childTaskId。

---

## R-5：SSE 非 ASCII 字节级验证（TaskEventsSseTest:149 自认 MockMvc 非 ASCII 变 '?'——全仓无 UTF-8 StringHttpMessageConverter 注册）

### 改动文件（文件:行）

| 文件 | 位置 | 内容 |
|---|---|---|
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/Utf8StringMessageConvertersConfiguration.java` | 新增 :24-35 | 生产配置类（@Configuration + WebMvcConfigurer）：`extendMessageConverters` **原位替换**既有 StringHttpMessageConverter 为 UTF-8 默认字符集实例（保序，Jackson 等 JSON 转换器不动；无既有实例则插到最前保底） |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/TaskEventsSseRealPortTest.java` | 新增 :256-355 | **真实端口字节级主测**：最小真实 Tomcat（回环随机端口）+@Import 生产配置类+真实 Controller/服务/PG；原始 TCP 套接字抓 SSE 帧（HTTP/1.0——响应不走 chunked，比对对象即帧本体）；断言①生产配置生效（上下文内 StringHttpMessageConverter 实例默认字符集=UTF-8）②帧字节与源 UTF-8 完全一致（`id:1\nevent:STEP_STARTED\ndata:{…中文任务🚀…规划会话🎯…}\n\n` 逐字节）③四字节 emoji 原样字节存在④无 `\uD83C` 转义无 '?' 替换 |
| 同上 | :357-383 | JSON 对照路径（非卡面范围）：事件增量 JSON 中文+四字节 emoji 语义无损回读 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/TaskEventsSseTest.java` | :148-160 | MockMvc 旧断言保留、注释更正：standalone MockMvc 未装配生产 UTF-8 转换器，帧形状断言用 ASCII 负载；真实字节口径归 TaskEventsSseRealPortTest（含四字节 emoji） |

### DoD 命令与结果

```
mvn -B -ntp test -pl engine-runtime -Dtest='TaskEventsSseTest,TaskEventsSseRealPortTest,SessionEventsEndpointTest,RunEventsHttpTest'
→ TaskEventsSseRealPortTest 2/2, TaskEventsSseTest 5/5, SessionEventsEndpointTest 11/11, RunEventsHttpTest 1/1
—— Tests run: 19, Failures: 0, BUILD SUCCESS
```

### 测试统计

- TaskEventsSseRealPortTest：2/2（新增）
- TaskEventsSseTest：5/5（既有断言零改动）
- H1-9 会话 SSE 定向：SessionEventsEndpointTest 11/11、RunEventsHttpTest 1/1

### 排障记录（字节级问题真实定位，全部实证）

1. 初跑失败一：响应 HTTP/1.1 chunked 编码把 chunk-size 行穿插进帧字节 → 改用 HTTP/1.0 请求（Tomcat 不走 chunked）。
2. 初跑失败二：期望 JSON 直接取 `payload::text`（PG jsonb 带 `: ` 空格）而泵出帧经 `Json.read→Json.write` 紧凑化 → 期望帧改用同一投影口径（Json.write(Json.read(payloadText))）。
3. 深挖发现（jshell 定点实证，写此存证）：**@ResponseBody JSON 路径**（Jackson 字节生成器直写）对增补平面字符输出合法 JSON 转义 `\uD83C\uDFAF`（jackson-core 2.19.0 与 2.21.4 行为一致，`writeValueAsBytes`/UTF8JsonGenerator 转义、`writeValueAsString`/Writer 路径原样）——该转义语义无损（解析回原字符），**与字符集/转换器注册无关**，本卡如实登记为对照路径口径；SSE 路径（StringHttpMessageConverter 字符串写出）不受其影响，字节与源一致。
4. DB/JDBC 定点实证：PG server/client_encoding=UTF8，emoji 经 JDBC→jsonb→`payload::text` 逐字节原样（`f0 9f 8e af`）——事实源无失真。
