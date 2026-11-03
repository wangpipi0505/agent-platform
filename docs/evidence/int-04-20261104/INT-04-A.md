# INT-04（A 席）：可信身份与短票接入包

- 日期：2026-11-04
- 席位：A（AP 后端）
- 基线 HEAD：`8b0c7313`（父提交）
- 范围：Authority 身份源档案（协议白名单 + 有限字段映射 + 租户/环境/过期/撤权语义）、网关短票的
  environment 绑定与 §6 错误码、门户会话 503 的可解释阻塞、`contracts/assistant-host-ticket.md`、
  `deploy/integration/README.md`
- 未触：`app-management`（迁移/控制器/凭据签发与撤销）、`GatewayIngressBoundary`、`ServiceTokens`、
  `SecurityConfiguration`、前端、任何既有测试断言

## 一、原责任 → 新责任

| 关注点 | 领取时现状 | 本卡交付 |
| --- | --- | --- |
| 身份源 | 只有硬编码 user-mgmt 字段（userName/status/roles[].roleKey/dataScope） | 协议枚举（仅 `USER_MGMT`）+ 八个目标的点分映射；未实现协议拒绝启动；映射驱动的状态/角色/部门/租户校验 |
| 租户上限 | 恒为配置 `tenant`，身份源返回值不校验 | 映射了 `tenant` 时必须与配置逐字相等，否则 403 且不签发 |
| 环境绑定 | 票据无环境维度 | 上限来自 `claim_limits.attributes.environment`；出票必填且逐字命中；`check` 每次复核；撤权/收紧 → 401 `INTEGRATION_TICKET_EXPIRED` |
| 客户端自报身份 | 顶层 tenant/principal 被静默忽略 | 顶层或 attributes、`pageContext` 出现即 403 `INTEGRATION_CLIENT_IDENTITY_REJECTED` |
| 来源不符 | 403 `ORIGIN_DENIED`（未登记） | 403 `INTEGRATION_SOURCE_NOT_ALLOWED`（§6 登记码） |
| 身份源不可达 | 401 `INVALID_CREDENTIAL`（把传输故障当凭据错误） | 503 `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`，零业务执行；门户路径两种 503 统一改写，其它 503/403 原样 |
| 撤销/策略变化 | 401 `TICKET_POLICY_CHANGED` | 401 `INTEGRATION_TICKET_EXPIRED`（§6 登记码），同一判定覆盖撤权、停用、环境收紧、摘要变化 |
| 接入文档 | 无；只有 `assistant-integration.md` 的原则 | 新增宿主短票协议与部署说明，明确未实现项 |

## 二、改动文件

| 文件 | 行数变化 | 说明 |
| --- | --- | --- |
| `admin-common/src/main/java/cn/agentplatform/common/authority/IntegrationClaims.java` | +2 | 新增 `ENVIRONMENT_ATTRIBUTE="environment"`（仅新资源引用，无行为变化） |
| `authority-service/src/main/java/cn/agentplatform/authority/identity/IdentityProperties.java` | +92/-18 | 身份源档案：protocol/mapping/activeStatus/environment；协议与路径校验；`profileSummary()` 脱敏 |
| `authority-service/src/main/java/cn/agentplatform/authority/identity/IdentityMapping.java` | 新增 138 | 有限映射器：八个目标、点分路径（≤3 段、一层 `[]`）、默认映射、kebab-case 归一 |
| `authority-service/src/main/java/cn/agentplatform/authority/identity/SessionService.java` | +92/-41 | 状态/用户名/角色/部门/数据范围/租户全部改走映射；角色支持字符串数组与角色对象 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/IntegrationPolicy.java` | +104/-8 | 环境上限与绑定复核、客户端身份拒绝、`pageContext` 拒绝、§6 来源码 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/IntegrationAccess.java` | +65/-22 | 身份类头拒绝、环境头、凭据源 4xx/5xx 分流、`check` 用 `bound` 与 §6 码 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/PortalSessionController.java` | +21/-4 | 503 改写为 `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`，其余原样 |
| `authority-service/src/test/java/cn/agentplatform/authority/IdentityProfileTest.java` | 新增 119 | 7 例：协议/环境/映射校验、摘要脱敏、路径解析 |
| `authority-service/src/test/java/cn/agentplatform/authority/IdentityProfileSessionTest.java` | 新增 198 | 4 例：映射驱动会话、租户不符、空用户名、源 500 |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/IntegrationHostTicketTest.java` | 新增 340 | 14 例：绑定、环境、自报身份、pageContext、来源、撤权、收紧、跨 app、503、过期、两用户 |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/api/PortalSessionDegradeTest.java` | 新增 43 | 4 例：两种 503 改写与两类原样 |
| `contracts/assistant-host-ticket.md` | 新增 205 | 宿主短票协议（v0.1，未激活） |
| `deploy/integration/README.md` | 新增 168 | 同源代理/BFF 部署、身份源档案、核对清单、验证与回退 |

## 三、不变量

1. 票据恒绑定 `tenant_id`（凭据记录）+ `sub`（`biz:user`）+ `app_code` + `biz_system_code` +
   `attributes.environment`（档案声明上限时）+ 300 秒有效期；`check` 每次请求重读档案，撤权/停用/
   收紧立即 401 `INTEGRATION_TICKET_EXPIRED`，不等待 JWT 过期。
2. `origin` 只做逐字相等校验（scheme/host/port 全等），失败 403 `INTEGRATION_SOURCE_NOT_ALLOWED`；
   origin 本身从不构成调用者身份。
3. 客户端自报 `tenant/tenantId/tenant_id/principal/principalId/principal_id`（顶层或 attributes）与
   `pageContext` 中的身份/凭据字段一律 403 `INTEGRATION_CLIENT_IDENTITY_REJECTED`，不静默覆盖。
4. 平台角色（`ADMIN/EDITOR/SERVICE/GATEWAY_AUTH`）永不出现在集成票中；角色按档案上限求交。
5. 环境上限为空（本卡前签发的旧凭据）保持"不绑定环境"；一旦声明，出票必填且逐字命中
   （否则 403 `CLAIM_LIMIT_EXCEEDED`），复核不通过 401 `INTEGRATION_TICKET_EXPIRED`。
6. 身份源协议只接受 `USER_MGMT`；`OIDC_USERINFO`/`SIGNED_JWT` 配置即拒绝启动；字段差异只能通过
   `mapping` 的八个目标 + 点分路径表达，不支持脚本/表达式/回调。
7. 身份源租户（映射了 `tenant` 时）必须等于档案 `tenant`，否则 403；签发的 `tenant_id` 永远取配置值。
8. 身份源/授权服务不可达 → 503 `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`（门户两种内部码统一改写），
   `retryable=false`，零业务执行；不降级 dev/共享账号，也不退化成 403。
9. 凭据源 4xx → 401 `INVALID_CREDENTIAL`，连接失败/超时/5xx → 503；不把传输故障包装成凭据错误。

## 四、命令与结果

环境：Windows + JDK 21.0.12 + Maven 3.9.16；真实 PG `127.0.0.1:35432/agent_platform_test`，
账号与口令只从 `.tmp-devdb/dev-ap.env` 注入，不回显、不入库。

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -pl platform-gateway -am -Dtest=IntegrationHostTicketTest -Dsurefire.failIfNoSpecifiedTests=false test` | **12/12 ERROR**：`IllegalStateException: SECURITY_MODE_REQUIRED`（测试上下文缺 `agent.security.mode`）；既有 `IntegrationSecurityTest` 同因 6/6 ERROR，属基线环境事实，未改其断言 |
| 2 | 同上（测试属性补 `agent.security.mode=dev-hmac`、`service-id=gateway`、`dev-auth=true`、`server.address=127.0.0.1`） | 10/12：`pageContextCannotCarryIdentityIntoTheActor` 期望 403 实得 200；`unknownOrigin…` 解析到纯文本 CORS 403 |
| 3 | 修复 `rejectClientIdentity` 在无 attributes 时提前返回的缺陷；未知来源用例改用部署级 CORS 已放行的 `http://localhost:15173` | 12/12 通过 |
| 4 | 追加两用户分离、过期票据两例后 `mvn -q -pl platform-gateway -am -Dtest='IntegrationHostTicketTest,PortalSessionDegradeTest' … test` | `IntegrationHostTicketTest` 14/14、`PortalSessionDegradeTest` 4/4 |
| 5 | `mvn -q -pl authority-service,platform-gateway -am -Dtest='SessionEndpointTest,IdentityProfileTest,IdentityProfileSessionTest,IntegrationHostTicketTest,PortalSessionDegradeTest' … test` | 7/7、7/7、4/4、14/14、4/4，共 36 例全绿；既有 `SessionEndpointTest` 回归绿 |
| 6 | `mvn -q -DskipTests compile`（全后端 reactor） | exit 0，无输出 |
| 7 | 真实 PG 探针 `java -cp postgresql-42.7.5.jar .tmp-devdb/Int04PgProbe.java` | `DATABASE=agent_platform_test`；`policy_revision`/`environment` 列与 `integration_config_release` 表存在；`TWO_PRINCIPALS=alice,bob`；`ENVIRONMENT_CEILING=["prod"]`；`REVOCATION_ISOLATION=partner-a:REVOKED:rev2,partner-b:ACTIVE:rev1`；`ROLLBACK_ROWS=0` |

证据文件：`target/surefire-reports/…{IntegrationHostTicketTest,PortalSessionDegradeTest,IdentityProfileTest,IdentityProfileSessionTest,SessionEndpointTest}.txt`。

## 五、未覆盖

1. 浏览器端到端（真实 iframe/SDK、CSP、跨域 Cookie）未在本卡运行；只覆盖服务端协议与错误码。
2. 真实 Authority + 真实 app-management + 真实 PG 的跨服务 HTTP 链路未跑通：app 侧既有 PG 测试硬编码
   `127.0.0.1:25432`（当前未监听），本次只用 gateway/authority 的 `@SpringBootTest` 桩与独立 JDBC 探针，
   未改 app 测试基址。
3. IAM 模式网关未做 `/embed/ticket` 实链路验证（`GatewayIngressBoundary` 当前不放行，见缺口 1）。
4. `Device/刷新令牌`、多环境同时出票、限流与撤权并发未覆盖。

## 六、契约缺口（只登记，未私改）

1. **IAM 入口**：`GatewayIngressBoundary.route()` 只放行 `/admin/apps/{code}/runs`、`/auth/session`、
   `/admin/runs/{id}`、`/admin/runs/{id}/events`；`/embed/ticket`、`/v1/**` 在 iam 网关 403 `SYS-AUTH-403`。
   本卡短票 provider 实际只服务非 IAM 的可信 BFF；放开入口属 admin-common 边界变更，超出白名单。
2. **环境一致性**：网关无 `app.integration_profile` 内部读取端点，无法自动断言
   `claim_limits.attributes.environment` 与 `IntegrationProfile.environment` 相等；当前靠管理员填写与核对。
3. **过期票错误码**：JWT 过期在解码器处拒绝，返回 `SYS-AUTH-401` 而非 `INTEGRATION_TICKET_EXPIRED`；
   本卡只保证撤权/停用/收紧/摘要变化返回登记码。
4. **保留协议**：`OIDC_USERINFO`、`SIGNED_JWT` 未实现（authority 不引入资源服务器验签依赖），
   配置即拒绝启动；新增协议须先登记为适配器。
5. **白名单外改动**：新增两个测试目录与 `deploy/integration/` 属卡内必要产物；`IntegrationClaims` 仅加常量。

## 七、回退

`git revert <本卡提交>`：只回退六个产品文件、三个测试文件、两份文档；无迁移、无数据修复。
数据影响：不触碰 `app.integration_credential` 行；已签发短票在回退后由旧 `check` 语义判定（最长 300 秒），
撤销状态仍以 PG 为准；不清理审计与会话记录。
