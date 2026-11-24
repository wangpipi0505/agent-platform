# R-39 授权存储开发证据

D3 已确认。授权关系、角色能力、能力页面映射及接入档案的角色映射迁移保存在分支 `r39-platform-role-storage`。构建期查表判权、管理接口和页面仍待接入。

JDK 21 + 独立 PostgreSQL 16.15 数据库 `agent_platform_role_storage_test`。执行 `mvn -B -ntp -pl app-management -am -Dtest=PlatformRoleStorageTest -Dsurefire.failIfNoSpecifiedTests=false test`。Flyway 执行 V124，验证同主体跨租户授权各自有效、空应用范围默认值、重复授权拒绝、回收状态约束、固定运行角色词表和非法 JSON 拒绝。1 例通过。

V125 新增追加式授权审计。首个 ADMIN 由 `AGENT_BOOTSTRAP_ADMIN_SUBJECT=tenant:subject` 引导，空表检查、授权及审计同事务提交。两个实例并发引导只形成一条授权和一条 `BOOTSTRAP_ADMIN_GRANTED` 事件，后续配置变更不加授权，普通更新与删除不能改写审计。验证命令为 `mvn -B -ntp -pl app-management -am -Dtest=PlatformRoleStorageTest,PlatformAdminBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test`，2 例通过。

空库正常依序迁移，已记录 V901 的开发库按生产 app Flyway 的 out-of-order 配置补迁 V125，并保留既有数据。
