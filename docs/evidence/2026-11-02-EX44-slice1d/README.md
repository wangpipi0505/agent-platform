# EX-44 切片 1d：`portal_gateway_events_open` 决策 + continuation

- 分支：`feat/v1.2-implementation`（基线 HEAD `dab5c297`，切片 1c）
- 时间：2026-11-02
- 范围：只改 `authority-service` 的 main/test；不改 `admin-common`、gateway、pom、依赖、迁移、yml、`.env`
- 结论：S0→S1→S2→S3 全链路在**真实客户端** `HttpCurrentAuthorityClient.beginGatewayPortalEvents` +
  `openGatewayPortalEvents` 下跑通；端到端 SSE 仍归 EX-45

## 1. 任务与背景

EX-44 的第四切片（1d）补上 S3：gateway 拿到 retained 票（S1）并完成 retained 确认（S2）后，用
`portal_gateway_events_open` 换取**事件断言**与**continuation**。S3 是 EX-44 最后一片，做完这一跳
S2/S3 才通；S3 之后 gateway 还要做 EX-45 的 `portal_events_child_exchange`（本切片不做）。

S3 请求体（客户端 `HttpCurrentAuthorityClient:640`）：

```json
{
  "operation": "portal_gateway_events_open",
  "decision_id": "<S2 决策 id，即 S2 断言 jti>",
  "binding": {
    "responseId": "...", "resource": {"kind":"run-snapshot","id":"r_...","versionDigest":"<64hex>"},
    "effectiveCursor": 7, "observationRequestSha256": "<64hex>",
    "hardDeadline": "<ISO-8601>"
  },
  "profile": "portal-gateway-events-response.v1",
  "immutable": { "...25 个字段（含 gateway_query）..." }
}
```

响应必须是 `{ "assertion": "<JWT>" }`（**不是票**），断言里带 `continuation_ref`；continuation 的
`exp` 必须等于 `immutable.hard_deadline`，`immutable` 必须整树回显。

## 2. 根因（为什么 1d 之前必然失败）

1. **决策端点不认这个 operation**：`AuthorityController.DECISION_OPERATIONS` 只有
   `authorize_workload`，`decide` 在派发前就 403 `decisionUri 不接受 operation=portal_gateway_events_open`，
   客户端只能看到 `SYS-AUTH-403`。这是红灯的直接原因。
2. **D3（S2 决策）没有 S3 需要的证据**：1c 的 `fromTicket` 把带 `parent_admission_id` 的决策写成
   `purpose=EVENTS`、`admissionNonce/admissionRequestSha256` 全是 `null`。S3 要按 `decision_id` 找到
   `RETAINED` 决策、再经 `parentAdmissionId` 反查 D1（S0），并校验 `immutable.admission_nonce` /
   `admission_request_sha256`，这些值只能是 S2 自己的请求 nonce / 请求摘要（客户端
   `RetainedAuthorization` 正是从 S2 断言取这两个值）。1c 不写，S3 无从比对。
3. **`DecisionStore.Decision` 缺 D3 消费字段**：没有 `bindingResponseId` / `continuationJti` /
   `hardDeadline`，S3 无法把「打开点」绑定到 `binding.responseId` 并审计 continuation。
4. **`AuthorityRequest` 没有类型化访问器**：`binding` / `profile` / `immutable` 只在 `raw` 里，
   没有长度上限与显式类型校验，直接 `get` 会把 400 语义拖到 500/403。
5. **`TicketService` 没有 continuation 签发**：只有 `at+jwt` 票和 `agent-authority-decision+jwt`
   断言，没有 `agent-portal-gateway-events-continuation+jwt`。
6. **源头摘要口径不一致（关键坑）**：客户端 `q.sourceDigest = AuthorityProtocol.digest(source)`
   = `Digest.sha256(Json.canonical(source))`，即对**加了 JSON 引号**的 token 字符串取 sha256；
   1c 服务端存的是 `Digest.sha256(token)`（token 原文）。S3 要求
   `immutable.gateway_query.source_token_sha256 == D1.sourceTokenSha256`，按旧口径**永远不相等**。
   必须把 1c 的 S0/S1 摘要统一到客户端口径，否则真实客户端必然 403 `EVENTS_IMMUTABLE`。

## 3. 改动清单

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java` | 196 → 201 | `DECISION_OPERATIONS` 增加 `portal_gateway_events_open`；`decide` 在 AUTHORIZE 分支后、集合校验前派发 `delegated.eventsOpen(...)`（未知 operation 仍 403） |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java` | 148 → 241 | 新增 `Binding` 记录与 `binding()` / `profile()` / `immutable()` 类型化访问器：`binding` 必须恰好 5 键、`resource` 必须恰好 `kind/id/versionDigest`、cursor 整型 ≥0、sha 64hex、`hardDeadline` 可解析为 `Instant`；任何违规 400 `INVALID_REQUEST` |
| `authority-service/src/main/java/cn/agentplatform/authority/api/TicketService.java` | 241 → 273 | 新增 `continuation(clientId, jti, immutable, hardDeadline, iat)`：`typ=agent-portal-gateway-events-continuation+jwt`、`token_use=portal-gateway-events-continuation`、`aud=urn:agent-platform:service:<clientId>`、`exp=hardDeadline`、`continuation_jti`/`hard_deadline`/`immutable` 回显 |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java` | 198 → 201 | `Decision` 增加 `bindingResponseId` / `continuationJti` / `hardDeadline`；`withTicketJti` / `consumed` 全量复制；`consume` 仍用 `compute` 做原子 CAS |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | 873 → 1131 | 新增 `EVENTS_OPEN` / `GATEWAY_EVENTS_PROFILE` / 55s 常量；新增 `eventsOpen(...)` 与 `expectedEventsImmutable(...)`、`ceilingMaterial(...)`、`subjectOf`、`resourceField`、`epochSeconds`、`textClaim` 等；1c 的 S0/S1 摘要改为 canonical 口径；S2 决策改为 `RETAINED` 并写入 S2 请求 nonce/摘要 |
| `authority-service/src/test/java/cn/agentplatform/authority/DecisionStoreTest.java` | 67 | 构造器适配 25 个组件 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1dSupport.java` | 169（新） | 基于 `GatewayEventsSlice1cSupport`：构造 S0/S1/S2，按客户端逐字段拼 `binding`/`immutable`/`gateway_query`，直连 `/internal/authorize` 发 S3 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1dContractTest.java` | 204（新） | 5 条协议不变量 + 1 条真实客户端 S0→S3 用例 |

未改：`admin-common`（含 `HttpCurrentAuthorityClient`、`TrustedJwtDecoder`、目录 JSON）、gateway、pom、
依赖、迁移、yml、`.env`。

## 4. 红 → 绿原文

### 4.1 红（测试先写，`eventsOpen` 尚未实现；当时 operation 被 endpoints 拒绝）

命令：

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl authority-service test -Dtest=GatewayEventsSlice1dContractTest
```

```
[ERROR] Tests run: 5, Failures: 5, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
...
eventsOpenReturnsAnAssertionEnvelopeAndASignedContinuation:56 [S3 body:
  {"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}]
  expected: 200 OK but was: 403 FORBIDDEN
replayingTheSameOpenIsForbiddenNotConflict:113 expected: 200 OK but was: 403 FORBIDDEN
aHardDeadlineBeyondTheServerClampIsRejected:135 Expecting value to be true but was false
aTamperedImmutableIsRejectedAsImmutableMismatch:151 Expecting value to be true but was false
anUnknownDecisionIdIsRejectedAsDecisionNotBinding:165 Expecting value to be true but was false
```

前两条证明「正路完全不通」；后三条失败在「期望审计原因」上，说明请求在
`AuthorityController.decide` 就被挡掉，还没进入业务判定。

### 4.2 绿（实现后，含真实客户端用例）

```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.933 s
       -- in cn.agentplatform.common.authority.GatewayEventsSlice1dContractTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

真实客户端用例 `theRealGatewayClientOpensTheWindowAndAcceptsTheContinuation` 通过，意味着
`HttpCurrentAuthorityClient` 本地对 continuation 的 `typ/aud/exp/immutable`、断言 `checked_until==exp`、
`exp<=hard_deadline`、D3 消费等几十项校验全部接受。

### 4.3 全模块回归

```
mvn -B -ntp -pl authority-service test
...
[INFO] Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（1c 的 10+1 条、1b 的 8 条、1a 的 3 条、RealClientContract 2 条全部保持绿。）

## 5. 断言点（错误原因 / 时间夹取 / D3 消费）

失败语义统一 403 `SYS-AUTH-403`（客户端看到 `IAM_AUTHORITY_FORBIDDEN`），过期 409
`AUTHORITY_EXPIRED`，形状错误 400 `INVALID_REQUEST`：

| 判定 | 失败原因 |
| --- | --- |
| `decision_id` 缺失 / 查不到 / 非 `RETAINED` / `parentAdmissionId` 查不到 D1(OPENING) | `EVENTS_DECISION` |
| D3 已被消费（重放） | `EVENTS_OPEN_REPLAY`（403，**绝不 409**） |
| D3 已过期 | 409 `EVENTS_DECISION_EXPIRED` |
| 请求 call 与 D3 的 target/action/resource 不一致、`binding.resource` 不一致、`observationRequestSha256` 重算不一致、`profile` 不是 gateway profile | `EVENTS_BINDING` |
| `hard_deadline <= now` | 409 `EVENTS_DEADLINE_EXPIRED` |
| `hard_deadline > clamp` | `EVENTS_DEADLINE` |
| `immutable.origin_authentication_id` 与 D3 不一致 | `EVENTS_ORIGIN` |
| `immutable` 与按 D3/D1 重建的整棵树不等值 | `EVENTS_IMMUTABLE` |

**时间夹取**（`DelegatedAuthority.eventsOpen`）：

```java
long clamp = Math.min(iat + EVENTS_OPEN_MAX_SECONDS,          // now + 55s
        epochSeconds(retained.operation(), "expires_at"),     // S2 operation.expires_at
        retained.grantExpiresAt().getEpochSecond(),           // S2 grant_expires_at
        admission.grantExpiresAt().getEpochSecond());         // D1 的 grant exp（≤ portal exp）
if (hardDeadline > clamp) throw denied("EVENTS_DEADLINE");
long checkedUntil = Math.min(iat + properties.assertionSeconds(), hardDeadline); // 5s 上限
```

- 断言 `exp = checked_until = min(now+5s, hard_deadline)`；continuation `exp = hard_deadline`。
- 之所以写回**请求里的 hardDeadline** 而不是把 clamp 写回：客户端 `verifyEventsContinuation` 要求
  `time(immutable,"hard_deadline") == time(continuation,"exp")`，服务端只能校验后原样使用；校验条件
  `now < requested <= clamp` 与设计 §4.5 一致。
- S3 请求体没有 portal token，`clamp` 的 portal exp 用 D1 `grantExpiresAt` 作代理（S0 已按 portal exp
  截断，且 `grant = min(S0+60s, portalExp) >= 任何合法 client hard`），不会误杀合法请求。

**D3 消费**：

```java
if (decisions.consume(retained.id(), clientId).isEmpty()) throw denied("EVENTS_OPEN_REPLAY");
```

`DecisionStore.consume` 用 `entries.compute` 对 `consumedAt == null` 做 CAS：并发/重放只有一个请求能
拿到返回值，其余一律 403 `EVENTS_OPEN_REPLAY`。测试断言：第二次 open 返回 403 且 body `code=SYS-AUTH-403`
（不是 409），审计出现 `reason=EVENTS_OPEN_REPLAY`，`DecisionStore.find(S2 id).consumedAt() != null`、
`consumedBy=gateway`；同时 D4（EVENTS）落库并记录 `bindingResponseId` / `continuationJti` /
`hardDeadline`，`parentAdmissionId = D3.id`。

**断言信封**：返回 `Map.of("assertion", signed)`，无 `ticket` 字段；断言含 `profile`、`immutable`
（整树回显）、`continuation_ref`、`checked_until == exp`、`ticket_jti`、`origin_authentication_id`、
`initial_ceiling` 等；不含 `parent_response_id` / `completion_permit` / `parent_response`。

## 6. 与设计的差异（均为通过真实客户端所必需）

1. **S0/S1 source token 摘要口径**：设计写的是 `sha256(portal token 原文)`，但真实客户端
   `q.sourceDigest` 是 `AuthorityProtocol.digest(source)`（对 JSON 引号化字符串取 sha256）。1c 的
   `fromSubject`（S0 存）与 `exchangeQueryTicket`（S1 比对）已统一改为
   `Digest.sha256(Json.canonical(token))`，否则 S3 与客户端必然对不上。同 token 的等值判断不变，
   换 token 仍拒绝，1c 测试不受影响。
2. **S2 决策 purpose**：设计 §4.2 要求 S3 只认 `RETAINED`，1c 代码写的是 `EVENTS`；本切片把
   `fromTicket` 带 `parent_admission_id` 的分支改为 `RETAINED`，并把 S2 请求的
   `nonce` / `request_sha256` 写入 D3——这正是客户端 `immutable.admission_nonce` /
   `admission_request_sha256` 的来源（取值自 S2 断言）。
3. **portal exp 代理**：S3 请求体不含 portal token，`clamp` 用 D1 `grantExpiresAt`（S0 已按 portal exp
   截断）代替设计的 “portal exp” 项。
4. **hardDeadline 写回**：设计只要求「校验后写入」，实现直接写请求值（= `min(request, clamp)`），
   以保证 continuation `exp` 与客户端 immutable 的 `hard_deadline` 严格相等。
5. **S2/D3 合并未采用**：设计允许把 S2 与 D2 合并；本实现保持 D3 独立（`authorize` 的决策），
   因为客户端 `decision_id` 指向的是 S2 的断言 `jti`，且 D3 消费点是设计明确的 open point。

## 7. 未覆盖（EX-45 还差什么）

- `portal_events_child_exchange` **不在** `DECISION_OPERATIONS`：gateway 拿到 S3 响应后调用
  `exchangePortalEventsChild` 会 403 `decisionUri 不接受 operation=portal_events_child_exchange`。
  这是 EX-45 的第一个具体失败点。
- 没有 child continuation：`typ=agent-portal-engine-events-child-continuation+jwt`、
  `token_use=portal-engine-events-child-continuation`、`immutable_sha256` 回显。
- 没有 gateway 侧 `checkGatewayPortalEvents` / `response_open` 端点与 engine profile
  （`portal-events-response.v1` / `portal-engine-events-child-response.v1`）。
- 没有 child 关联校验：`child_association_sha256`、`portal_events_child` marker、child open 票据、
  child receipt / `acceptGatewayChildReceipt`。
- 没有 engine 侧 S3 等价决策（`portal_events_response_open` 等）与长连接 SSE 转发。

## 8. 回滚

无迁移/配置/数据库变更，回滚 = 还原 6 个 main/test 改动并删除 2 个新测试文件：

```
git checkout -- authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java \
  authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java \
  authority-service/src/main/java/cn/agentplatform/authority/api/TicketService.java \
  authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java \
  authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java \
  authority-service/src/test/java/cn/agentplatform/authority/DecisionStoreTest.java
rm authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1dSupport.java \
   authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1dContractTest.java
```

（本次未 `git add`、未 `git commit`。）

## 9. 复现

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl admin-common install -DskipTests -q
mvn -B -ntp -pl authority-service test -Dtest=GatewayEventsSlice1dContractTest  # 6 tests
mvn -B -ntp -pl authority-service test                                        # 71 tests
```

不要 `source .tools/env-load.sh`。
