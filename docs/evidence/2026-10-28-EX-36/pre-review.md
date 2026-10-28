# EX-36 独立只读预审

- 对象：三主入口v2.7/v1.7登记的EX-36，两文件白名单及本卡证据；这是测试设计和最小修正的预审，**不是独立终审或完成签收**。
- 作者：`/root`。预审者：独立任务`/root/ex00_collector_review`，未修改Java、测试、脚本、三主入口或index；仅新增本文件。未使用superpower，未执行Maven、Java、PG、HTTP、启停进程或产品测试。
- 当前工作区指纹（未冻结，终审须重新采集）：`OntologyContextPacks.java` SHA256 `9F462FF6D960FE11DB4412E7A7FF08230A7B864125D8C7AC46CF0C68A8B7DD74`；`OntologyContextPacksTest.java` SHA256 `6C1652FD420AA9D876548D72D2142E7D61DF51CEB223109A05D007AAC058A652`。

## 构造与失败测试判断

1. 原生产类为普通Java组件，具有public两参`@Value`构造和private四参工厂构造，没有无参构造。参数上的`@Value`不等于已明确构造注入入口，不能把“只有一个public构造”直接当成“只有一个声明构造”的自动选择前提。
2. 本仓Boot3.5.0缓存BOM管理Spring6.2.7。预审只读检查本仓`spring-beans-6.2.7.jar`的classfile静态结构：`AutowiredAnnotationBeanPostProcessor.determineCandidateConstructors`调用`Class.getDeclaredConstructors`并检查构造注入标记；`AbstractAutowireCapableBeanFactory.createBeanInstance`在无候选/构造配置时可转普通实例化。与当前双构造、无默认构造的原失败前提相符。这是本地二进制静态核查，未启动Java，也不声称已读框架源码。JAR SHA256为`C623AB697867D060E29CDAE8AC7443FABB63598E33E9596EF0722205AE4E654A`。
3. 新方法`springInjectsProductionConstructorAndUsesConfiguredSecretReference`确实执行`context.register(OntologyContextPacks.class)`、`refresh()`、`getBean()`。没有supplier、手工`@Bean`实例、mock组件或`forApiKey`绕过构造选择。它隔离目标Bean的装配，不等于整个EngineApplication已启动。
4. URL和opaque引用由`MapPropertySource`注入，`SecretVault.put`用临时目录、合成主密钥和合成tenant生成真实`vault:`引用；随后实际`fetch`必须到达该环回URL、路径匹配、请求头为解析后的精确合成key且无Authorization。忽略URL/ref、把ref原文当key或改走测试工厂均不能满足这些断言。两个系统属性在finally恢复，原三个工厂测试保持不变；该协议桩不模拟OR业务或真实IAM授权。
5. 读审期间作者已在原public构造添加`@Autowired`，新增import及一句中文不变量说明；当前生产diff仅这三行。两处语义标识（import和注解）足以明确现有入口，说明行不改变行为；无需增加无配置默认构造、删除私有工厂、改变构造参数或扩散其它Bean。

作者随后交付的[原产品红阶段安全事实](runs/red-spring-20261028-225550-607/failure-facts.json)记录：原生产内容未改、native exit1、四方法中原三方法通过，新方法在Spring创建Bean处出现精确无参构造目标。预审已读该回执，未运行此阶段或将其当作绿测。README已更新为红已复现、最小修正后待绿/模块/JAR；终审仍须核实际XML、源码时序与正式异常链，而不是只凭此JSON签收。

## 客户端不变量与906基线

- 当前生产diff没有改变只发`X-API-Key`、禁止重定向、5秒连接/15秒请求超时、256KiB上限、5分钟包缓存/30秒绑定缓存/容量64、失败不缓存、响应形状及候选摘要校验、租户SecretVault解析与403保留。原工厂、三个原测试和其它既有客户端规则应继续保持。
- 完整engine比较必须使用EX-00明确的新前提基线`baseline/20261028-184530-895`：93类/93XML、906case＝791passed/35failure/80error/0skip；不能退回缺库时的883case口径或覆盖原红。
- 新增一个Spring方法全绿后，若其余源码/方法不变，预期目标类为原3＋新1，完整模块在原906身份之上增加该通过方法。不能只比较总数或失败数；须按classname、原method及status核查906旧方法，单列新增/消失/类级错误、跳过和未执行。存量红不因此被自动豁免。
- 本次不需要为这个独立Spring测试修改PG、增加库/迁移、使用H2或跳过其它测试。完整模块仍须使用原专用PG前提并保留配置/源码指纹。`run-tests.ps1`已补`securityModePresent/securityMode`记录；原基线为`AGENT_DEV_AUTH=true`、`ENGINE_PG_TEST=true`、基础环境无显式`AGENT_SECURITY_MODE`，不能仅以`.env`哈希证明继承环境一致。实际值与完整方法差异留待运行后终审。

## 尚需交付的真实正式JAR补验

1. 保留EX-01原正式engine JAR SHA、源码/配置指纹、命令/启动时间及`ontologyContextPacks`异常链；本卡真红与该原异常对应，不使用改坏代码制造红。
2. 同一测试窗保存修正后的目标类新鲜XML/退出码及完整engine方法对照。新增非通过、坏类、跳过、未执行均须为0；出现另一阻断先登记，不能顺手扩白名单。
3. 从当前源码重新打包正式engine，记录新旧源码与JAR SHA的关系。由EX-01在受限服务环境文件和本次独立测试库上，经本仓登记进程脚本启动，核对实际进程命令/启动时间/JAR、就绪、JMX有效配置及真实生产Bean存在；不得手工注入测试实例或只凭端口监听认定成功。
4. 在新正式JAR上完成EX-01计划的本体客户端实际URL/opaque引用消费和相应OR验证，秘密只保留指纹/安全事实；只在就绪前后看到Bean不报错，不能代替属性真实消费。原legacy/PROCESS及无关已绿步骤保留各自RunId，新JAR补验单列，不拼成同一轮全绿。

预审结论：**测试设计和当前最小装配修正方向可接受；绿色、906旧方法对照、当前源码正式JAR就绪/客户端及独立终审均未在本预审签收。** 原PLAT-13/T13.1和EX-07联合J3/J5～J8继续按自身关闭条件推进，不以本卡构造修正替代。后续终审必须审新冻结候选和真实证据，不把本文件当最终review.md。
