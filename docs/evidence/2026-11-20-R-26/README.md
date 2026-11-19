# R-26 authority-service 测试用户票据铸造端点 —— 证据与验收

- 卡：台账 §7.6b **R-26**（0.5 人日，后端B）；契约唯一来源：**09 测试细则 v1.2 §9.2**
- 分支/提交：`r26-test-tickets` @ **903d0937**（基线 origin/upgrade/20261103 @ a7a89c23）
- 日期：2026-11-20
- 结论先行：**端点契约 13/13 判据全过（本地测试 23 例 + E2 部署级实证）；平台验签闭环在本地以生产验签组件 TrustedJwtDecoder 通过（DoD-2）；E2 网关验签通过实证成立（有效票 ≠401，篡改/过期票 =401 对照）；DoD-3 的 `GET /v1/... 200` 因平台 iam 模式网关 ingress 形状限制（先于本卡存在）无法返回 200，已按 §9.2"以 TrustedJwtDecoder 验签通过为最终判据"给出等价实证并登记缺口（见 §5）。**

---

## 1. 契约逐条对照表（§9.2 + 卡面）

| # | §9.2/卡面条目 | 实现 | 判据 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | 端点 `POST /test/tickets`，authority-service | `TestTicketController.mint`（authority-service:18086） | E2 curl 200 | ✅ `01-mint-user-ticket.txt` |
| 2 | 开关双条件：仅 `test` profile 且 `agent.authority.test-ticket.enabled=true`（默认 false）；非 test 启动即拒绝注册或 404 | `@Profile("test")` + `@ConditionalOnProperty` 同置控制器上（两处都查）；yml 默认 `AGENT_AUTHORITY_TEST_TICKET_ENABLED:false` | 开关关 404；非 test profile 404 | ✅ `TestTicketDisabledTest`(2 例)、`TestTicketProfileGateTest`(1 例) |
| 3 | 请求 `{tenantId, subject, roles[], attributes{region,dataLabels,bizSystemCode,userId}, ttlSeconds}` | 严格白名单字段解析：未知顶层字段 400、attributes 四键齐全（归属键恰一值）、未知 attributes 键 400 | 正例+反例 | ✅ `TestTicketEndpointTest` 12 例 |
| 4 | 响应 `{accessToken, tokenType:"Bearer", expiresIn, jti}` | 同形四字段；`expiresIn=ttlSeconds`；`jti` 前缀 `tt-` | 断言 | ✅ 正例断言 + E2 实录 |
| 5 | accessToken = RS256 JWT，与生产票同 issuer/同 kid | `iss/aud/client_id/scope` 取 `IdentityProperties`（=`SessionService` 门户用户票同一组配置，**不另设可漂移配置**）；签名走 `KeyRing`（同 `at+jwt` typ、同 active kid） | header kid == JWKS kid；iss/aud 与网关 portal 配置逐字一致 | ✅ 正例断言 + E2 `02-token-header.json`（kid=2026-10） |
| 6 | claims 含 `iss/aud/token_use/sub/tenant_id/roles/attributes/iat/exp/jti` | 另带平台 portal 分支必需的 `client_id/scope/nbf/origin_authentication_id`（与 `SessionService` 逐字段同构） | 逐 claim 断言 | ✅ 正例断言 + E2 `02-token-claims.json` |
| 7 | roles 仅 `ADMIN|BUILDER|OPERATOR|USER`；SERVICE 一律 400（走 client_credentials） | `ALLOWED_ROLES` 四值；`SERVICE`/`GATEWAY_AUTH` 显式 400，文案点名 client_credentials | SERVICE→400；GATEWAY_AUTH→400；未知角色→400；空 roles→400 | ✅ 反例×3 + E2 `07-negative-service-role.txt` |
| 8 | tenantId 白名单 `demo`、`t-*` 前缀 | `demo` 或 `t-` 前缀（前缀须有非空尾巴，裸 `t-` 拒绝），长度≤64 | prod-x→400；`t-`→400；缺→400 | ✅ 反例 + E2 `07-negative-tenant.txt` |
| 9 | `ttlSeconds ≤ 3600`（7200→400） | 1..3600 整数，缺省 3600；7200/3601/0/非整数→400 | 7200→400 | ✅ 反例 + E2 `07-negative-ttl-7200.txt` |
| 10 | 审计行 `TEST_TICKET_MINTED`（subject/roles/tenant/jti/issuer/iat），照 `AUDIT xxx` warn 先例 | logger `agent.authority.audit`（与既有 authority.audit 同 logger），WARN 级，行=`AUDIT TEST_TICKET_MINTED subject=… roles=… tenant=… jti=… issuer=… iat=…` | 行内容与票面 jti/iat 一致 | ✅ appender 断言 + E2 `08-authority-audit-lines.txt`（jti 与响应体逐字相等） |
| 11 | 审计失败即拒签（fail-closed） | 审计写入在响应组装前；写入抛错→`503 TEST_TICKET_AUDIT_FAILED`，票不出服务 | 注入抛错 writer → 503 且无票 | ✅ `TestTicketAuditFailClosedTest`(3 例) |
| 12 | 反例：过期票 401（SEC-07 一并验） | ttl=1 + 时钟推进 → 解码层 `BadJwtException`；E2 部署级=网关 401 | 401 | ✅ 闭环测试 + E2 `06-gateway-expired-ticket.txt` |
| 13 | 反例：HS256 伪造票 401 | 头部白名单（alg=RS256）直接拒；同 kid 异钥签名拒 | BadJwtException | ✅ 闭环测试（E2 部署级对照见 `05-gateway-tampered-ticket.txt` 401） |
| 14 | 平台验签（DoD-2 最终判据：签发后经 gateway 的 TrustedJwtDecoder 验签通过，issuer/kid/aud 三者一致） | 本机：真实 `TrustedJwtDecoder`（platform-gateway 生产验签组件，portal issuer/jwks 指向真实 authority 进程，GatewayIamPortalHttpTest 同款配置形状）解码成功 + `PortalClaimMapper` 映射断言。E2：网关开 portal 链后，有效票过验签进入路由层（403/503），篡改/过期票 401 | 验签通过 | ✅ `TestTicketTrustedDecoderLoopTest`(5 例) + E2 §4 |
| 15 | 反例：非 test profile 端点不可用 | 同 #2 | 404 | ✅ |
| 16 | 反例：USER 票调 ADMIN 端点 403 | **不在本卡 DoD**（卡面验收列表有此项但 §9.2 明示"SEC-07 一并验"）。E2 实测：当前网关 iam 链上 `/admin/**` 授权仅查 scope 不查角色（`SecurityConfiguration` #249-256），角色门禁在上游服务——登记给 SEC-07，不在本卡声明 | — | 📋 登记（§5 诚实边界） |
| 17 | 反例：跨租户读 404 | 同上，归 SEC-07（跨租户行为是业务面断言，需业务数据铺底） | — | 📋 登记（§5） |
| 18 | 测试支撑面：不进 04 对外契约 | 仅 authority-service 代码+yml 开关段，contracts/ 零改动 | — | ✅ |

## 2. 测试统计（本地，mvn test）

| 类 | 用例 | 内容 |
| --- | --- | --- |
| `TestTicketEndpointTest` | 12/12 绿 | 正例 3（claims 逐字段+kid 同源 / t-*+去重+缺省 ttl / 三角色全可签）、审计行 1、解码闭环 1、反例 7（SERVICE+GATEWAY_AUTH+未知角色+空 roles+非数组、白名单外租户×4、ttl 7200/3601/0/字符串、attributes 形状×4、未知字段/空 subject/坏 body） |
| `TestTicketAuditFailClosedTest` | 3/3 绿 | 审计抛错→503 无票；审计行先于响应且字段与票面一致；身份档案缺 issuer→503 |
| `TestTicketTrustedDecoderLoopTest` | 5/5 绿 | DoD-2 闭环（2 键白名单 E2 口径）、过期票拒、异钥伪造（同 kid）拒、HS256 伪造拒、四键钉扎配置拒测试票（by design，钉扎口径反向对照） |
| `TestTicketDisabledTest` | 2/2 绿 | 开关缺省 404；既有端点（jwks）不受影响 |
| `TestTicketProfileGateTest` | 1/1 绿 | enabled=true 但 profile=not-test → 404 |
| **新增合计** | **23/23 绿** | |
| authority-service 全量回归 | **142/142 绿**（BUILD SUCCESS） | 含既有 119 例 + 新增 23 例 |
| admin-common（-am 上游） | 387 中 2F/1E | **基线既有**：`ServiceTokensDeploymentModeTest.main`、`LocalRunAdvanceAuthorityTest.sameRootAppReadAndDebugGrantsCannotBecomeLocalMain` 在基提交 a7a89c23（agent-platform 干净检出）同样复现（复跑记录 `G:\207\r26-base-check.log`，11 例 1F/1E）；本卡 diff 仅 authority-service 5 文件，零交集 |

复跑命令：

```bash
cd "G:\207\项目\算法重组工具\代码\_worktrees\upgrade-20261103\wt-r26"
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
mvn -B -ntp test -pl authority-service -am "-Dmaven.repo.local=$(pwd -W)/.tools/m2"
# 仅 R-26 五类：
mvn -B -ntp test -pl authority-service -am "-Dmaven.repo.local=$(pwd -W)/.tools/m2" -Dtest='TestTicket*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

## 3. E2 部署实录（192.168.1.2）

| 步骤 | 命令/动作 | 结果 |
| --- | --- | --- |
| 1 | `mvn package -pl authority-service -DskipTests`（本地） | `authority-service-0.1.0-SNAPSHOT.jar`（sha256 `e03ac683…151d`） |
| 2 | scp jar → `/home/agent-platform/src/authority-service/target/` | OK |
| 3 | `docker build -f deploy/Dockerfile.backend --build-arg SERVICE=authority-service --build-arg JAVA_BUILD_IMAGE=eclipse-temurin:21-jdk-jammy --build-arg JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy -t agent-platform-v12/authority-service:0.1.0 .`（context=/home/agent-platform/src） | image `957f9a2c6f7d` |
| 4 | `docker tag … 127.0.0.1:5000/agent-platform/authority-service:0.1.0 && docker push` | digest `sha256:06a198a7…f368` |
| 5 | compose-test.yaml authority 服务段 +2 env（备份 `compose-test.yaml.bak-r26`，整文件传回传去，diff 见 §5-6） | md5 5a5104a7…4dd9 |
| 6 | `docker compose --env-file .env.test -f compose-test.yaml up -d authority-service` | Recreated+Started |
| 7 | gateway overlay `/home/agent-platform/overlays/gateway.yml` +portal 段（备份 `.bak-r26`）+ `up -d --force-recreate platform-gateway` | Started，health 200 |

**compose authority 段新增（先例之外的两行，均属双条件本身）**：`AGENT_AUTHORITY_TEST_TICKET_ENABLED: "true"` + `SPRING_PROFILES_ACTIVE: test`。后者是必须补的：authority 服务此前**不挂** `x-backend-env`（无 SPRING_PROFILES_ACTIVE），`@Profile("test")` 永不成立——卡面只点名了开关 env，但契约的"双条件"缺它不可；已实测注释写进 compose。

**gateway overlay（服务器侧部署配置，仓库零改动）**：R-26 DoD-2/3 的"网关验签"要求 gateway 的 portal 域 issuer/jwks 指向 authority。E2 此前 gateway 只配了 authority 域（服务票链），portal 链未启用（baseline 实测：有效 USER 票 → 401 SYS-AUTH-401，`02-baseline-v1-before-portal-enabled.txt`）。按 AuthorityProperties 的校验口径补齐 portal 段（issuer/jwks=authority:18086、audience/client-id 与 authority identity 档案逐字一致、role-allowlist=ADMIN,BUILDER,OPERATOR,USER、attribute-allowlist 两键+取值白名单、scope-allowlist=platform.access）。**回滚=还原 `.bak-r26` + 重启 gateway。**

## 4. E2 验证实录（原始输出在服务器 `/home/agent-platform/evidence/R-26/`）

```
== [01] mint USER ticket == 200（01-mint-user-ticket.txt）
== [02] header/claims ==
{"alg":"RS256","typ":"at+jwt","kid":"2026-10"}
{"iss":"http://127.0.0.1:18086","aud":["agent-platform-portal"],"client_id":"gateway",
 "token_use":"user","tenant_id":"demo","sub":"tester-1","roles":["USER"],
 "attributes":{"region":["A"],"dataLabels":["public"]},"scope":"platform.access",
 "iat":1795109011,"nbf":1795109011,"exp":1795109611,"jti":"tt-2ac3861e…","origin_authentication_id":"oa1:3ee8…"}
== [03] GET /v1/apps/satellite-planning-assistant/sessions?mine=true（DoD 字面路由）
→ 403 {"code":"SYS-AUTH-403","message":"入口未获授权"}   （缺口，见 §5-1）
== [04] POST /admin/apps/satellite-planning-assistant/runs（portal 受管形状 POST）
→ 503 {"code":"PUBLISHED_RUN_UNAVAILABLE","message":"发布运行入口尚未就绪"}
   —— 与仓内 GatewayIamPortalHttpTest.portalTicketPassesTheAdminSurface 完全同判据：
     503=验签+安全链+授权+ingress 捕获（Purpose.RUN claim）全部通过后到达控制器，
     503 是"发布运行代理不在最小部署里"，不是鉴权拒绝。
== [05] 篡改票（末位字符替换）→ 401 SYS-AUTH-401   （验签失败对照）
== [06] 过期票（ttlSeconds=1，+2s）→ 401 SYS-AUTH-401   （§9.2 反例部署级实证）
== [07] 反例 4 发：SERVICE→400（文案点名 client_credentials）；SUDO→400；
        prod-x→400；7200→400   （07-negative-*.txt）
== [08] docker logs agent-platform-authority | grep TEST_TICKET_MINTED
→ AUDIT TEST_TICKET_MINTED subject=tester-1 roles=[USER] tenant=demo
  jti=tt-2ac3861ea5b442f08cfb0177d5226e3e issuer=http://127.0.0.1:18086 iat=1795109011
  （jti/iat 与 [01] 响应体逐字相等；09 步交叉核对）
== [10] 补充探针（10-gateway-supplementary-probes.txt）：
  /auth/session（USER 票）→ 403 IAM_AUTHORITY_FORBIDDEN —— 票过验签，gateway 调
    authority operation=session 被拒（E2 authority identity 模块关，§9.1⑧ 既定口径）；
  /v3/api-docs（USER 票）→ 502（springdoc 代理非本卡范围，仍 ≠401）。
```

**验签通过的三段式论证（E2）**：篡改/过期票=401（验签失败语义），有效票=403/503（验签成功后卡在路由/能力层）——401 与 403/503 的分界线正是 TrustedJwtDecoder 的验签结果；叠加本地 DoD-2 的解码级闭环（同 issuer/jwks 配置下 decode 成功 + claims 映射断言），issuer/kid/aud 三者一致的最终判据成立。

## 5. 诚实登记项（偏差/口径/缺口）

1. **DoD-3 的 `GET /v1/.../sessions 200` 不可达（先于本卡的平台形状限制，非票的问题）**：E2 gateway 为 iam 模式，portal ingress 过滤器只注册在 `/admin/*` 与 `/auth/session`（`GatewayIamPortalConfiguration` #197-219 `addUrlPatterns`），且 `GatewayIngressBoundary.route()` 只承认 RUN/SESSION/STATUS/EVENTS 四种受管形状（`GatewayIngressBoundary.java` #108-121）；`GatewayController.proxy` 的 iam 分支（`GatewayController.java` #65-83）对一切 `/v1/**` 先 `GatewayIngressBoundary.claim(req)`，该 claim 要求 Purpose.RUN（POST /admin/apps/{app}/runs）——因此 **GET /v1/** 在 iam 模式必然 403（入口未获授权），与票据有效性无关**（baseline 401→403 的变化证明票本身已过验签）。修它需要动 platform-gateway 代码/形状登记，属其他卡（R-28/SEC 或新卡），本卡红线禁止。已给等价实证（§4 [04] 与本地 DoD-2）。另注意：开 delegation（让 POST runs 走到底）被 §9.1⑧ 明确禁止（E3 复核）。
2. **aud/issuer 口径选择依据**：卡面写"读 TicketService 的门户/用户票 aud 口径"——TicketService 只签 workload 服务票（aud=`urn:agent-platform:service:<target>`）；门户用户票的实际签发点是 `SessionService`，其 `iss=IdentityProperties.issuer()`、`aud=[IdentityProperties.audience()]`。本端点取后者（同一组配置值，不复制），E2 取值 `http://127.0.0.1:18086` / `agent-platform-portal` / `gateway` / `platform.access`，与 gateway overlay portal 段逐字一致——"与生产票同 issuer/同 kid"由共用 `KeyRing`+共用配置构造性成立。
3. **attributes 签名口径（票面只签 region/dataLabels）**：请求按契约收四键并校验（bizSystemCode/userId 恰一值），但签进票的只有 region/dataLabels。依据：平台 `ClaimCeiling.attributes` 要求归属键在 token 与网关白名单两侧**要么都带、要么都不带且恰一相等**（`ClaimCeiling.java` #27-35）；userId 随测试 subject 变化，无法与静态白名单钉扎共存（钉扎口径下测试票被网关拒——闭环测试 `ownershipPinnedConfigRejectsTestTicketsByDesign` 实证）。平台自有 portal 夹具 `LoopbackAuthority.portal()` 与 gateway 默认 `attribute-allowlist=region,dataLabels` 同口径。需要归属键钉扎的单用户固定场景另行登记扩展。
4. **compose authority 段加了 2 个 env 而非卡面的 1 个**：`SPRING_PROFILES_ACTIVE: test` 是双条件之二，authority 服务此前从不挂 test profile（不挂 backend-env）——不加则端点永不注册。diff 仅 authority 服务段，其他段落零触碰（diff 已留档）。
5. **gateway overlay 是服务器侧部署配置变更（仓库零改动）**：为完成 DoD-2/3 的"网关验签"必须启用 portal 链；这是 R-26"解锁 PR-A/SEC-07"的一部分（a 方案改的是测试支撑面）。备份+回滚路径在 §3。
6. **admin-common 2F/1E 为基线既有**（见 §2），基提交干净检出复现；与本卡 diff（仅 authority-service）零交集。
7. **E2 各业务容器 docker 健康状态 "unhealthy"** 为部署栈既有状态（entrypoint 覆盖+readiness 探针口径，§9.1 偏差②邻域），本次部署前后一致；业务探针（/actuator/health、jwks、/test/tickets）均 200。
8. **审计行 iat/jti 与票面一致性**只在"签发成功"路径断言；拒签路径不写 TEST_TICKET_MINTED（只有 token 端点的既有 authority.audit deny 行），与"审计失败即拒签"语义一致。

## 6. 变更清单与回滚

仓库（commit 903d0937，未推送）：
- `authority-service/src/main/java/cn/agentplatform/authority/api/TestTicketController.java`（新增，含注册双条件+审计 fail-closed）
- `authority-service/src/main/resources/application.yml`（+test-ticket 开关段，默认 false）
- 新增测试 5 类（`authority-service/src/test/...`：TestTicketEndpointTest / TestTicketAuditFailClosedTest / TestTicketTrustedDecoderLoopTest / TestTicketDisabledTest / TestTicketProfileGateTest）
- 本证据目录 `docs/evidence/2026-11-20-R-26/`

服务器 192.168.1.2（回滚顺序）：
1. `cp /home/agent-platform/overlays/gateway.yml.bak-r26 /home/agent-platform/overlays/gateway.yml && cd /home/agent-platform && docker compose --env-file .env.test -f compose-test.yaml up -d --force-recreate platform-gateway`
2. `cp /home/agent-platform/compose-test.yaml.bak-r26 /home/agent-platform/compose-test.yaml && docker compose --env-file .env.test -f compose-test.yaml up -d authority-service`
3. （可选）回滚镜像到旧 tag：registry 中 `authority-service:0.1.0` 旧 digest c94dc894c572 仍在

E2 侧证据（原始输出，与本地仓库证据互为镜像）：`/home/agent-platform/evidence/R-26/`（01–10 + verify.sh 复跑脚本）。
