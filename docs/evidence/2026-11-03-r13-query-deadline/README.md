# R13 异步查询绝对期限取消修复

用户于2026年11月3日授权直接修复r05复核剩余P2。本项只改HttpAsyncAdapter的查询传输和新增纯HTTP功能测试；不使用superpower，不启动全量验收、业务场景或独立审计，不操作PostgreSQL，不重启产品服务。

## 改动与原因

原deadlineGuard只能在读取前检查，已经阻塞的读取仍会等待完整socket超时；错误正文与Spring响应关闭排空也会绕过检查。复核实测T=1000ms时直到1672ms才退出。

查询分支复用项目现有的Spring JdkClientHttpRequestFactory。每次真实createRequest重新计算同一个queryDeadline的剩余Duration，并使用独立factory，避免并发串扰。Spring的请求计时器覆盖连接、响应头、成功/错误正文和关闭期间，在截止时取消真实请求或关闭实际响应流；读前检查继续拒绝过期缓冲数据。

adapter懒创建并复用一个JDK HttpClient，不跟随重定向；销毁时拒绝再次创建，shutdownNow并有限等待、保留中断。提交入口的null期限分支继续使用原Simple工厂。身份映射后的期限复查、远端认证错误及RESULT_UNKNOWN/对账语义保持。

## 两轮定向结果

- 第一轮在基线c7184c2d：AsyncQueryDeadlineHttpTest六例中2通过、4真实断言失败，零错误/跳过。失败为成功正文在途读取1659ms、错误正文2664ms、拒绝后的关闭1669ms，以及构造后过期仍实际发送。
- 最小修复后第二轮：七类32/32，零失败/错误/跳过，BUILD SUCCESS。新增六例覆盖在途成功正文、错误正文、结果拒绝后的关闭、响应头未返回、实际发送前过期，以及正常成功/401业务码保留。
- 同批相邻检查：HttpAsyncAdapterTest 2、AuthSchemeOutboundTest 5、ArtifactHandoverDeclarationTest 10、ProcessOutputCancellationTest 2、OntologyImportControllerTest 6、AlgorithmOffloadResponseOwnershipTest 1。
- 新测试源SHA256两轮均为2410E62FBB687524B0D52FB29DA7783B0755CFF3CBC04645C3A8F1853DDFCE4F；时间阈值T=1000ms、完成须小于1400ms，未放宽断言。

命令（项目根；Java21，离线）：

```powershell
mvn.cmd -o -B -pl skill-registry -am -Dtest=AsyncQueryDeadlineHttpTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Djdk.net.unixdomain.tmpdir=C:/Temp/ap-r13 test
mvn.cmd -o -B -pl skill-registry -am -Dtest=AsyncQueryDeadlineHttpTest,HttpAsyncAdapterTest,AuthSchemeOutboundTest,ArtifactHandoverDeclarationTest,ProcessOutputCancellationTest,OntologyImportControllerTest,AlgorithmOffloadResponseOwnershipTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Djdk.net.unixdomain.tmpdir=C:/Temp/ap-r13 test
```

本机JDK回环初始化需要短ASCII Unix-domain临时目录。只给测试fork设置上述系统属性，目录预先创建；未改系统/应用配置。原复核的初始化失败证据仍保留，不覆盖为产品通过。

结构化安全结果见results.json；原始red.log/green.log及red.xml保留在本地证据目录，不将JVM完整属性作为交付数据。实际本地提交与主入口摘要另记local-commit.json。

## 完成边界

本项功能缺陷已修、定向功能检查通过；R13真实业务register-result→归档→下载/再次消费，以及正式联合、全量、跨服务/环境验收仍按原手册等待用户确认。49张卡计数不因本项局部结果增加完成数，整体验收未通过的历史结论保留。
