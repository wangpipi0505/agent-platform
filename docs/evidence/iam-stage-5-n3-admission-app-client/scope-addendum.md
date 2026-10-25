# N3 具名最小依赖例外

Root 在本轮正式授权：「只在 engine-runtime/pom.xml 增加 org.apache.httpcomponents.client5:httpclient5:5.4.4（固定 Boot3.5.0 BOM 的 core5.3.4），仍只改原 3 个生产 Java，不改 root/common POM、runner、contracts 或新增生产文件。」原 formal brief 保持原字节；本文件是该具名例外的证据，未扩大服务启动/路由/IAM能力。

修改前 engine-runtime/pom.xml SHA256 `30bb8920dcf6baf2aff404f9a0c7cf3e918a40c9cadc6bac03fff5d7c34c1982`；物理副本已在本轮 before/engine-runtime/pom.xml，且在 POM 修改前复制到本 evidence/pom-before.xml。唯一允许 POM 差异是一个固定版本 httpclient5 依赖；core5 5.3.4 使用既有 Boot 3.5.0 BOM，不修改其余依赖版本。

原因：固定 OpenJDK `jdk-21+35` MultiExchange.java 463–560 的 retryOnFailure/getExceptionalCF 允许 ConnectException 重试及 GET 的 ConnectionExpiredException 重发；无 per-instance 禁用 API。普通 Java HttpClient 只能证明应用无循环，不能满足本 brief 的零自动重试。这个 tag 是机制参考，**实际执行环境为 Eclipse Adoptium 21.0.12.8-hotspot**，不宣称与参考 tag 精确同版本。

内建替代也不成立：同固定 tag 的 SunHttpURLConnection.getInputStream0 在 streaming() 时调用 getOutputStream，后者要求 doOutput 且将 GET 改 POST（1428–1433、1627–1630）；SunHttpClient 的 streaming retry guard 不能消除 SunHttpURLConnection.writeRequests 743–760 无 streaming guard 的二次写。没有为该替代运行探针。实际源文件及 URL/version/license/SHA 保存在 upstream/。

实现限定为原 RuntimeClients 的具体 published 调用：成熟客户端 per-instance disableAutomaticRetries/disableRedirectHandling，关闭 cookie/认证缓存；固定 app origin/唯一 GET、connect≤1s、完整 body≤2s/4MiB、取消和生命周期仍必须实际验证。没有全局 System 属性、反射 JDK、手写 HTTP 协议栈、通用传输框架。

Classpath 影响审阅：engine-runtime/src/main/java 中 RuntimeClients.java 和 TestInputClient.java 是仅有的 RestClient 创建点，二者明确指定 SimpleClientHttpRequestFactory；没有 RestTemplate 创建点。AgentDriver.java 仅捕获 RestClientException。故增加 Apache 依赖不会改变这些已明确工厂的现有调用。Spring 可能对未指定工厂的未来调用改变自动选择；本次未找到该实际路径，不扩大七模块回归。最终仍为原授权四个测试类。

Root 要求该例外在 report/handoff 中显式交接；不据此宣称普通 EngineApplication IAM 启动可用、N4/全 Task5 完成。
