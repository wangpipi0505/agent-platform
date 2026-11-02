# EX-45：SSE 出流第二段（authority 侧 `portal_events_child_exchange`）

- 分支：`feat/v1.2-implementation`，HEAD `b19e886e feat(authority): SSE opening 决策与 continuation 票据 (EX-44 1d)`
- 范围：只改 `authority-service` 的 main/test + 本证据目录；未动 `admin-common`（客户端是契约）、`platform-gateway`、engine、pom、依赖、迁移、yml、`.env`
- 结论：gateway 的 `PortalRunEventsHttp` 在 `openGatewayPortalEvents` 成功后会立刻调用 `exchangePortalEventsChild`，此前该 operation 在 `AuthorityController.decide` 就被 403 挡住——这是 SSE 不出流的最后一个服务端缺口。本卡补齐后，真实客户端 `HttpCurrentAuthorityClient.exchangePortalEventsChild` 全链（含 `verifyAssociation` / `verifyChildOpen` / `digest(association)` 逐字节比对）通过。

## 1. 根因

两个独立缺陷，只有先读客户端才能同时定位：

1. **入口缺失**：`AuthorityController.DECISION_OPERATIONS` 只有 `authorize_workload`、`portal_gateway_events_open`，
   `decide()` 也没有 `portal_events_child_exchange` 的分支，请求在进入判定前就被
   `decisionUri 不接受 operation=...` 拒绝（红测 8 个用例全部 403，见 §5）。
2. **产物落点错位**（只读设计文档看不出来）：客户端 `exchangePortalEventsChild` 里

   ```java
   JsonNode marker = eventsObject(verified.claims(), "portal_events_child");      // :683
   if (!AuthorityProtocol.same(AuthorityProtocol.digest(association),
           text(envelope, "child_association_sha256"))) throw denied();           // :685
   ```

   `verified` 是 `verifiedTicket(...)` 的返回，`VerifiedTicket.claims()` 是**被验票面**（G 票）的 claims；
   `envelope` 是决策断言 claims。因此 `portal_events_child={association,child_open}` 必须签进 **G 票**，
   `child_association_sha256` 必须签进 **决策断言**——把两者都放信封（或都放票面）会在 `:683`/`:685` 直接 403。

## 2. 判定顺序（`DelegatedAuthority.eventsChildExchange`）

固定顺序，reason 可区分（外部码不变：403 `SYS-AUTH-403` / 409 `AUTHORITY_EXPIRED`）：

1. `requireEnabled()`；`clientId != "gateway"` → `EVENTS_CHILD_CALLER`；
2. `requireChildPurpose(call)`：target=`engine`、action=`RunObservationSelectors.EVENTS_ACTION`、
   资源 `run-snapshot` + run id/sha 形状，否则 `EVENTS_CHILD_PURPOSE`；
3. `verifyChildContinuation`：`keys.verify(continuation, agent-portal-gateway-events-continuation+jwt)`、
   `token_use=portal-gateway-events-continuation`、iss、`aud=urn:agent-platform:service:gateway`、
   `jti`/`continuation_jti` 存在、`immutable` 是对象；任一不满足 → `EVENTS_CHILD_CONTINUATION`；
4. 按 `continuation_jti` 反查 D4，必须 `purpose=EVENTS`，否则 `EVENTS_CHILD_CONTINUATION`；
5. **重放先于过期**：D4 已被消费 → `EVENTS_CHILD_REPLAY`；D4 已过期 → 409 `EVENTS_CHILD_CONTINUATION_EXPIRED`；
6. `continuation.exp == immutable.hard_deadline`，否则 `EVENTS_CHILD_CONTINUATION`；
7. 窗口：`hard_deadline <= now` → 409 `EVENTS_CHILD_DEADLINE_EXPIRED`；`hard_deadline > now+55` →
   `EVENTS_CHILD_DEADLINE`；`hard_deadline != D4.hardDeadline` → `EVENTS_CHILD_DEADLINE`
   （服务端不接受自签 continuation 放大窗口）；
8. `parent_immutable` 必须与 continuation 随签节点逐字节相同，否则 `EVENTS_CHILD_IMMUTABLE`；
9. 用 D4→D3→D1 反查后**整棵重建** immutable（`expectedEventsImmutable`）并整节点比较，
   `binding.responseId == D4.bindingResponseId`，否则 `EVENTS_CHILD_IMMUTABLE`；
10. call 资源三元组必须等于 D4 资源与 binding 资源，否则 `EVENTS_CHILD_BINDING`；
11. `subject_token` 必填、`verifyPortalUser` 通过、`sha256(canonical(token)) == D1.sourceTokenSha256`、
    origin 一致，否则 `EVENTS_CHILD_SOURCE`；
12. **CAS 消费 D4**（一个父窗口只换一次 child），失败 → `EVENTS_CHILD_REPLAY`；
13. 落 D5（`purpose=CHILD`）、签 G 票（带 marker）、签 `child_open`、回 `child_association_sha256`。

## 3. 改动清单（`git diff --numstat` + 新文件行数）

| 文件 | 变更 |
| --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | +290/-1（现 1420 行）：`CHILD_EXCHANGE`、`CHILD_ASSOCIATION_SCHEMA`、`eventsChildExchange`、`verifyChildContinuation`、`requireChildPurpose`、`bindingOf`、`nodeEpoch`、`ticket(...)` 的 jti/附加 claims 重载 |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java` | +35/-1（现 235 行）：`Purpose.CHILD`、`continuations` 索引、`findByContinuationJti` |
| `authority-service/src/main/java/cn/agentplatform/authority/api/TicketService.java` | +30（现 303 行）：`CHILD_OPEN_TYPE`/`CHILD_OPEN_USE`、`childOpen(...)` |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java` | +25（现 266 行）：`parentContinuation()`、`parentImmutable()`（派生访问器，不改 record 形状） |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java` | +5/-1（现 205 行）：`DECISION_OPERATIONS` + `decide` 分支（与 `EVENTS_OPEN` 同源，不收 service_ticket） |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1eSupport.java` | 新增 105 行 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1eContractTest.java` | 新增 291 行 |

`DecisionStore.Decision` 仍是 25 个字段（不持久化 association 摘要：EX-46 可以直接用 `child_open.association_sha256`，
少一个会漂移的副本）；`Purpose.CHILD` 的 `expiresAt = hardDeadline`（不是 5s 断言窗），否则 EX-46 的
`child_open` 反查会先被 sweep 掉。

## 4. `child_association` 字段与摘要口径

服务端组装的节点（`LinkedHashMap` → `Json.read(Json.write(...))` 规范化）：

```
schema                 "portal-events-child-association.v1"
parent                 请求回显的 S3 immutable（整节点，含 gateway_query）
engine.jti             本跳 G 票 jti（先于 association 生成）
engine.grant_ref       本跳新 grant_ref（≠ immutable.grant_ref）
engine.grant_expires_at
engine.authorization_version
engine.operation       本跳 operation（camelCase，operation_id/expires_at 全新）
engine.initial_ceiling
engine.origin_authentication_id
engine.action          proxy.engine.get.RunController.events
engine.target          "engine"
engine.caller          "gateway"
engine.resource        {kind,id,version_digest}
source_token_sha256    D1 记录的 portal 令牌摘要（= gateway_query.source_token_sha256）
child_binding          {responseId, resource{kind,id,versionDigest}, effectiveCursor,
                        observationRequestSha256, hardDeadline(ISO)}
hard_deadline          epoch 秒（= child_binding.hardDeadline）
```

摘要口径：`child_association_sha256 = Digest.sha256(Json.canonical(association))`。
`Json.canonical` 逐层按 key 排序（数组保序、数字保宽），节点里没有 `Set`，因此与客户端
`AuthorityProtocol.digest(association) = Digest.sha256(Json.canonical(sorted(association)))` 逐字节一致；
真实客户端用例 `theRealGatewayClientExchangesTheChildAndGetsALiveProof` 断言的正是客户端 `:685` 的 `same(...)`。

`child_open`（typ `agent-portal-events-child-open+jwt`、use `portal-events-child-open`、
aud `urn:agent-platform:service:engine`、exp = `hard_deadline`）携带
`association_sha256 / parent_response_id / child_response_id / exp / iat`，
满足客户端 `verifyChildOpen` + `requireAssociationToken`。

## 5. 红 → 绿原文

红（实现前，只加测试与两处常量；`red-1e.txt` 全文）：

```
[ERROR] Tests run: 8, Failures: 6, Errors: 2, Skipped: 0
[ERROR] GatewayEventsSlice1eContractTest.theChildExchangeEchoesTheAssociationChildOpenAndAChildDecision:100
        [S4 body: {"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}]
[ERROR] GatewayEventsSlice1eContractTest.replayingTheSameParentIsForbiddenNotConflict:186 expected: 200 OK but was: 403 FORBIDDEN
[ERROR] Tests run: 8, Failures: 3, Errors: 2  ← 补入口后仍有 5 个：EVENTS_CHILD_BINDING（父资源 vs 子调用比较错位）
```

绿（`green-1e.txt` / `green-full.txt`）：

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.071 s
    -- in cn.agentplatform.common.authority.GatewayEventsSlice1eContractTest
[INFO] BUILD SUCCESS

[INFO] Tests run: 79, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

全量 79 个用例覆盖 `authority-service` 现有 17 个测试类（含 EX-44 的 1a/1b/1c/1c-expiry/1d 与
`RealClientContractTest`），未放宽任何既有断言。

## 6. 断言点（8 个用例）

1. `theRealGatewayClientExchangesTheChildAndGetsALiveProof`：真实 `HttpCurrentAuthorityClient`
   `beginGatewayPortalEvents → openGatewayPortalEvents → exchangePortalEventsChild`，
   `proof.requireVerified()`，G 票 claims（caller/client_id=gateway、target=engine、scope=events action、
   authority_mode=delegated、aud=`urn:agent-platform:service:engine`、`operation.expires_at == ticket.exp`）。
2. `theChildExchangeEchoesTheAssociationChildOpenAndAChildDecision`：响应体不含 `ticket`；schema；
   `association.parent` 与父 immutable 整节点相等；`source_token_sha256` 同时等于 `gateway_query` 的值与
   `digest(portal)`；`child_binding` 换 `responseId`/`hard_deadline`、资源/游标/观测摘要不变、且不超父窗口；
   `engine` 11 个字段与 G 票逐项相等、`grant_ref`/`operation_id` 与父不同；`child_open` 的
   use/aud/exp/`association_sha256`/`parent_response_id`/`child_response_id`；决策断言
   `child_association_sha256 == digest(association)`；不带 `parent_response_id/parent_response/completion_permit/child_receipt`；
   D4 被 gateway 消费、D5 `purpose=CHILD` + `parentAdmissionId=D4` + `bindingResponseId=child responseId`。
3. `replayingTheSameParentIsForbiddenNotConflict`：第二次 403 + `EVENTS_CHILD_REPLAY`（不是 409）。
4. `anExpiredParentContinuationIsRejected`：403 + `EVENTS_CHILD_CONTINUATION`。
5. `aTamperedParentImmutableIsRejectedAsImmutableMismatch`：改 `admission_nonce` → 403 + `EVENTS_CHILD_IMMUTABLE`。
6. `aChildDeadlineBeyondTheParentWindowIsRejected`：自签 continuation + 自洽 immutable 把窗口放大 60s → 403 + `EVENTS_CHILD_DEADLINE`。
7. `aNonGatewayCallerIsRejected`：engine 凭据直接调用 → 403 + `EVENTS_CHILD_CALLER`。
8. `theChildAssociationDigestIsTheCanonicalDigestOfTheEchoedAssociation`：服务端摘要 == `AuthorityProtocol.digest(association)`。

## 7. 与设计的差异（有意）

1. **child 窗口 == 父窗口**：客户端入参只有 `parent_continuation`/`parent_immutable`，没有 child deadline，
   因此"更短的硬窗"没有来源；服务端 clamp（`min(now+55, D4.hardDeadline, 父 operation.expires_at, 父 grant_expires_at)`）
   在 S3 已夹取后恒等于父 `hard_deadline`。仍按 clamp 计算，防止将来 S3 的夹取变化后放大窗口。
2. **过期 continuation 是 403 不是设计 §5 的 409**：`KeyRing.verify` 对 `exp<=now` 统一 403，
   与 `query_ticket` 的既有取舍一致（continuation 不是可续期的"授权过期"状态）。409 只用于
   "D4 仍在库里但已过期"（`EVENTS_CHILD_CONTINUATION_EXPIRED`）与"窗口已过"（`EVENTS_CHILD_DEADLINE_EXPIRED`）。
3. **marker 在票面、摘要在信封**：设计文档没有规定落点，由客户端 `:683`/`:685` 反推（见 §1）。
4. **D5.sourceTokenSha256 取 D1 的摘要**：S2 走 `service_ticket`，`fromTicket` 不写 `source_token_sha256`，
   D3/D4 该字段为空；权威值在 D1（与 `immutable.gateway_query.source_token_sha256` 同源）。
5. **本卡不产出 `child_receipt`、不签 `portal-engine-events-child-continuation`**：
   客户端这两个产物都从 `portal_events_child_open` 的响应读取（`:731-769`），属于 EX-46。

## 8. 未覆盖 / 后续

- **EX-46（engine 侧）**：`portal_events_child_open`（收 G 票 + `child_open`、落 child 决策、签
  `child_receipt` 与 child continuation）、`portal_events_child_check`（续期，客户端 `renewedEventsEvidence`）。
- **`authorize` 对 `portal_events_child` 的回显**：客户端 `verifiedChildReceiver`（`:692-702`）要求
  G 票与 `authorize` 决策断言同时带同一 marker 节点；本卡未改 `authorize`，若后续让 G 票再走 `authorize`
  必须补回显，否则 403。当前链路（child → `portal_events_child_open`）不经过它。
- **D4 的 5s 断言窗**：`D4.expiresAt = min(now+5s, hard_deadline)`，S4 必须落在 S3 之后 5s 内
  （客户端在同一个 `eventsWork` 里立刻换取）。迟到 S4 会因 D4 已被 sweep 得到 403
  `EVENTS_CHILD_CONTINUATION`——这是 S3 单次 consumption 的预期语义，未放宽。
- **进程级联调**：engine 未实现，只验证到 authority 边界 + 真实客户端（不启 gateway 进程）。

## 9. gateway 侧 `PortalRunEventsHttp` 还需要什么（未改它）

现有代码顺序已经正确：`:302 openGatewayPortalEvents` → `:312 exchangePortalEventsChild(originalPortalSource, g)`
→ `:313 child = issued` → `:324-329` 用 `child.ticket().value()` 作为 engine `/admin/runs/{id}/events` 的 Bearer。
EX-45 之后这一段可通。仍然缺：

1. **child 窗口续期**：G/child 窗口 ≤55s，而 `livePrivate()`（`:412-418`）每次读都 `child.requireVerified()`，
   长流会在窗口到期后 abort。需要走 `portal_events_child_check`（客户端 `renewedEventsEvidence`）或重开窗口，
   否则 SSE 只能连续出流 ≤55s。
2. **engine 的 child 接收**（EX-46）：engine 必须验 G 票（aud=engine、action=events、caller=gateway），
   调 `portal_events_child_open` 把 `child_receipt` 回给 gateway；gateway 侧接收路径已存在
   （`PortalRunEventsHttp:235 acceptReceipt → GatewayPortalEventsResponse.acceptChildReceipt`，
   `PortalEventsMaterialVerifier:124` 调用），但没有 engine 就没有输入。
3. **engine 的 events proxy 鉴权**：`/admin/runs/{id}/events` 侧要看 G 票的 `operation`/`ceiling`/`resource`，
   本卡只保证票面可被 `verifiedTicket` 接受。

## 10. 回滚

无迁移、无配置、无外部状态；全部变更集中在内存态 `DecisionStore` 与新增分支：

```bash
git checkout -- authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java \
                authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java \
                authority-service/src/main/java/cn/agentplatform/authority/api/TicketService.java \
                authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java \
                authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java
rm authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1eSupport.java \
   authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1eContractTest.java
rm -rf docs/evidence/2026-11-02-EX45-child-exchange
```

回滚后 `portal_events_child_exchange` 重新在 `decide` 处 403，gateway 的 SSE 回到"开窗成功、child 换取失败"的状态。

## 11. 复现命令

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl admin-common install -DskipTests -q      # 客户端契约（只装，不改）
mvn -B -ntp -pl authority-service test                   # 79 个用例
mvn -B -ntp -pl authority-service test -Dtest=GatewayEventsSlice1eContractTest   # 只跑本卡
```

`red-1e.txt` / `green-1e.txt` / `green-full.txt` 是本目录下三次运行的原始输出。
