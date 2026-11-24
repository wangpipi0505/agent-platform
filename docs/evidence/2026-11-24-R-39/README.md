# R-39 授权存储开发证据

D3 已确认。授权关系、角色能力、能力页面映射及接入档案的角色映射迁移保存在分支 `r39-platform-role-storage`。

JDK 21 + 独立 PostgreSQL 16.15 数据库 `agent_platform_role_storage_test`。执行 `mvn -B -ntp -pl app-management -am -Dtest=PlatformRoleStorageTest -Dsurefire.failIfNoSpecifiedTests=false test`。Flyway 执行 V124，验证同主体跨租户授权各自有效、空应用范围默认值、重复授权拒绝、回收状态约束、固定运行角色词表和非法 JSON 拒绝。1 例通过。

V125 新增追加式授权审计。首个 ADMIN 由 `AGENT_BOOTSTRAP_ADMIN_SUBJECT=tenant:subject` 引导，空表检查、授权及审计同事务提交。两个实例并发引导只形成一条授权和一条 `BOOTSTRAP_ADMIN_GRANTED` 事件，后续配置变更不加授权，普通更新与删除不能改写审计。验证命令为 `mvn -B -ntp -pl app-management -am -Dtest=PlatformRoleStorageTest,PlatformAdminBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test`，2 例通过。

空库正常依序迁移，已记录 V901 的开发库按生产 app Flyway 的 out-of-order 配置补迁 V125，并保留既有数据。

## 应用构建判权与管理页

应用工作台按平台能力和应用范围执行权限检查。门户会话从 app 读取平台角色、能力及页面路径。管理页支持用户检索、角色分配、应用范围、保存和回收。V126 加入初始能力配置，V127 记录能力与页面配置改动。

- 改动前，同一测试票自带外部 `ADMIN` 时可通过应用写入口。改动后，PostgreSQL 回归证明缺平台授权返回 403，授予 scoped BUILDER 后允许目标应用，其他应用和其他租户拒绝，回收后同一身份再次返回 403。覆盖配置映射立即生效、配置审计、重复保存和回收。
- Java 定向回归共 29 例通过。网关使用本地 HTTP 和 authority 夹具验证真实委派调用，数据测试使用独立 PostgreSQL，用户检索和命名查询联系本地真实 user-mgmt 实例。
- Vue 类型检查、40 例定向前端回归和管理台构建通过。Playwright 使用本地页面和 HTTP 夹具验证实际界面的检索、选角色、选应用、保存、回收，以及两次写请求的主体和应用范围。
- [输出摘录](platform-build-result.txt)。本地原始输出为 `raw-build-entry-before.log`、`raw-platform-audit.log`、`raw-vue-final.log`、`raw-browser-verified.log`。

```powershell
# Java 使用 JDK 21，并按平台存储测试要求注入隔离数据库与目录测试凭据。
mvn -B -ntp -pl platform-gateway,app-management -am '-Dtest=GatewayIamPortalHttpTest,GatewayIngressBoundaryTest,PlatformBuildEntryTest,PlatformRoleStorageTest,PlatformAdminBootstrapTest,PlatformUserDirectoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
docker compose -f deploy/compose-test.yaml config --no-interpolate --quiet
# 从 web 运行以下命令。
npm exec -- vue-tsc --noEmit
npm exec -- vitest run tests/portal-auth.test.ts tests/navigation-policy.test.ts tests/permission.test.ts
npm exec -- vite build --config admin/vite.config.ts
# 从 web 另起终端运行 npm run dev -- --host 127.0.0.1 --port 25176 后执行。
npm exec --yes --package=@playwright/cli -- playwright-cli -s=agent-r39 open about:blank
npm exec --yes --package=@playwright/cli -- playwright-cli -s=agent-r39 run-code --filename ../docs/evidence/2026-11-24-R-39/browser-check.js
```

## 本地 IAM 授权联验

- 网关在模型供应商和模型条目的精确管理入口核验平台 `model.manage`，然后为模型服务签发绑定当前主体的构建委派。模型管理方法使用该能力判定。
- 本机 authority、网关、app 和 model 服务使用 PostgreSQL，并访问真实 user-mgmt 的用户检索及主体状态接口。门户用 authority 测试档签发的 RS256 票。
- `operatorA` 的外部票带 `ADMIN`，缺平台授权时创建模型供应商和应用均返回 `403 PLATFORM_CAPABILITY_DENIED`。本地已授权的管理员持外部 `USER` 票创建模型供应商返回 200。
- 对同一张 `operatorA` 票，检索用户、授予 scoped `BUILDER`、读门户会话、访问指定应用、拒绝访问其他应用、回收后再次拒绝均在真实服务完成。管理员从授权审计查询到保存和回收两条事件。
- Java 网关及服务授权链 24 例通过。 [联合验证结果](joined-build-result.txt)

技能、插件等其余构建面权限接入及 E2 联验继续跟踪。
