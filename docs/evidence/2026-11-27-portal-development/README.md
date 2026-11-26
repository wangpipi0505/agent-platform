# 门户身份与平台应用范围回归

基线代码 44d9f906。新回归先在原实现复现外部角色被委派、范围外会话未按平台授权拒绝，再在修复后复跑。身份源返回的角色可继续用于身份资料，应用授权按当前租户、主体及 app 授权表判定。访问已有会话也检查当前应用范围。

JDK 21、Maven 离线库。定向运行 ActorSubjectSnapshotTest、DelegatedSubjectTokenTest、SessionEndpointTest、IdentityProfileSessionTest、GatewayIamPortalHttpTest、GatewayPublishedRunProxyTest。另在本机临时 PostgreSQL 16 执行 PlatformRoleStorageTest、PlatformAdminBootstrapTest、PlatformBuildEntryTest，使用各自专用库名。网关 HTTP 套件每例重置回环夹具的限量授权集合。

[修复前](contract-red.txt) · [相邻回归](contract-verified.txt) · [平台授权数据库测试](app-grants.txt) · [最终范围判定与网关 HTTP](final-scope.txt)。SessionEndpointTest 中两项依赖专属环境的用例在该 Maven 轮次跳过，E2 登录、代查和撤销另行取证。
