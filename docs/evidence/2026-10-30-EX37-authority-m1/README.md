# EX-37 authority-service M1（IAM 口径 B 最小可消费面）

- 分支：`feat/v1.2-implementation`；模块：`代码/agent-platform/authority-service`（新建，已注册进根 `pom.xml` `<modules>`）
- 规格基线：`文档/设计/IAM可信身份与票据机制-口径B-20261030.md` §4（三组端点）+ §4.4（claims）
- **关键修订**：M1 的线上形态以**平台真实消费方**为准，而非设计文档里的简化示意。消费方是
  `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java`，
  其协议由 `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java`（761 行保真桩）定义。
  两者不一致处以消费方为准，差异见 §6。
- 范围：只做 M1（三组端点 + JWKS + 静态配置 + 测试）。不做数据库、不做 token-exchange、不做 user-mgmt（M3）、不做轮换（M4）。
- 无新增第三方依赖：RS256 / JWKS 全部用 JDK `java.security` + `Base64` 手写，未引入任何 JWT 库。

## 1. 验证（原文）

```
$ export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
$ mvn -B -ntp -pl authority-service test
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.869 s -- in cn.agentplatform.authority.DecisionEndpointTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.060 s -- in cn.agentplatform.authority.JwksEndpointTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.116 s -- in cn.agentplatform.authority.TokenEndpointTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.224 s -- in cn.agentplatform.common.authority.RealClientContractTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`-am` 变体（`mvn -B -ntp -o -Dmaven.repo.local=$PWD/.tools/m2 -pl authority-service -am test`）会在
**admin-common 自身的测试**上红灯，与本卡无关、且未修改该模块：单独跑 `-pl admin-common test` 得到
`Tests run: 363, Failures: 1, Errors: 6`（`AuthorityPolicyTest` 控制器模板目录漂移、
`LocalRunAdvanceAuthorityTest` 桩内断言、`ServiceTokensDeploymentModeTest` 读文件 `MalformedInputException`）。
因此本卡的绿灯判据用上面那条 `-pl authority-service`（模块已通过 `admin-common` 已安装的 `0.1.0-SNAPSHOT` 解析依赖）。

## 2. 文件清单

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `pom.xml` | 51 | 模块声明；`admin-common` 排除 `spring-boot-starter-oauth2-resource-server`（本服务是签发方，不是资源服务器） |
| `src/main/resources/application.yml` | 39 | 全部配置键，均可环境变量覆盖 |
| `AuthorityApplication.java` | 26 | 启动类；排除 Security 自动配置 |
| `AuthorityException.java` | 49 | 带 HTTP 状态 + 错误码 + 审计原因的受控异常 |
| `StrictJson.java` | 30 | 唯一 JSON 解析入口：拒绝重复键与尾随字节（否则 `request_sha256` 语义依赖解析器） |
| `config/AuthorityServiceProperties.java` | 126 | `agent.authority` 配置校验（kid/TTL/客户端名/密钥引用） |
| `config/IssuerResolver.java` | 82 | 绑定端口后解析 issuer；非回环且未显式配置 → 启动失败 |
| `config/AuthorityConfiguration.java` | 25 | `Clock` / `KeyRing` Bean |
| `sign/SigningKey.java` | 34 | RSA 密钥对 + JWK 输出（n/e 去掉符号位） |
| `sign/KeyRing.java` | 265 | PEM 加载/临时密钥、JWKS、RS256 签名、票据校验 |
| `route/RouteDirectory.java` | 55 | 复用 `ServiceRoutePolicy` 读同一份 `agent-service-authority.json` |
| `client/ClientRegistry.java` | 75 | Basic 凭据校验（常量时间比较），空密钥=客户端禁用 |
| `api/AuthorityRequest.java` | 110 | 请求形状校验（operation/target/action/resource{kinds}/nonce） |
| `api/TicketService.java` | 230 | 票据 + 决策断言签发、票据校验 |
| `api/AuthorityController.java` | 139 | 三个端点 + 审计日志 |
| `api/AuthorityErrorHandler.java` | 44 | 统一 `{code,message}` 错误体 |
| `src/test/java/cn/agentplatform/authority/TestJwt.java` | 82 | 测试侧 JWT 解析/验签 |
| `.../AuthorityTestSupport.java` | 109 | 共享测试常量与 HTTP 助手（4 个测试类复用同一 Spring 上下文） |
| `.../JwksEndpointTest.java` | 65 | JWKS 形状 + KeyFactory 还原 + 真实票据验签 |
| `.../TokenEndpointTest.java` | 156 | 签发/凭据失败/不支持 operation/资源形状 |
| `.../DecisionEndpointTest.java` | 120 | authorize_workload 许可与全部拒绝路径 |
| `.../cn/agentplatform/common/authority/RealClientContractTest.java` | 110 | **真客户端** `HttpCurrentAuthorityClient` 对真实端口的契约测试 |
| `src/test/resources/authority-test-key.pem` | 28 | 测试固定 PKCS#8 私钥（2048 位，仅测试用） |

## 3. 端点

| 方法 | 路径 | 认证 | 请求 | 成功响应 |
| --- | --- | --- | --- | --- |
| GET | `/.well-known/jwks.json` | 无 | — | `{"keys":[{kty,use,alg,kid,n,e}]}`，`Cache-Control: max-age=300, public` |
| POST | `/oauth/token` | Basic（`client_id:client_secret`） | JSON：`operation=client` + `target/action/resource/nonce` | `{"assertion":"<JWT>","ticket":"<JWT>"}` |
| POST | `/internal/authorize` | Basic | JSON：`operation=authorize_workload` + `service_ticket` + 同上四项 | `{"assertion":"<JWT>"}` |

- 认证失败 → `401 {"code":"SYS-AUTH-401","message":"调用方凭据无效"}`；形状非法 → `400 {"code":"INVALID_REQUEST",...}`。
- 其余 operation（`forward/authorize/exchange/resume/response_*` 等）在本轮 **一律 403** 并写审计（M1 只开最小面）。
- 目录未命中 / `mode=disabled` / 客户端与 target 不匹配 / 票据与请求四项不等 → `403 {"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}`；
  具体原因只进审计日志 `authority.audit … reason=…`，不回给调用方。

## 4. 票面 claims

`operation=client` 签发票据（`typ=at+jwt`，平台 `TrustedJwtDecoder` 要求的头）：

| claim | 值 |
| --- | --- |
| `iss` | `agent.authority.issuer` |
| `aud` | `urn:agent-platform:service:<target>` |
| `token_use` | `service` |
| `iat` / `nbf` / `exp` | 秒级；`exp-iat = agent.authority.ticket-seconds`（默认 60，上限 60） |
| `jti` | `tkt-<uuid>` |
| `target` / `action` | 与请求、与目录行完全相等（不重写、不放大） |
| `resource` | `{kind,id,version_digest}`（消费方 `ServiceCall.resource()` 形状） |
| `scope` | 恰好 `action`（平台 `requireServiceScope` 要求） |
| `caller` / `client_id` / `sub` | 均为已认证的 `client_id` |
| `authority_mode` | `workload` |

原始响应**不含** `tenant_id / grant_ref / roles / attributes / operation`（workload 票面禁止携带，测试断言其缺失）。

断言（`typ=agent-authority-decision+jwt`，`token_use=current-decision`）额外携带：
`nonce`、`request_sha256`（= `Digest.sha256(Json.canonical(请求体))`）、`client_id`、`active=true`、`current=true`、
`exp-iat = assertion-seconds`（默认 5，上限 5），`operation=client` 时还有
**`ticket`（票据本体）**、`ticket_jti`、`subject_caller`、`authority_mode`。

> `ticket` 必须放在断言 claim 内，而不是只放响应体：`HttpCurrentAuthorityClient.verifiedTicket`
> 读的是 `envelope["ticket"]`，而 `query()` 返回的是**已验签的断言 claims**（`HttpCurrentAuthorityClient.java:420` 附近）。
> 响应体顶层的同名字段仅为人工排查方便。

## 5. 配置键

| 键 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `server.address` | `AGENT_BIND_ADDRESS` | `127.0.0.1` | 默认只回环 |
| `server.port` | `SERVER_PORT` | `18086` | |
| `agent.authority.issuer` | `AGENT_AUTHORITY_ISSUER` | 空 | 空值时仅**回环绑定**按实际端口推导；非回环留空 → 启动失败 `AUTHORITY_ISSUER_REQUIRED` |
| `agent.authority.key-path` | `AGENT_AUTHORITY_KEY_PATH` | 空 | PKCS#8 PEM，支持 `classpath:` 前缀；空 → 启动生成一次性密钥并 WARN（生产必须挂载） |
| `agent.authority.kid` | `AGENT_AUTHORITY_KID` | `2026-10` | `[A-Za-z0-9._-]{1,64}` |
| `agent.authority.previous-key-path` | `AGENT_AUTHORITY_PREVIOUS_KEY_PATH` | 空 | 轮换预留：JWKS 同时暴露旧公钥，签发只用 active |
| `agent.authority.previous-kid` | `AGENT_AUTHORITY_PREVIOUS_KID` | 空 | 需与 previous-key-path 同时出现且不同于 kid |
| `agent.authority.ticket-seconds` | `AGENT_AUTHORITY_TICKET_SECONDS` | `60` | 1..60 |
| `agent.authority.assertion-seconds` | `AGENT_AUTHORITY_ASSERTION_SECONDS` | `5` | 1..5 |
| `agent.authority.clients.<service>` | `AGENT_SECRET_IAM_<SERVICE>_CLIENT` | 空 | 与 `WorkloadCredentialResolver` 读同一批变量；空值=客户端禁用（401） |

平台侧对接配置（`application.yml`）示例：

```yaml
agent:
  security:
    mode: iam
    service-id: gateway
    authority:
      issuer: https://authority.internal
      jwks-uri: https://authority.internal/.well-known/jwks.json
      token-uri: https://authority.internal/oauth/token
      decision-uri: https://authority.internal/internal/authorize
      client-id: gateway
      client-secret-ref: env:AGENT_SECRET_IAM_GATEWAY_CLIENT
      allowed-algorithm: RS256
      audience: urn:agent-platform:service:gateway
```

## 6. 三条 curl（对本机 18086 实测）

```bash
# 1) JWKS
curl -sS http://127.0.0.1:18086/.well-known/jwks.json
# => {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"2026-10","n":"uRsv…","e":"AQAB"}]}
#    Cache-Control: max-age=300, public

# 2) 换票（gateway 调 app 的 integration.origin.check）
curl -sS -X POST http://127.0.0.1:18086/oauth/token \
  -H 'Authorization: Basic Z2F0ZXdheTp0ZXN0LWdhdGV3YXktc2VjcmV0' \
  -H 'Content-Type: application/json' \
  -d '{"operation":"client","target":"app","action":"integration.origin.check",
       "resource":{"kind":"integration-origin","id":"app-a",
       "version_digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
       "nonce":"demo-nonce-1"}'
# => 200 {"assertion":"eyJ…","ticket":"eyJ…"}   # ticket 头: {"alg":"RS256","typ":"at+jwt","kid":"2026-10"}

# 3) 验票（app 接受 gateway 的票，只放行这一跳）
curl -sS -X POST http://127.0.0.1:18086/internal/authorize \
  -H 'Authorization: Basic YXBwOnRlc3QtYXBwLXNlY3JldA==' \
  -H 'Content-Type: application/json' \
  -d '{"operation":"authorize_workload","target":"app","action":"integration.origin.check",
       "resource":{"kind":"integration-origin","id":"app-a",
       "version_digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
       "nonce":"demo-nonce-2","service_ticket":"<上一步的 ticket>"}'
# => 200 {"assertion":"eyJ…"}
```

实测拒绝面：

```
# 目录无 workload 行（gateway -> engine/run.wake）
{"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}      HTTP 403
# decisionUri 不接受 operation=forward
{"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}      HTTP 403
# 客户端密钥错误
{"code":"SYS-AUTH-401","message":"调用方凭据无效"}            HTTP 401
```

审计（同一次运行）：

```
authority.audit endpoint=token outcome=permit client_id=gateway operation=client target=app action=integration.origin.check nonce=demo-nonce-1
authority.audit endpoint=decision outcome=permit client_id=app operation=authorize_workload target=app action=integration.origin.check nonce=demo-nonce-2
authority.audit endpoint=token outcome=deny client_id=gateway operation=client target=engine action=run.wake status=403 code=SYS-AUTH-403 reason=目录无 workload 行 client=gateway target=engine action=run.wake
authority.audit endpoint=decision outcome=deny client_id=app operation=forward target=app action=integration.origin.check status=403 code=SYS-AUTH-403 reason=decisionUri 不接受 operation=forward
authority.audit endpoint=token outcome=deny client_id=null operation=null target=null action=null status=401 code=SYS-AUTH-401 reason=client_secret 不匹配: gateway
```

**真实客户端**（`RealClientContractTest`，`RANDOM_PORT` + 真 HTTP）两条断言：

1. `gateway.client(new ServiceCall("app","integration.origin.check", new ResourceBinding("integration-origin","app-a",<64hex>)))`
   成功拿到票据；`app.authorizeWorkload(ticket, call)` 成功返回 `WorkloadAuthority`，且 `authority_mode=workload`、`sub==caller`。
2. 目录拒绝时：**裸 HTTP** 是 `403 {"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}`；
   真实客户端抛出 `PlatformException`，实测 `status()==503`、`code()=="IAM_AUTHORITY_UNAVAILABLE"`
   （`BoundedIamHttp` 把一切非 200 折叠成 503，见 §8 M2 待办）。

## 7. 为什么验收用例不是 `skill -> engine / run.wake`

设计文档 §9 的 M1 判据写的是"skill 调 engine 成功"，但用真实消费方跑不通，且三条原因都不在本卡可改范围：

1. `run.wake` 在目录里只有 **delegated** 行（`{caller:skill,target:engine,action:run.wake,mode:delegated,POST /internal/runs/{id}/wake}`），
   而 `HttpCurrentAuthorityClient.client()` 只用 `workload` 模式换票 → 目录直接拒绝。
2. 整个目录只有 **2 行 workload**，都是 `gateway -> app`（`integration.origin.check`、`integration.credential.read`），
   不存在任何 `caller=skill` 的 workload 行；造 test-only 目录行等于绕开"目录即事实"。
3. `client()` 要求 `ServiceCall.resource()` 非空，而 `run.wake` 的调用在文档示意里正是"只有 action 没有资源"。

因此改用等价用例：`serviceId=clientId=gateway`、`gateway -> app / integration.origin.check`、
`ResourceBinding("integration-origin","app-a",<64hex sha256>)`。**delegated 路由（含 skill→engine/run.wake）要等 M2 的
`forward` 语义**（把受限授权转发给下游），不在 M1 面内。

## 8. 与设计文档 §4.4 / 平台校验器的偏差（M2 必修）

文档 §4.4 的票面在当前平台校验器下**不可直接消费**，差异如下（本卡按消费方契约实现，文档待回写）：

| 维度 | 文档 §4.4 | 平台实际校验 | 本实现 |
| --- | --- | --- | --- |
| `aud` | `agent-platform` | `AuthorityProperties.audience` = `urn:agent-platform:service:<serviceId>`（每服务） | 每服务 audience；断言用 caller、票据用 target |
| TTL | `exp-iat <= 300` | `maxTicketSeconds<=60`、`maxDecisionSeconds<=5` | 票 60s、断言 5s |
| `resource` | `{type,id}` | `{kind,id,version_digest}` 三键且全为文本 | 三键 |
| `typ` | 未写 | 票据 `at+jwt`、断言 `agent-authority-decision+jwt` | 同 |
| 传输 | form / `{decision,ticket}` | JSON + Basic，响应 `{assertion,…}`，票据在断言内 | 同 |
| 额外必填 | 无 | 票据需 `nbf/client_id/scope`；workload 需 `sub==caller` 且禁带 `tenant_id/grant_ref/roles/attributes/operation` | 同 |

**M2 待办清单**（按优先级）：

1. `BoundedIamHttp` 错误折叠修正：非 200 应按状态码映射（401/403/409/5xx），403 不得伪装成 503 —— 否则调用方无法区分"被拒绝"与"授权服务不可用"。
2. `forward` 语义：让 delegated 路由（如 skill→engine/run.wake）可被转发授权；本卡对 `forward/authorize/resume/exchange/response_*` 一律 403。
3. token-exchange（`urn:ietf:params:oauth:grant-type:token-exchange`）+ `SUBJECT_MISMATCH` 判定（M2）。
4. 设计文档 §4.4 回写为实际的 `aud/TTL/resource` 形状，或让平台校验器接受 `{type,id}`（二选一，不能两套并存）。
5. 目录同 `(caller,target,action,mode)` 多行（不同 method/template）时，M1 只按四元组判定；M2 需要连 method+template 一起判，避免动作级放行覆盖到别的路径。
6. 审计落库（`caller,target,action,resource,decision,jti,trace_id`）——M1 只有单行日志。
7. 轮换编排（双 kid 已具备能力，缺调度与旧密钥回收）、限流、HA。

## 9. 未覆盖（明确不做）

- **token-exchange**（`subject_token` 换票）与 `SUBJECT_MISMATCH`：M2。
- **user-mgmt（M3）**：delegated 票据的 `initial_ceiling/roles/attributes` 派生、`SUBJECT_DISABLED`。
- **轮换（M4）**：自动轮转、旧 kid 退役、密钥托管；当前只有"同时暴露 previous kid"的静态能力。
- **多租户**：M1 workload 票面不携带 `tenant_id`（平台 workload 校验器禁止），tenant 解析留 M3。
- 未接数据库、未接任何真实密钥托管（`clientSecretRef` 的解析在消费方，本服务只读 env 变量）。
- 未修改 `agent-service-authority.json`（只读消费）；未改任何其它模块源码。
