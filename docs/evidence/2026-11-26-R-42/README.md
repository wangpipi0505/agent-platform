# R-42 门户会话开发验证

## 冻结候选实测

[浏览器三轮](e2-browser-three.json)、[截图](e2-chat.png) 与 [数据库和拒绝审计](e2-db-audit.txt) 记录同一条浏览器链路。另一个同版本会话保留 [三个真实终态](e2-session-cursor.json) 及 [断流续读](e2-replay.json)：事件 2 后关闭连接，Last-Event-ID=2 再收 3 至 12，无缺号、重复。真实账号的两次独立登录验证旧票 401、另一票 200；跨应用访问被拒 403。当前 E2 同一测试应用只有 admin 所有的用户会话，第二用户负向仍需真正独立的测试主体。

## 切换前记录

2026-11-26，本地隔离 PostgreSQL、签名授权夹具、Spring Boot HTTP 和已构建的业务助手页面。三轮测试的模型使用可观察请求内容的回环适配器。

| 轮次 | PG 运行号 | 终态 | 实际回答 |
| --- | --- | --- | --- |
| 1 | r_5003bc3eefa345bcad20854c49cf1cad | SUCCEEDED | answer-1 |
| 2 | r_8ae25f3d0acf4697a85cb3deca873eab | SUCCEEDED | answer-2 |
| 3 | r_0cd9e04950434519b543d79f80a9a17b | SUCCEEDED | answer-3 |

`EvaluationRunPostgresTest` 对每次正式模型调用逐条比较系统提示、已成功的问答和新输入，并重放同一个 requestId。每轮仅产生一次调用。三个测试通过。跨应用与他人会话返回 403。`SessionEventsEndpointTest` 11 项和 `SessionSseBackpressureTest` 4 项通过。`GatewayIamPortalHttpTest` 在正常网关入口验证 GET 快照、POST 轮次和 SSE 的状态码、真实换行帧、游标头与过期票 401。

`SessionEndpointTest` 的单票撤销用例使用独立 PG 和真实签发端点。未实现时撤销端点返回 404，修复后重复撤销返回 200，旧用户票换票和旧流委派票再次授权均返回 401，同账号另一登录仍为 200。外部身份源由本地 HTTP 夹具提供。该测试类 9 项通过，1 项需要外部双目录凭据的既有联验跳过。

Playwright 驱动已构建的 `/w/chat-fixture`，HTTP 夹具主动截断首轮 SSE。三次提交使用三个不同 requestId，浏览器发送的 Last-Event-ID 依次为 0、2、6、9。刷新后 3 问 3 答共 6 条，无页面异常或控制台错误。截图见同目录 portal-chat.png。前端 `npm test` 638 项通过，`npm run build` 完成类型检查与三个生产构建。

复跑命令在代码根执行，Java 21。PG 用例使用类上登记的隔离数据库变量，单票用例需 AGENT_AUTHORITY_REVOCATION_DB，禁止使用业务库。

```text
mvn -pl engine-runtime -am -Dtest=EvaluationRunPostgresTest,SessionEventsEndpointTest,SessionSseBackpressureTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl authority-service -am -Dtest=SessionEndpointTest,DelegatedResumeContractTest,DelegatedForwardContractTest,ExchangeContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E2 仍需用真实身份、真实模型和本次构建执行 PR-A，收集流恢复、403 审计和撤销回执。原部署没有本次实现。扩大检查时 admin-common 的 403 个测试中 4 个断言失败、1 个用例出错。以下五项在改动前提交 96072c46 的独立源码副本上均复现。全量后端构建仍有这五项基线问题。

- AuthorityPolicyTest 的 catalogSeparatesActionsAndModesAndKeepsUnusedEntrypointsDisabled、catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards。
- LocalRunAdvanceAuthorityTest 的 localWrongSourceOrOriginIsRejectedBySignedTicketValidation、sameRootAppReadAndDebugGrantsCannotBecomeLocalMain。
- ServiceTokensDeploymentModeTest 的 actualIamOverridesJvmTestAndClosesAllLegacySignersBeforeListener。
