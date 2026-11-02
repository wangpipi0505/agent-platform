# EX-44 切片 1c：exchange 变体 B（`query_ticket`）+ 一次性消费

- 仓库：`代码/agent-platform`；分支 `feat/v1.2-implementation`；基线 HEAD `e62e25de`（`feat(authority): SSE opening 换票的目的与 origin 校验 (EX-44 1b)`）
- 日期：2026-11-02
- 范围：`authority-service` 的 main/test；不改 `admin-common`、gateway、pom、依赖、迁移、yml、`.env`
- 结论：变体 B 与一次性消费已落盘，`authority-service` 全量 65 例全绿；未提交（按任务要求由委派方提交）

## 1. 命令与环境

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
cd 代码/agent-platform

# 红跑前（admin-common 未改动，仅保证本地仓库有最新 SNAPSHOT）
mvn -B -ntp -pl admin-common install -DskipTests -q

# 红跑
mvn -B -ntp -pl authority-service test -Dtest='GatewayEventsSlice1c*'

# 绿跑（1c 专项 → 全量）
mvn -B -ntp -pl authority-service test -Dtest='GatewayEventsSlice1c*'
mvn -B -ntp -pl authority-service test
```

未使用 `.tools/env-load.sh`；未执行 `git add` / `git commit`。

## 2. 根因（含"未修前如何绕过"）

服务端 `AuthorityRequest.parse` 是**白名单解析**：只读取它认识的字段，其余 JSON 字段被静默丢弃。1b 之前 `query_ticket` 不在名单里，于是：

1. `POST /oauth/token`（Basic `gateway`）的 body 里带 `query_ticket` 与 `gateway_events_opening` 都不会被解析；
2. `DelegatedAuthority.exchange` 只走变体 A：拿 `subject_token` 现签一张 delegated 票；
3. 目录里本来就有 `(caller=gateway, target=gateway, action=gateway.run.events.observe, delegated)` 这一行，因此**持任意 portal 用户票的人可以完全跳过 S0 opening、跳过目的校验、跳过 origin 派生，直接换出 gateway 观测票**。客户端 `HttpCurrentAuthorityClient:611-651` 假设必须先 opening 再换票，而服务端没有任何一条规则强制这件事。

### 未修前如何绕过（可复现）

```bash
# 1) 拿一张 portal 用户票（真实环境来自会话端点；测试里用同形自签票）
PORTAL='...'
# 2) 不做 opening，直接 exchange 到 gateway 观测 action
curl -sS -u gateway:test-gateway-secret -H 'Content-Type: application/json' \
  -d '{"operation":"exchange","target":"gateway","action":"gateway.run.events.observe",
       "resource":{"kind":"run-snapshot","id":"r_0123456789abcdef0123456789abcdef",
                   "version_digest":"<64hex>"},
       "nonce":"bypass-1","subject_token":"'"$PORTAL"'"}' \
  http://127.0.0.1:8081/oauth/token
# 1c 之前：200 + {"assertion":...,"ticket":...}（gateway 观测票，直接可用）
# 1c 之后：403 {"code":"SYS-AUTH-403"}，审计 reason=EVENTS_RETAINED_PURPOSE
```

红跑里 `plainExchangeToTheGatewayObserverCallIsRejected` 的首个失败就是这条旁路：`expected: 403 FORBIDDEN but was: 200 OK`。同一形态下把 `query_ticket` 塞进 body 也无济于事——旧代码把它当未知字段忽略，仍然 200，这就是"传了 query_ticket 但其实没人校验"的第二重伪装。

## 3. 改动清单

| 文件 | 行数（+/-） | 改动 |
| --- | --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java` | +10/-6（共 148） | record 增第 6 个组件 `queryTicket`；`parse` 用 `optionalText(node,"query_ticket",MAX_TICKET)` 读取（存在且非空才是变体 B）；javadoc 说明两变体互斥及"不得静默降级"的理由 |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java` | +13/-0（共 196） | `handle()` 增两条 400：`gateway_events_opening` 与 `query_ticket` 同现；`query_ticket` 出现在非 `exchange` 操作 |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java` | +125/-17（共 198） | `Purpose{OPENING,RETAINED,EVENTS}`；`Decision` 增 7 字段（purpose/sourceTokenSha256/admissionNonce/admissionRequestSha256/consumedAt/consumedBy/parentAdmissionId）；`ConcurrentHashMap` + 插入序队列 + `tickets`(jti→id) 索引；`consume(id,consumer)` 用 `compute` 单键 CAS；`findByTicketJti`；`put` 的清理/淘汰语义保持 |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | +242/-14（共 873） | `exchange` 按 `queryTicket` 分流；`exchangeQueryTicket`（验票→查 D1→逐项绑定→原子消费→签 retained 决策与票）；`verifyQueryTicket`/`verifyPortalUser`；`requireRetainedPurpose`/`requireNotRetainedPurpose`；D1/D2 记录 admission nonce/request_sha256/source_token_sha256/parentAdmissionId；票面透传 `parent_admission_id`，forward 跳透传 purpose/parentAdmissionId |
| `authority-service/src/test/java/cn/agentplatform/authority/DecisionStoreTest.java` | +3/-1（共 67） | 位置参数构造补 7 个 null（本测试只覆盖容量/过期） |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1aContractTest.java` | +32/-12（共 166） | **fixture 修正**：1a 原来的 `gateway→gateway` 普通 exchange 现在正是被关闭的旁路，改为 opening 目的（engine/metadata，带 `gateway_events_opening=true`）+ 变体 B 两步；jti 回写与 origin 透传断言不变，`authorize` 用例改走真实 S0→S1 后再 `fromTicket` |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1cSupport.java` | 新增 160 | 1c 测试基类：`open`/`exchangeQuery`/`retainedCall`/`command`/`portalToken`/`orphanOpeningTicket`/`hasReason` |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1cContractTest.java` | 新增 236 | 10 例：正例、重放、普通旁路、未知 jti、跨用户、换 source token、非 opening 票、互斥、非 exchange、并发 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1cExpiryTest.java` | 新增 49 | 1 例：`decision-seconds=1` 造出"票仍有效、D1 已过期"，断言 `EVENTS_TICKET_EXPIRED` |

生产代码只动了 `AuthorityRequest`、`AuthorityController`、`DecisionStore`、`DelegatedAuthority` 四个文件；`admin-common`、gateway、pom、迁移、配置零改动。

## 4. 设计决策

1. **两变体互斥在解析层就拒绝，不靠"分支里先判断"**：`gateway_events_opening && query_ticket != null` → 400。理由：一旦两者同时被接受，服务端就必须选择信任哪一个，而客户端 `openGatewayPortalEvents` 从不这么发；含糊的语义会在审计里留下无法解释的决策。
2. **变体 B 的"目的"再验一次**：`requireRetainedPurpose` 要求 target=`gateway`、action=`gateway.run.events.observe`（客户端常量是包私有的，只能对字面量）、resource 为 `run-snapshot` + `r_<32hex>` + 64hex 摘要。否则查询票就能被拿去换任意 delegated 票。
3. **普通 exchange 打 retained 目的一律拒绝**（`EVENTS_RETAINED_PURPOSE`）：这正是"绕过 opening"的入口；只靠"变体 B 校验 query_ticket"而不封死变体 A，漏洞仍然敞着。
4. **绑定不是"对一下 id"，而是把票钉死到一张 portal 令牌原文**：`sourceTokenSha256` 在 S0 时写入 D1，S1 用本次 `subject_token` 现算摘要比对，换一张同用户、同 origin 的票也复用不了。
5. **一次性消费用 `ConcurrentHashMap.compute` 单键 CAS**：`consume` 内不做"先读 consumedAt 再写"，并发两个 S1 只有一个能改映射，输家在 `put(retained)` 之前就 403，不会留下第二张票或第二条决策。
6. **`EVENTS_TICKET_EXPIRED` 判定的是 D1 决策的 `expiresAt`**：T1 自身过期在 `KeyRing.verify` 就被拒（统一文案），到不了这里；但 D1 的有效期是"opening 这一跳的回执"，S1 可能晚于它，所以这个分支仍有真实语义（测试用 `decision-seconds=1` 制造该窗口）。
7. **`request_sha256`/`nonce` 不做跨跳相等比较**：S0 与 S1 是两次不同的 HTTP 请求，nonce 本就不同，硬比会把正常链路拒掉。按设计 §4.1 把 S0 的 `nonce`/`request_sha256` 写进 D1、S1 的写进 D2，供 1d 的 `gateway_query` 使用。
8. **内存消费是显式债务**（设计 §6.1）：`DecisionStore` 类注释写明"单实例、重启即失效、EX-46 迁 PG 由唯一约束接管"。

## 5. 红跑原文

先写测试、后改 main；红跑时间 2026-11-02 12:32（改动 main 之前）：

```
[ERROR] Tests run: 11, Failures: 10, Errors: 0, Skipped: 0
```

- `GatewayEventsSlice1cExpiryTest.queryTicketWhoseOpeningDecisionExpiredIsRejected:45`（1 run / 1 fail）
- `GatewayEventsSlice1cContractTest`（10 run / 9 fail），失败断言只有两种形态：
  - 8 个"应当 403"的用例：`expected: 403 FORBIDDEN but was: 200 OK`（重放、普通旁路、未知 jti、跨用户、换 source token、portal 票当 query_ticket、并发）
  - 2 个"应当 400"的用例：`expected: 400 BAD_REQUEST but was: 200 OK`（opening+query_ticket 同现、非 exchange 带 query_ticket）
- 唯一通过的是正例 `queryTicketExchangeMintsTheRetainedDelegation`——因为旧代码的普通 exchange 本来就能签出同形状的 delegated 观测票，**正例与旁路在旧代码里不可区分**，这正是根因。

（红跑报告随后被绿跑覆盖；以上为当时 `mvn` 输出的原文摘录。）

## 6. 绿跑原文

1c 专项：

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.539 s -- in cn.agentplatform.common.authority.GatewayEventsSlice1cContractTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.948 s -- in cn.agentplatform.common.authority.GatewayEventsSlice1cExpiryTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`authority-service` 全量（含 1a/1b/Exchange/Forward/RealClient 回归）：

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ...GatewayEventsSlice1aContractTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in ...GatewayEventsSlice1bContractTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in ...GatewayEventsSlice1cContractTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ...GatewayEventsSlice1cExpiryTest
[INFO] Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 7. 断言清单

正例 `queryTicketExchangeMintsTheRetainedDelegation`（S0 签 T1 → S1 用 `query_ticket` 换 T2）：

- 响应仍是 `{"assertion","ticket"}`；断言 claim `ticket` 与响应 `ticket` **同值**；`assertion.ticket_jti == ticket.jti`；`assertion.origin_authentication_id == origin`。
- T2 票面：`token_use=service`、`authority_mode=delegated`、`caller=client_id=gateway`、`target=gateway`、`action=scope=gateway.run.events.observe`、`aud=urn:agent-platform:service:gateway`、`tenant_id=tenant-a`、`sub=alice`、带 origin。
- `operation.source_kind=origin_kind=PORTAL`、`operation.resource` 为 `run-snapshot` 且 id=请求 run id；`operation.expires_at <= portal exp`；`ticket.exp <= operation.expires_at`；`grant_expires_at == operation.expires_at`。
- **新 mint**：`T2.grant_ref != T1.operation.grant_ref` 且 `T2.operation_id != T1.operation.operation_id`（§4.4）。
- 存储侧：`D2.purpose=RETAINED`、`D2.parentAdmissionId=D1.id`、`D2.sourceTokenSha256=sha256(portal 原文)`、`D2.admissionNonce/RequestSha256` 非空、`D1.consumedAt != null`、`D1.consumedBy=gateway`。

负例：

| 用例 | 期望 | 断言 |
| --- | --- | --- |
| 同 T1 再来一次 | 403 | `SYS-AUTH-403` + 审计 `reason=EVENTS_TICKET_CONSUMED`，且 D1.consumedAt 不变 |
| 普通 exchange 打 retained 目的 | 403 | `reason=EVENTS_RETAINED_PURPOSE` |
| 票面 jti 无对应决策 | 403 | `reason=EVENTS_TICKET_UNKNOWN` |
| S1 换 portal 用户（同 origin） | 403 | `reason=EVENTS_TICKET_BINDING` |
| S1 换一张同用户 portal 票 | 403 | `reason=EVENTS_TICKET_BINDING`（`sourceTokenSha256` 不等） |
| `query_ticket` 是 portal 用户票 | 403 | `reason=EVENTS_QUERY_TICKET` |
| opening + query_ticket 同现 | 400 | `INVALID_REQUEST` |
| `query_ticket` 用于 `client` 操作 | 400 | `INVALID_REQUEST` |
| 两个并发 S1 抢同一 T1 | 1×200 + 1×403 | 恰好一张票；403 侧 code 仍 `SYS-AUTH-403` |
| D1 已过期（decision-seconds=1） | 403 | `reason=EVENTS_TICKET_EXPIRED` |

### 绑定校验逐项表

| 比对项 | 谁生成 | 服务端怎么比 |
| --- | --- | --- |
| `decision_id` | S0 `ticket()` 写入 `decision.id` | 票面 `decision_id` == 索引命中的 `D1.id` |
| `jti` | S0 `newId("tkt")`，S0 决策 `withTicketJti` 回写 | `jti` 查 `tickets` 索引命中，且 == `D1.ticketJti`；找不到 → UNKNOWN |
| `origin_authentication_id` | portal 会话票（S0 写入 D1） | 票面 origin == D1.origin == S1 `subject_token` 的 origin（`oa1:<32hex>` 形状先验） |
| `grant_ref` | S0 `newId("grt")` 写入票面与 D1 | 票面 == `D1.grantRef` |
| `operation` 全节点 | S0 `operation(...)`（含 operation_id/issued/expires/resource/ceiling_sha256/tenant/sub） | 两侧归一到 `Json.canonical` 文本全等（避免 Long/Integer 装箱差异误判） |
| `target`/`action`/`resource` | S0 请求的 call | 票面 call 与 D1 存的三项逐项相等 |
| `tenant_id`/`sub` | portal 票 claim | S1 重验的 portal 主体 == `D1.actor` |
| `source_token_sha256` | S0 时 `Digest.sha256(subject_token)` 写入 D1 | S1 现算 `sha256(S1 的 subject_token 原文)` == `D1.sourceTokenSha256`（换票即失败） |
| 未消费 | S1 | `D1.consumedAt == null`，真正的判定由 `consume()` 的 `compute` CAS 给出 |
| 过期 | — | `D1.expiresAt > now` |

`request_sha256`/`nonce` 不跨跳比较（见 §4.7），但 S0/S1 各自写入 D1/D2 的 `admissionNonce`/`admissionRequestSha256`。

## 8. 与设计/任务口径的差异、自行补位

1. **reason 命名以任务为准**：任务给的是 `EVENTS_TICKET_UNKNOWN`/`EVENTS_TICKET_BINDING`/`EVENTS_TICKET_CONSUMED`/`EVENTS_TICKET_EXPIRED`，设计 §5 表里写的是 `EVENTS_OPENING_REPLAY`/`EVENTS_QUERY_SUBJECT` 等。实现采用任务命名；形状/验签失败沿用设计 §5 的 `EVENTS_QUERY_TICKET`；对外 code 恒 `SYS-AUTH-403`，reason 只进审计。
2. **新增 `EVENTS_RETAINED_PURPOSE`**（任务未点名）：没有它，普通 exchange 仍能直接换 gateway 观测票，任务的安全目标不成立。这是本切片最关键的补位。
3. **1a 的 fixture 变更**：1a 的三个用例原本就用"普通 exchange 打 gateway 观测 action"来验证 jti/origin 透传，这条路径现在必须关闭。改为 opening（engine/metadata + `gateway_events_opening=true`）与变体 B 两步后，断言语义（jti 同源、origin 三处一致）不变；`authorize` 用例改为消费变体 B 换出的 target=gateway 票，仍覆盖 `fromTicket` 的 origin 透传。
4. **`toMap` 的装箱陷阱**：D1 的 `operation` 是内存 Map（epoch 秒为 `Long`），票面经 JSON 往返后是 `Integer`；直接 `Map.equals` 会把合法绑定判成 BINDING（绿跑前实测 4 例失败）。改为两侧规范 JSON 文本比较。
5. **D1/D2 额外落库字段**：`sourceTokenSha256`、`admissionNonce`、`admissionRequestSha256`、`parentAdmissionId` 都是设计 §4.2 的字段，本切片先落库/透传，1d 直接可用。

## 9. 未覆盖

- **多实例/重启**：消费标记只在单进程内存里原子；两个实例各自内存，重启后 D1 消失（重放会落到 UNKNOWN）。设计 §6.1 已接受为显式债务，EX-46 迁 PG。
- **T1 自身过期**：`KeyRing.verify` 先拒（通用"票据已过期"），因此对外表现为 `EVENTS_QUERY_TICKET` 而不是 `EVENTS_TICKET_EXPIRED`；后者只覆盖 D1 决策过期（测试用 `decision-seconds=1` 制造窗口）。
- **S2/S3**：`authorizeRetained`（S2）已由 `fromTicket` 支持（1a 用例覆盖 origin 透传），但客户端 `:640` 的 `eventsQuery("portal_gateway_events_open", ...)`（S3）在 `AuthorityController.decide` 里仍走 `DECISION_OPERATIONS=Set.of("authorize_workload")` → 403，属 1d。
- **真实客户端 end-to-end**：1c 的绿跑只到 S1；`HttpCurrentAuthorityClient.openGatewayPortalEvents` 在 S1 之后还会调 S2/S3，因此客户端全链路要等 1d。
- **gateway 侧**：未改 gateway 代码（本切片范围外）。

## 10. 1d 还缺什么

1. `decide` 新增 `portal_gateway_events_open` 决策操作（`DECISION_OPERATIONS` 白名单 + `DelegatedAuthority` 的 S3 分支）。
2. S3 的 `gateway_query` 比对：`ticket_jti`/`grant_ref`/`operation_id` 来自 S2 断言；`source_token_sha256`/`nonce`/`request_sha256` 来自 **D1**——需要从 S2 决策的 `parentAdmissionId` 反查 D1（本切片已保证 D2 带 parent、D1 存 S0 的 nonce/request_sha256/source 摘要）。
3. `binding.responseId` 与 D3 的消费（重复 403，不返回 409）；`continuationJti`、`hardDeadline` 字段。
4. S3 的票据/断言时间夹取（`min(observed+55s, portal exp, S2 operation.exp, S2 grant_exp)`）。
5. EX-46：把 `DecisionStore` 的一次性消费迁到 PG 唯一约束（消除 §6.1 债务）。

## 11. 回滚

无迁移、无配置、无依赖、无目录改动，回滚是纯代码回退：

```bash
cd 代码/agent-platform
git checkout -- \
  authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java \
  authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java \
  authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java \
  authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java \
  authority-service/src/test/java/cn/agentplatform/authority/DecisionStoreTest.java \
  authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1aContractTest.java
rm authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1c{Support,ContractTest,ExpiryTest}.java
```

回退后行为回到 1b：`query_ticket` 被当未知字段忽略，`gateway.run.events.observe` 可由普通 exchange 直接换出——**即漏洞重新出现**，所以回滚只应作为紧急处置，并需同时回退客户端侧对 S1 的启用。
