# C2b1 exact EVENTS ingress and explicit production component wiring

本任务从已限定Accepted的C2a继续v1.2实施计划，先完成网关入口/实际Boot接线组件；后续C2b2另以独立GW/E/issuer/新PG/TLS验证生产E encoder联合首链，C2c再改四前端消费点。用户已授权持续开发、无真实外部环境时用隔离端点，无需中途询问是否继续。唯一工作目录G:/207/项目/算法重组工具/代码/agent-platform，禁止读/复用/关联相邻旧工程代码/配置/数据/进程/依赖。

先读本brief、`.local/iam-o2b-f2c2b1-events-wiring-frozen-inputs.json`、AGENTS、docs/implementation-brief.md及contracts/README.md；按SDD implementer/TDD/verification/ponytail执行。用户v1.2与四外部契约不改，时间安排不做。无commit/deploy/旧main重启，不git add -A；PG仍业务权威，不引别的业务服务JAR或反射siblingtarget。不将本组件验证写成17/34/30全部完成。

## 基线与唯一写权

C2a certificate `.local/iam-o2b-f2c2a-acceptance.json`7479B/e8909c36662526517921b40832b482a0c5bd9a3b4cde928fcb3c331ab1ee4818。accepted source630=6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d；历史logicaltarget2298=304be175bbbcd46f11fa045617b34c456526be83925a0599b3de43877081eeda。完整immutable source baseline `.local/iam-o2b-f2c2a-http-exit-fixes/primary`（复制排除.git），已root实际校验；不要重跑旧审计/绿测/Git。新八索引保护checkpoint5864B/efc58b820878c0db69b8a883a6570076b19c9d3791ac7b0fed2f075f3fc38a42，设计原hash3c72b206...不变。

现授权4个既有修改source：

1. admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java
2. platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java
3. platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java
4. platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java

现授权4个新增source：

1. platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunEventsProxy.java
2. platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayPortalEventsConfiguration.java
3. admin-common/src/test/java/cn/agentplatform/common/authority/GatewayEventsIngressBoundaryTest.java
4. platform-gateway/src/test/java/cn/agentplatform/gateway/infra/GatewayPortalEventsServletTest.java

预计source630→634，4既有修改/4新增/其余626名字bytesSHA exact。若某额外文件确有必要，先报具体依赖和最小变动，不自行扩权。不得改Http/Budget/Verifier/Decoder/TLS/engine/其它test/POM/main/前端/原设计/四契约/八索引。可更新仅由实际compiler/input/created/资源/commonfixtureJAR/actualRunning所证明的有限target；新owned `.local/iam-o2b-f2c2b1-events-wiring/`、`docs/evidence/iam-stage-4-o2b-f2c2b1-events-wiring/`，helpers `.tools/iam-o2b-f2c2b1-events-wiring-*`、报告`docs/evidence/iam-stage-4-o2b-f2c2b1-events-wiring-report.md`。root在你活动期间不读活动8source/target/newowned。

## 外部语义与精确鉴权入口

Java21/Boot3.5.0/Security6.5/Nimbus9.37.3/SpringAI1.0固定。凭据secret_ref，不日志/输出sourceJWT。tenant/sub只从TrustedJwtDecoder实际验签JWT取得。新精确EVENTS purpose仅IAM、GET、原REQUEST、空context、canonical `/admin/runs/r_[0-9a-f]{32}/events`，原GatewayController实际HandlerMethod/Bean/Classloader及框架coverage/installed filter校验不放宽。无实际RunEventsProxy bean则默认拒绝；不扩大RUN/STATUS/session入口。

游标按v1.2和现有fetch Authorization用法：允许唯一Last-Event-ID请求头，亦允许原详设2431/2360所示唯一字面query `Last-Event-ID=<canonical decimal>`；二者二选一，有两种来源或重复即拒绝。未提供时0；ASCII canonical0或非零开头最多19位且≤Long.MAX_VALUE，不容空白/正负号/前导0/逗号/溢出/Unicode数字；query不容其它参数、别名、编码key或拼接。**只对这个EVENTS purpose允许这个单一游标query，STATUS/RUN/session原无query规则完全保留。** 此正式要求修正旧source-only预备稿对EVENTS一概noquery的假设，不修改冻结设计/契约。拒绝body/Transfer-Encoding、cookie/APIkey/伪身份头/query身份，保持原JWT token_use/scope与sourceBudget/expiry门。头与query只在可信边界解析一次，capture持long，异步不重新相信可变原请求参数。

在GatewayIngressBoundary内新增private-constructor PortalEventsRequest及claimEvents。Proof仍只能由owned filter生成，preHandle确认真实handler后保存该EVENTS请求的**实际MVC request/response对象身份**，claim/requireLive严格核这两个exact对象、原thread、REQUEST、route、anchor、claimed/ready/invalid/sourceBudget/expiry。不能用“任意wrapper有同一raw anchor”替代实际handler参数身份。clear/afterConcurrent/afterCompletion同时切新增r/s反向字段，不能让旧capture复用或保留wrapper图。独立purpose，不能拿STATUS/RUN capture冒EVENTS或反向复用。

## 实际MVC包装与SSE接缝

root具名核当前Sse RequestSlot.requireOriginal：它仅接受Catalina原facade精确相等，而真实Spring Security/MVC会传wrapper。因此本任务必须实现窄可信接缝，不能靠测试Servlet/关闭Security/unwrap输出流跳过问题。

保留原raw Servlet start路径的原facade/原thread/bridge/origin/epoch/socket/claimed门（可收窄原String source/run/cursor入口为包可见；现已知调用都在infra测试）。新增production public typed start重载，接收GatewayIngressBoundary.PortalEventsRequest；source/run/cursor仅取自该capture，不同时收caller tuples。先capture.requireLive实际r/s，原RequestSlot再核原REQUEST/thread/current origin/epoch/socket；通过有界公开ServletRequestWrapper/ServletResponseWrapper身份链确认实际r/s最终包含同一Catalina原facade。严禁反射私有字段，严禁对底层raw response取outputStream；首次getter、listener与所有写仍用**实际MVC supplied response**。

不可让caller给boolean、Actor、普通attribute、公有构造器或任意wrapper生成production许可。private start核心可复用，类型capture只在原线程bootstrap检查，不存入State/Session/Handle/async callback。一次request token只准一个已注册Handle，仍先准入Handle再forSse Session/Q/lease/State，不能接收已建Session。wrapper支持不放宽原untyped facade路由。注释说明这个真实MVC适配与来源不变量，避免通用wrapper框架。

RunEventsProxy采用最小application具体类，Controller显式EVENTS分支、claimEvents后forward，随后独立原STATUS和RUN分支；旧四/五参constructor兼容。application只调用typed SSE entry，不创建G/Owner或保存activeHandle集合/历史目录/额外线程。bootstrap失败按已有有限ApiResponse/Portal401/业务403/不可用语义；已有async/committed流不能追加普通JSON或更换writer，不能为了错误包削弱真实stop/completion门。遇无法区分的真实生命周期错误先给root具体分支，不自行新造重异常对象/输出旁路。

## 显式生产Bean与Boot

GatewayPortalEventsConfiguration采用与现GatewayIamAssembly相同的**显式import**方式（不加自动扫描stereotype来悄悄开启默认main）。提供实际生产Sse/Http/RunEventsProxy beans与TomcatWebServerFactoryCustomizer；依赖已安装的真实PortalEventsTls与CurrentAuthorityClient/可信decoder，缺失或歧义失败默认拒绝。不要用test@Bean替代被验生产Bean。可由隔离Boot launcher显式import生产Controller/Assembly/新配置，并提供隔离issuer/CA/config。

Boot公开factory.setProtocol(PortalRunEventsSse.ControlledProtocol.class.getName())与context customizer装同contextValve（同infra包可访问），不能先new一个unusedProtocol再沿用default。Boot3.5默认customizer会覆盖constructor maxThreads/maxConnections/cache；真实启动必须最终1024/64/64、内部有界executor、固定64桥、HTTP1.1 NIO、无HTTP2/virtual/customexecutor；现ControlledProtocol.start已有前后verify，不平行再造校验框架。隔离launcher显式给固定值；错误值要真实启动拒绝，不默默改回掩盖错误。每个启动case独立JVM，终身仅一个controlled instance；不可反射重置静态或同JVM顺序重启。

C2a共享1536/1792/3MiB、原Owner规则、真实socket外锁/recycle/11SPI、Http重helper实际返回门/Sse unique finisher等全部保持。新工作不改生命周期清理，只加可信入口与固定安装。没有per-request TLS clone、动态会话注册表或新reaper；列新增固定单例/常量有限模型，不泛化wholeTLS/JVM/RSS/native容量。默认GatewayApplication未启用IAM import这件事仍待后续生产启动任务；本任务不能声称默认生产launcher/实际外部IAM/K8s部署已验。

## 失败优先与限定验证

先冻结native before630/实际名字，再以旧公开API+真实Boot安全链建立缺EVENTS入口的行为RED，不能用新class/method不存在的编译错误冒RED。root可协调最小实际harness，但不能为方便改已有protected tests或关闭鉴权。所有新的测试/运行根只用本任务owned；不调用旧JUnit parent向旧冻结peers根写新子目录。若复用本module既有package-private fixture helper，传入新owned run目录；只同module测试类，不反射/依赖其它业务target。

有限GREEN集合为新Common test、新Gateway Boot test、原Common GatewayIngressBoundaryTest三方法，以及原raw Servlet bootstrap的一个直接回归（可在新Gateway test ownchild中调用既有同module helper并传新run目录，不运行其hardcoded-old-root父JUnit）。原bootstrap名originalRequestBootstrapAndContinuousCursorTerminal；若其可复用helper不可访问先报root，不复制大套件或改原test路径。不要跑C2a原29/14/10/7/5/mixed/cap/GC或PG/provider全套；**本任务无GC授权**。

新测试至少覆盖：真实JWT+原Controller/Assembly/newConfig/security包装的正例EVENTS收到可信事件并实际清理；header/query各一合法游标及default0/Long边界、重复/双源/乱码/越界/非法query拒；fake/copied proof/foreignHandler/foreignr/s/wrapper/非原thread/expired/reclaim/clear后重用全部在新Session/Q前拒；无Proxy默认拒；body/身份伪装/非canonicalpath/context/dispatcher拒；RUN/STATUS/session原purpose未扩大且旧constructor兼容。实际Boot固定profile启动成功与错误maxThreads/maxConnections/cache/HTTP2或customexecutor配置至少具名代表拒绝。正例必须真实生产Sse/Http/authority接口和既有asset/receipt的隔离private peer，不caller forge正许可；普通testServlet结果不能代替真实GatewayController。

若第一次startup因容器框架包装/Bean序列/coverage遇真实问题，保留失败实际exit，只修本scope并针对新失败检查，不重跑已绿矩阵仅补报告。测试可按独立bounded child modes集中到少量JUnit方法，各child实际waitFor/collector与TLS peer停止记录。不开真实PG也不声称PG链；C2b2才作实际生产E/PG/issuer联合。没有凭据时继续隔离端点，不问用户是否继续。

## 开源依据/交还

source-only预备`.local/iam-o2b-f2c2b-wiring-preparation.md`包含已具名读的实际API及官方Boot3.5源码链接（factory getWebServer→newConnector→customizeConnector、公开setProtocol/contextcustomizer、Tomcat属性覆盖顺序）。固定本地依赖/官方源是API依据，不能凭印象声称已对照完整上游源码；必要查public wrapper API也只读实际版本/官方源。写设计/来源/故意限制注释，不逐句废注释、不搬Tomcat内部实现。

最终先自审再第一次native634 after全blobs、实际8path binary patch、plain apply --check/apply各一次，无replay index/aftercopy。source/target/counts按实际允许文件登记，成功命令/hash立刻cache；metadata错误原helper/错误/cache保留，只续未完成，注意PS5 BOMless UTF8路径编码历史坑。own-inventory含所有新owned/helpers/report/handoff，仅排自身。报告测试命令/实际Running/XML/有限compiler/resource target/每epoch与实际child退出；没有sourceToken/rawpayload/JWT/全manifest输出。

返回report/handoff/inventory实际bytesSHA/count、concerns，明确8source/有限target/newowned/helpers/report写权冻结归还root。不得自行标Accepted或改八索引。fresh taskreview及root有限审计通过仅签C2b1组件，随后不中断推进C2b2/C2c。
