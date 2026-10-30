# EX-40 人身份会话签发（user-mgmt API-Key → 平台 portal 令牌）

2026-10-30 · 分支 `feat/v1.2-implementation` · 模块 `authority-service`

## 1. 问题

按口径 B，`user-mgmt-system` 是"人的权威源"，但它只有 `X-API-Key → 身份` 的查询面（`/system/apikeys/identity/self`），
**没有 JWKS、没有 OIDC、没有票据签发**（`grep jwks|oidc|.well-known|PublicKey` 零命中）。

平台侧的 `TrustedJwtDecoder`（`admin-common/src/main/java/cn/agentplatform/common/authority/TrustedJwtDecoder.java:78-100`）
对 portal 令牌有硬要求：`typ=at+jwt`、`token_use=user`、`iss/aud/client_id` 三处与配置全等，且
`tenant_id/sub/roles/attributes/scope` 齐备。因此"把 API-Key 换成平台认得的会话令牌"这一段，
只能由持有签发私钥的 authority 服务完成——既不能在 user-mgmt 里补一套（它不该持有平台私钥），
也不能让 gateway 自己签（那等于把签发权下放到资源侧）。

## 2. 改动

| 文件 | 变更 |
| --- | --- |
| `authority-service/.../identity/IdentityProperties.java` | 新增。会话签发配置（enabled/url/api-key-ref/identity-path/tenant/issuer/audience/client-id/scope/biz-system-code/三个超时与时限/role-denylist）。启用时强校验：url 必须是 http(s) 且无 userinfo/片段/尾斜杠、identity-path 必须以 `/` 开头、超时 100..10000ms、session 60..3600s。 |
| `authority-service/.../identity/SessionService.java` | 新增 297 行。`operation=session` 的完整处理：只有 `gateway` 可换（其它 client 403 且**不打身份服务**）；`api_key_ref` 只认 `env:NAME`，`api_key` 直传仅 test profile；身份查询 `GET {url}{identity-path}` 带 `X-API-Key`；`HTTP != 200` / 空体 / 超 64KiB / 不可解析 / 非 200 业务码 → `503 IAM_IDENTITY_UNAVAILABLE`；`status != "0"` 或无可用角色 → `403` 且**响应体不含用户名**（防账号枚举）；角色按 denylist 剔除 `SERVICE`/`GATEWAY_AUTH`；`attributes` 写入 `deptId/deptName/dataScope/userId/bizSystemCode`（平台 portal 上限要求 `bizSystemCode`、`userId` 各恰一值）；签出 `at+jwt`，`exp-iat=session-seconds`、`nbf=iat`、`jti=ses_…`。 |
| `authority-service/.../api/AuthorityController.java` | `/oauth/token` 在解析目录请求**之前**分流 `operation=session`，走独立的 `handleSession` 审计（`operation=session`、`outcome=permit|deny`）；其余请求体仍走既有 `issueTicket`。 |
| `authority-service/.../AuthorityApplication.java` | `@EnableConfigurationProperties` 加入 `IdentityProperties`。 |
| `authority-service/src/main/resources/application.yml` | 新增 `agent.authority.identity.*` 11 个键，全部有环境变量覆盖，`enabled` 默认 `false`（不配置即 403，既有部署行为不变）。 |
| `authority-service/src/test/java/cn/agentplatform/authority/SessionEndpointTest.java` | 新增 6 例（见 §3）。用真实 JDK `HttpServer` 桩 user-mgmt，而不是 mock `HttpClient`——要证明的是"请求头里是 `X-API-Key`""HTTP 200 + body code""停用账号不泄露身份"这些线级契约。 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/RealClientContractTest.java` | 一处期望值更新（见 §4）。 |

## 3. 测试

命令：`mvn -B -ntp -o -Dmaven.repo.local=$PWD/.tools/m2 -pl authority-service -Dtest=SessionEndpointTest test`

红（把 `AuthorityController` 的 session 分流临时改成 `if (false && …)` 后）：
```
Tests run: 6, Failures: 5, Errors: 0, Skipped: 0
[ERROR] BUILD FAILURE
```
绿：
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.605 s -- in cn.agentplatform.authority.SessionEndpointTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
模块全量（`-pl authority-service test`，含既有 18 例 + 契约测试）：
```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

六个用例各钉一个不变量：

1. `issuesPortalTokenBoundToUserIdentityAndAttributes` — 200；`X-API-Key` 头里是传入的 key；令牌可验签（`typ=at+jwt`）、`iss/aud/client_id/token_use/tenant_id/sub/scope` 全等；`exp-iat=600`、`nbf=iat`、`jti` 前缀；**`SERVICE` 角色被剔除**；`attributes.deptId/userId/bizSystemCode/dataScope` 齐备。
2. `disabledAccountIsRejectedWithoutRevealingIdentity` — `status="1"` → **403** 且响应体不含 `wangwu`/`userName`。
3. `identityWithoutUsableRolesIsRejected` — 无角色 → 403 且不含用户名。
4. `identityServiceFailureIsUnavailableNotDenied` — 桩返回 500 → **503 `IAM_IDENTITY_UNAVAILABLE`**（可重试，不能伪装成无权限）。
5. `onlyGatewayMayExchangeIdentityForToken` — app 的 Basic → 403，且 `LAST_API_KEY` 为 null（**被拒时根本没打身份服务**）。
6. `nonSessionBodiesStillGoThroughTheTicketPath` — 既有 `operation=client` 仍签发 ticket（新增分支不影响原路径）。

## 4. 顺带修掉的遗漏（EX-41 的相邻契约）

`RealClientContractTest.clientSurfacesDirectoryDenialAsPlatformException` 期望"目录无行 → 503 `IAM_AUTHORITY_UNAVAILABLE`"，
这是 M2 **之前**的行为。EX-41（`07480d95`）按 M2 改动后，authority 的 403 被映射为 `403 IAM_AUTHORITY_FORBIDDEN`，
该用例随之失败（`expected: 503 / but was: 403`）。这不是新缺陷，而是 EX-41 漏跑的相邻契约：

- 语义上：**"目录里没有这条行"是可区分的拒绝，不是服务不可用**；503 只留给传输/协议故障，否则运维无法从状态码区分"配置缺失"和"服务挂了"。
- 已把断言改为 403 + `IAM_AUTHORITY_FORBIDDEN`，并在测试内写明理由。

## 5. 未覆盖 / 已知限制

1. **未与真实 user-mgmt 联调**：本卡只到"桩住身份服务"这一层；真实链（18070 + MySQL `user_mgmt` + 10 把 API-Key）属 D1 的下一步。
2. `api_key` 直传只允许 test profile；生产必须 `api_key_ref=env:NAME`（这条路径在单测里由桩覆盖，未做真实环境变量注入验证）。
3. 未做会话**刷新/吊销**：令牌 1 小时内有效，`revoke` 未实现（口径 B 文档 M4 的剩余项）。
4. 未校验 user-mgmt 的 `deptId` 与平台 `dept_scope` 的业务映射规则（当前原样放入 `attributes.deptId`）。
5. 平台侧 `AuthorityProperties.validate()` 对 portal 属性上限还有额外要求（`attributeAllowlist` 必须含 `bizSystemCode`/`userId` 且各恰一值）；签发端已按此产出，但最终对账要等 D1 真实链跑通。
