# 宿主接入短票协议（assistant-host-ticket）

状态：v0.1 草案，未激活。激活须在 `contracts/freeze.json` 的 `engineeringAddenda` 登记本文件 sha256，
并按 `contracts/assistant-integration.md` §8 完成路由/权限/OpenAPI 生成链登记。本文只描述
**已实现且可核验**的行为；未实现项集中在 §10，不得据本文假定其可用。

适用范围：业务系统以 L1/L2 方式嵌入助手时，由**可信后端**（BFF 或同源反向代理）换取短期运行票据；
浏览器只持有该短票，永不接触集成 ApiKey、模型密钥、vault 引用或 Authority 凭据。

## 1. 两条受控路径

| 路径 | 调用者 | 凭据 | 产出 | 模式 |
| --- | --- | --- | --- | --- |
| 业务出票 | 宿主 BFF / 同源代理 | 集成 ApiKey（`id:secret`），仅置于后端 | 300 秒运行票据（JWT，`token_use=integration`） | 既有 `POST /embed/ticket`，未改动 |
| 门户会话 | 平台门户 | 已验证的 portal JWT（`token_use=user`，含 `platform.access`） | `SessionView`（tenant/principal/roles/expiresAt） | 既有 `GET /auth/session`，未改动 |

两条路径的主体恒等均来自**已验 JWT 或已验凭据派生**，不接受请求参数、iframe 来源、postMessage、
`pageContext` 或任何客户端自报的 tenant/principal/role。`origin` 字符串只用于来源校验，本身不是调用者身份。

## 2. 业务出票

### 2.1 请求

```http
POST /embed/ticket
Authorization: ApiKey <credentialId>:<secret>
Origin: https://partner.example
Content-Type: application/json

{
  "userId": "alice",
  "roles": ["operator"],
  "attributes": { "region": ["A"] },
  "environment": "prod"
}
```

- `Authorization` 必须是 `ApiKey <id>:<secret>`；缺失 401 `API_KEY_REQUIRED`，格式或密钥不符 401
  `INVALID_CREDENTIAL`。secret 只比较 SHA-256 摘要（常量时间），不落日志。
- `Origin` 必须与接入档案 `allowedOrigins` **逐字相等**（scheme/host/port 全等，不解析通配、不比较后缀）；
  不符 403 `INTEGRATION_SOURCE_NOT_ALLOWED`。部署级 CORS 可能在更早的过滤器拒绝未登记来源，
  此时响应不含本协议错误码——宿主应把两者都视为来源不符。
- `userId` 必填，且必须落在 `claim_limits.principalIds` 内，否则 403 `CLAIM_LIMIT_EXCEEDED`。
- `roles` 与档案 `claim_limits.roles` 求交；平台角色（`ADMIN`/`EDITOR`/`SERVICE`/`GATEWAY_AUTH`）一律剔除，
  集成票永不带平台权限。
- `attributes` 只保留档案允许的键值；`bizSystemCode`、`userId` 由服务端注入，调用方提供即被覆盖。
- `environment` 为环境选择器（不是身份）；档案声明上限时必填且须逐字命中，见 §3。
- 携带 `tenant`/`tenantId`/`tenant_id`/`principal`/`principalId`/`principal_id`（顶层或 `attributes` 内）
  一律 403 `INTEGRATION_CLIENT_IDENTITY_REJECTED`，不做静默覆盖。
- `pageContext` 只承载页面上下文（最多 16 个字段，值 ≤256 字符），出现 tenant/principal/userId/roles/
  permissions/token/authorization/cookie/secret/secretRef 即 403 `INTEGRATION_CLIENT_IDENTITY_REJECTED`；
  它永不改变 Actor。

### 2.2 响应

```json
{
  "code": "OK",
  "data": {
    "ticket": "<jwt>",
    "accessToken": "<jwt>",
    "expiresIn": 300,
    "tokenType": "Bearer"
  },
  "traceId": "..."
}
```

`ticket` 与 `accessToken` 当前为同一值，宿主只应使用 `ticket`（`accessToken` 为兼容字段保留）。
响应必须 `Cache-Control: no-store`，宿主不得写入 URL、localStorage、日志、可导出配置或前端埋点。

### 2.3 票据 claims

| claim | 含义 | 示例 |
| --- | --- | --- |
| `iss` | 平台签发者 | `agent-platform-v12` |
| `sub` | 主体标识 `bizSystemCode:userId`（超长时为 `ext_`+sha256 前 60） | `partner:alice` |
| `aud` | 固定受众 | `agent-platform-embed` |
| `tenant_id` | 租户 | `tenant-a` |
| `token_use` | 固定 | `integration` |
| `app_code` / `biz_system_code` | 应用与业务系统绑定 | `ops` / `partner` |
| `user_id` | 终端用户 | `alice` |
| `credential_id` | 接入档案 id | `1` |
| `policy_digest` | 档案策略摘要（撤销/收权后必变） | `sha256:...` |
| `roles` / `attributes` | 裁剪后的角色与属性（含 `environment`） | — |
| `exp` / `iat` / `jti` | 300 秒有效期 | — |

票据**不包含** secret、vault 引用、模型密钥、Authority 凭据或平台角色。

## 3. 环境绑定

- 上限来源：接入档案 `claim_limits.attributes.environment`（字符串数组，元素须匹配
  `^[a-z][a-z0-9-]{0,31}$`），由平台管理员经既有 `PUT /admin/apps/{code}/integration/credentials/{biz}` 填写。
- 档案声明上限：请求必须给出 `environment` 且逐字命中，否则 403 `CLAIM_LIMIT_EXCEEDED`；
  命中后写入票据 `attributes.environment`。
- 档案未声明上限：保持旧行为（票据不绑定环境）。**新接入必须声明**，否则视为未完成环境上限配置。
- 每次请求复核：`IntegrationPolicy.bound(credential, ticketEnvironment)` 重新读取档案；撤权、停用或
  上限收紧后，已签发但未过期的票立即 401 `INTEGRATION_TICKET_EXPIRED`，不等待 300 秒过期。
- 契约缺口：网关当前无法读取 `app.integration_profile`，因此**无法自动断言**票据环境与
  `IntegrationProfile.environment` 相等；上线检查须由平台管理员核对（见 §10）。

## 4. 身份源档案（Authority）

平台管理员可在 Authority 既有 `agent.authority.identity.*` 配置中填写身份源档案；
只支持**明确有限字段映射**，不支持任意脚本、表达式语言或回调 URL。

| 配置 | 说明 |
| --- | --- |
| `enabled` | 关闭时 `/auth/session` 返回可解释阻塞，不降级 dev/共享账号 |
| `protocol` | 仅 `USER_MGMT`（可信身份查询）。`OIDC_USERINFO`、`SIGNED_JWT` 为保留名，配置即拒绝启动 |
| `url` / `identityPath` | `http(s)`、无 userinfo/fragment/尾斜杠；GET，`X-API-Key` |
| `apiKeyRef` | 只接受 `env:NAME` 引用；生产 profile 不接受明文 |
| `tenant` / `issuer` / `audience` / `clientId` / `scope` | 会话声明映射；`clientId` 必须为 `gateway` |
| `environment` | 该档案服务的环境（`^[a-z][a-z0-9-]{0,31}$`） |
| `activeStatus` | 视为可用的状态值，默认 `"0"` |
| `sessionSeconds` | 60～3600，默认 3600 |
| `roleDenylist` | 默认 `SERVICE,GATEWAY_AUTH`，命中即剔除 |
| `mapping` | 目标 → 源字段路径，见下表 |

映射目标固定八个：`userName`（必填）、`status`、`roles`、`roleKey`、`roleDataScope`、`deptId`、`deptName`、`tenant`。
路径语法为点分标识符，最多三段，可带一层 `[]`（列表展开），例如 `profile.login`、`grants[].code`；
未知目标、非法路径、启用却未映射 `userName` 均拒绝启动（`AUTHORITY_IDENTITY_INVALID`）。

验证顺序：身份源可达性（不可达 503 `IAM_IDENTITY_UNAVAILABLE`）→ 业务 `code`（非 200 拒绝）→
`activeStatus` 与 `userName` 非空（否则统一 403，不泄露账号是否存在）→ `roles`（去 denylist，≤64）→
属性（`deptId`/`deptName`/`dataScope` 仅展示，不进签名面）→ 租户（若映射了 `tenant`，须等于档案 `tenant`，
否则 403）→ 签发 `at+jwt`，`token_use=user`，`origin_authentication_id=oa1:<sha256("portal-session:"+jti)[0:32]>`。

`profileSummary()` 可回显协议/issuer/audience/tenant/environment/sessionSeconds 与映射键，
**绝不回显** `apiKeyRef` 的值。

## 5. 门户会话

`GET /auth/session` 只接受 `Authorization: Bearer <portal jwt>`；成功后返回
`{tenantId,principalId,roles,expiresAt}`，`Cache-Control: no-store`，并在响应写出前后各校验一次
来源票（`proof.requireVerified()`），防止中途刷新导致的主体漂移。

当 Authority 不可达（503 `IAM_AUTHORITY_UNAVAILABLE`）或身份源不可达（503 `IAM_IDENTITY_UNAVAILABLE`）时，
网关对外统一改写为 `503 INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`「身份源暂不可用，未执行业务操作」，
`category=TRANSPORT`、`retryable=false`。**不得**退化为 403 或 dev 身份：否则宿主会把基础设施故障
误判为凭据错误并降级到共享账号，这正是 §3.4 禁止的路径。

## 6. 错误码（沿用 `assistant-integration.md` §6 登记集）

| HTTP | code | 触发 |
| --- | --- | --- |
| 403 | `INTEGRATION_SOURCE_NOT_ALLOWED` | Origin 不在档案 `allowedOrigins` |
| 403 | `INTEGRATION_CLIENT_IDENTITY_REJECTED` | 自报 tenant/principal、`pageContext` 携带身份、身份头 |
| 401 | `INTEGRATION_TICKET_EXPIRED` | 票过期、撤权、停用、上限收紧、策略摘要变化 |
| 503 | `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE` | 接入档案/身份源不可达；零业务执行 |
| 401 | `INVALID_CREDENTIAL` / `API_KEY_REQUIRED` | 既有凭据错误（未改名） |
| 403 | `CREDENTIAL_REVOKED` / `CLAIM_LIMIT_EXCEEDED` / `APP_BINDING_DENIED` | 既有出票期拒绝（未改名） |

401/403 的 `retryable=false`；仅 429 `retryable=true`。宿主只能依据 `code` 分类处理，
不得解析 `message` 文本。

## 7. 刷新、退出与撤权

- 有效期 300 秒；到期前宿主 BFF 重新调用 `/embed/ticket`（同一 ApiKey、同一 `userId`），
  不做静默续期，不把旧票当 cookie 复用。
- 每次业务请求都重新读取接入档案：撤销、停用、改上限、改来源后**立即生效**，已发票据随之失效。
- 用户退出时宿主丢弃内存中的票据即可；平台侧不提供 refresh token，也不需要服务端会话。
- 审计只记录：凭据 id、`userId`、`app_code`、`biz_system_code`、`environment`、票据 `jti` 与摘要、
  HTTP 状态、拒绝原因码。绝不记录 secret、ApiKey 明文、完整票据。

## 8. 部署形态

- **同源反向代理**：`/assistant/*` 与 `/embed/ticket` 同域，代理注入 ApiKey 并透传 `Origin`；
  浏览器只看到同源路径。示例见 `deploy/integration/README.md`。
- **独立 BFF**：宿主服务持有 ApiKey，按登录会话映射 `userId`，服务端调用 `/embed/ticket`，
  把票据注入 iframe/SDK。ApiKey 不得下发到浏览器或移动端。
- 两种形态都必须：TLS、`Cache-Control: no-store`、CSP 限制 `frame-ancestors` 为登记来源、
  日志脱敏、错误码透传。

## 9. 验收与证据

- 网关 14 例：`IntegrationHostTicketTest`（两用户归属分离、伪造 tenant/principal、`pageContext`、
  跨 app、过期、撤权、上限收紧、来源不符、档案不可达 503、运行路径白名单）。
- 门户 4 例：`PortalSessionDegradeTest`（两种 503 改写、`PORTAL_SESSION_UNAVAILABLE` 原样、403 原样）。
- Authority 11 例：`IdentityProfileTest`（协议/映射/环境校验）、`IdentityProfileSessionTest`
  （映射驱动声明、租户不符 403、空用户名 403、源 500 → 503）。
- 真实 PG（`agent_platform_test`）：两份凭据并存、`environment` 上限往返、撤销一份不影响另一份、
  事务回滚零残留。

## 10. 未实现与已知缺口

1. **IAM 模式入口**：`GatewayIngressBoundary` 只放行 `/admin/apps/{code}/runs`、`/auth/session`、
   `/admin/runs/{id}`、`/admin/runs/{id}/events` 四种形状；`/embed/ticket` 与 `/v1/**` 在 iam 网关
   一律 403 `SYS-AUTH-403`。当前短票 provider 实际只服务非 IAM 的可信 BFF 部署；IAM 门户模式以
   portal JWT 作为短凭据。放开入口属平台边界变更（admin-common），不在 INT-04 白名单。
2. **环境与接入档案的一致性**：网关无 `app.integration_profile` 内部读取端点，无法自动校验
   `environment` 与 `IntegrationProfile.environment` 相等；当前以 `claim_limits.attributes.environment`
   为唯一上限，需管理员核对。
3. **过期票错误码**：JWT 过期在解码器处被拒，返回 `SYS-AUTH-401` 而非 `INTEGRATION_TICKET_EXPIRED`；
   本卡只保证撤权/收权/策略变化返回登记码。
4. **保留协议**：`OIDC_USERINFO`、`SIGNED_JWT` 未实现；authority 不引入资源服务器验签依赖，
   因此不能对接任意签名令牌。
5. **无刷新令牌**、无服务端会话存储；宿主每次重取。

## 11. 激活与回退

激活：确认 §2～§8 行为与部署一致 → 在 `freeze.json` 登记 sha256 → 通知宿主按 §9 用例验收。

回退：停用接入档案（`status=REVOKED` 或 `PUT policy` 收紧）、移除代理注入配置、回到独立入口；
不清审计与会话记录。回退不恢复已撤销的权限，也不复活已过期票据。
