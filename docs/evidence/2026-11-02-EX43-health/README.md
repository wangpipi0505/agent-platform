# EX-43 /actuator/health 403 — 根因与修复（2026-11-02）

## 现象

gateway 以 `mode=iam` 启动后，三个探针中 `POST /auth/dev-token` 返回 404（正确）、
`POST /internal/x` 返回 401（正确）、`GET /actuator/health` 返回 403 且响应体是
`{"code":"SYS-AUTH-403","message":"入口未获授权"}`。

## 根因（三层，逐层用排除法定位）

1. **入站边界过滤器**：`admin-common/.../authority/GatewayIngressBoundary.IngressFilter.doFilter`
   第一句调用 `route(r)`，而 `route()` 只认五种受管形态（`/admin/apps/{code}/runs`、
   `/admin/runs/r_<32hex>`、`/admin/runs/r_<32hex>/events`、`/auth/session`、`POST` 形态），
   其余一律 `denied()` → 空体 403（由 `reject()` 写出 `SYS-AUTH-403`）。
   健康探针在安全链的 `permitAll` 之前就被拒。
2. **MVC 拦截器**：同一个边界还以 `MappedInterceptor(null, null, this)` 映射到所有路径，
   `preHandle` 在没有 `Proof` 属性时同样 `denied()`。修完过滤器后 403 变成 401，
   401 来自共享安全链的 `anyRequest().access(...)` —— 说明拦截器仍在拦。
3. **管理端口未分离**：`/actuator/**` 与业务同端口时没有任何 handler 归属，
   即使安全链放行也只会 404；探针必须由独立管理端口提供。

## 修复

- `GatewayIngressBoundary.IngressFilter.doFilter`：`/actuator/health` 前缀直接
  `chain.doFilter` 放行（`9847bb5c`）。
- `GatewayIngressBoundary.preHandle`：`KEY` 属性缺失（未经过滤器准入）时直接 `return true`，
  拦截器只对“过滤器已准入”的请求行使否决权。
- `GatewayIamAssembly.gatewayIamChain`：加 `securityMatcher("/admin/**", "/auth/session")`，
  只治理五种入站形态，不再吞掉其它路径。
- `SecurityConfiguration`：健康/免鉴权白名单从路径 lambda 改为具名
  `AntPathRequestMatcher`（MVC 解析器解析不到 actuator 端点；lambda 不会被登记成授权规则）。
- `platform-gateway/src/main/resources/application.yml`：`management.server.port`
  透传 `MANAGEMENT_SERVER_PORT`，并注释说明 iam 模式下探针必须走管理端口。

## 复验（gateway，iam，2026-11-02 09:3x）

| 探针 | 结果 |
| --- | --- |
| `GET 127.0.0.1:18090/actuator/health`（管理端口） | **200** `{"status":"UP"}` |
| `GET 127.0.0.1:18080/actuator/health`（业务端口） | 401（端点不在业务端口，安全链拒绝匿名） |
| `POST 127.0.0.1:18080/auth/dev-token` | **404** |
| `POST 127.0.0.1:18080/internal/x` | **401** |

## 测试

`mvn -pl admin-common test -Dtest='GatewayIngressBoundaryTest,GatewayEventsIngressBoundaryTest,ServiceAuthorizationTest'`
→ 20 例全绿（14 + 3 + 2 + 1）；`mvn -pl platform-gateway test -Dtest='GatewayIamPortalHttpTest,GatewayEventsIngressBoundaryTest'`
→ 通过（12 例）。

## 未覆盖

- 没有新增“探针 200”的端到端自动化用例（当前只做了手工 HTTP 复验）。
- 业务端口 `/actuator/**` 仍是“无 handler + 安全拒绝”，未显式返回 404 语义。
