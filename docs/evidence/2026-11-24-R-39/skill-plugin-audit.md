# R-39 技能、插件、MCP 与运行审计联验

2026-11-24，本机 IAM 联验使用正式 authority-service、gateway、app-management、skill-registry、monitor 和 engine，使用隔离 PostgreSQL 及 user-mgmt 的实际目录查询。其中门户票使用测试环境签发入口，两次授权对照使用同一张门户票。

| 操作和授权条件 | HTTP | 结果 |
| --- | --- | --- |
| 外部 ADMIN 缺少平台授权，新增技能、插件来源或 MCP 连接 | 403 | `PLATFORM_CAPABILITY_DENIED` |
| 外部 ADMIN 只有限定应用的 ADMIN，新增全局技能 | 403 | `PLATFORM_CAPABILITY_DENIED` |
| 同票新增全局 BUILDER，创建及读取技能草稿 | 200 | 草稿可读 |
| 同票同时拥有全局 BUILDER 及限定应用 ADMIN，新增带凭据引用的全局技能 | 403 | `SYS-APP-4031` |
| 回收全局 BUILDER，用原票读取已创建的技能草稿 | 403 | `PLATFORM_CAPABILITY_DENIED` |
| 外部 USER 持全局平台 ADMIN，新增插件来源、创建与读取 MCP 草稿 | 200 | 登记成功 |
| 外部 ADMIN 缺少平台授权，读取运行审计 | 403 | `PLATFORM_CAPABILITY_DENIED` |
| 同票新增全局 OPERATOR，读取运行审计 | 200 | 由 monitor 委派到 engine，返回已存运行事件 |
| 同票尝试读取审批，回收 OPERATOR 后再读运行审计 | 403 | 分别由入口权限与平台授权拒绝 |

测试命令在项目根目录执行，JDK 21。数据库使用 `AGENT_DB_URL_ROLE_STORAGE` 指向隔离库 `agent_platform_role_storage_test` 并通过环境变量设置测试数据库账号。

```powershell
mvn -B -ntp -pl app-management,platform-gateway,skill-registry,authority-service,monitor,engine-runtime,model-gateway -am '-Dtest=PlatformBuildRoutesTest,PlatformRoleStorageTest,ServiceAuthorizationTest,GatewayIamPortalHttpTest,RouteCoverage*Test,IntegrationCredentialVerificationTest,IntegrationPolicyTest,EvaluationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.jar.forceCreation=true' package
```

定向测试 62 例通过，七个服务模块打包成功。新增路由测试首先复现技能语义目录缺少 IAM 路由，修复后通过。首次本地联验发现 authority-service 旧包带入旧目录，强制重建服务 JAR 后复验通过。

当前证据覆盖已经接线的构建操作及运行审计读取。E2 部署复验及使用面中断事件的产生由对应联验另行记录。
