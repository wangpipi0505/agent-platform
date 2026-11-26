# R-47 发布元数据 HTTP 自测

开发工作树以 b6b4b1e5 为起点。通过网关真实监听端口提交运行，用签名主体换票，下游测试服务验证票据并使用生产 ApiResponse.error 生成错误响应。

| 用例 | 请求或条件 | 结果 |
| --- | --- | --- |
| publishedRunUsesTheNormalIamAssemblyAndReportsMissingVersions | 已发布、草稿、不存在、旧四字段错误体 | 200 且保留运行回执、409 APP_UNAVAILABLE、404 APP_NOT_FOUND、404 APP_NOT_FOUND |
| publishedRunReportsDeniedApplicationWithAuthorizationCode | 应用拒绝访问 | 403 SYS-AUTH-403 |

修复前的两个断言见 [red.txt](red.txt)。修复后 [网关 HTTP](GatewayIamPortalHttpTest.txt) 26 项、[运行代理](GatewayPublishedRunProxyTest.txt) 33 项通过。重跑命令如下。

```sh
mvn -pl platform-gateway -am test -Dtest=GatewayIamPortalHttpTest,GatewayPublishedRunProxyTest -Dsurefire.failIfNoSpecifiedTests=false
```
