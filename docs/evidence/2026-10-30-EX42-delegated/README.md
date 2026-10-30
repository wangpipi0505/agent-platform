# EX-42 证据：authority 委派判权与转发（M3 + M2 后半）

卡片：`文档/优化方案/执行卡片-统一补差-20261028.md` §「卡 EX-42」（行 738 起）。
范围：只改 `authority-service/**` 与本草稿目录；**未**触碰平台其它模块、
`admin-common/src/main/resources/agent-service-authority.json`、`web/**`、任何 `pom.xml`。

## 1. 结论

```
cd "G:/207/项目/算法重组工具/代码/agent-platform"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl authority-service test
```

```
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

38 = 既有 24（Decision 6 / Jwks 2 / Session 6 / Token 8 / RealClientContract 2）+ 本卡新增 14：

| 用例类 | 条数 | 覆盖 |
| --- | --- | --- |
| `common.authority.DelegatedForwardContractTest` | 4 | ①两跳（真实 `HttpCurrentAuthorityClient`）③跨目标越权 ④delegated 票冒充 workload ⑤未登记 action |
| `authority.DelegatedSubjectTokenTest` | 3 | ①的另一入口（`subject_token`，portal 用户令牌）、过期令牌、错受众令牌 |
| `authority.DelegatedDecisionExpiryTest` | 2 | ②决策过期 409；未知决策 403（不伪装成 409） |
| `authority.DelegatedDisabledTest` | 2 | ⑤开关（默认关）下 `authorize`/`forward` 一律 403 |
| `authority.DecisionStoreTest` | 3 | ⑥容量淘汰、写时清理过期、get 命中/未命中 |

## 2. 改动清单

### 改（8 个文件，+67 / −8）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/AuthorityApplication.java` | +3/−1 | 注册 `DelegationProperties` 配置块 |
| `.../authority/AuthorityException.java` | +8 | 新增 `expired(auditReason)` → 409 `AUTHORITY_EXPIRED` |
| `.../authority/api/AuthorityController.java` | +12/−1 | tokenUri 分派 `forward`、decisionUri 分派 `authorize` |
| `.../authority/api/AuthorityRequest.java` | +17/−3 | 白名单解析 `subject_token` / `decision_id` / `grant_ref` / `operation_id` |
| `.../authority/api/TicketService.java` | +17/−3 | 抽出公开 `envelope(...)`（决策断言复用 M1 骨架） |
| `.../authority/config/AuthorityConfiguration.java` | +7 | `DecisionStore` bean（容量、Clock 注入） |
| `.../authority/route/RouteDirectory.java` | +2 | `DELEGATED` 常量 |
| `authority-service/src/main/resources/application.yml` | +9 | `agent.authority.delegation.*`（默认关） |

### 新增（主代码 640 行）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `.../authority/decision/DelegatedAuthority.java` | 513 | `authorize` / `forward` 两条 operation、主体校验、决策登记、票据签发、审计、拒绝语义 |
| `.../authority/decision/DecisionStore.java` | 83 | 内存决策存储：容量上限、写时过期清理、`Clock` 注入 |
| `.../authority/config/DelegationProperties.java` | 44 | `enabled`（默认 false）、`grant-seconds`(60)、`decision-seconds`(60)、`capacity`(256) |

### 新增（测试 685 行）

| 文件 | 行数 |
| --- | --- |
| `.../test/java/cn/agentplatform/common/authority/DelegatedForwardContractTest.java` | 145 |
| `.../test/java/cn/agentplatform/authority/DelegatedSubjectTokenTest.java` | 117 |
| `.../test/java/cn/agentplatform/common/authority/DelegatedTickets.java`（造 M2 形态的 delegated 票） | 108 |
| `.../test/java/cn/agentplatform/authority/DelegatedTestSupport.java` | 99 |
| `.../test/java/cn/agentplatform/authority/DelegatedDisabledTest.java` | 92 |
| `.../test/java/cn/agentplatform/authority/DecisionStoreTest.java` | 65 |
| `.../test/java/cn/agentplatform/authority/DelegatedDecisionExpiryTest.java` | 59 |

## 3. 接口与票面（以真实客户端为准）

### 3.1 `authorize`（`decisionUri` = `POST /internal/authorize`，JSON + Basic，调用方限 `gateway`）

请求体：`operation=authorize` + `target/action/resource/nonce` + 二者之一

- `service_ticket`：EX-40 签的 **delegated 服务票据**（平台真实路径：`ServiceAuthorization`
  → `HttpCurrentAuthorityClient.authorizeRetained` 传的就是它）；
- `subject_token`：gateway 手里的 **portal 用户令牌**（`token_use=user`、`iss`=本服务、
  `aud`=`agent.authority.identity.audience`（默认 `agent-platform-portal`）、
  `client_id`=`agent.authority.identity.client-id`（默认 `gateway`））。

服务端校验：`KeyRing` 验签 + `exp/nbf/iat`；主体四项（tenant/sub/roles/attributes）齐全；
目录 `caller -> target/action` 必须是 `delegated` 行（否则 `ROUTE_NOT_REGISTERED`）。

回执 `{ "assertion": <JWS> }`：

- `typ=agent-authority-decision+jwt`、`token_use=current-decision`、
  `aud=urn:agent-platform:service:gateway`、`jti=decision_id`、TTL=`assertion-seconds`（≤5s，客户端
  `maxDecisionSeconds=5` 强制）；
- 复用 M1 骨架：`iss/iat/nbf/exp/jti/client_id/target/action/resource/nonce/request_sha256/active/current`；
- delegated 专属：`authority_mode=delegated`、`decision_id`、`tenant_id/sub/roles/attributes`、
  `initial_ceiling`（**Actor 形状**，客户端 `actor()` 反序列化后重算 `ceiling_sha256`）、`grant_ref`、
  `grant_expires_at`、`authorization_version`、`operation`、`subject_caller`、`ticket_jti`（仅
  `service_ticket` 来源有）。

### 3.2 `forward`（`tokenUri` = `POST /oauth/token`，JSON + Basic）

请求体：`operation=forward` + `decision_id` + `grant_ref` + `operation_id` + 本次 `target/action/resource/nonce`。
调用方必须是**签发该决策的调用方**（`FORWARD_CALLER`）。

校验链：决策存在（`DECISION_UNKNOWN`）→ 决策未过期（409 `DECISION_EXPIRED`）→ grant 未过期
（409 `GRANT_EXPIRED`）→ `grant_ref`/`operation_id` 一致（`FORWARD_GRANT`）→ `resource` 沿用决策内资源
（`FORWARD_ESCALATION`）→ 目录里 `caller -> 新 target/action` 是 `delegated` 行（`FORWARD_ESCALATION`）。

回执 `{ "assertion": <JWS>, "ticket": <JWS> }`，`ticket`：

`typ=at+jwt`、`token_use=service`、`authority_mode=delegated`、
`aud=urn:agent-platform:service:<新 target>`、`caller=client_id=<转发方>`、`sub=<主体>`、
`scope=action`、`tenant_id/roles/attributes`、`grant_ref`、`grant_expires_at`、
`authorization_version`、`initial_ceiling`、`operation`、`subject_caller`、
`exp=min(iat+ticket-seconds, grant_expires_at)`（≤60s）；同时为这一跳登记一条决策（链式授权，
窗口被原 grant 截断）。

`forward` 只能签 **delegated** 票、不能改 `sub`、不能换 `resource`，所以"把 delegated 决策当 workload 用"
两侧都走不通：M2 的 `authorize_workload` 只认 workload 票（`TICKET_MODE`），M3 的 `forward` 只出 delegated 票。

## 4. 拒绝语义（可区分）

| 场景 | 状态 | code | 审计 `reason=` |
| --- | --- | --- | --- |
| 目录里没有该 `caller->target/action` 的 delegated 行 | 403 | `SYS-AUTH-403` | `ROUTE_NOT_REGISTERED` |
| 决策过期 / grant 过期 | 409 | `AUTHORITY_EXPIRED` | `DECISION_EXPIRED` / `GRANT_EXPIRED` |
| 跨目标/换资源/换调用方转发 | 403 | `SYS-AUTH-403` | `FORWARD_ESCALATION`（另 `FORWARD_CALLER` / `FORWARD_GRANT`） |
| 主体令牌不可用/不合法 | 403 | `SYS-AUTH-403` | `SUBJECT_*` |
| 开关关闭 | 403 | `SYS-AUTH-403` | `DELEGATION_DISABLED` |

## 5. 审计

logger `agent.authority.audit`，决策签发与转发成功各一条：

```
delegation.audit endpoint=decision outcome=permit operation=authorize caller=… target=… action=…
                 decision_id=… grant_ref=…
delegation.audit endpoint=decision outcome=permit operation=forward caller=… target=… action=…
                 decision_id=… next_decision_id=… grant_ref=…
delegation.audit endpoint=… outcome=deny operation=… caller=… target=… action=… decision_id=…
                 status=… code=… reason=…
```

不写令牌原文：`DelegatedForwardContractTest` 对成功两跳的审计行断言
`noneMatch(line -> line.contains("eyJ"))`（JWS 段固定以 `eyJ` 开头）。

## 6. 开关与回滚

```
agent.authority.delegation.enabled       # 环境变量 AGENT_AUTHORITY_DELEGATION_ENABLED，默认 false
agent.authority.delegation.grant-seconds # 默认 60（delegated 票据/grant 上限）
agent.authority.delegation.decision-seconds # 默认 60（决策在存储里的窗口）
agent.authority.delegation.capacity      # 默认 256（单进程决策条数上限）
```

关闭时 `authorize` / `forward` 一律 403 `DELEGATION_DISABLED`；`client` / `authorize_workload` / `session`
不受影响（`DecisionEndpointTest`、`TokenEndpointTest`、`SessionEndpointTest` 全绿）。回滚 = 置 false，
无需改代码。

## 7. 红证（先红后绿）

四次变异，每次都**先证明用例会红**，再恢复：

| 证据 | 变异 | 结果 |
| --- | --- | --- |
| `red-A-operations-and-guards-removed.log` | 拆掉 authorize/forward 分派 + workload mode 校验 + `DecisionStore.sweep()` | `Tests run: 11, Failures: 6, Errors: 1`（①③④⑤ 全红；⑥ 3 条里 2 条红，get 命中用例不受影响） |
| `red-B-expiry-and-route-checks-off.log` | 拆掉决策过期判断 + 目录行校验 | `Tests run: 6, Failures: 2`（②⑤ 红） |
| `red-C-rollback-switch-on.log` | yml 开关默认值改 true | `Tests run: 2, Failures: 2`（审计 reason 变成 `token 不是合法 JWS` / `DECISION_UNKNOWN`） |
| `red-D-subject-token-path-off.log` | `fromSubject` 短路 | `Tests run: 3, Failures: 1`（portal 令牌换决策 403） |

控制台是 GBK，日志里的中文断言消息表现为乱码；源码与仓库文件均为 UTF-8，ASCII 的测试名/计数不受影响。

## 8. 与卡片的偏差（按真实客户端落地）

1. **决策 TTL**：卡片写"TTL ≤5s"。真实客户端 `maxDecisionSeconds=5` 约束的是**回执断言**的 `exp−iat`，
   已满足（`assertion-seconds` 默认 5）；`DecisionStore` 里的决策要活到 `forward` 那一刻，窗口取
   `decision-seconds`（默认 60s）且被 `grant` 到期截断。若把存储窗口也压到 5s，客户端两跳之间会必然过期。
2. **`subject` 结构**：卡片写 `subject{subject_id,roles,attributes}`。客户端 `AuthorityProtocol.actor()`
   要求扁平 `tenant_id` / `sub` / `roles` / `attributes`，且 `initial_ceiling` 必须是 Actor 形状
   （`ceiling(Actor)` 的摘要材料是 `{owner, roles, attributes}`）。按客户端：票面用扁平字段 +
   Actor 形状 `initial_ceiling`；"subject" 只作为语义概念存在于 `DelegationRef.subject`。
3. **决策过期状态码**：卡片 409 `AUTHORITY_EXPIRED`；客户端 `AuthorityProtocol.expired()` 是 403 同 code。
   客户端 `query(...)` 对非 2xx 一律按 body 的 `code` 抛 `PlatformException`，**不看状态码**，因此取卡片的 409。
4. **`subject_token` vs `service_ticket`**：卡片只写 `subject_token`；真实客户端 `authorizeRetained` 发的是
   `service_ticket`（delegated 服务票据）。两种都接受：`service_ticket` 是平台真实路径，`subject_token`
   保留给 gateway 直连换决策（另有 `DelegatedSubjectTokenTest` 覆盖）。
5. **`forward` 端点**：卡片写的是 `tokenUri`，与客户端 `query(..., token=true)` 一致。红证 A 第一版曾把
   `forward` 发到 `decisionUri`，被 403 `decisionUri 不接受 operation=forward` 挡住——这正是该口径的证据。
6. **测试动作**：卡片举例 `run.wake`，但目录里 `run.wake` 的 delegating 方是 `skill`，与
   `gateway → engine → skill` 的两跳方向相反。测试改用目录真实行：
   `gateway -> engine / proxy.engine.post.RunController.run`、`engine -> skill / skill.process.cancel`。
7. **拒绝语义的粒度**：卡片要求"越权转发 403 且 `reason=FORWARD_ESCALATION`"。实现里"换 resource"
   与"目录无新行"都归到 `FORWARD_ESCALATION`，`grant_ref`/`operation_id` 不符单独用 `FORWARD_GRANT`
   （仍是 403 `SYS-AUTH-403`），便于排查且不扩大语义。

## 9. 变异恢复复核

```
$ grep -rn "&& false" authority-service/src/main/java
(no match)

$ grep -n "AGENT_AUTHORITY_DELEGATION_ENABLED" authority-service/src/main/resources/application.yml
63:      enabled: ${AGENT_AUTHORITY_DELEGATION_ENABLED:false}

$ grep -c "DelegatedAuthority.AUTHORIZE\|DelegatedAuthority.FORWARD" .../api/AuthorityController.java
2

$ grep -n "sweep();" .../decision/DecisionStore.java
57:        sweep();

$ grep -n "@DefaultValue(\"false\") boolean enabled" .../config/DelegationProperties.java
28:        @DefaultValue("false") boolean enabled,

$ grep -n "token == null" .../decision/DelegatedAuthority.java
173:        if (token == null) {
```

## 10. 未覆盖 / 已知限制

- **多副本**：`DecisionStore` 是单进程内存（容量上限 256）。多副本部署时 `authorize` 与 `forward` 必须
  落在同一实例（粘性路由），否则 `DECISION_UNKNOWN`；卡面未要求共享存储，`DecisionStore` 已把接口收在
  `find/put` 两个方法里，后续换 Redis 不动调用方。
- **user-mgmt 桩**：`subject_token` 用例直接造"同形态"portal 令牌（`KeyRing` 签，claims 与
  `SessionService` 一致），没有起 JDK `HttpServer` 桩；`SessionService` → user-mgmt 的 HTTP 路径由既有
  `SessionEndpointTest` 覆盖。
- **链式多跳**：只测两跳（authorize → forward 一跳）。`forward` 会登记新决策，理论上可继续转发，
  未做三跳用例。
- **属性矩阵**：`roles`/`attributes` 的组合只覆盖 `EDITOR` + `region=[A]`；`ceiling`/`within` 的
  越权矩阵属于 M2 既有用例，未在 M3 重复。
- **审计落盘**：只断言审计行内容与"不含令牌原文"，未做日志脱敏的格式化单测。
