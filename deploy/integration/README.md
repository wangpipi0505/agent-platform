# 业务系统接入部署说明（INT-04）

本目录给出 L1/L2 嵌入的最小部署形态：**宿主可信后端换票，浏览器只拿 300 秒短票**。
协议与错误码以 `contracts/assistant-host-ticket.md` 为准；本文只讲怎么摆、怎么配、怎么验。

## 1. 拓扑

```
浏览器 ──同源──> 宿主站点 /assistant/*        （静态 SDK + 页面）
   │                 │
   │                 └─ /assistant/api/ticket ──> 宿主 BFF ──> 网关 /embed/ticket
   │                                                   （注入 ApiKey + Origin）
   └── iframe/SDK ──> 助手页面（携带短票，内存持有）
```

两种等价形态：

1. **同源反向代理**：`/assistant/api/ticket` 由 nginx/网关代理到平台 `/embed/ticket`，ApiKey 由代理注入；
2. **宿主 BFF**：宿主服务端按登录会话调用 `/embed/ticket`，再把票据返回给前端。

无论哪种，**ApiKey 不得出现在浏览器、移动端、前端构建产物、URL 或 localStorage**。

## 2. 前置条件（缺一不可）

| 项 | 位置 | 说明 |
| --- | --- | --- |
| 应用已发布 | app-management | 未发布应用出票返回 409 `APP_UNPUBLISHED` |
| 集成凭据 ACTIVE | `PUT /admin/apps/{code}/integration/credentials/{biz}` | 返回 `secretOnce`，只显示一次；服务端保存到 secret manager |
| allowedOrigins 精确登记 | 同上 | 协议+主机+端口逐字相等；不支持通配/后缀 |
| 环境上限 | `claim_limits.attributes.environment` | 如 `["prod"]`；新接入必须声明 |
| 主体上限 | `claim_limits.principalIds` | 允许的终端用户；出票时校验 |
| 身份源档案（仅门户模式） | authority `agent.authority.identity.*` | 见 §4；不配置则 `/auth/session` 返回可解释阻塞 |

同一业务系统允许一个租户下一份凭据；不同业务系统（`bizSystemCode`）各自独立，撤销互不影响。

## 3. 同源代理示例（nginx）

```nginx
# ApiKey 从 secret manager 注入到变量，禁止写入仓库或镜像层。
map $host $assistant_upstream { default "http://platform-gateway:18080"; }

location = /assistant/api/ticket {
    limit_except POST { deny all; }
    proxy_pass $assistant_upstream/embed/ticket;
    proxy_set_header Authorization "ApiKey $assistant_credential";  # 由入口脚本注入
    proxy_set_header Origin $http_origin;                           # 必须透传，不能改写
    proxy_set_header Content-Type application/json;
    proxy_hide_header Set-Cookie;
    add_header Cache-Control "no-store" always;
}

location /assistant/ {
    proxy_pass http://assistant-static/;
    add_header Cache-Control "no-store" always;
    add_header Content-Security-Policy "frame-ancestors https://partner.example" always;
}
```

要点：
- `Origin` 原样透传；改写或缺失会导致 403 `INTEGRATION_SOURCE_NOT_ALLOWED`。
- 不要在代理层缓存 `/assistant/api/ticket`；票据响应必须 `no-store`。
- 代理不要附加 `Cookie`、`X-User-*`、`X-Tenant`、`X-Principal`——网关对身份类头默认拒绝。
- 若平台与宿主不同站，必须显式配置 CORS，且只登记精确来源。

## 4. Authority 身份源档案（门户模式）

```yaml
agent:
  authority:
    identity:
      enabled: true
      protocol: USER_MGMT          # 仅此协议已实现；OIDC_USERINFO/SIGNED_JWT 会被拒绝启动
      url: https://iam.partner.example
      identity-path: /system/apikeys/identity/self
      api-key-ref: env:PARTNER_IAM_KEY   # 只接受 env: 引用，生产禁止明文
      tenant: tenant-a
      issuer: https://authority.example
      audience: agent-platform-portal
      client-id: gateway
      environment: prod
      active-status: "0"
      session-seconds: 3600
      mapping:
        user-name: profile.login
        status: profile.state
        roles: grants
        role-key: code
        role-data-scope: scope
        dept-id: org.id
        dept-name: org.name
```

- 映射是**有限字段路径**，不支持脚本、表达式或回调；未知目标/非法路径/缺少 `user-name` 会拒绝启动。
- 身份源返回的租户（若映射了 `tenant`）必须等于档案 `tenant`，否则 403 且不签发。
- 身份源不可达 → `/auth/session` 返回 503 `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`；
  **不要**在宿主侧重试到降级 dev/共享账号。

## 5. 宿主 BFF 换票（伪代码）

```java
// 登录态来自宿主自己的会话；userId 由服务端解析，绝不信任前端传参。
String userId = session.userId();
Map<String, Object> body = Map.of(
    "userId", userId,
    "roles", session.roles(),                 // 只会与档案上限求交
    "attributes", Map.of("region", session.region()),
    "environment", "prod");                   // 必须命中档案上限

HttpResponse r = http.post(gateway + "/embed/ticket")
    .header("Authorization", "ApiKey " + credentialId + ":" + secret)
    .header("Origin", "https://partner.example")
    .json(body).send();

// 透传状态码与 §6 错误码；401/403 不重试，503 可提示稍后再试（零业务执行）。
return r.status(), r.json().path("data").path("ticket");
```

前端只在内存保存 ticket，调用助手 SDK 时放入 `Authorization: Bearer <ticket>`；
刷新页面即重新换票，不做持久化。

## 6. 安全核对清单

- [ ] ApiKey/secret 只在服务端 secret manager，构建产物与镜像层无明文。
- [ ] `Origin` 透传且与 allowedOrigins 逐字相等；CORS 只放行登记来源。
- [ ] 响应与页面 `Cache-Control: no-store`；CSP `frame-ancestors` 限定宿主域。
- [ ] 前端无 `localStorage`/`sessionStorage`/cookie 存票；无 `?access_token=`。
- [ ] 日志不记录 secret、ApiKey、完整 JWT；只记 credential id、userId、jti 摘要、状态码。
- [ ] 宿主不接受前端传入的 `tenant`/`principal`/`roles` 作为身份；一律由服务端会话派生。
- [ ] 助手失败时显示可恢复错误，不用假数据或共享账号兜底。

## 7. 验证步骤（与验收用例对应）

```bash
# 1) 正常出票：应 200，ticket 可解出 sub/tenant_id/app_code/attributes.environment
curl -sS -X POST https://partner.example/assistant/api/ticket \
  -H 'Content-Type: application/json' -H 'Origin: https://partner.example' \
  -d '{"userId":"alice","environment":"prod"}'

# 2) 来源不符：403 INTEGRATION_SOURCE_NOT_ALLOWED
curl -sS -o /dev/null -w '%{http_code}\n' -X POST ... -H 'Origin: https://evil.example'

# 3) 自报 tenant：403 INTEGRATION_CLIENT_IDENTITY_REJECTED
curl -sS -X POST ... -d '{"userId":"alice","tenant":"tenant-b"}'

# 4) 两用户分离：alice 与 bob 各取票，sub 分别 partner:alice / partner:bob
# 5) 撤权后旧票：管理员撤销凭据，旧票立即 401 INTEGRATION_TICKET_EXPIRED
# 6) 身份源不可达：停掉 Authority，/auth/session 应 503 INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE 且无业务执行
```

真实 PG 定向证据（`agent_platform_test`）：两份凭据并存、environment 上限往返、撤销隔离、
事务回滚零残留，见 `docs/evidence/int-04-20261104/INT-04-A.md`。

## 8. 回退

1. 撤销接入凭据（`POST /admin/apps/{code}/integration/credentials/{biz}/revoke`）或停用档案；
2. 移除代理注入配置与 BFF 换票路由，恢复独立入口；
3. 不清审计与会话记录；已撤销的权限不因回退恢复；
4. 回退后复核：旧票全部 401、页面回落独立入口、无共享账号登录。

## 9. 已知限制

- IAM 网关当前不放行 `/embed/ticket` 与 `/v1/**`（`GatewayIngressBoundary` 只允许四种固定形状），
  短票 provider 只服务非 IAM 的可信 BFF 部署；IAM 门户模式以 portal JWT 为短凭据。
- 网关无法读取 `IntegrationProfile`，环境上限与 `IntegrationProfile.environment` 的一致性由管理员核对。
- JWT 过期由解码器拒绝，返回 `SYS-AUTH-401` 而非 `INTEGRATION_TICKET_EXPIRED`。
- `OIDC_USERINFO`、`SIGNED_JWT` 协议未实现；无 refresh token。

详见 `contracts/assistant-host-ticket.md` §10。
