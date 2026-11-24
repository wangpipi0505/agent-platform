# R-39 授权存储开发证据

D3 已确认。授权关系、角色能力、能力页面映射及接入档案的角色映射迁移保存在分支 `r39-platform-role-storage`。构建期查表判权、平台首个管理员引导、管理接口和页面仍待接入。

JDK 21 + 独立 PostgreSQL 16.15 数据库 `agent_platform_role_storage_test`。执行 `mvn -B -ntp -pl app-management -am -Dtest=PlatformRoleStorageTest -Dsurefire.failIfNoSpecifiedTests=false test`。Flyway 执行 V124，验证同主体跨租户授权各自有效、空应用范围默认值、重复授权拒绝、回收状态约束、固定运行角色词表和非法 JSON 拒绝。1 例通过。
