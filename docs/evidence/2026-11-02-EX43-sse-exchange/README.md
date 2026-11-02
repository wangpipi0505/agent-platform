# EX-43 `POST /oauth/token operation=exchange`（iam 模式下 SSE 换票，2026-11-02）

## 1. 为什么：SSE 403 的根因链

iam 模式下 gateway 的 SSE 订阅（`/admin/runs/{id}/events`、`/v1/runs/{id}/events`）
要用一张 portal 用户令牌换一张“gateway → engine、action=events”的 delegated 票，
换票入口是 `HttpCurrentAuthorityClient.exchange(subjectToken, call)`
（`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:124-141`）。

1. 对 `token_use=user` 的 portal 令牌，客户端走 `portalExchange`（同文件 `:145-156`）：
   本地验 portal 令牌、要求 `platform.access`，然后向 authority 的 tokenUri 发
   `operation=exchange` + `subject_token` + target/action/resource。
2. authority 侧旧 `issueTicket` 只认 `operation=forward`（改前
   `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java:89-90`），
   其余走 `TICKET_OPERATIONS=Set.of("client")`（`:41`）。`exchange` 于是落到
   `throw AuthorityException.denied("tokenUri 不接受 operation=exchange")`（改前 `:92-94`），
   返回 403 + body `SYS-AUTH-403`。
3. gateway 侧把 403 映射成 `PlatformException: 可信授权服务拒绝了该调用（403）`，
   SSE 流在建立之前就失败——这就是“合法订阅拿不到票”的直接原因。
4. 根因不是目录：`admin-common/src/main/resources/agent-service-authority.json` 里
   `GET /v1/runs/{id}/events`、`GET /admin/runs/{id}/events` 已有 gateway→engine、
   `proxy.engine.get.RunController.events` 的 delegated 行（EX-43 已补，本卡未改）。
5. 还有一个必须同形的协议点：客户端 `query(...)`（同文件 `:460-481`）强制校验响应里的
   `assertion`，`verifiedTicket(...)` 又从断言 claim 取 `ticket`（`:419-441`，取票在 `:420`）。
   所以 exchange 响应必须是 `{"assertion": <current-decision jwt>, "ticket": <delegated at+jwt>}`，
   且断言 claim 里也带 `ticket`；只回一个 `ticket` 字段会被客户端在验签处拒绝。

## 2. 改了什么

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | +62 | `:60` 新增 `public static final String EXCHANGE = "exchange"`；`:168-216` 新增 `exchange(clientId, request)`：`requireEnabled`、caller 必须是 gateway、目录 delegated 行、校验 portal 令牌 `exp`、grant 夹到 `exp`、签 delegated 票、返回 `assertion`+`ticket`、成功/失败写 `delegation.audit`；`:219-221` 原两参 `fromSubject` 委托到新重载；`:226-263` 新增 `Instant cap` 重载并只缩短 grant 到期 |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java` | +10/-4 | `:25-36` 类注释改为 tokenUri 支持 `client/forward/exchange`、decisionUri 支持 `authorize_workload/authorize`，其余仍未实现；`:94-97` 在 FORWARD 分支旁新增 EXCHANGE 分支，转调 `delegated.exchange(clientId, request)`；`TICKET_OPERATIONS` 保持只含 `client` |
| `authority-service/src/test/java/cn/agentplatform/common/authority/ExchangeContractTest.java` | +210（新文件） | 4 个契约用例：`HttpCurrentAuthorityClient` 正例换票；非 gateway、目录无行、`token_use=service` 三个负例 |

未改：`admin-common` 主源、`agent-service-authority.json`、pom、依赖、其它模块、`.env`。

两处与任务文字的必要偏差（均为协议约束，不是扩权）：

- 响应体带了 `assertion`（与 `forward` 同形）。客户端 `query` 先验断言、`verifiedTicket` 再从断言 claim 取票，
  只回 `Map.of("ticket", ...)` 会让真实客户端失败；断言只是本次决策的签名回显，没有新增可访问目标。
- 对外 body code 仍是 `SYS-AUTH-403`。`EXCHANGE_CALLER_NOT_GATEWAY`/`ROUTE_NOT_REGISTERED` 按本仓库既有契约
  只进审计 reason（`AuthorityException.denied` 的注释明确不把拒绝原因回显给调用方），测试同时断言 body code
  与审计 reason。

## 3. 先红后绿

### 红（实现前，2026-11-02 11:11）

命令（JDK 21，只跑新类）：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -pl authority-service test -Dtest='ExchangeContractTest' \
  -Dmaven.repo.local=$PWD/.tools/m2 -DargLine="-Xmx1024m"
```

控制台节选：

```text
[ERROR] Tests run: 4, Failures: 3, Errors: 1, Skipped: 0
...
cn.agentplatform.common.PlatformException: 可信授权服务拒绝了该调用（403）
...
reason=tokenUri 不接受 operation=exchange
```

3 个负例被旧的 `TICKET_OPERATIONS` 门先拒（审计 reason 都是 `tokenUri 不接受 operation=exchange`，
尚未到达 caller/目录校验）；正例在客户端收到 403 后抛 `PlatformException`。
该次 surefire 文本报告随后被绿跑覆盖，以上为当时的控制台原文。

### 绿（11:12）

```text
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.518 s -- in cn.agentplatform.common.authority.ExchangeContractTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 验收回归（11:13）

命令：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -pl authority-service test \
  -Dtest='ExchangeContractTest,DelegatedForwardContractTest,SessionEndpointTest,TokenEndpointTest,DecisionEndpointTest' \
  -Dmaven.repo.local=$PWD/.tools/m2 -DargLine="-Xmx1024m"
```

```text
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.289 s -- in cn.agentplatform.authority.DecisionEndpointTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.992 s -- in cn.agentplatform.authority.SessionEndpointTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.097 s -- in cn.agentplatform.authority.TokenEndpointTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.638 s -- in cn.agentplatform.common.authority.DelegatedForwardContractTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.369 s -- in cn.agentplatform.common.authority.ExchangeContractTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 4. 断言清单

正例 `gatewayExchangesAPortalUserTokenForTheSseTicket`（用真实 `HttpCurrentAuthorityClient`，
serviceId=gateway，call=`engine` / `proxy.engine.get.RunController.events` /
`run-snapshot:r_0123456789abcdef0123456789abcdef`）：

- 票据 `typ=at+jwt`，`authority_mode=delegated`，`caller=client_id=gateway`；
- `target=engine`、`action=proxy.engine.get.RunController.events`、
  `aud=urn:agent-platform:service:engine`、`scope=action`；
- `tenant_id=tenant-a`、`sub=alice`（portal 主体）、`grant_ref` 非空、
  `authorization_version=delegated-v1`、`initial_ceiling.sub=alice`；
- `operation.source_kind=PORTAL`；
- `operation.expires_at <= portalToken.exp`，且 30s 令牌下**恰好等于** `portalToken.exp`
  （证明 grant 被令牌 exp 夹住，而不是默认 60s）；
- `ticket.exp <= operation.expires_at`，`ServiceTicket.expiresAt()` 与 `operation.expires_at` 一致；
- 审计含 `operation=exchange` + `outcome=permit`，且不含 `eyJ`（不落令牌原文）。

负例（原始 HTTP，`POST /oauth/token`）：

| 场景 | HTTP | body code | 审计 reason |
| --- | --- | --- | --- |
| 调用方是 `engine` 而不是 gateway | 403 | `SYS-AUTH-403` | `EXCHANGE_CALLER_NOT_GATEWAY` |
| action=`run.wake`，目录无 delegated 行 | 403 | `SYS-AUTH-403` | `ROUTE_NOT_REGISTERED` |
| portal 令牌 `token_use=service` | 403 | `SYS-AUTH-403` | `SUBJECT_TOKEN_USE` |

测试里的 portal 令牌由本服务 `KeyRing` 直接签成 `token_use=user`（`iss=authority.test`、
`aud=agent-platform-portal`、`client_id=gateway`、`scope=platform.access`），
test 类注释写明“这就是 EX-40 会话端点签发的形状”。

## 5. 未覆盖

- **真实 SSE 订阅端到端**：本卡只覆盖到 `HttpCurrentAuthorityClient.exchange` 拿到 delegated 票这一跳。
  gateway 的 `beginGatewayPortalEvents`/`openGatewayPortalEvents` 后续还要 events-opening 断言
  （`gateway_events_opening`、`query_ticket`、child exchange），并且消费 `origin_authentication_id`；
  authority-service 当前没有该 claim 的生产者，本卡没有凭空补它，因此没有跑真实 SSE 流的端到端用例。
- **多副本 DecisionStore**：`exchange` 仍在本实例的内存决策表登记 decision；票本身是无状态 JWT，
  但后续 `forward` 若落到另一副本会找不到 decision。本卡未做多副本路由/共享存储验证。
- **`exchange_integration`**（`token_use=integration` 的服务令牌换票）仍未实现，不在本卡范围。
- **portal `exp` 边界竞态**：只测了 30s/300s 与“刚好等于本秒”的拒绝，没有测时钟偏移、
  跨秒边界和并发换票。
- 没有压测/容量验证；delegation 决策表容量上限仍由 `capacity=256` 决定。

## 6. 回滚面

- 开关：`agent.authority.delegation.enabled`（环境变量 `AGENT_AUTHORITY_DELEGATION_ENABLED`，默认 `false`）。
  关掉后 `exchange` 在 `requireEnabled()` 处 403 `DELEGATION_DISABLED`，不会进入验令牌/签票路径；
  gateway 行为回到本卡之前的“换票 403”，SSE 仍不可用但没有新增暴露面。
- 代码回滚：删除 `DelegatedAuthority.EXCHANGE`/`exchange(...)`/`Instant cap` 重载与
  `AuthorityController` 的 EXCHANGE 分支即可；没有 schema、迁移、目录行或依赖变更。
- 已签票据最多 60s，且被 portal 令牌 `exp` 再夹一层；回滚后自然过期，无需清理。
