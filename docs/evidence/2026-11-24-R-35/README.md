# R-35 门户运行入口复核

日期 2026-11-24。开发分支 `r35-portal-runtime-ingress`，契约提交 `7ae6fa59`。

## 失败基线

JDK 21 执行 `mvn -B -ntp -pl platform-gateway -am -Dtest=GatewayIamPortalHttpTest -Dsurefire.failIfNoSpecifiedTests=false test`。19 例中有 2 例失败，门户票访问会话集合实际返回 403。原始报告见 `baseline-surefire.txt`。

## 修复验证

在 JDK 21 和环回隔离 PostgreSQL 16.15 执行：

```text
mvn -B -ntp -pl platform-gateway,engine-runtime,app-management,authority-service -am -Dtest=GatewayIamPortalHttpTest,RoutesTest,ServiceAuthorizationTest,RouteCoverageVerifierTest,PublishedPayloadHttpTest,*Published*Client*Test,AuthorityDelegationEndpointTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl engine-runtime,authority-service -am -Dtest=RuntimeClientsPublishedHttpTest,TasksApiTest,TaskConfigReleasePinTest,ChatSessionsTest,DelegatedSubjectTokenTest,DelegatedForwardContractTest,DelegatedDisabledTest -Dsurefire.failIfNoSpecifiedTests=false test
```

第一轮执行 100 例，第二轮执行 43 例，零失败、零错误、零跳过。第二轮使用进程内测试专用 JWT 密钥与 UTF-8 子进程输出。原始 Maven 汇总及主要 Surefire 报告随本目录归档。

- 本地真实网关 HTTP 端口验证 USER 门户票六种集合请求，使用签名委派票、测试引擎 HTTP 接收器、原始查询与请求体。无票、过期、伪造、服务票、声明头、管理写入以及未登记路径拒绝。
- 真实 MVC 接收方验证委派票不能跨应用使用，编码变体也被拒绝。独立 PG 的 app 接收方验证发布读取按应用绑定，在匹配时返回当前发布并保留原运行版本绑定的回归。
- E2 部署、完整 SEC-01 和真实门户联调由环境验收执行。IAM 配置需要启用既有 `AGENT_AUTHORITY_DELEGATION_ENABLED`。
