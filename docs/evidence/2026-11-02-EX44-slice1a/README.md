# EX-44 切片 1a 证据：观测订阅目录行 / origin_authentication_id / Decision.ticketJti

- 日期：2026-11-02
- 分支：`feat/v1.2-implementation`，HEAD `88beadb4`
- 范围：EX-44 **仅 1a**（目录行 + `origin_authentication_id` 生产者/透传 + `Decision.ticketJti` 回写）；1b/1c/1d 未实现
- 环境：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`；**未** `source .tools/env-load.sh`；`-Dmaven.repo.local=.tools/m2`

## 1. 根因

`HttpCurrentAuthorityClient.openGatewayPortalEvents` 的 SSE 出流第二步（S1）用
`new ServiceCall("gateway", RunObservationSelectors.GATEWAY_EVENTS_ACTION, resource)`
向 authority 交换 `caller=gateway → target=gateway` 的观测订阅票，但目录
`admin-common/src/main/resources/agent-service-authority.json` 里只有
`gateway → engine` 的 `/admin/runs/{id}/events` 行；`RouteDirectory.permits` 精确匹配失败，
exchange 与随后的 authorize 都返回 `403 ROUTE_NOT_REGISTERED`。

同时 authority-service 从不签发 `origin_authentication_id`：`SessionService.claims()` 只写 `jti`，
`DelegatedAuthority` 也不读主体凭据上的该 claim，所以客户端在 `:594 origin(ticket.claims())`
直接 403，且 `ticket_jti` 在 exchange 路径恒为 null（`fromSubject` 写死 null），
1c 无法从 decision 反查 S0 的票。

## 2. 改动文件

| 文件 | +/- | 内容 |
| --- | --- | --- |
| `admin-common/src/main/resources/agent-service-authority.json` | +12 | 新增 1 行 gateway→gateway 观测订阅目录行（第 962–973 行） |
| `authority-service/.../identity/SessionService.java` | +11/-1 | 签发人会话 JWT 时写 `origin_authentication_id`，新增 `originAuthenticationId(jti)` |
| `authority-service/.../decision/DelegatedAuthority.java` | +51/-15 | `ticket()` 返回 `IssuedTicket(value,jti)`；`exchange()` 回写 `ticketJti` 后再造断言；`ticket()`/`authorityClaims()` 注入 origin；`fromSubject`/`fromTicket` 读 origin；`originOf` 严格校验 `oa1:<32hex>` |
| `authority-service/.../decision/DecisionStore.java` | +7 | `Decision` 增加 `originAuthenticationId` 字段 + `withTicketJti(String)` 副本方法 |
| `authority-service/src/test/.../GatewayEventsSlice1aContractTest.java` | 新 146 行 | 1a 契约测试（ticketJti 回写、origin 三处透传、authorize 继承） |
| `admin-common/src/test/.../GatewayEventsCatalogTest.java` | 新 58 行 | 目录行形状 + `require()` 精确命中 |
| `authority-service/src/test/.../SessionEndpointTest.java` | +27 | 断言 `/auth/session` 令牌的 origin 公式（`oa1:` + sha256(`portal-session:`+jti) 前 32 hex） |
| `admin-common/src/test/.../AuthorityPolicyTest.java` | +9/-4 | 覆盖全集加入 Servlet 能力 `gateway\|GET\|/admin/runs/{id}/events`，catalog 159→160 |
| `admin-common/src/test/.../GatewayPortalEventsAuthorityTest.java` | +9/-1 | 旧断言“目录没有该 action”改为“恰好一条 closed 行（delegated、integrationAllowed=false）” |
| `authority-service/src/test/.../DecisionStoreTest.java` | +1/-1 | 适配 `Decision` 新字段 |

未改：`HttpCurrentAuthorityClient`、`TrustedJwtDecoder`、`BoundedIamHttp`、pom、依赖、迁移、`.env`、`application.yml`。
未 `git add` / 未 `git commit`。

## 3. 目录行最终字段

```json
{
  "method": "GET",
  "template": "/admin/runs/{id}/events",
  "caller": "gateway",
  "target": "gateway",
  "action": "gateway.run.events.observe",
  "mode": "delegated",
  "integrationAllowed": false,
  "source": "admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:621",
  "callerSource": "platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:55",
  "reason": "SSE 出流第二步：gateway→gateway 的观测订阅票；缺此行使第二次 exchange 与 authorize 都 403 ROUTE_NOT_REGISTERED"
}
```

`method`/`template` 取自客户端真实调用形状：`HttpCurrentAuthorityClient.java:621` 构造
`ServiceCall("gateway", "gateway.run.events.observe", RunObservationSelectors.events(run, versionDigest))`，
对应 SSE Servlet 路由 `GET /admin/runs/r_[0-9a-f]{32}/events`（`PortalRunEventsSse.java:449`）。

## 4. 红（先写测试，未实现）

### 4.1 目录行缺失

```bash
mvn -B -ntp -pl admin-common -Dtest=GatewayEventsCatalogTest -Dmaven.repo.local=.tools/m2 test
```

```
[ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0
```

断言原文：`Expected size: 1 but was: 0 in: []`；`require()` 抛“服务调用能力未登记”。

### 4.2 origin / ticketJti 缺失（目录行已加、行为未实现）

注意：`mvn -pl authority-service` 会取本地仓库中旧的 admin-common jar（不含新行），
必须走 reactor（`-pl admin-common,authority-service`）或先 install admin-common。

```bash
mvn -B -ntp -pl admin-common,authority-service \
  -Dtest=GatewayEventsSlice1aContractTest,SessionEndpointTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=.tools/m2 test
```

```
[ERROR] Tests run: 10, Failures: 4, Errors: 0, Skipped: 0
```

- `SessionEndpointTest`：`Expected size: 36 but was: 0`（令牌无 origin）
- `GatewayEventsSlice1aContractTest`：`expected: "oa1:0123456789abcdef0123456789abcdef" but was: ""`（ticket/断言/decision 均无 origin），`decision.ticketJti()` 为 null

## 5. 绿

### 5.1 目标测试

```bash
mvn -B -ntp -pl admin-common,authority-service \
  -Dtest=GatewayEventsCatalogTest,GatewayEventsSlice1aContractTest,SessionEndpointTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=.tools/m2 test
```

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   (GatewayEventsCatalogTest)
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0   (SessionEndpointTest)
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0   (GatewayEventsSlice1aContractTest)
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0  (聚合)
BUILD SUCCESS
```

### 5.2 authority-service 全量

```bash
mvn -B -ntp -pl admin-common -DskipTests install -Dmaven.repo.local=.tools/m2
mvn -B -ntp -pl authority-service test -Dmaven.repo.local=.tools/m2
```

```
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  8.967 s
[INFO] Finished at: 2026-11-02T12:05:38+08:00
```

### 5.3 admin-common 全量

```
[ERROR] Tests run: 386, Failures: 1, Errors: 6, Skipped: 0
```

失败类与 HEAD 基线一致（见 §7），本切片未新增失败；`GatewayPortalEventsAuthorityTest` 18/18 绿。

## 6. 断言与设计映射

- `SessionService.claims()`：`origin_authentication_id = "oa1:" + hex32(sha256("portal-session:" + jti))`，长度 36（设计 §3.1/卡片 §5）；由 `SessionEndpointTest` 断言公式、前缀、长度与十六进制形状。
- `DecisionStore.Decision.ticketJti`：exchange 签发票后立即 `withTicketJti(ticket.jti())` 回写并重新 `put`，随后才生成断言，因此断言也带 `ticket_jti`；`GatewayEventsSlice1aContractTest#exchangeRecordsTheIssuedTicketJtiOnTheDecision` 用 `decision_id` 反查 decision 并与票的 `jti` 比对。
- origin 透传：`fromSubject` 从主体（portal 会话）令牌读取并写入 decision；`ticket()` 写入票；`authorityClaims()` 写入断言；`fromTicket` 从上游票透传。测试覆盖“票 / 断言 / decision”三处同值，以及 S1 的 authorize 从 `service_ticket` 继承 origin。
- `authorityClaims()` 仍只在 `decision.ticketJti()!=null` 时写 `ticket_jti`，未放宽既有语义。
- 未触碰客户端判定：`origin()` 仍只校验 ≤128，`same()`、`:629` 反重用、`:630` 55s 硬期限未改。

## 7. 验收命令与预先存在的失败

卡片 §7 验收命令原文：

```bash
mvn -B -ntp -pl authority-service -am test -Dmaven.repo.local=.tools/m2
```

本机原文结果（`exit 1`）：

```
[ERROR] Tests run: 386, Failures: 1, Errors: 6, Skipped: 0
[INFO] admin-common ....................................... FAILURE [02:48 min]
[INFO] authority-service .................................. SKIPPED
[INFO] BUILD FAILURE
```

这 3 个类在 **HEAD `88beadb4`（把我的改动 stash 后）同样失败**，与本切片无关：

- `AuthorityPolicyTest.catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards:160`：
  测试用 `indexOf("public class ")` 扫描控制器源码，而 `PortalSessionController` /
  `ModelInputMaterialController` 是 `public final class`，恒为 -1（测试与代码脱节）。
- `LocalRunAdvanceAuthorityTest.sameRootAppReadAndDebugGrantsCannotBecomeLocalMain:38`：
  `HttpCurrentAuthorityClient.query` 返回 active/current=false。
- `ServiceTokensDeploymentModeTest` 5 个用例 `MalformedInput Input length = 1`（`runScenario:44` 读源码的字符集问题）。

基线（同一组 3 个类，HEAD）原文：`Tests run: 25, Failures: 1, Errors: 6`。
因此 `-am` 会在 admin-common 处中断、authority-service 被 SKIPPED；本切片的 authority-service
回归改用 §5.2 的两步命令（先 install 带新目录行的 admin-common，再跑 authority-service 全量），结果 46/46 绿。

## 8. 未覆盖（留给 1b/1c/1d）

- 1b：`gateway_events_opening`（S0）与 `query_ticket`（S1）两种 exchange 变体、一次性消费、`source_token_sha256`/nonce 入 decision。当前客户端 `beginGatewayPortalEvents`/`openGatewayPortalEvents` 仍会分别因未实现变体而失败，属预期。
- 1c：`portal_gateway_events_open` events 协议（`opening_decision_id`/`parentAdmissionId` 反查、immutable 组装、`checked_until==exp`）。
- 1d：`GatewayEventsRejectionTest`（N1–N8）与全链路证据；本切片只落地 `GatewayEventsSlice1aContractTest` + `GatewayEventsCatalogTest` 两个最小类。
- 本轮未对完整 SSE 链路做端到端验证（依赖 1b/1c）。
- `origin_authentication_id` 仅由 authority 签发到 portal 会话 JWT；`TrustedJwtDecoder` 未改（不是入站校验项），客户端靠自有 claim 读取。

## 9. 回滚

1. 总开关：`agent.authority.delegation.enabled=false`（默认值），委派 exchange/authorize 立即整体关闭。
2. 目录行：删除 `agent-service-authority.json` 中第 962–973 行的 `gateway.run.events.observe` 行，恢复 `RouteCoverageVerifier`/`AuthorityPolicyTest` 的 159 行口径。
3. 代码：回退本 README §2 的 6 个生产/测试文件即可；无迁移、无依赖、无配置变更，故无需数据回滚。

## 10. 与设计/卡片不一致或需确认之处

1. **source / callerSource 方向**：卡片 §3 写 `source=platform-gateway/.../PortalRunEventsHttp.java`、`callerSource=admin-common/.../HttpCurrentAuthorityClient.java:622`；与用户 m00001（source=客户端文件:行号，callerSource=gateway 侧发起处）相反。按用户口径落盘，未自行放宽。
2. **origin 透传范围**：用户第 2 条只点名 `SessionService` 生产；设计 §8 的 1a 定义与卡片 §6 步骤 1 都包含“生产者（票/断言透传）”。按文档权威口径实现透传（未新增 1b/1c 能力）。
3. **测试类命名**：卡片 §3 预留 `GatewayEventsContractTest`/`GatewayEventsRejectionTest`（1d 的全量契约/拒绝面）；本切片按最小面拆成 `GatewayEventsSlice1aContractTest` + `GatewayEventsCatalogTest`，拒绝面留 1d。
4. **origin 形状收紧**：`originOf` 对非 `oa1:<32hex>` 值直接 403（`SUBJECT_ORIGIN`/`TICKET_ORIGIN`）；设计只要求客户端 ≤128。该收紧用于防止伪造/畸形 origin 被透传，属收紧而非放宽；如需兼容其它形状需产品确认。
5. **`AuthorityPolicyTest` 全集**：为纳入 Servlet 能力新增 `servletIngress` 常量（159→160），测试从“只算 MVC”改为“MVC+已知 Servlet 行”；不改变其它 159 行的校验。
6. **预先存在失败**：`AuthorityPolicyTest`/`LocalRunAdvanceAuthorityTest`/`ServiceTokensDeploymentModeTest` 在 HEAD 即红（§7），本切片未修复也未掩盖；`-am` 验收命令因此无法整体绿，需先独立修复这 3 个类。
