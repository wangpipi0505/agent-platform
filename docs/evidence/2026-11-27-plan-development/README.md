# R-22 本人计划权限开发轮次

上一轮冻结候选 `76fc3252` 的真实门户可创建 PLAN 任务，但本人详情与修订各返回 403。[E2 原始失败回执](../2026-11-27-portal2-R-22/pr-a-plan.json)。

本次使用既有公开接口。读、修订、确认和执行按现行接口设计 §6 分别判定。引擎从已验主体及持久化任务确定应用，调用 app 的实时授权查询；同一主体在另一个应用持有的 USER 权限不能改变其仅有 OPERATOR 授权的应用。

验证命令（JDK 21，PG 16 使用独立测试库）：

```text
mvn -pl platform-gateway,engine-runtime,app-management -am test -Dtest=PortalTaskRoutesTest,PortalClaimMapperTest,GatewayIamPortalHttpTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl app-management -am test -Dtest=PlatformRoleStorageTest,PlatformBuildEntryTest,PlatformAdminBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl engine-runtime -am test -Dtest=PlansApiTest -Dsurefire.failIfNoSpecifiedTests=false
```

[路由和网关](routes.txt) · [按角色和应用范围判定、撤销](app-grants.txt) · [计划修订、确认、执行重放及原因入库](engine-plans.txt)。
