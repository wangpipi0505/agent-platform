# EX-46 SSE 续期与 engine `child_receipt` 回传

- 仓库：`代码/agent-platform`，分支 `feat/v1.2-implementation`，HEAD `b7bc9adc`（EX-45 child 交换与 child_open 票据）。
- 范围：只做 ①gateway 私有相位续期、②engine `child_receipt` 回传；不做 PG 化，不改 `admin-common`、pom、迁移、`.env`，不 `git add`/`git commit`。

## 1. 根因（先证后改）

1. authority 的 events 判决把 live 窗口设成 `checked_until = min(iat + assertionSeconds, hard_deadline)`，`assertionSeconds` 默认 **5s**（`AuthorityServiceProperties.java:29`，上限 5s）。硬窗 `hard_deadline` 才是 55s。
2. 客户端的 checked 窗口只能靠续期刷新：`HttpCurrentAuthorityClient` 的后台 deadline 线程每秒 `tick()`，`PortalEventsResponse.tick()` 在剩余 ≤3s 时调 `renew(owner)` → `renewedEventsEvidence(previous)`，按 profile 选 operation：G 窗 → `portal_gateway_events_check`，child 窗 → `portal_events_child_check`（`HttpCurrentAuthorityClient.java:1117-1131`）。续期**保留** `hard_deadline`/`continuation`/`immutable`，只换 `current`（`PortalEventsEvidence` 拷贝构造 + `install` 代次自增）。
3. 但 `AuthorityController.decide()` 只认 `authorize`/`portal_gateway_events_open`/`portal_events_child_exchange`（`DECISION_OPERATIONS`），**没有任何 check 分支**，续期请求一律 403 `decisionUri 不接受 operation=…`；`renew()` 失败走 `stop(failure)`，随后 `requireCurrent()` 重抛 → gateway 的 `current()` 抛错 → SSE 在 ~5s 结束。
4. 同理 engine 侧：`PortalRunEventsService.open` 走 `authority.openPortalEventsChild(...)`，请求 `portal_events_child_open`，authority 也没有该分支；客户端还要求响应里带 `child_receipt` 并当场验签（`HttpCurrentAuthorityClient.java:731-769`）。因此 ② 的缺口同样只在 authority-service（engine 侧的 `portalEventsChildReceipt` → HELLO 帧 relay 在 `b7bc9adc` 已存在，本卡未改 engine 主代码）。

结论：本卡在 authority-service 补 S5（child 开窗 + receipt + child continuation）与 S6/S6'（gateway/child 续期），在 gateway 补“私有相位主动驱动续期”，不改客户端契约。

## 2. 改动（+452/−11，未含新增测试文件）

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `authority-service/.../api/TicketService.java` | +60 | `CHILD_CONTINUATION_TYPE/USE`、`CHILD_RECEIPT_TYPE/USE`；`childContinuation(clientId,jti,immutableSha256,hardDeadline,iat)`（child continuation 带 `immutable_sha256` 而非 immutable 节点，符合客户端 CHILD 分支比较摘要）；`childReceipt(target,associationSha256,parentResponseId,childResponseId,openingDecisionId,admissionNonce,admissionRequestSha256,childImmutableSha256,hardDeadline,iat)`（aud 指向 **gateway**，`exp=hard_deadline`）。 |
| `authority-service/.../api/AuthorityRequest.java` | +22 | `childOpen()`（`child_open`）、`continuationRef()`（`continuation_ref`）两个形状/长度校验访问器；语义判定留在 decision 层。 |
| `authority-service/.../decision/DecisionStore.java` | +19/−4 | `Purpose` 增加 `CHILD_OPEN`；`Decision` 增加第 26 个字段 `JsonNode immutable`（续期必须原样回显开启时的 immutable；child 窗的 `opening_decision_id`/`admission_nonce` 来自 engine 请求，服务端无法重算）；`withTicketJti`/`consumed` 透传。 |
| `authority-service/.../decision/DelegatedAuthority.java` | +306/−6 | 新增 `eventsChildOpen`（S5）、`eventsWindowCheck`（S6/S6'）、`eventsWindowClaims`、`verifyWindowContinuation`；S4 现在把 `{association_sha256, child_open_sha256}` 摘要写进 D5 的 `immutable`，供 S5 校验“同一 child_open、同一 association”。 |
| `authority-service/.../api/AuthorityController.java` | +9 | `decide()` 增加 `portal_events_child_open` 与 `portal_gateway_events_check`/`portal_events_child_check` 三个分支。 |
| `platform-gateway/.../infra/PortalRunEventsHttp.java` | +34 | 私有相位续期：`RENEW_AHEAD_NANOS=10s`、`RENEW_INTERVAL_NANOS=2s`、`renewedAtNanos`；`livePrivate()` 先 `renewPrivateWindow()`；该方法在剩余 ≤10s 时调 `ResponseAuthorityClient.checkGatewayPortalEvents(gatewayResponse)`，并重新 `current()`。 |
| `authority-service/src/test/.../DecisionStoreTest.java` | +2/−1 | 26 参构造补 `null`（`immutable` 快照）。 |

新增测试（未 tracked）：

| 文件 | 行数 |
| --- | --- |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1fSupport.java` | 190 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1fContractTest.java` | 291 |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsPrivateRenewalTest.java` | 131 |

## 3. 红/绿原文

环境：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`；`mvn -B -ntp -pl admin-common install -DskipTests -q` 已先执行；未 `source .tools/env-load.sh`。

### ①-a 续期/开窗缺少 authority 分支（红 → 绿）

红（临时摘掉 `decide()` 的两段 EX-46 分支，只保留 HEAD 行为）：

```
[ERROR] Tests run: 10, Failures: 10, Errors: 0, Skipped: 0, Time elapsed: 3.056 s <<< FAILURE! -- in cn.agentplatform.common.authority.GatewayEventsSlice1fContractTest
[ERROR] Tests run: 10, Failures: 10, Errors: 0, Skipped: 0
```

红时审计原文（每条都是同一根因）：

```
authority.audit endpoint=decision outcome=deny client_id=engine operation=portal_events_child_open target=engine action=proxy.engine.get.RunController.events status=403 code=SYS-AUTH-403 reason=decisionUri 不接受 operation=portal_events_child_open
authority.audit endpoint=decision outcome=deny client_id=gateway operation=portal_gateway_events_check target=gateway action=gateway.run.events.observe status=403 code=SYS-AUTH-403 reason=decisionUri 不接受 operation=portal_gateway_events_check
```

绿（恢复分支）：

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.984 s -- in cn.agentplatform.common.authority.GatewayEventsSlice1fContractTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

### ①-b gateway 是否真的驱动续期（红 → 绿）

红（`livePrivate()` 里把 `renewPrivateWindow();` 注掉，其余不变）：

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 6.346 s <<< FAILURE! -- in cn.agentplatform.gateway.infra.PortalRunEventsPrivateRenewalTest
Exception in thread "main" java.lang.AssertionError: bounded condition deadline
[ERROR]   PortalRunEventsPrivateRenewalTest.thePrivateStreamDrivesItsOwnGatewayWindowRenewal:29->spawn:101 Exception in thread "main" java.lang.AssertionError: bounded condition deadline
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

绿（恢复调用；同跑邻近私有链路用例确认无回归）：

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.70 s -- in cn.agentplatform.gateway.infra.PortalRunEventsPrivateAuthorityTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 101.1 s -- in cn.agentplatform.gateway.infra.PortalRunEventsPrivateHttpTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.383 s -- in cn.agentplatform.gateway.infra.PortalRunEventsPrivateRenewalTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

### ② 全模块回归（authority-service）

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.279 s -- in cn.agentplatform.common.authority.GatewayEventsSlice1eContractTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.417 s -- in cn.agentplatform.common.authority.GatewayEventsSlice1fContractTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.053 s -- in cn.agentplatform.common.authority.RealClientContractTest
[INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（EX-45 收尾时是 79，本卡 +10，共 89。）engine-runtime / platform-gateway 模块全量未跑（按要求）。

## 4. 断言点

`GatewayEventsSlice1fContractTest`（10 个，全部走真实 `HttpCurrentAuthorityClient` 或按客户端字段顺序重算）：

1. `theEngineOpensTheChildWindowAndGetsAChildReceipt`：S5 200；响应 `profile=portal-engine-events-child-response.v1`、`immutable` 原样回显、`continuation_ref` 的 typ/use/aud(`urn:agent-platform:service:engine`)/`exp=hard_deadline`/`immutable_sha256` 都对，且**不带** `parent_response_id`/`completion_permit`/`service_ticket`；`child_receipt` 的 typ/use/aud(`urn:agent-platform:service:gateway`)/`exp`/`association_sha256`/`parent_response_id`/`child_response_id` 全对；D6 `Purpose.CHILD_OPEN`、`parentAdmissionId=D5`、未消费；D5 已被 `engine` 消费；`exp==checked_until<=hard_deadline` 且未过期。
2. `theGatewayRebuildsTheEngineImmutableFromTheAssociationAndTheReceipt`：三个 `engine_*` 声明与 immutable 一致；association 里确实没有 `admission_nonce`/`opening_decision_id`；`digest(expectedChildImmutable(association, digest(child_open), receiptClaims)) == receipt.child_immutable_sha256 == digest(immutable)`——这正是 gateway `acceptChildReceipt` 在 `PortalEventsMaterialVerifier:124` 处的校验等式。
3. `theChildWindowRenewsWithoutConsumingTheOpenedDecision`：`portal_events_child_check` 连续两次 200，profile/immutable/continuation/decision_id 回显，`exp==checked_until<=hard_deadline && >=now`，D6 始终未消费、D5 仍是被 engine 消费那一次。
4. `aChildRenewalBeyondTheOpenedWindowIsRejected`：伪造 `exp=hard_deadline+60` 的 child continuation → 403 + 审计 `EVENTS_CHECK_CONTINUATION`（续期窗口严格不超父窗）。
5. `theGatewayWindowRenewsWithoutConsumingD4Again`：`portal_gateway_events_check` 两次 200 且 `decision_id` 始终是 D4；D4 `consumedAt` 前后都是 null（S3 消费的是 retained D3），S2 的消费时间戳不变——同一 parent 的连续续期不重复消费。
6. `replayingTheSameChildOpenIsForbiddenNotConflict`：重复 `portal_events_child_open` → 403 `SYS-AUTH-403` + 审计 `EVENTS_CHILD_OPEN_REPLAY`（不是 409）。
7. `aTamperedChildAssociationIsRejected` → `EVENTS_CHILD_OPEN_ASSOCIATION`；8. `aChildImmutableWithADifferentDeadlineIsRejected` → `EVENTS_CHILD_OPEN_DEADLINE`；9. `aNonEngineChildOpenIsRejected` → `EVENTS_CHILD_OPEN_CALLER`；10. `aRenewalWithAnUnknownContinuationIsRejected` → `EVENTS_CHECK_CONTINUATION`。

`PortalRunEventsPrivateRenewalTest`（真实 TLS 子 JVM，本模块既有做法）：私有相位一进入 body，剩余窗口就是 ~5s ≤ 10s，必须在 10s 内驱动至少一次 `checkGatewayPortalEvents`，且该调用真的落到权威的 `portal_gateway_events_check` 分支（`f.iam.observedOperations()` 频次=1）。

## 5. 续期窗口与消费语义（本卡结论）

- **窗口**：续期只刷新 `checked_until = min(iat + assertionSeconds(5s), hard_deadline)`；`hard_deadline`、`continuation`、`immutable` 不变。因此“续期窗口严格不超过父窗口”由三处共同保证：continuation 的 `exp` 必须等于 immutable 的 `hard_deadline`；S6 再校验 `window.hardDeadline` 与 immutable 一致；`checkedUntil` 永远取 `min(..., hardDeadline)` 且 `<=iat` 直接 409。
- **消费**：续期 **不新建决策、不消费任何决策、不签发票**（`eventsWindowCheck` 只有 find + 校验 + 重签断言），所以同一 parent 连续续期不会重复消费 D4；D4/D5 的消费点在开窗时（S3 消费 D3、S5 消费 D5，均为单键 CAS）。
- **child 票**：客户端 `exchangePortalEventsChild` 是一次性的（`childReserved`），`PortalEventsChildAuthorization.expires` 构造后冻结，**没有换票续期面**。因此 gateway 只续 G 窗；child 授权只在 body 入口 `livePrivate(true)` 检查一次。这是与“续期时换一张新 child 票”最直接的设计差异（见 §6-1）。
- **失败语义**：续期失败即抛出，异常结束本次 SSE（`abort()` 只是 `stopped + signal + stopTransport`，选择不用它来“续命”），**不会**降级到公共流。

## 6. 与设计的差异（有意）

1. **不是“换 child 票”，而是续 G 窗**：客户端契约里 child 授权不可续（`childReserved` + 冻结 `expires`），可续面只有 `portal_gateway_events_check`/`portal_events_child_check`。所以 ① 落在“gateway 主动驱动 G 窗续期”，而不是替换 `child` 引用；`livePrivate()` 的 `child` 仍是 body 入口那一次交换的结果。
2. **D5 的 `expiresAt` 用 `childExpiry`（=hard_deadline），S5 再消费它**：S5 需要按 id 反查 D5 并确认只开一次窗，因此 D5 的生命周期必须覆盖到 child 窗，不能是 5s 断言窗。
3. **association/`child_open` 只在 G 票上**：EX-45 已如此，S5 因此从“请求里的 immutable（它本身带 `child_association`）+ D5 的摘要承诺”两侧交叉验证，而不是让 engine 再回述一份 association。
4. **receipt 里带三个 `engine_*`**：客户端 `expectedChildImmutable` 用 receipt 的 `engine_opening_decision_id`/`engine_admission_nonce`/`engine_admission_request_sha256` 补齐它无法从 association 推出的字段；服务端无法重算这些值（它们来自 engine 自己的 `authorize` 请求），只能回显。
5. **`DecisionStore` 仍是进程内账本**：按“不做 PG 化”的要求保留；本卡只在记录里加 `immutable` 快照。真正上 PG 时，`(id)` 唯一约束接管“同一 child_open 只开一次窗”。

## 7. 未覆盖 / 必须留到端到端验收

1. **真起六服务**：`PortalRunEventsJoinedChainTest`（gateway，断言 `portal_gateway_events_check` + `portal_events_child_check` 真实发生）与 `PortalEventsMaterialAuthorityTest`（gateway，含 `receipt-red` 篡改 receipt 必须被拒）都依赖真实 engine/gateway/IAM 子 JVM 与 `.local/iam-o2b-f2c2b2-joined-chain/engine-classpath.txt`，本会话无 IAM 服务，未跑。
2. **>55s 的连续性**：本卡保证的是“5s checked 窗不再掐断流”，硬窗 `hard_deadline` 仍是 `min(open+55s, …)`；要观察 60s+ 连续出流，需要真实链路上跑满 55s 并在到期前完成 `hard55` 场景。
3. **engine 侧未改主代码**：`portalEventsChildReceipt` → `PacketMaterial.privateReceipt` → HELLO `{"receipt": …}` 的 relay 在 `b7bc9adc` 已存在（`RunEventReader.java:75,239-240`、`PortalEventsPacketWriter.java:34,62`），真实链路上的 HELLO 校验由 `PortalEventsMaterialVerifier.hello()` 完成；本卡只补了 authority 的产出端与契约测试。
4. **gateway 客户端的 3s watchdog** 与 gateway 的 10s 主动续期会并存：前者是兜底、后者保证 5s 窗口一进来就续一次。两者同时触发时依赖客户端 `renew()` 的 single-flight（`pending`）；未做多线程压力用例。

## 8. 回滚

```bash
cd 代码/agent-platform
git checkout -- authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java \
  authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java \
  authority-service/src/main/java/cn/agentplatform/authority/api/TicketService.java \
  authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java \
  authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java \
  authority-service/src/test/java/cn/agentplatform/authority/DecisionStoreTest.java \
  platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java
rm authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1fSupport.java \
   authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1fContractTest.java \
   platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsPrivateRenewalTest.java
rm -rf docs/evidence/2026-11-02-EX46-sse-renewal
```

回滚后行为回到 HEAD：`portal_events_child_open`/`portal_gateway_events_check`/`portal_events_child_check` 仍 403，私有 SSE 仍在 ~5s 结束（EX-45 交接单里描述的状态）。

## 9. 复现命令

```bash
cd 代码/agent-platform
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl admin-common install -DskipTests -q
mvn -B -ntp -pl authority-service test -Dtest=GatewayEventsSlice1fContractTest
mvn -B -ntp -pl authority-service test
mvn -B -ntp -pl platform-gateway test -Dtest=PortalRunEventsPrivateRenewalTest
```
