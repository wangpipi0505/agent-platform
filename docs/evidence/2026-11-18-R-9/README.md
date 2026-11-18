# R-9②③④⑤ 网关与契约缺陷收口——证据（2026-11-18）

基线：`b63bfe0c`（R-9① Memories 双构造器修复后），分支 `r9-gateway-defects`，worktree `wt-r9`。
M5-2/M5-3 真实链路验收暴露的四个网关/契约缺陷（M5-2 README 诚实登记 2/3 条 + wt-m53 缺陷登记②③④）本卡源码修复并全链验证。**全部真实请求经网关 18680，零直连、零 shim**（R-9① 后 engine 直接 `java -jar` 可启动，M5-2 时代的 `AGENT_DIRECT_SERVICE_PROXY` 直连绕行随本卡下线）。

## 一、缺陷② 网关路由缺口（/v1/tasks/{id}/plans*、/v1/tasks/{id}/execute 经网关 404）

**根因**：authority 目录（admin-common/agent-service-authority.json）在早前卡中已补齐 T2-3 四条目录行（POST plans / GET revisions / POST confirm / POST execute，`caller=engine→target` 代理行），但 `platform-gateway` 的 `Routes.service()` 正则未接线这批子路径——落入 `/v1/tasks/[^/]+(?:/.*)?` 兜底拒绝行，经网关一律 404 "此任务入口不存在"。

**修复点**：
- `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java:42-50`——任务前缀正则补 `|/plans(?:/[A-Za-z0-9_-]+/revisions/[A-Za-z0-9_-]+(?:/confirm)?)?` 与 `|/execute` 两个显式登记子路径；未登记子路径仍落兜底拒绝行（收口不放水）。
- `platform-gateway/src/test/java/cn/agentplatform/gateway/domain/RoutesTest.java:123-148`——新增用例 `routesTaskPlanAndExecuteEndpointsToEngineWithUnknownSubpathsRejected`：5 条已登记形状（含业务号 `task-abcdef0123456789abcd` 与数字 id 形态）断言入 engine，7 条相近/越界形状（裸 planId、缺 revision、confirm 深层扩展、plansx/executex 等）断言仍拒绝。**用例数演进：19→20**。
- authority 目录核对：`admin-common/src/main/resources/agent-service-authority.json` 四行已在位（POST `/v1/tasks/{taskId}/plans`、GET `/v1/tasks/{taskId}/plans/{planId}/revisions/{revision}`、POST `.../confirm`、POST `/v1/tasks/{taskId}/execute`，均 `caller=gateway` delegated、`integrationAllowed=false`），本卡零改动。

## 二、缺陷③ 网关代理 PATCH 全 500（HttpURLConnection ProtocolException）

**根因**：`GatewayController.proxy()` 用 `HttpURLConnection` 转发，`setRequestMethod("PATCH")` 在 JDK 内建方法白名单外直接抛 `ProtocolException`（未捕获→500）；PATCH 端点（记忆 CAS 更新等）经网关全灭。

**修复点**（与缺陷④同一处转发核心重写）：
- `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java:97-118`——转发核心由 `HttpURLConnection` 换 `java.net.http.HttpClient`（JDK 内建支持任意方法+请求体）；请求方法、Authorization/Content-Type/X-Trace-Id/Last-Event-ID 头如实透传。
- `GatewayController.java:174-176`——共享 `UPSTREAM_CLIENT`（连接建立 5s 上限=原 connectTimeout 额度）；请求级 `timeout(650s)`=原 readTimeout 额度（响应头超时→HttpTimeoutException(IOException 子类)→原 502 DEPENDENCY_UNAVAILABLE 信封）。
- 选型说明：JDK 21 下反射改 `HttpURLConnection.method` 私有场需 `--add-opens`（InaccessibleObjectException），跨 dev 脚本/测试/服务的 JVM 参数布点面过大，故取最小侵入且语义等价的 HttpClient 路线；SSE 流式转发路径（BodyHandlers.ofInputStream 逐块 flush）与错误信封保持原样。

## 三、缺陷④ 网关代理丢弃 DELETE 体

**根因**：原转发仅对 `Set.of("POST","PUT","PATCH")` 开 doOutput 转发请求体，DELETE 带体（记忆撤销要求 `requestId` 幂等键）体被静默丢弃。

**修复点**：
- `GatewayController.java:102-118`——体判定改为"非幂等安全方法即转发体"（GET/HEAD/OPTIONS/TRACE 之外一律 `BodyPublishers.ofInputStream`），DELETE/PATCH/POST/PUT 全部如实透传。
- `GatewayController.java:179-199`——`BoundedInputStream` 保持原有界流转发语义（普通 2MiB / artifacts multipart 65MiB，`requestLimit` 复用），超限 IOException→502 信封不变，不缓存整体。

## 四、缺陷⑤ ID 契约核查（先核查再动）

**核查结论：违反契约，engine 端最小修复。**

契约依据：
1. `contracts/error-codes.md` §1（C0-2 契约冻结，来源=接口稿 §2 全表）："时间/标识 | ISO-8601 UTC；**对外标识一律 public_id/taskId 等业务号，不暴露内部 bigint**"。
2. 接口稿《04-接口设计-20261112》§5.2（`文档/设计/1109现行/04-接口设计-20261112.md:279-360`）：请求/响应示例中 taskId 形状=业务号（`"upstreamTaskId": "task-99"`、`"taskId": "task-77"`、响应 `{taskId, state:'ACCEPTED',...}`）；任务详情/事件/计划/执行端点路径变量统一 `{taskId}` 同形状。
3. 事实（M5-2 登记②）：`Tasks.create` 生成并返回 `task-` + 20 hex 的 public_id（`Tasks.java:125`），而 `TaskController` 9 个 `{taskId}` 端点全收 `long`（内部 bigint）——浏览器面受理/列表拿到的业务号回传即 `400 MethodArgumentTypeMismatch`，无桥。

**修复点**（engine 端最小：收业务号解析到内部 id，数字 id 保持兼容）：
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/Tasks.java:883-905`——新增 `resolveTaskId(String, Actor)`：纯数字=内部 id 直传（存量 caller/测试兼容）；其余按 `public_id+tenant_id` 查内部 id（租户隔离，查无/他租户/越形→404 `TASK_NOT_FOUND` 与 `ownedTask` 同口径，不区分泄露存在性；归属核验仍由 ownedTask 承接 id+tenant+principal）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/api/TaskController.java:37-39` 与全部 `{taskId}` 端点（detail/events/stream/cancel/resume/reconcile/stepCalls/appendPlan/planRevision/confirmPlan/executePlan/result 共 12 处）——`@PathVariable long` 改 `String` 经 `resolve()` 解析；服务层签名零改动。
- 网关路由形状同步：既有 `[A-Za-z0-9_-]+` 本就覆盖 `task-*` 业务号（RoutesTest 新用例含业务号形状断言），零改动。
- 测试扩展：`engine-runtime/src/test/java/cn/agentplatform/engine/application/TasksApiTest.java:187-211`——新增 `resolveTaskIdAcceptsBusinessPublicIdAndKeepsNumericCompat`（业务号解析/数字兼容/不存在 404/他租户 404 四断言）。**用例数演进：6→7**。

## 五、前端绕行收回

M5-2 因缺陷②③④加的 dev-only 直连绕行下线：
- `web/admin/vite.config.ts:4-13`——删除 `AGENT_DIRECT_SERVICE_PROXY=1` 全部分流分支（按服务前缀直连 182xx），dev server 与生产同路径一律经网关（AGENT_GATEWAY_URL 可覆盖目标）。
- `web/shared/api-task.ts` 核查：**无绕行**——统一经 `useApi()`（/v1 → 网关），无数字 id 映射/直连分支；`listTasks` 逐行补详情、`api-plan.ts` 计划链本就以业务号传参，缺陷⑤修复后天然成立，零改动。
- vitest 对比基底：基底（stash web 改动后）13 failed / 621 passed；收回绕行后 **13 failed / 621 passed（零新增失败）**。既有失败 4 文件（assertion-editor 6、mcp-registration 2、run-detail-navigation 4、satellite-planning 1）均为基底既有回归。
- `npm run build`：通过（vue-tsc --noEmit + admin/webapp/assistant.js 三产物 + copy-assistant）。

## 六、DoD 命令与结果（逐条留痕）

1. **最小栈经网关真实请求**：`bash docs/evidence/2026-11-18-R-9/start-stack.sh`——四服务 18680-18683（网关/app/engine/skill），专用库 `r9_fix`（服务启动 Flyway 全链迁移+V901 种子自动应用）；R9 端口段 186xx；Redis/MinIO/model-gateway/monitor 未起（健康探针 503/派发停受理属预期，M5-2 同口径如实登记）。真实请求实录见 `api-evidence.log`（`node docs/evidence/2026-11-18-R-9/api-evidence.mjs` 生成），要点：
   - 缺陷③：PATCH 错误 expectedVersion → **409 MEMORY_VERSION_CONFLICT**（体到达引擎被业务处理）；正确版本 → **200** version 1→2。
   - 缺陷④：DELETE 带 `{requestId}` 体 → **200 status=REVOKED**；幂等重放 → **200 REVOKED**。
   - 缺陷②：POST plans（baseRevision=0 真建修订 VALID）→ GET revisions/1 → POST confirm（confirmSeq=1）→ POST execute → **全 200，execute 真实产出 executionTaskId**。
   - 缺陷⑤：受理返回 `task-972dc30f1af846809e8c`；detail/events/result 以业务号回传 → **全 200**（taskId 业务号回显）。
   - 未登记子路径（裸 planId / plansx / executex / confirm 深层扩展）→ **网关 404 NOT_FOUND**（收口不放水）。
2. **网关定向测试**：`mvn -B -ntp test -pl platform-gateway -Dtest='RoutesTest,IntegrationSecurityTest'` → **RoutesTest 20/20（19→20）、IntegrationSecurityTest 7/7，BUILD SUCCESS**。
3. **verify-routes**：`node scripts/verify-routes.mjs` → **handler 324 / 目录行 336 / MISSING 0 / PORTAL_SURFACE 0 / EXCLUDED 0 / STALE 6（基线同）**，blocking=false。
4. **engine 定向回归**：`mvn -B -ntp test -pl engine-runtime -Dtest='MemoryApiTest,TasksApiTest'`（`ENGINE_PG_URL=jdbc:postgresql://127.0.0.1:25432/r9_fix`，只建/只用 r9_fix）→ **MemoryApiTest 9/9（=基线）、TasksApiTest 7/7（6→7，零新增失败）**。
5. **前端**：见 §五（vitest 零新增失败 + build 过）。

## 七、诚实登记项

1. **execute 后续执行链**：execute 受理五项校验全过并创建执行任务（executionTaskId 真实产出），但 model-gateway/monitor 未起，执行派发停受理态（M5-2 登记⑪同口径）——本卡验证面是网关可达性与契约形状，不含模型出话。
2. **demo 租户票据**：V901 种子 tenant=demo 数据面（记忆/任务）经本地铸造 HS256 票（与 ServiceTokens 同构、服务端同密钥真实验签；M5-2 同先例，仅本机验证库）；SPA 登录面 /auth/dev-token（local-v12）为网关真实出票。
3. **数字 id 兼容形态保留**：契约只规定对外标识为业务号，未禁止数字形态入站；为存量 caller/既有测试不断裂，`resolveTaskId` 保留纯数字=内部 id 直传。如实取舍：未按"仅收业务号"收紧（收紧会破坏 EngineContextPostgresTest/夹具既有数字 id 用例，且接口稿未列 deprecated 窗口）。
4. **代理读超时语义微差**：原 readTimeout=每阻塞读 650s；HttpClient 侧等价为"响应头 650s 超时"（体流式阶段不再受限）。长同步请求（≤650s 出头）行为等同；纯 SSE 头早到不受影响。
5. **既有前端测试失败**：vitest 13 失败为基底既有回归（本卡 stash 对照零新增），归属原卡不在此收口。
6. **authority 目录零改动**：缺陷②的目录行在早前卡已补齐（T2-3 四行），本卡实际缺口只在网关 Routes 方向正则——按实登记，不虚报 admin-common 改动。

## 八、本卡改动范围与提交

- `platform-gateway`：Routes.java（②）、RoutesTest.java（②）、GatewayController.java（③④）
- `engine-runtime`（仅⑤）：Tasks.java、TaskController.java、TasksApiTest.java
- `web/admin/vite.config.ts`（收回绕行；web/shared/api-task.ts 核查后零改动）
- `docs/evidence/2026-11-18-R-9/**`（本 README、start-stack.sh、api-evidence.mjs/.log）
- 台账零改动；本地提交不推送（分缺陷提交，hash 见交付说明）。
