# R-43 发布版本运行装配

## 冻结候选实测

实际 CHAT 工作区“正式运行 → 发送”请求 /demo-chat，返回 403 SYS-AUTH-403。独立调用 /runs 对已发布 r22e2-real-v2 返回 403 GATEWAY_RUN_REJECTED；对未发布 r22e2-real-v1 与不存在的应用均返回 503 GATEWAY_RUN_REJECTED。未产生本卡成功运行。见 [三分支响应](e2-console-results.json) 与 [实际界面](e2-console-run.png)。已登记 R-46、R-47。

## 切换前记录

E2 原记录是管理台已发布版本直跑返回 503。2026-11-26 本地通过正式 Spring 装配、实际 HTTP 网关入口与受权下游夹具，核对有版本 200、未发布 409 APP_UNAVAILABLE、应用不存在 404 APP_NOT_FOUND。开关关闭时失败关闭。明确开启而下游地址不合规时装配失败。通过关闭传输对象释放客户端资源。

网关三组用例分别为 GatewayIamPortalHttpTest 25 项、GatewayPublishedRunProxyTest 33 项、PublishedMetadataClientTest 10 项。引擎另用 PostgreSQL 运行已发布的独立 CHAT，生成 r_dbdfd8b2ee0c498292791a79e92c361b，终态 SUCCEEDED，回环模型返回 answer-4，输入仅有本次提示和提问。两组测试使用各自隔离的下游。

```text
mvn -pl platform-gateway -am -Dtest=GatewayIamPortalHttpTest,GatewayPublishedRunProxyTest,PublishedMetadataClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E2 待用新镜像、受信服务地址和真实模型，从管理台点击已发布版本，保留前端回执及数据库实际终态。
