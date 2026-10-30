# EX-26 AUTHORITY 生产装配与 IAM 真链路 — 端到端验证证据

- 任务：EX-26（执行卡片 `文档/优化方案/执行卡片-统一补差-20261028.md` 第 307 行，「AUTHORITY 生产装配与 IAM 真链路」）
- 口径：`文档/设计/IAM可信身份与票据机制-口径B-20261030.md`（口径 B：平台只持公钥，令牌由独立 authority-service 签发）
- 时间：2026-10-30 15:36–16:02（Asia/Shanghai）
- 结论一句话：**协议级真链路（a/b/c）全部跑通且可被第三方复算；服务级装配（`agent.security.mode=iam` + `agent.skill.service-ticket-mode=AUTHORITY`）被两处既有闸门拒绝，本轮如实记为「未达成」，并已定位为 D3/EX-43 的立项依据。**

## 1. 判定表

| 子项 | 内容 | 判定 | 证据 |
| --- | --- | --- | --- |
| a | gateway 以 `operation=client` 拿 workload 票，app 侧验票并出决策 | **达成**（协议级，真实 HTTP + 真实 RSA） | §4.1、`logs/chain-delegation-on.utf8.log` |
| b | gateway 起委派（session→authorize→forward），skill 用生产代码 `ServiceTickets(AUTHORITY)` 换 `skill→engine` 票，engine 采纳 | **达成**（协议级，生产类真实发票/验票） | §4.2、同上 |
| c | 关 `agent.authority.delegation.enabled` 后同一路径 403，且不回落 legacy | **达成** | §4.3、`logs/chain-delegation-off.utf8.log` |
| 服务级 | 六服务默认模式基线 UP | **达成** | §5.1、`logs/baseline-six-services.log` |
| 服务级 | gateway 以 `agent.security.mode=iam` 启动 | **未达成**：`IllegalStateException: IAM_TRUST_CHAIN_UNAVAILABLE` | §5.2、`logs/service-gateway-iam-refusal.log` |
| 服务级 | skill 以 `agent.skill.service-ticket-mode=AUTHORITY` 启动 | **未达成**：`IllegalStateException: AUTHORITY_REQUIRES_SKILL_IAM` | §5.3、`logs/service-skill-authority-refusal.log` |
| 缺陷修复 | 会话令牌 `attributes` 与委派天花板同集合 | **已修**（1 个生产文件 + 1 个测试断言） | §6 |

> a/b/c 的「达成」限定：平台侧**客户端**（`HttpCurrentAuthorityClient`、`ServiceTickets`、`IamServiceTicketProvider`、`RetainedAuthorization`）与**授权服务进程**（真实 jar、真实 RSA 私钥、真实 HTTP Basic）都跑的是生产代码与真实网络；但接收端服务进程本身无法以 IAM 模式启动（见 §5），因此「接收端验票」由该服务**自己的客户端配置实例**在真实 HTTP 上完成，而不是由运行中的 app/engine 进程完成。这一区别在 §10 记为限制 1，不得被读作「六服务已在 IAM 模式下互调」。

## 2. 环境

| 项 | 值 |
| --- | --- |
| 仓库 | `G:\207\项目\算法重组工具\代码\agent-platform` |
| JDK | Eclipse Adoptium 21.0.12（`JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`）；maven 默认 java 是 17，必须显式导出 |
| 构建 | `mvn -B -ntp -o -Dmaven.repo.local=.tools/m2` |
| authority-service | `authority-service/target/authority-service-0.1.0-SNAPSHOT.jar`，端口 **18086**，PID 23308（delegation=true 的收尾进程） |
| user-mgmt 同形态桩 | 127.0.0.1:**18170**（真实依赖 18070 被旧工程 `ruoyi-admin` 占用，见 §10 限制 4），PID 2908（`netstat` 取到的 JVM） |
| Redis / PostgreSQL | 127.0.0.1:6379 / 127.0.0.1:25432（`scripts/start-database.ps1` 拉起，新建工程专用实例） |
| 密钥 | `.local/ex26-authority-run/authority-key.pem`（PKCS#8，2048-bit，`kid=ex26-20261030`，仅本地、`.local/` 已被 git 忽略），**未提交任何私钥** |
| JWKS | `GET http://127.0.0.1:18086/.well-known/jwks.json` → 200，`{"kty":"RSA","use":"sig","alg":"RS256","kid":"ex26-20261030"}`（`logs/authority-jwks.json`，公钥可公开） |

授权服务启动日志要点（`.local/ex26-authority-run/logs/authority-service-delegation-true.log`，PID 23308）：

```
authority.clients enabled=[gateway, app, engine, skill, model, monitor]
authority.routes rows=184 enabled=167 disabled=17 workload=2
authority.issuer configured issuer=http://127.0.0.1:18086 bind=127.0.0.1
Tomcat started on port 18086
Started AuthorityApplication in 1.965 seconds
```

## 3. 环境变量清单（只有键名，无值）

密钥值只存在于 `.local/ex26-authority-run/env.sh` 与各 `svc-*.env`（均被 `.gitignore` 的 `.local/` 覆盖），本文件与任何提交内容都不含值。

**authority-service 进程**（`env.sh` 全量导出）：

```
SERVER_PORT=18086
AGENT_BIND_ADDRESS=127.0.0.1
AGENT_AUTHORITY_ISSUER=http://127.0.0.1:18086
AGENT_AUTHORITY_KEY_PATH=.local/ex26-authority-run/authority-key.pem
AGENT_AUTHORITY_KID=ex26-20261030
AGENT_AUTHORITY_DELEGATION_ENABLED=true|false      # 判据 c 就是切这个
AGENT_AUTHORITY_IDENTITY_ENABLED=true
AGENT_AUTHORITY_IDENTITY_URL=http://127.0.0.1:18170
AGENT_AUTHORITY_IDENTITY_TENANT=local-v12
AGENT_AUTHORITY_IDENTITY_ISSUER=http://127.0.0.1:18086   # 必须等于 AGENT_AUTHORITY_ISSUER，否则 SUBJECT_ISSUER
AGENT_AUTHORITY_IDENTITY_AUDIENCE=urn:agent-platform:portal
AGENT_AUTHORITY_IDENTITY_CLIENT_ID=gateway
AGENT_AUTHORITY_IDENTITY_SCOPE=platform.access
AGENT_AUTHORITY_IDENTITY_BIZ_SYSTEM_CODE=agent-platform
AGENT_AUTHORITY_IDENTITY_API_KEY_REF=env:EX26_USERMGMT_API_KEY
EX26_USERMGMT_API_KEY=<随机值>
AGENT_SECRET_IAM_GATEWAY_CLIENT=<随机值>
AGENT_SECRET_IAM_APP_CLIENT=<随机值>
AGENT_SECRET_IAM_ENGINE_CLIENT=<随机值>
AGENT_SECRET_IAM_SKILL_CLIENT=<随机值>
AGENT_SECRET_IAM_MODEL_CLIENT=<随机值>
AGENT_SECRET_IAM_MONITOR_CLIENT=<随机值>
```

注意 `_CLIENT` 后缀是强制的：`authority-service/src/main/resources/application.yml:34-39` 读 `${AGENT_SECRET_IAM_<SERVICE>_CLIENT:}`，且平台侧 `AuthorityProperties` 要求 `client-secret-ref` 精确等于 `env:AGENT_SECRET_IAM_<SERVICEID大写>_CLIENT`。未配置的 client 不会获得凭证（authority 对该 client 401），不是默认放行。

**链验证（skill-registry 测试 JVM）**：只需要 `AGENT_SECRET_IAM_{GATEWAY,APP,ENGINE,SKILL}_CLIENT` 五个值（`WorkloadCredentialResolver` 从进程环境读，缺失即 `IllegalStateException: IAM_CLIENT_CREDENTIAL_REQUIRED`）。

**gateway 服务级负例文件** `.local/ex26-authority-run/svc-gateway-iam.env`：

```
AGENT_SECURITY_MODE=iam
SPRING_PROFILES_ACTIVE=test
AGENT_DEV_AUTH=false
SPRING_APPLICATION_JSON={"agent.security":{"mode":"iam","service-id":"gateway","authority":{...}}}
AGENT_SECRET_IAM_GATEWAY_CLIENT=<随机值>
AGENT_SECRET_BINDINGS={"AGENT_SECRET_IAM_GATEWAY_CLIENT":"local-v12"}
```

两个启动器约束（踩过，写在这里省下次的时间）：① `env:AGENT_SECRET_*` 引用必须同时在该服务的 `AGENT_SECRET_BINDINGS` 里绑定 tenant，否则 `start-backend.ps1` 在拉起前就报 `…密钥引用缺少环境值或租户绑定`；② authority 的驼峰键不能写成 `AGENT_SECURITY_AUTHORITY_JWKS_URI`——Spring 会把下划线当层级，绑成 `agent.security.authority.jwks.uri`，被 `ignoreUnknownFields=false` 判为未知键整服务拒启，所以整块走 `SPRING_APPLICATION_JSON`。

## 4. 真链路（a/b/c）

### 4.0 复现命令

```bash
cd "G:/207/项目/算法重组工具/代码/agent-platform"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
set -a && source .local/ex26-authority-run/env.sh && set +a

# 构建（authority-service 及其依赖）
mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl authority-service -am package -DskipTests

# 起桩 + 起授权服务（delegation=true）
bash .local/ex26-authority-run/start-usermgmt-stub.sh          # 18170
bash .local/ex26-authority-run/restart-authority.sh true       # 18086

# a+b：真实链路
EX26_AUTHORITY_CHAIN=true EX26_EXPECT=delegation-on \
  mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl skill-registry test -Dtest=Ex26AuthorityChainTest

# c：负例
bash .local/ex26-authority-run/restart-authority.sh false
EX26_AUTHORITY_CHAIN=true EX26_EXPECT=delegation-off \
  mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl skill-registry test -Dtest=Ex26AuthorityChainTest
bash .local/ex26-authority-run/restart-authority.sh true       # 收尾恢复
```

测试类：`skill-registry/src/test/java/cn/agentplatform/common/authority/Ex26AuthorityChainTest.java`（`@EnabledIfEnvironmentVariable(named="EX26_AUTHORITY_CHAIN")` 门控，默认不跑；`EX26_EXPECT` 决定跑 a/b 还是 c）。输出逐行带 `[a-1]…[b-6]/[c-1]…[c-4]` 前缀，直接可贴。

实测：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 1`（delegation-on，跳过 c）；`Tests run: 4, Failures: 0, Errors: 0, Skipped: 3`（delegation-off，跳过 a/b）。原始输出在 `logs/chain-delegation-on.log`（GBK 原文）/`.utf8.log`（UTF-8 转写）/`chain-delegation-off.*`。

### 4.1 判据 a：`operation=client` workload 票 + app 验票

```
[a-1] POST /oauth/token operation=client client=gateway target=app action=integration.origin.check
[a-1] http=200 ticket.header=at+jwt|RS256|kid=ex26-20261030 ticket.len=1092 expiresAt=…:39Z
[a-1] claims={iss="http://127.0.0.1:18086", aud=urn:agent-platform:service:app, sub="gateway",
        caller="gateway", client_id="gateway", scope="integration.origin.check",
        authority_mode="workload", iat=1793346819, exp=1793346879, jti=tkt-80***}
[a-2] POST /internal/authorize operation=authorize_workload client=app
[a-2] http=200 caller=gateway decisionId=dec-30*** checkedAt=…:39Z expiresAt=…:44Z
```

- 票面：`typ=at+jwt`、`alg=RS256`、`kid=ex26-20261030`；`exp-iat=60`（`ticket-seconds`）；`authority_mode=workload`；**没有** `tenant_id/sub=人/roles/attributes/operation`（workload 票零容忍，见 §10 复算表 a-4）。
- 验票由 `HttpCurrentAuthorityClient.authorizeWorkload(ticket, call)` 完成：`TrustedJwtDecoder` 真验 RS256 签名 + `typ/iss/aud/exp` + `iss` 必须命中授权服务 issuer，随后 `authority(JsonNode,ServiceCall)` 校验 `authority_mode=workload`、`target/action/scope` 一致、`sub/caller/client_id` 都是 `app` 视角的调用方、且决策断言里不得出现人身份字段。
- 决策断言（app 侧收到并验过的那张）：`typ=agent-authority-decision+jwt`，`token_use=current-decision`，`aud=urn:agent-platform:service:app`，`active=true`，`current=true`，`iat` 不早于请求发起，`nonce` 回显，`request_sha256` 与客户端本地 `Digest.sha256(Json.canonical(body))` 逐字节相等。
- 负例（同一次运行）：
  - `[a-3] gateway.client(target=engine, action=run.wake)` → **403** `code=IAM_AUTHORITY_FORBIDDEN`（服务端审计 `outcome=deny … code=SYS-AUTH-403 reason=目录无 workload 行`）。目录里 engine 方向只有 delegated 行，没有 workload 行，所以拒绝来自 `RouteDirectory.permits`，不是权限位。
  - `[a-4] skill.authorize(workload ticket)` → **403** `code=SYS-AUTH-403` `message=当前授权不允许此操作`：`authorizeRetained` 要求 `authority_mode=="delegated"`，workload 票不能被当委派票消费。
  - `[a-5] ServiceTickets(AUTHORITY).ticket(...)` 未绑定 `AuthorityContext` → **403** `code=AUTHORITY_CONTEXT_MISSING` `message=当前请求没有可验证授权，无法签发服务票据`，`legacy_fallback=false`：没有回落自签 HS256。

### 4.2 判据 b：委派链（gateway → skill），skill 生产代码换 `skill→engine` 票

链路（4 跳，全部真实 HTTP、全部生产类）：

```
session(gateway) ──portal token──▶ /internal/authorize(operation=authorize)
   ──decision(assertion)+grant──▶ /oauth/token(operation=forward,target=skill)
   ──delegated ticket#1──▶ skill.authorize ──VerifiedAuthority──▶
   ServiceTickets(AUTHORITY).ticket("skill","POST",engine/internal/runs/{id}/wake)
   ──IamServiceTicketProvider.issue → HttpCurrentAuthorityClient.forward──▶ delegated ticket#2
   ──▶ engine.authorize(ticket#2) 采纳
```

```
[b-1] session → http=200 token_use=user sub=ex26-o*** tenant=local-*** roles=["ADMIN"] scope=platform.access
        iss=http://127.0.0.1:18086 aud=["urn:agent-platform:portal"]
[b-2] /internal/authorize → http=200
      assertion.header=agent-authority-decision+jwt|RS256|kid=ex26-20261030
      claims={iss="http://127.0.0.1:18086", aud=urn:agent-platform:service:gateway,
              client_id="gateway", token_use="current-decision", decision_id=dec-3f***,
              subject_caller="gateway", grant_ref=grt-cf***, grant_expires_at=1793346879,
              authorization_version="delegated-v1", active=true, current=true,
              authority_mode="delegated", target="skill", action=proxy.skill.post.SkillController.create,
              nonce=ex26-9***}
      request_sha256=53ecd5c3…06969b5 recomputed=53ecd5c3…06969b5 match=true ttl=5s
      initial_ceiling={tenant_id="local-v12", sub="ex26-operator", roles=["ADMIN"],
                       attributes={"bizSystemCode":["agent-platform"],"userId":["ex26-operator"]}}
      operation={source_kind="PORTAL", origin_kind="PORTAL", ceiling_sha256=af652b***}
[b-3] /oauth/token operation=forward → http=200 ticket.len=2465
      ticket1.claims={aud=urn:agent-platform:service:skill, sub="ex26-operator", caller="gateway",
              scope=proxy.skill.post.SkillController.create, authority_mode="delegated",
              grant_ref=grt-cf***, grant_expires_at=1793346879, authorization_version="delegated-v1",
              subject_caller="gateway"}
[b-4] skill.authorize → ok caller=gateway decisionId=dec-1c*** grant=grt-cf*** exp=…:44Z
[b-5] ServiceTickets(AUTHORITY).ticket POST http://127.0.0.1:18082/internal/runs/run-ex26/wake → ok ticket.len=2380
      ticket2.claims={aud=urn:agent-platform:service:engine, sub="ex26-operator", caller="skill",
              scope="run.wake", authority_mode="delegated", grant_ref=grt-cf***,
              operation={"source_kind":"PORTAL", "resource":{kind:skill-definition,id:ex26-demo-skill@1,
                         version_digest:0123…(64)}, …}, subject_caller="gateway"}
[b-6] engine.authorize(ticket2) → ok caller=skill tenant=local-*** sub=ex26-o*** roles=[ADMIN]
      resource=skill-definition/ex26-demo-skill@1
```

要点：

1. **`[b-2]` 的 `request_sha256` 是现场复算的**：测试用 `Digest.sha256(Json.canonical(请求体))` 本地算一遍与断言里的值比较，`match=true`。任何第三方按口径 B 文档同样能复算（见 §7）。
2. **`[b-5]` 走的是生产代码**：`ServiceTickets`（skill 生产类）+ `IamServiceTicketProvider` + `HttpCurrentAuthorityClient.forward` + `ServiceRoutePolicy.require("skill","engine","POST","/internal/runs/{id}/wake")`，测试里只是把 `VerifiedAuthority` 用 `AuthorityContext.bind(...)` 绑到当前线程（生产里由入站拦截器绑）。路由模板匹配与「forwarder 自己必须拥有该 caller→target 行」的校验都真实发生了：`skill` 是 `run.wake` 行的 caller。
3. **资源不被放大**：`forward` 服务端强制 `decision.resource().equals(resourceOf(request.call()))`，所以 `ticket2` 的 `resource` 与 `[b-2]` 决策、`ticket1` 完全一致；`[b-6]` 的 `engine.authorize` 又用 `same(operation)` 复核一次。
4. 链路首跳之所以用原始 HTTP 而不是平台客户端：`CurrentAuthorityClient.authorize(String,ServiceCall)` 只接受**已经是 delegated** 的票，`exchange/exchange_integration/resume` 对应的服务端 operation 在 `AuthorityController` 里没有实现（tokenUri 只收 `client|forward|session`，decisionUri 只收 `authorize|authorize_workload`）。这是产品缺口，记在 §10 限制 3，不是本次验证的取巧。

### 4.3 判据 c：关掉委派后 403，且不回落 legacy

`bash .local/ex26-authority-run/restart-authority.sh false` → `authority-service pid=28912 port=18086 delegation=false`，同一测试类以 `EX26_EXPECT=delegation-off` 运行：

```
[c-1] POST /oauth/token operation=session → http=200（身份链仍在，只关委派）
[c-2] POST /internal/authorize operation=authorize → http=403
      body={"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}
[c-3] POST /oauth/token operation=forward → http=403
      body={"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}
[c-4] ServiceTickets(AUTHORITY).ticket(无绑定额度) → 403 code=AUTHORITY_CONTEXT_MISSING legacy_fallback=false
```

结论：开关关闭时 `DelegatedAuthority` 入口直接拒绝（`DELEGATION_DISABLED` → 403 `SYS-AUTH-403`），会话/客户端票不受影响；平台侧 `ServiceTokens.issue` 在 IAM 部署下本就拒绝自签（`AUTHORITY_CONTEXT_MISSING`），所以**没有任何路径回落 HS256 legacy**。收尾已恢复 `delegation=true`（PID 23308）。

## 5. 服务级验证（D3 立项依据）

### 5.1 基线：六服务默认模式全 UP

`pwsh scripts/start-backend.ps1 -SkipBuild`（不传任何 env 文件）→ 六个 `/actuator/health` 全 `{"status":"UP"}`，日志落在 `logs/baseline-six-services.log`：

| 服务 | 端口 | PID（当次） |
| --- | --- | --- |
| platform-gateway | 18080 | 26488 |
| app-management | 18081 | 42544 |
| engine-runtime | 18082 | 52832 |
| skill-registry | 18083 | 6568 |
| model-gateway | 18084 | 38316 |
| monitor | 18085 | 36496 |

默认模式是 `dev-hmac`（`start-backend.ps1` 未显式设置时的缺省；日志里没有 mode 行，`/actuator/env` 401，故以启动器缺省 + 启动成功为证）。

### 5.2 gateway 以 IAM 模式启动 → 拒启

```powershell
.\scripts\start-backend.ps1 -Services platform-gateway -SkipBuild `
  -ServiceEnvironmentFiles @{'platform-gateway'='G:\…\.local\ex26-authority-run\svc-gateway-iam.env'}
# 配置覆盖键：AGENT_DEV_AUTH, AGENT_SECRET_BINDINGS, AGENT_SECRET_IAM_GATEWAY_CLIENT,
#              AGENT_SECURITY_MODE, SPRING_APPLICATION_JSON, SPRING_PROFILES_ACTIVE
# platform-gateway 已启动，PID=63532，端口=18080   ← 随后端口未监听
```

```
2026-10-30T15:58:21.276  INFO 63532 … The following 1 profile is active: "test"
2026-10-30T15:58:22.100  WARN 63532 … Exception encountered during context initialization -
                                    cancelling refresh attempt: java.lang.IllegalStateException: IAM_TRUST_CHAIN_UNAVAILABLE
java.lang.IllegalStateException: IAM_TRUST_CHAIN_UNAVAILABLE
    at cn.agentplatform.common.SecurityConfiguration.lambda$authorityModeGuard$0(SecurityConfiguration.java:51)
```

`admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java:47-52` 是无条件闸门，源码注释即"`D1 component receivers do not open production IAM; D3 must migrate every service first`"。所以 `agent.security.mode=iam` 目前对任何服务都是拒启——`AuthorityProperties` 的校验（含 `IAM_DEV_AUTH_FORBIDDEN`）根本走不到。

### 5.3 skill 以 AUTHORITY 启动 → 拒启

```powershell
.\scripts\start-backend.ps1 -Services skill-registry -SkipBuild `
  -ServiceEnvironmentFiles @{'skill-registry'='G:\…\.local\ex26-authority-run\svc-skill-authority.env'}
# 配置覆盖键：AGENT_SKILL_SERVICE_TICKET_MODE
# skill-registry 已启动，PID=59312，端口=18083   ← 随后端口未监听
```

```
2026-10-30T15:59:17.570 WARN 59312 … cancelling refresh attempt: …UnsatisfiedDependencyException…
Caused by: org.springframework.beans.BeanInstantiationException: Failed to instantiate
  [cn.agentplatform.common.authority.HttpCurrentAuthorityClient]:
  Factory method 'skillCurrentAuthorityClient' threw exception with message: AUTHORITY_REQUIRES_SKILL_IAM
    at cn.agentplatform.skill.infra.ServiceTicketConfiguration.skillCurrentAuthorityClient(ServiceTicketConfiguration.java:32)
```

`skill-registry/.../ServiceTicketConfiguration.java` 的两个工厂方法都要求 `agent.security.mode=iam` 且 `service-id=skill`。两条互相咬死：**AUTHORITY 需要 iam，iam 被 5.2 的闸门挡死**。这就是「provider 已装配但生产不可用」的准确状态，也是本次无法把接收端服务起成 IAM 模式的直接原因（§10 限制 1）。

## 6. 缺陷修复（与验证分开）

### 6.1 症状

`[b-2]` 首次运行 → `403 {"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}`，授权服务审计 `reason=SUBJECT_ATTRIBUTES`。

### 6.2 根因

- `authority-service/.../identity/SessionService.java` 把人身份画像（`deptId`、`deptName`、`dataScope`、`userId`、`bizSystemCode`）整包签进会话令牌的 `attributes`；
- `authority-service/.../decision/DelegatedAuthority.java:427-446` 的 `attributesOf(JsonNode)` 对白名单 `ATTRIBUTE_KEYS={region,dataLabels,bizSystemCode,userId}` 之外的键 fail-closed（`SUBJECT_ATTRIBUTES`）；
- 既有测试 `authority-service/src/test/java/cn/agentplatform/authority/DelegatedSubjectTokenTest.java` 的 `subjectToken()` 夹具只放 `Map.of("region", List.of("A"))`，注释写着"令牌形态对齐 SessionService"但从未覆盖真实会话 claims 集。

净效果：**授权服务自己签出的人令牌，无法作为它自己委派链的主体令牌**——这不是测试环境问题，任何接 user-mgmt 的生产部署都会 100% 触发。

### 6.3 修复（最小面）

`authority-service/src/main/java/cn/agentplatform/authority/identity/SessionService.java`：签名面只放天花板维度，响应体仍返回完整画像。

```java
private static final Set<String> CEILING_KEYS = Set.of("region", "dataLabels", "bizSystemCode", "userId");
...
claims.put("attributes", ceilingAttributes(attributes));   // 原：claims.put("attributes", attributes)
...
private static Map<String, Object> ceilingAttributes(Map<String, Object> attributes) {
    Map<String, Object> ceiling = new LinkedHashMap<>();
    attributes.forEach((key, value) -> { if (CEILING_KEYS.contains(key)) ceiling.put(key, value); });
    return ceiling;
}
```

配套 `authority-service/src/test/java/cn/agentplatform/authority/SessionEndpointTest.java`：把"签名 claims 必须含 dataScope/deptId"的旧断言改为"签名 claims 不得含 dataScope/deptId，响应体仍含"。

修复后 `mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl authority-service test` → `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`。`[b-2]` 的 `initial_ceiling` 现在正是四维集合（`bizSystemCode`+`userId`），`SUBJECT_ATTRIBUTES` 不再出现。

> 未动 `SecurityConfiguration.java`、`agent-service-authority.json`、`start-backend.ps1` 等共享面。§5 的两处拒启是既有设计，本次只取证、未改。

## 7. 第三方复算指引（每条判据 → 类/方法 → 期望）

| 步骤 | 复算者应读的代码 | 期望 |
| --- | --- | --- |
| a-1 发票 | `authority-service/.../ticket/TicketService` + `sign/KeyRing.sign("at+jwt",…)` | header `typ=at+jwt,alg=RS256,kid`；claims `aud=urn:agent-platform:service:<target>`、`sub=caller`、`scope=action`、`authority_mode=workload`、`exp-iat=60s`、无人身份字段 |
| a-2 验票 | `admin-common/.../authority/HttpCurrentAuthorityClient.authorizeWorkload` → `TrustedJwtDecoder` → `AuthorityResponseVerifier.verify(...,"agent-authority-decision+jwt","current-decision",…)` | 200；断言 `nonce` 回显、`request_sha256==Digest.sha256(Json.canonical(body))`、`client_id==serviceId`、`active&&current` |
| a-3 目录拒绝 | `authority-service/.../directory/RouteDirectory.permits(caller,target,action,"workload")` → `AuthorityProtocol.denied()`；客户端 `BoundedIamHttp` 403→`IAM_AUTHORITY_FORBIDDEN` | 403；审计 `reason=目录无 workload 行` |
| a-4 模式混用 | `HttpCurrentAuthorityClient.authorizeRetained` 的 `authority_mode=="delegated"` 前置 + `same(ticket.jti,current.ticket_jti)` 等 | 403 `SYS-AUTH-403` |
| a-5 无上下文 | `ServiceTickets.currentAuthority()` / `ServiceTokens.issue` | 403 `AUTHORITY_CONTEXT_MISSING`，无自签 |
| b-2 决策 | `DelegatedAuthority.fromSubject` + `TicketService.envelope` | `token_use=current-decision`、`aud=…:gateway`、`decision_id`、`subject_caller`、`grant_ref`、`operation.source_kind=PORTAL`、`request_sha256` 可复算 |
| b-3 首跳 forward | `DelegatedAuthority.forward` | `authority_mode=delegated`、`aud=…:skill`、`caller=gateway`、`scope=action`、同 `grant_ref` |
| b-5 skill 发票 | `skill-registry/.../infra/ServiceTickets.ticket` → `IamServiceTicketProvider.issue` → `HttpCurrentAuthorityClient.forward`；`ServiceRoutePolicy.require("skill","engine","POST","/internal/runs/{id}/wake")` | `caller=skill`、`aud=…:engine`、`scope=run.wake`、`operation.resource` 与 b-2 逐字段相等 |
| b-6 engine 采纳 | `HttpCurrentAuthorityClient.authorize` → `authorizeRetained`（`subject_caller`/`grant_ref`/`authorization_version`/`initial_ceiling`/`operation` 复核） | 200；`caller=skill`，人身份与 b-4 相同 |
| c-2/c-3 关闭委派 | `DelegatedAuthority` 入口 `DELEGATION_DISABLED` | 403 `SYS-AUTH-403`，无 HS256 回落 |

审计行可直接核对（`logs/authority-audit-sample.log`，logger `agent.authority.audit`，无密钥、无票据原文、无 `eyJ`）：

```
audit endpoint=token outcome=permit client_id=gateway operation=client target=app action=integration.origin.check …
audit endpoint=decision outcome=permit client_id=app operation=authorize_workload target=app action=integration.origin.check …
audit endpoint=token outcome=deny client_id=gateway operation=client target=engine action=run.wake status=403 code=SYS-AUTH-403 reason=目录无 workload 行
audit endpoint=decision outcome=permit operation=authorize caller=gateway target=skill action=proxy.skill.post.SkillController.create decision_id=dec-b00c64a0…
audit endpoint=decision outcome=permit operation=forward caller=gateway target=skill action=proxy.skill.post.SkillController.create …
audit endpoint=decision outcome=permit operation=authorize caller=skill target=skill action=proxy.skill.post.SkillController.create …
```

## 8. 未达成项与原因（如实记录）

| # | 未达成 | 原始错误 | 归属 |
| --- | --- | --- | --- |
| 1 | 六服务无法以 `agent.security.mode=iam` 运行 | `java.lang.IllegalStateException: IAM_TRUST_CHAIN_UNAVAILABLE`（`SecurityConfiguration.java:51`） | D3/EX-43：入站 `serviceSecurity` 链未装配、`ServiceAuthorization` 只被测试装配引用、`/internal/**` 仍是 `!trusted && ROLE_SERVICE` |
| 2 | skill 无法以 `service-ticket-mode=AUTHORITY` 运行 | `Factory method 'skillCurrentAuthorityClient' threw exception with message: AUTHORITY_REQUIRES_SKILL_IAM` | 同上，AUTHORITY 与 iam 互相咬死 |
| 3 | 平台无「门户 token → 委派票」的生产引导 | `authority-service` 的 tokenUri/decisionUri 未实现 `exchange`/`exchange_integration`/`resume` | 授权服务侧缺口，本次用口径 B 的原始 HTTP 契约完成引导 |
| 4 | 真实 user-mgmt 18070 不可用 | 端口被旧工程进程占用（`ruoyi-admin.jar`，PID 62248） | 按 AGENTS.md 不动邻近旧工程进程；改用同形态桩 18170，形态与真实接口一致（`GET /system/apikeys/identity/self` + `X-API-Key`，HTTP 200 + body `code`） |

## 9. 文件清单

**修改（2 个，缺陷修复）**

- `authority-service/src/main/java/cn/agentplatform/authority/identity/SessionService.java`
- `authority-service/src/test/java/cn/agentplatform/authority/SessionEndpointTest.java`

**新增（3 个，验证与桩）**

- `skill-registry/src/test/java/cn/agentplatform/common/authority/Ex26AuthorityChainTest.java`（a/b/c 链验证，环境变量门控，默认不跑）
- `authority-service/src/test/java/cn/agentplatform/authority/ex26/UserMgmtStubMain.java`（user-mgmt 同形态桩，测试作用域，单文件源码模式 `java UserMgmtStubMain.java 18170 <api-key>`）
- `docs/evidence/2026-10-30-EX26-authority-chain/`（本文件 + `logs/`）

**本地未跟踪（`.local/`、`.gitignore` 覆盖，不入库）**

- `.local/ex26-authority-run/{env.sh, authority-key.pem, authority-key.pub.pem, start-*.sh, restart-authority.sh, svc-*.env, logs/}`

**未改动**：`SecurityConfiguration.java`、`agent-service-authority.json`、`scripts/start-backend.ps1`、各服务 `application.yml`。

**回滚**：`agent.skill.service-ticket-mode=LEGACY` 即恢复 legacy 出站；本次缺陷修复可用 `git checkout -- authority-service/src/main/java/cn/agentplatform/authority/identity/SessionService.java authority-service/src/test/java/cn/agentplatform/authority/SessionEndpointTest.java` 撤销。

## 10. 限制（不得被读超）

1. **a/b 的接收端是「服务自己的客户端配置实例」**，不是运行中的 app/engine 进程——因为 §5.2 让任何服务都无法以 iam 模式启动。签发端（authority-service 进程）与平台客户端代码（`HttpCurrentAuthorityClient`/`ServiceTickets`/`IamServiceTicketProvider`）都是真的，验票逻辑也是真的，但没有「运行中的 app-management 在 IAM 模式下接收 `/internal/integration-origins`」这一事实。
2. **gateway 生产代码目前没有 workload 票的调用方**：`platform-gateway/src/main/java/cn/agentplatform/gateway/application/IntegrationAccess.java:228` 仍用 `ServiceTokens.issue(BOOTSTRAP)`（legacy HMAC）调 `GET /internal/integration-origins`。判据 a 证明的是「票与验票链路可用」，不是「gateway 生产路径已切换」。
3. `exchange`/`exchange_integration`/`resume` 服务端未实现（§8-3），委派链的入口目前只能由持有门户令牌的一方按口径 B 契约发起。
4. user-mgmt 用同形态桩（§8-4）；桩只做「凭 API-Key 查身份」这一件事，不落库、不写密钥、支持 `/actuator/health` 就绪探针。
5. 日志编码与留存：surefire 子进程与部分服务日志按 GBK 落盘，中文需 `iconv -f GBK -t UTF-8` 才能正确阅读；`logs/*.log`（含 `.utf8.log`）被 `.gitignore` 的 `**/*.log` 忽略、**不随提交**，仅作本机复核；可持久化的是本文件内嵌的关键行，完整原文按 §4.0 复现（`logs/authority-jwks.json` 是公钥、不被忽略）。
6. `.local/logs/<service>.out.log` 会被下次启动覆盖（`start-backend.ps1` 行为），本目录的 `logs/*-refusal.log` 是当时摘录，PID/时间戳已在文件头注明。

## 11. 证据文件索引

| 文件 | 内容 |
| --- | --- |
| `logs/chain-delegation-on.log` / `.utf8.log` | a+b 完整实测输出（GBK 原文 / UTF-8 转写），22 条 `[a-*]/[b-*]` 步骤行 |
| `logs/chain-delegation-off.log` / `.utf8.log` | c 负例实测输出（4 条 `[c-*]` 行） |
| `logs/baseline-six-services.log` | 六服务默认模式 `/actuator/health` 全 UP 与 PID |
| `logs/service-gateway-iam-refusal.log` | gateway 以 mode=iam 拒启的原始行（含 `SecurityConfiguration.java:51` 栈帧） |
| `logs/service-skill-authority-refusal.log` | skill 以 AUTHORITY 拒启的原始行（含 `ServiceTicketConfiguration.java:32` 栈帧） |
| `logs/authority-jwks.json` | 授权服务公钥集（`kid=ex26-20261030`） |
| `logs/authority-audit-sample.log` | `agent.authority.audit` 审计行样本（permit/deny、reason） |
