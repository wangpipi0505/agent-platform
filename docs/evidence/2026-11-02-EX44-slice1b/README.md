# EX-44 切片 1b 证据：`POST /oauth/token` 的 exchange 变体 A（`gateway_events_opening`）

- 仓库：`代码/agent-platform`
- 分支：`feat/v1.2-implementation`
- HEAD：`f5f4baa5 feat(authority,common): SSE 观测订阅的目录行、origin 与会话票 jti (EX-44 1a)`
- 范围：只做 1b 的 opener 变体 A（S0）。不做 1c/1d，不做变体 B（`query_ticket`），不新增端点。
- 本切片**未** `git add`、未 `git commit`：工作树只有下面 3 个生产文件被修改 + 1 个新测试文件（未跟踪）。

## 1. 命令与环境

```bash
cd 代码/agent-platform
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl admin-common install -DskipTests -q
mvn -B -ntp -pl authority-service test -Dtest='GatewayEventsSlice1bContractTest,ExchangeContractTest,SessionEndpointTest'
```

没有 `source .tools/env-load.sh`（其中 `AGENT_DEV_AUTH=true` 会让 iam 形态的用例先抛 `IAM_DEV_AUTH_FORBIDDEN`，与本次改动无关）。

## 2. 根因（红跑暴露的缺口）

`beginGatewayPortalEvents`（`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:574`）在 S0 发出的请求体是：

```text
operation=exchange, target=engine, action=run.observation.metadata.read,
resource={kind:run-observation-selector-v1, id:<selector canonical>, version_digest:<sha256>},
nonce, subject_token, gateway_events_opening:true
```

但 1a 的 `AuthorityRequest.parse` 只解析 8 个白名单字段，`gateway_events_opening` 落在 `raw` 里**被静默忽略**。于是服务端把一次 opening 请求当成普通 exchange：

1. 不做目的校验——`(gateway,engine,run.observation.metadata.read,delegated)` 或任意其它已登记 delegated 行都能换到票，客户端随后必然以 `requirePurpose` 不符拒绝；
2. 不要求 `origin_authentication_id`——会话端没写 origin 时服务端仍 200 签票，客户端 `HttpCurrentAuthorityClient:594` 的 `origin(claims)` 必失败，等于发一张废票；
3. 判别位取非法形状（如字符串 `"true"`）或出现在非 `exchange` 的 operation 上时也被忽略，拼错的请求会被降级处理。

红跑（测试先行、生产代码未改）证明了这三点：5 条负例全部拿到 200。

## 3. 改动清单

| 文件 | 行数 | 改动 |
| --- | --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityRequest.java` | +24/−1（144 行） | record 新增 `boolean gatewayEventsOpening`（:36）；`parse` 用 `optionalBoolean(node,"gateway_events_opening")` 解析并传入构造（:103、:105）；新增 `optionalBoolean`（:127-143）：缺省/null→false，非布尔→400 `INVALID_REQUEST`；类 javadoc 记录"只在 exchange 合法、只认布尔 true"（:23-25） |
| `authority-service/src/main/java/cn/agentplatform/authority/api/AuthorityController.java` | +6（183 行） | `handle()` 在 parse 之后加守卫（:156-161）：判别位为 true 且 `operation != exchange` → 400 `INVALID_REQUEST`，审计 reason `gateway_events_opening on operation=<op>` |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | +34（645 行） | import `RunObservationSelectors`（:16）；`exchange` javadoc 增补变体 A 契约（:181-183）；caller 校验后加目的校验（:191-193）；`fromSubject` 之前要求主体凭据带 origin（:212-217，缺失→403 `EVENTS_ORIGIN`）；新增 `requireOpeningPurpose(ServiceCall)`（:432-449）：`target=="engine" && action==RunObservationSelectors.ACTION && resource.kind()==RunObservationSelectors.KIND`，否则 403 `EVENTS_OPENING_PURPOSE` |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsSlice1bContractTest.java` | 新增 338 行 | 8 个契约测试（红→绿），含一条用**真实客户端**跑完整 S0 的正例 |

未改：`admin-common` 生产代码（含 `HttpCurrentAuthorityClient`）、`agent-service-authority.json`、pom、依赖、迁移、yml、`.env`。

### 设计决策（为什么这样落）

- **目的校验放在目录 permits 之前**：这是不需要令牌的结构检查，设计 §4.1 的 S0 序列也是"目的校验 → 目录 permits"。目录行只约束 `caller/target/action/mode`，不约束 `resource.kind`，单靠目录行无法把 opening 钉在 metadata 选择器上。
- **不反解 run id**：`resource.id` 是客户端把 run id 编进去的选择器 canonical 文本。设计 §2.2 明言服务端"只需要 `AuthorityProtocol.operation(...)` 的 `same(resource, 请求 resource)` 结构校验"，所以这里只钉 target/action/kind，等值仍由既有的 operation 结构校验兜底。
- **origin 缺失在服务端拒绝（而不是透传 null）**：客户端对 opening 票强制读 origin，签一张不带 origin 的票只会把失败推迟到客户端且不可诊断。放在 `fromSubject` 之前拒绝，避免把 grant/decision 写进 store 后再失败。
- **判别位形状收紧**：非布尔 400、非 exchange 400。两种都是本切片新增的拒绝，理由同上：静默忽略会把拼错/误用的请求降级成普通 exchange，绕过上面两道检查。

## 4. 红跑原文（先写测试，生产代码未改）

命令：`mvn -B -ntp -pl authority-service test -Dtest='GatewayEventsSlice1bContractTest'`

```text
[ERROR] Tests run: 8, Failures: 5, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

5 条失败（实际状态均为 200，即"判别位被静默忽略、按普通 exchange 签票"）：

| 用例 | 期望 vs 实际 |
| --- | --- |
| `openingRejectsATicketThatIsNotTheMetadataPurpose`（`GatewayEventsSlice1bContractTest:167`） | 403 `SYS-AUTH-403` / `reason=EVENTS_OPENING_PURPOSE` vs 200 |
| `openingRejectsAResourceThatIsNotTheMetadataSelector`（:186） | 403 / `reason=EVENTS_OPENING_PURPOSE` vs 200 |
| `openingRejectsANonBooleanFlag`（:200） | 400 `INVALID_REQUEST` vs 200 |
| `openingRejectsAPortalTokenWithoutOrigin`（:215） | 403 / `reason=EVENTS_ORIGIN` vs 200 |
| `openingIsOnlyValidOnTheExchangeOperation`（:231） | 400 `INVALID_REQUEST` vs 200 |

红跑时另外 3 条已经通过：真实客户端正例 `beginGatewayPortalEventsCompletesTheOpeningExchange`、`openingExchangeAnswersTheExactClientCallShape`、`openingFlagFalseKeepsThePlainExchange`。这本身是有用信息：1a 的服务端在 happy path 上已经满足客户端 30+ 条逐字段校验（call 全等、`request_sha256`、operation 结构、ceiling、`exp` 夹取、5 秒窗），1b 只需补上"拒绝错误请求"的三道闸。

## 5. 绿跑原文

命令：`mvn -B -ntp -pl authority-service test -Dtest='GatewayEventsSlice1bContractTest,ExchangeContractTest,SessionEndpointTest'`

```text
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.518 s -- in cn.agentplatform.authority.SessionEndpointTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.615 s -- in cn.agentplatform.common.authority.ExchangeContractTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.103 s -- in cn.agentplatform.common.authority.GatewayEventsSlice1bContractTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 6. 断言清单（`GatewayEventsSlice1bContractTest`）

1. `beginGatewayPortalEventsCompletesTheOpeningExchange`（正例，真实客户端）：`beginGatewayPortalEvents(portal, r_<32hex>, 7)` 在 5 秒窗内不抛；票 `typ=at+jwt` 且 `authority_mode=delegated`；`caller=client_id=gateway`、`target=engine`、`action=run.observation.metadata.read`、`aud=urn:agent-platform:service:engine`；`tenant_id/sub` 来自会话票；**`origin_authentication_id` 与 portal 票同值**；`grant_ref`/`authorization_version=delegated-v1` 齐全；`operation.source_kind=origin_kind=PORTAL`、`operation.resource.kind=run-observation-selector-v1` 且 `id` 与 `RunObservationSelectors.metadata(...)` 逐字段相等；`operation.expires_at <= portal exp`、`exp <= operation.expires_at`、`ServiceTicket.expiresAt()` 与 `operation.expires_at` 相等；`opening.publicRunId()/effectiveCursor()` 原样返回。
2. `openingExchangeAnswersTheExactClientCallShape`：响应恰好两个键 `assertion`/`ticket`；断言 claim 的 `ticket` 与响应 `ticket` **同一 JWS**；`ticket_jti` 等于票 `jti`；票与断言都带 `origin_authentication_id`；`decision_id` 两处相等；`operation` 整棵相等；`DecisionStore` 里能按 decision_id 查到同一 `originAuthenticationId`/`ticketJti`；审计有 `operation=exchange outcome=permit` 且不含 `eyJ`（不落令牌原文）。
3. `openingRejectsATicketThatIsNotTheMetadataPurpose`：目录里真实存在的 delegated 行 `proxy.engine.get.RunController.events` + `gateway_events_opening:true` → 403 `SYS-AUTH-403`，审计 `reason=EVENTS_OPENING_PURPOSE`。
4. `openingRejectsAResourceThatIsNotTheMetadataSelector`：action/target 对但 `resource.kind=run-snapshot` → 403 + `reason=EVENTS_OPENING_PURPOSE`。
5. `openingRejectsANonBooleanFlag`：`"gateway_events_opening":"true"`（字符串）→ 400 `INVALID_REQUEST`。
6. `openingRejectsAPortalTokenWithoutOrigin`：会话票无 `origin_authentication_id` → 403 `SYS-AUTH-403`，审计 `reason=EVENTS_ORIGIN`。
7. `openingIsOnlyValidOnTheExchangeOperation`：`operation=client` 带判别位 → 400 `INVALID_REQUEST`。
8. `openingFlagFalseKeepsThePlainExchange`：判别位 `false` 时退回普通 exchange 语义（同一 events 行 200 签票，形状不变）。

回归：`ExchangeContractTest` 4/4（真实客户端普通 exchange、非 gateway 403、无目录行 403、service 形主体 403）、`SessionEndpointTest` 7/7（会话票 origin 未变）。

## 7. 与设计不一致 / 自行补位（未放宽任何既有约束）

1. **`reason=EVENTS_ORIGIN` 用在 S0**：设计 §5 只把 `EVENTS_ORIGIN` 列在 S3（origin 不等/缺失）。S0 的"会话票缺 origin"这一行在设计里没有对应条目；本切片沿用同一 reason 以保持"码不变、reason 可区分"（§5.383）的口径，属于补位，请评审确认。
2. **`reason=EVENTS_OPENING_PURPOSE` 是新增 reason**：设计 §5 没有"opening 目的不符"行，也没有指定错误码。这里用 403 `SYS-AUTH-403` + 新 reason，不新增对外错误码。
3. **非布尔判别位 400、判别位出现在非 exchange 的 operation 上 400**：设计只规定"`gateway_events_opening` 存在且为布尔 true 才是变体 A"，没有规定非法形状的处置。这里选择显式拒绝而不是按 false 处理（见 §3 设计决策）。
4. **未加 §4.2 的 `Decision` 字段**（`purpose/sourceTokenSha256/admissionNonce/admissionRequestSha256/consumedAt/bindingResponseId/continuationJti/hardDeadline/parentAdmissionId`）：这些字段的语义由 S1/S3 的消费点定义（§4.3），本切片不实现 1c/1d，提前加字段只会留下无人读写的半个不变量。当前 S0 的决策与普通 exchange 决策同形，仅多出 origin/ticket_jti（1a 已回写）。
5. **`exp`/`grant_expires_at` 夹取没有改代码**：既有 `fromSubject` 已经实现 `grant_expires_at=min(now+grantSeconds, portal exp)`、`exp=min(now+ticketSeconds, grant_expires_at)`（§4.5），正例断言逐条验证通过，不需要重写。

## 8. 未覆盖（明确留给 1c/1d 或后续切片）

- **变体 B（`query_ticket`）与一次性消费**（§2.3、§4.3）：`openGatewayPortalEvents:622` 发 `exchange` + `query_ticket`，服务端今天仍忽略该字段。
- **`portal_gateway_events_open`**（1c）：`eventsQuery` 的决策 operation，服务端尚无。
- **§4.2 决策字段与 `purpose=OPENING/RETAINED/EVENTS`**：需要与消费点一起落。
- **真实 SSE 双跳**：`platform-gateway` 的 `PortalRunEventsSse`/`PortalRunEventsHttp` 端到端未在本切片的测试范围内（它们走自己的 fake authority）。
- **决策存储仍是内存**（§6.1 已知债务）：重启即丢，跨实例不可共享。
- **服务端不感知客户端 5 秒硬窗**：服务端只做 `exp` 夹取，deadline 由客户端 `remaining()` 强制。

## 9. 让 `beginGatewayPortalEvents` 这一跳真正通过，还缺什么

S0 本身已经通过（第 6 节第 1 条用真实客户端跑完，含客户端全部 30+ 条校验）。但"打开 SSE 订阅"完整走通还差两段：

1. **S1 = 变体 B（`query_ticket`）**：`openGatewayPortalEvents:622` 用 `exchange` + `Map.of("subject_token",source,"query_ticket",<S0 票>)` 换 `(gateway,gateway.run.events.observe,run-snapshot)` 票。今天服务端忽略 `query_ticket`，会**当普通 exchange 照签**——因为 portal 令牌仍在有效期内、目录行也存在。客户端随后 :623-639 会一路验过，看起来"能用"，实际是**安全洞**：opening 决策没有被查找、没有被消费、也没有 origin/nonce/request_sha256/source_token_sha256 绑定，任何持有 portal 令牌的调用者都能自己发一次 exchange 拿到 gateway 票，绕过 opening。变体 B 必须补：解析 `query_ticket` → 按 `ticket_jti` 找 S0 决策 → 比对 origin/nonce/request_sha256/source_token_sha256 → 一次性消费 → 签发 `purpose=RETAINED` 决策（§2.5）。
2. **S2 = 1c（`portal_gateway_events_open`）**：`openGatewayPortalEvents:640` 的 `eventsQuery("portal_gateway_events_open", ...)` 打到决策端点；`AuthorityController.decide` 目前只接受 `AUTHORIZE` 与 `authorize_workload`，该 operation 会被拒，`openGatewayPortalEvents` 在 S2 失败。这就是 1c 的缺口。

## 10. 回滚

- **运维开关**：`agent.authority.delegation.enabled=false`（默认 false）会让 `exchange`（含变体 A）整条 403，1b 的判别位随之失效。
- **代码回滚**：单切片回滚 = revert 第 3 节表里的 3 个生产文件并删除新测试；删掉 record 组件后 `gateway_events_opening` 重新变成"未知字段被忽略"，即回到 1a 行为（服务端 200、客户端在 purpose/origin 处失败），不会留下半开状态。
- 本切片无迁移、无配置变更、无新增依赖，不需要数据回滚。
