# INT-02 子步3（A 席）：受控执行分发前接线 + engine 引用传递 + 健康探针

- 卡/子步：INT-02 子步3（AP A 席）
- 分支：`upgrade/20261103`
- 基线 HEAD：`912fa874`（子步2 第二笔）
- 日期：2026-11-03
- 口径：契约以 `contracts/configuration-profile.md` §5.2/§10/§11.4 与 P 席 m01556 派单为准；缺契约处只登记、不私改。

## 1. 原责任 → 新责任

| 责任 | 之前 | 现在 |
| --- | --- | --- |
| 调用携带的连接引用 | 无（引擎不知道这次调用要连哪个受控连接） | `engine.tool_call.binding_refs` 随登记钉住，来源为运行快照 `config.bindingRefs`（载荷由引擎构造，浏览器不可写） |
| 分发前复核 | 无（技能拿到什么就用什么） | `SkillExecution.gateApproved` 在进入适配器之前统一调用 `ApprovedConnectionResolver.gate`；`execute`（首次/ PREPARE-EXECUTE / 只读重试共用）与 `reconcile` 两处入口 |
| 运行期可达性 | 无观测手段 | `GET /internal/connection-profiles/{id}/versions/{rev}/health` 只读探针（不进 resolve 回执） |
| 解析回执 | 只有"谁解析了什么" | 追加 run/call/attempt/operation 作用域（V318），被拒的调用不留"已解析"记录 |

## 2. 改动文件

app 侧：
- `app-management/src/main/java/cn/agentplatform/app/application/ConnectionHealth.java`（新增）：`probe(profileId, profileRev, environment)` 取该修订被批准且钉住的绑定，按 `healthPolicy.path` 发一次不带凭据的 GET；404 `CONFIG_REF_NOT_FOUND`、停用 `CONFIG_RELEASE_NOT_ACTIVE`、非批准修订 `CONFIG_STATE_TRANSITION_INVALID`；无 `path` 时 `UNKNOWN`（不因"看不懂"放行或误拒），远端异常折叠 `FAILED` + 异常类名（不外泄内网细节）；`timeoutMs` 夹在 100–10000ms。
- `app-management/src/main/java/cn/agentplatform/app/api/InternalConnectionProfilesController.java`：新增 health 端点（`InternalConnectionProfilesController.java:41`），构造器增 `ConnectionHealth`。
- `app-management/src/test/java/cn/agentplatform/app/application/AppCopyConcurrencyTest.java`、`PublishGateTest.java`：DataSource 改接 `TestDatabase`（`AGENT_DB_URL` 约定），消除硬编码 `25432`。

engine 侧：
- `engine-runtime/src/main/resources/db/migration/V226__tool_call_binding_refs.sql`（新增）：`alter table engine.tool_call add column if not exists binding_refs jsonb` + 数组性 check；存量行为 null 即 legacy。
- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java`：`register` 写入 `binding_refs`（载荷没有时回退取运行快照，引用是引擎权威事实而不是调用方自报）；`args_digest` 复核后追加 `sameRefs` 复核 → 409 `CALL_BINDING_CHANGED`；`view()` 输出 `bindingRefs`（无引用为 JSON null，不是空数组）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java`：`skill(...)` 把运行快照 `config.bindingRefs` 放进调用载荷。
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ToolCallBindingRefsTest.java`（新增）。

skill 侧：
- `skill-registry/src/main/resources/db/migration/V318__connection_resolution_call_scope.sql`（新增）：回执表追加 `run_public_id/call_id/attempt_id/operation`（仍追加型，子步2 历史行不补）。
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ConnectionResolutionReceipts.java`：5 参重载（带作用域），4 参重载委托。
- `skill-registry/src/main/java/cn/agentplatform/skill/application/ApprovedConnectionResolver.java`：`gate(contract, request, skillCode)` + `resolve(..., scope)`；引用来源"请求优先、契约缺省"，两处都空 = legacy 原路径且零副作用；逐条解析并校验操作白名单（400 `CONNECTION_OPERATION_NOT_ALLOWED`）与摘要一致（409 `CONNECTION_BINDING_CHANGED`）；引用自带 `tenantId` 直接拒绝。
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java`：`gateApproved` 在 `execute`（只读重试分支之后）与 `reconcile` 调用；未装配解析器却声明了引用时 503 `CONNECTION_RESOLUTION_UNAVAILABLE`，不静默降级。
- `skill-registry/src/test/java/cn/agentplatform/skill/application/ApprovedConnectionGateTest.java`（新增）。

权限目录：
- `admin-common/src/main/resources/agent-service-authority.json`：追加 1 条 `app.connection.health`（`integrationAllowed=false`），并把 `skill.approved-connection.read` 的 `callerSource` 从占位 `RunService.java:255` 改为真实调用点 `skill-registry/src/main/java/cn/agentplatform/skill/api/ApprovedConnectionSkillController.java:23`；其余行只读不重排（`git diff --numstat` 13 行增、1 行改）。

## 3. 不变量

1. 引用只来自引擎权威：运行快照（服务端）或引擎构造的载荷；浏览器/pageContext 不参与。
2. 同一 `idempotency_key` 引用一旦登记即固定，换引用 409，旧行不被改写。
3. 未声明引用的存量技能走原路径，行为与改动前一致（legacy null 而非空数组）。
4. 解析不缓存：撤权、停用、换版在下一次调用立即生效；被拒的调用不写回执。
5. 拒绝发生在进入适配器之前——没有解析成功就没有业务调用发出。
6. 健康探针只读（无凭据、无请求体、不跟随重定向），不进 resolve 回执。
7. 既有错误码/事务边界/摘要算法未改；新增码仅 `CALL_BINDING_CHANGED`、`CONNECTION_OPERATION_NOT_ALLOWED`、`CONNECTION_RESOLUTION_UNAVAILABLE`（待登记，见 §5）。

## 4. 命令与结果

环境：`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`，`AGENT_DB_USER=dev_ap_test`，口令从私有准备根读入（未回显），`ENGINE_PG_TEST=true`。

| # | 轮次 | 命令要点 | 结果 |
| --- | --- | --- | --- |
| 1 | 首轮 | `-Dtest='ApprovedConnectionGateTest,ToolCallBindingRefsTest'` | 编译红：`ApprovedConnections.java` 泛型推断失败；修后引擎 3 例仍 2 红、gate 7 例全红 |
| 2 | 二轮 | 同上 | 引擎 3 红→修断言；gate 5 红 2 错：`ApprovedConnectionResolver.bindingRefs` 用 `List.of(null)` 空指针 |
| 3 | 功能轮 | `-Dtest='ToolCallBindingRefsTest,ApprovedConnectionGateTest'` | engine 3/3、skill 9/9 全绿 |
| 4 | 目录+集成 | `-Dtest='RouteCoverageVerifierTest,ApprovedConnectionFlowTest,ToolCallBindingRefsTest,ApprovedConnectionGateTest'`（含 `-pl admin-common,app-management,skill-registry,engine-runtime`） | admin-common 10/10、app 3/3、engine 3/3、skill 9/9，合计 25 绿 |
| 5 | 夹具规范化 | `-pl app-management -Dtest='AppCopyConcurrencyTest,PublishGateTest'` | 1/1 与 11/11 全绿 |

已修的三处实现缺陷（都不是改断言糊过去）：
- `ApprovedConnections` 泛型推断（`List<?>` → `String`）；
- 引用数组断言把 Jackson `path("0")` 当字段名用（数组要用 `path(0)`）；
- `ApprovedConnectionResolver.bindingRefs` 用 `List.of(first, second)` 装可空来源，换 `Arrays.asList`。

## 5. 未覆盖 / 契约缺口

未覆盖：
- 未跑全量回归与真实启动（`ROUTE_COVERAGE_MISSING` 只能由 P 的全量窗口确认；本席只用 `RouteCoverageVerifierTest` 校验目录可加载与精确模板）。
- 健康探针没有集成用例（需要一个会应答/不应答的本机 HTTP 端点）；当前只有实现与契约对齐，端点路径与调用方也尚未冻结。
- 凭据注入（`secretRef` → 真实密钥）与受控执行接线不在本子步。
- ⑤"未引用新对象的存量技能成功"目前由 `callsWithoutRefsDoNothingAtAll`（零交互）与 `callsWithoutBindingRefsKeepTheLegacyShape`（legacy null）覆盖，端到端版本待 INT-03/受控执行接线时补。

契约缺口（只登记，不私改）：
1. §11.4 未给健康端点路径与调用方，本席按"内部只读"实现（`/internal/...`，`integrationAllowed=false`）。
2. `CONNECTION_OPERATION_NOT_ALLOWED`、`CONNECTION_RESOLUTION_UNAVAILABLE`、`CALL_BINDING_CHANGED` 三个码未在 `assistant-integration.md` §6 登记。
3. skill 侧受控入口（`/internal/skills/{code}/approved-connections/...`）仍未在契约冻结。
4. `config_write_request` 未覆盖 grant/revoke 幂等（子步2 用天然键代替），本子步未改。
5. 引用里的 `environment` 语义（缺省取运行环境还是绑定默认）未在契约明确；当前缺省即"不过滤"。

## 6. 回退

```sql
-- skill
drop table if exists skill.connection_resolution;
-- 回退到 V317 形态需重建该表并保留子步2 列；
-- engine
alter table engine.tool_call drop column if exists binding_refs;
```
代码侧按文件回退（app 两个类、engine 两个类、skill 三个类、目录一行、迁移两个）。
