# INT-02 子步1（A 席）证据：ADMIN 连接与环境绑定目录

- 卡：INT-02 子步1「ADMIN 批准连接与环境绑定目录」（D2 批 A 席）
- 依据：`contracts/configuration-profile.md` §2/§3/§8、`contracts/assistant-integration.md` §6/§8（AP 交接提交 `6c137894`）
- 迁移号：V111（D2 批核发；app-management 现最大 V110）
- 日期：2026-11-03
- 范围声明：只做 app 侧 ADMIN 目录 + V111 迁移 + 权限目录；不做 resolve、不做 skill 受控入口、不做生成客户端同步、不做前端页面。

## 1. 基线

- 工作树：`代码/_worktrees/upgrade-20261103/agent-platform`，分支 `upgrade/20261103`
- 开工 HEAD：`57d843103f62fcdb53b02a57c864fde835c2272b`（已在 MNT-08 `fd73758d`、INT-01 `6c137894` 之上）
- 相关既有事实：`app.connection_profile*` / `app.environment_binding*` 在 V111 之前不存在；网关 `Routes.service` 对 `/admin/connection-profiles`、`/admin/environment-bindings` 会抛「此入口不存在」

## 2. 原责任 → 新责任

| 关注点 | 基线 | 本子步之后 |
| --- | --- | --- |
| 连接档案的存在与版本 | 无对象；连接信息散落在技能/集成配置里 | `app.connection_profile` + 不可变 `connection_profile_version`（`profile_rev` 递增） |
| 环境地址与凭据引用 | 无独立对象 | `app.environment_binding` + 不可变 `environment_binding_revision`，外键钉住 `(connection_profile_id, connection_profile_rev)` |
| 写幂等 | 无 | `app.config_write_request`（`tenant_id + request_id` 唯一；同号换内容拒绝） |
| 并发安全 | 无 | 更新语句携带 `current_rev` 做 CAS，未命中区分 404/409/400 |
| 网关可达 | `Routes` 抛「此入口不存在」 | 两条前缀归 `app`，权限仍由 app 侧 ADMIN 门与 `ServiceAuthorization` 决定 |
| 权限目录 | 无条目 | `agent-service-authority.json` 增加 12 条精确条目（禁 ANY/通配） |

## 3. 改动文件

- `app-management/src/main/java/cn/agentplatform/app/api/ConnectionProfileController.java`（新）：6 个 ADMIN 端点，鉴权先于 JSON 校验。
- `app-management/src/main/java/cn/agentplatform/app/api/EnvironmentBindingController.java`（新）：同上，绑定 6 个端点。
- `app-management/src/main/java/cn/agentplatform/app/application/ConnectionProfiles.java`（新）：档案的创建/修订/批准/停用；字段归一、凭据扫描、摘要计算；含被绑定复用的校验工具。
- `app-management/src/main/java/cn/agentplatform/app/application/EnvironmentBindings.java`（新）：绑定的创建/修订/批准/停用；引用完整性前置校验。
- `app-management/src/main/java/cn/agentplatform/app/infra/ConnectionProfileRepository.java`（新）：唯一写入口；事务、锁超时降级、`requestId` 幂等、`expectedRev` CAS、只追加修订。
- `app-management/src/main/resources/db/migration/V111__connection_profiles_and_env_bindings.sql`（新）：5 张表 + CHECK/jsonb 约束 + 外键；文件头写死语义与手写回滚。
- `admin-common/src/main/resources/agent-service-authority.json`（改，仅追加）：12 条 `gateway → app` 精确条目，`integrationAllowed=false`。
- `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java`（改，先登记后动）：`/admin/connection-profiles`、`/admin/environment-bindings` 两条前缀转发到 app。
- `app-management/src/test/java/cn/agentplatform/app/application/ConnectionProfilesRulesTest.java`（新，10 例，不依赖 DB）。
- `app-management/src/test/java/cn/agentplatform/app/infra/ConnectionProfileRepositoryTest.java`（新，6 例，真实 PG + 执行 V111 迁移，`AGENT_DB_PASSWORD` 门控）。

## 4. 不变量（本子步保证，后续子步不得破坏）

1. 修订不可变：任何内容变化只能追加 `*_rev+1`；旧修订行不 UPDATE、不 DELETE。
2. 写幂等：同一 `(tenant_id, requestId)` 只产生一次结果，重放返回首次响应且 `replayed=true`；同号不同载荷 409 `CONFIG_REV_CONFLICT`。
3. 并发 CAS：`expectedRev` 不匹配一律 409 `CONFIG_REV_CONFLICT`，不静默覆盖。
4. 引用完整性：绑定修订必须指向已存在的连接修订（应用层 400 `CONFIG_VERSION_UNKNOWN` + 数据库外键兜底）；停用档案不能被新绑定引用（400 `CONFIG_RELEASE_NOT_ACTIVE`）。
5. 无明文凭据：声明中出现 secret/password/token/apikey/credential 之类字段名即 400 `CONFIG_SECRET_PLAINTEXT_REJECTED`；`secretRef` 仅做 `scheme:ref` 形状校验。
6. 地址形状：`baseEndpoint` 只接受 https（本机回环可用 http），拒绝查询串/片段；来源策略只接受 origin。
7. 租户隔离：所有读写都带 `tenant_id`，同名对象在不同租户互不影响。

## 5. 命令与实际结果

环境：JDK 21.0.12（`JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`）、Maven 3.9.16、离线 `-o`。

1. 编译（含网关）+ 纯规则用例：
   `mvn -o -q -pl app-management,platform-gateway -am -Dtest=ConnectionProfilesRulesTest -Dsurefire.failIfNoSpecifiedTests=false test`
   结果：编译通过；`ConnectionProfilesRulesTest` 第一轮 10 跑 9 绿 1 红（明文凭据被"未知字段"抢先报 `INVALID_INPUT`），把凭据扫描提前到字段校验之前后第二轮 10/10 绿。
2. 真实 PG 用例（DEV 测试库 `127.0.0.1:35432/agent_platform_test`，账号 `dev_ap_test`，密码来自私有根，未入库）：
   `AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test AGENT_DB_USER=dev_ap_test AGENT_DB_PASSWORD=*** mvn -o -q -pl app-management -Dtest=ConnectionProfileRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`
   结果：`tests="6" errors="0" skipped="0" failures="0"`（1.266s）。用例在 `@BeforeAll` 里按 V111 文件本身建表，因此同时证明该迁移可执行。

## 6. 未覆盖 / 已知缺口

- 未跑全量回归与全量启动：`RouteCoverageVerifier`（启动期强制"每个 handler 有目录行"）与网关路由表只做了静态核对，需由 P 的全量构建/启动确认。
- 未做 Controller 层 MVC 用例：`Actor.current()` 依赖已验 JWT 的安全上下文，本子步未搭 MockMvc，ADMIN 门（`requireRole("ADMIN")` → 403 `SYS-APP-4031`）属既有公共组件行为，未在本卡重复证明。
- 未同步 `contracts/openapi.json` 与生成客户端（不在 D2 白名单；前端页面同样未做）。
- `resolve` 端点（`/internal/connection-profiles/{id}/versions/{rev}/resolve`）按领取单留给子步2。

### 契约缺口（只登记，未私改契约）

1. `configuration-profile.md` §2 只列字段名不列类型：本次实现固定 `authScheme={kind∈NONE/API_KEY/OAUTH2_CLIENT_CREDENTIALS/MTLS/SIGNED_REQUEST, headerName?}`、`identityPolicy={mode∈PLATFORM_PRINCIPAL/SERVICE_ACCOUNT, subjectSource?}`、`endpointPolicy={hosts?,pathPrefixes?,httpMethods?}`、`runtimeRef={runner?,namespace?,engine?}`、`healthPolicy={path?,intervalSeconds?,timeoutMs?}`，需契约侧确认或回写。
2. `assistant-integration.md` §6 缺「对象不存在」「状态迁移非法」两类码：本次映射为 404/400 `CONFIG_VERSION_UNKNOWN` 与 400 `INVALID_INPUT`；停用档案被引用映射为 400 `CONFIG_RELEASE_NOT_ACTIVE`。请在 §6 登记或给出正式码。
3. §8 建议的 `connection_profile_grant` 未建：批准即 `status=APPROVED`，无独立授权行；grant 是否随子步2（resolve/授权投影）落地需明确。
4. 绑定可否引用 `DRAFT` 档案契约未规定：本次允许（便于先建目录后批准），仅在引用 `DISABLED` 时拒绝；激活语义留给 resolve。
5. `environment` 取值集合未在契约中枚举：本次只校验 `^[a-z][a-z0-9-]{0,31}$`，未与 `publish_pin.environment` 的既有取值（默认 `production`）建立一致性约束。

## 7. 回退

- 代码：`git revert <commit>`（本子步为单个提交）。
- 数据：按 V111 文件头的手写 down 语句 `DROP TABLE IF EXISTS app.config_write_request, app.environment_binding_revision, app.environment_binding, app.connection_profile_version, app.connection_profile;`；因是新增表，回退不触碰任何既有表。
