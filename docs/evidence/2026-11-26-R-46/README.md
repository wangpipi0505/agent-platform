# R-46 已发布 CHAT 的控制台与独立运行

开发侧使用隔离的应用库和本地服务，正常会话签发入口对接真实 user-mgmt。平台 ADMIN 来自 demo 租户的授权记录。通过项目现有模型代理调用已配置的 DeepSeek（deepseek-flash），测试应用及消息只含公开测试文本。

- [真实管理员会话](identity.json)、[浏览器截图](console.png) 和 [网络响应](browser-network.json) 对应 `/console/#/apps/r22e2-real-v2/run`。创建会话、读快照、订阅 SSE、提交轮次均返回 200。
- [运行及模型行](browser-db.json) 列出页面发送和独立 `POST /admin/apps/r22e2-real-v2/runs` 的两个 runId。两条运行均为 SUCCEEDED，输出 R46_OK，DeepSeek 调用也均为 SUCCEEDED。
- [修复前](red-runs.json) 与 [修复后](green-runs.json) 对照隐式版本、当前版本、旧版本、应用范围外的主体。旧版本返回 409 APP_PUBLICATION_CHANGED，未授权应用返回 403。授权范围外的主体即使提交旧版本，也得到 403。[接入票的角色映射](role-mapping.json)显示 USER 映射为 AGENT_OPERATOR，运行与委派中的角色一致。
- [撤销记录](browser-network.json) 显示旧登录读取 401，独立登录读取 200。[他人会话拒绝、跨应用拒绝及数据库审计](../2026-11-26-R-42/second-subject-green.json) 使用同租户的两位隔离测试主体。

## 复测入口

使用正常门户宿主提供的 admin 会话打开上述控制台地址，发送 `Reply R46_OK`。再用同一登录向 `/admin/apps/r22e2-real-v2/runs` 提交 `{"versionNo":1,"inputs":{"message":"Reply R46_OK"},"requestId":"<新业务号>"}`。用两次回执中的 runId 关联 `engine.run.public_id` 与 `model.call_log.run_id`。独立开发库中的发布版本为 1。

## 合并后的相邻回归

Java 21、PostgreSQL 隔离库。为 PostgreSQL 用例配置其开发库连接后，在代码根目录运行以下命令。前端命令在 web 目录运行。网关 60 项、应用管理 35 项、引擎 68 项全部通过，前端 638 项通过，类型检查及三个生产构建完成。[按用例类列出的原始输出](adjacent-tests.txt)。

```sh
mvn -pl platform-gateway,app-management,engine-runtime -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GatewayPublishedRunProxyTest,GatewayIamPortalHttpTest,PublishedMetadataHttpTest,PublishedPayloadHttpTest,ChatSessionsTest,IntegrationTaskReadPostgresTest,OutputPolicyPostgresTest,RunSchedulerPostgresTest,RunSchedulerTimingTest,SessionEventsEndpointTest
cd web
npm test
npm run build
```
