# 开源实现对照

本文件只记录实际使用或实际阅读的来源。运行代码独立编写，开源库按详设直接依赖；原型或任何相邻业务项目不作为参考。

| 来源与固定版本 | 已核对的机制 | 本工程落点与边界 |
| --- | --- | --- |
| Spring AI 1.0.0（Apache-2.0），[官方OpenAI接口](https://docs.spring.io/spring-ai/reference/1.0/api/chat/openai-chat.html) | 以实际1.0.0 JAR API核对OpenAiChatModel/OpenAiApi、MessageAggregator、ToolResponseMessage；关闭internalToolExecutionEnabled；框架单次调用与工具循环分离 | model-gateway ModelCalls；同步/真流式/FC均实际测试，SDK重试设1次；受控循环由engine实现 |
| DeepSeek Harness，MIT，commit `47f943859bef60e4160492346772ded9b24f765a` | 实际只读LICENSE、core/system-prompt/src/index.ts、core/session/src/invariant.ts/chunk-rows.ts、core/tools/src/invariant.ts：有序组装、调用配对、单调事件与chunk分离、冻结参数 | ContextAssembler、AgentDriver、TraceStore、ChunkBus、ToolCalls。机制借鉴后以Java/PG独立实现，不导入TS运行时；裁剪/压缩未实现的部分不计完成 |
| [Dify 1.6.0](https://github.com/langgenius/dify/tree/390e4cc0bf203a979e13694ed99ec9fda6b42cc8)，commit `390e4cc0bf203a979e13694ed99ec9fda6b42cc8` | 实际阅读该commit LICENSE、web/app/components/base/chat/chat/hooks.ts的handleStop/onData/onCompleted、index.tsx滚动监听：取消、流式增量和最终快照核对、用户上翻时保留阅读位置 | 仅对照交互职责。未复制React组件、CSS、商标或运行代码；使用本平台Vue组件与tokens。该版本为附加条件许可，不将其表述为无条件Apache-2.0 |
| AntV X6 2.18.x，MIT；Element Plus / Vue3.5，MIT | 直接依赖并实际运行Graph、History、节点/边序列化及Vue表单 | web/flow-designer；业务definition与layout分开，X6 JSON不作为执行契约；未声称已阅读完整上游源码 |
| jtokkit 1.1.0，MIT | 只对其明确支持的模型使用模型映射；其余模型须专用tokenizer | ModelCalls计量准入；字符估算仅展示，未知模型不凭固定密度放行 |
| [MCP Java SDK v0.10.0](https://github.com/modelcontextprotocol/java-sdk/tree/v0.10.0)，MIT | 实际读取HttpClientSseClientTransport、FlowSseClient、McpClientTransport/McpTransport、McpClientSession、McpAsyncClient/McpSyncClient、McpSchema相关方法及LICENSE；核实stock行聚合/接收无界和资源关闭边界 | 保留SDK原生初始化、协商、JSON-RPC配对及工具API；以正式transport扩展点编写有界SSE，不复制整套MCP会话。75新增测试/全skill242为历史候选，独立审查确认资源边界问题后正在返修，见evidence/review-mcp-transport-interim.md；尚未部署或验收真实供应商 |
| [networknt 1.5.6](https://github.com/networknt/json-schema-validator/tree/1.5.6)，Apache-2.0 | 实际读取JsonSchemaFactory/JsonSchema/DefaultSchemaLoader/SchemaLoaders及LICENSE；核实自定义loader优先、引用懒加载和initializeValidators | SkillRules封闭所有外部加载，仅遍历实际Schema位置、初始化未使用定义，保留实例literal引用；真实HTTP外取计数红绿。引擎InputDeclarations/OutputPolicy另有既有定向证据，未引入跨业务jar依赖 |
| [RE2/J re2j-1.8](https://github.com/google/re2j/tree/re2j-1.8)，BSD-3-Clause | 资源防护作者实际读取Pattern/Parser/Compiler/Machine/RE2/README/LICENSE；核实NFA匹配、programSize及计数重复编译展开，原networknt RegularExpressionFactory扩展点沿用find语义 | 固定1.8，编译前保守预算、实际指令/实例工作预算，无JDK正则回退。skill270为作者候选阶段，随后迁共享组件并补引擎入口；明确RE2语法及循环Schema子集限制，完整说明见evidence/mcp-resource-budget.md。分发附带docs/licenses/re2j-1.8-BSD-3-Clause.txt |
| Java21本机src.zip，GPL-2.0 + Classpath exception；Spring Context6.2.7，Apache-2.0 | 调度作者实际读取Semaphore、ScheduledThreadPoolExecutor相关方法；Spring生命周期仅用javap核ScheduledAnnotationBeanPostProcessor的ContextClosedEvent分支；MCP作者读取Http1HeaderParser默认头部上限 | RunScheduler先槽后线程、专属poll/heartbeat及drain；MCP接收边界。并发实现仍须独立审查/实测，不把字节码核对描述为读取完整Spring源码，见run-scheduler-report.md和mcp-transport.md |
| Kubernetes Java Client 23.0.0 / swagger-parser 2.1.27 / MinIO Java 8.5.17 / Flyway（Boot管理） | 直接依赖Job、OpenAPI解析、对象存储和迁移API | skill-registry适配及各域迁移；本机真实MinIO已验证；固定SDK的算法隔离协议读源/有界HTTP证据见 [算法报告](evidence/algorithm-protocol-report.md)，真实K8s/RBAC/容器与生产容量仍待外部验收，未声称已读全部SDK源码 |

本轮依赖细版本以父POM、各服务POM及web/package-lock.json为准。ECharts采用6.1.0以消除依赖审计已确认问题，设计未固定5.x API约束；前端构建/图表集成验证已覆盖。实现注释说明借鉴的具体机制，不将相似概念充作已读源码证据。

2026-10-20后续状态：上表242/270与“返修/未部署”是各原始阶段。三纯Schema组件已迁admin-common并覆盖skill及engine输入/输出，安全独立复核40项APPROVE，见evidence/review-shared-schema-resource.md；随后root单轮846项及262源配置前后摘要一致、六份嵌新common/RE2/J1.8的制品实际更新。真实MCP隔离协议和完整页面、两个实际engine JVM调度均通过，见mcp-protocol-live、run-scheduler-replicas-live。循环Schema及不支持RE2语法明确拒绝并留原正例档案；不宣称原耗尽探针重新通过，也不把原自动中断审查改作批准。K8s完整协议另按计划继续，真实供应商/集群验收仍待。


## 独立基础设施来源

- Redis7.2.16：从[官方固定源码](https://download.redis.io/releases/redis-7.2.16.tar.gz)编译，SHA256 `960a8ec15e34ff40e57ff16837b26b33bd81f2da6d24497bb63de532a323a18e`，与[redis-hashes官方表](https://github.com/redis/redis-hashes)核对；实际读取COPYING，BSD-3-Clause。仅在本工程专用WSL/数据目录运行，未声称阅读完整Redis内部算法。
- MinIO server：官方源码tag RELEASE.2025-10-15T17-29-55Z，module `v0.0.0-20251015172955-9e49d5e7a648`，GoSum `h1:6TdolSCLSs2nwm8i0PpWDqf9iX2Ty9WQK8wmr7dCnUM=`；本工程Go1.27.2独立编译，AGPL-3.0。不是官方预编译发行二进制。Java SDK8.5.17实际源API对照及中断multipart处理见artifact-report。
- PG16.15 EDB官方Windows分发、Alpine3.24.1官方rootfs（SHA512核对）、Go1.27.2官方zip（SHA256核对）仅作为独立本地工具。专用WSL名AgentPlatformV12，未启动或复用旧Docker发行版。
- 部署/观测固定工具及官方摘要见deployment-report：Kubernetes v1.33.0 API schema、Kustomize5.7.1、Collector contrib0.137.0、OTel Javaagent2.20.1。仅做静态/原生配置检查，未把工具下载算作生产环境验收。

## MCP 资源修复所读来源

- [networknt 1.5.6](https://github.com/networknt/json-schema-validator/tree/1.5.6)，Apache-2.0：追加实际读取 RefValidator.preloadJsonSchema、JsonSchema.validate/initializeValidators、SchemaValidatorsConfig、RegularExpressionFactory、PatternValidator、PatternPropertiesValidator、JDKRegularExpression。SkillRules 在库构造前做引用图展开预算，使用原 regex factory 扩展，闭合 loader 与原 JSON Schema 框架继续使用。未将 MAX eager 深度或原始 Schema 字节上限冒充展开工作上限。
- [RE2/J re2j-1.8](https://github.com/google/re2j/tree/re2j-1.8)，BSD-3-Clause：实际读取 Pattern、Parser、Compiler、Machine、RE2、README、LICENSE；固定 Maven `com.google.re2j:re2j:1.8`。Schema 正则先做有限编译准入、再用实际 programSize/实例工作预算；无 JDK fallback。保留搜索语义，明确拒绝不支持模式。许可原文 `docs/licenses/re2j-1.8-BSD-3-Clause.txt`；精确限制和安全红绿见 `docs/evidence/mcp-resource-budget.md`。原审查中断未被当作批准，后续完整冻结需再次独立审核。

## 算法原任务协议实际读源（本增量）

固定 Kubernetes Java Client 23.0.0（Apache-2.0）直接依赖不变。实际读取 Maven Central 固定源码包中的 BatchV1Api.createNamespacedJob/readNamespacedJob/buildCall、CoreV1Api.listNamespacedPod/readNamespacedPodLog/buildCall（包括 container/follow/limit/timeoutSeconds/limitBytes 文档）、ApiClient.handleResponse/deserialize/setHttpClient，以及 JobStatus/OwnerReference/ContainerStatus 相关模型。SDK负责路径、认证、JSON和模型；本地短PG事务负责发送/身份/UID事实。日志不用 execute() 转String，不设置可能截断业务JSON的limitBytes/tailLines。

同时实际读取固定 OkHttp 4.12.0 RealCall/BridgeInterceptor/ResponseBody 和 Okio 3.6.0 RealBufferedSource/Segment（Apache-2.0文件头），确认应用拦截器位于透明gzip解码之后、SDK string()/模型之前；解码字节cap同样覆盖成功和错误正文。Okio单段SIZE=8192，原缓冲和外包装缓冲均固定有界；SDK可见Source只消费cap+1探测字节即关闭，未将其表述为TCP/压缩字节接收配额。单调deadline设在每个SDK Call和日志流，连接重试/重定向关闭。

源码包/已读文件/许可证精确摘要保存在 [算法SDK读源摘要](evidence/algorithm-protocol-artifacts/sdk/source-sha256.json)。来源：[固定SDK源码](https://repo.maven.apache.org/maven2/io/kubernetes/client-java-api/23.0.0/client-java-api-23.0.0-sources.jar)、[官方SDK许可证](https://github.com/kubernetes-client/java/blob/v23.0.0/LICENSE)、[OkHttp固定源码](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0-sources.jar)、[Okio固定源码](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0-sources.jar)。这是相关机制实际读源记录，不是全SDK审计或真实集群验收。

算法转存尾段同时实际读取固定 MinIO 8.5.17 MinioClient.putObject/getObject 的 future.get、S3Base.putObjectAsync 的有限分片读取和 executeAsync 的 enqueue（PUT/POST会派生 retry-disabled builder）。OkHttp 4.12 RealCall.enqueue 在异步 timeout.enter 前触发 callStart，Builder复制时保留eventListenerFactory。因此仅算法派生 ArtifactStore 给各个实际Call设置同一个绝对 deadlineNanoTime，覆盖 region/上传/分片/回读，而非每次刷新相对预算；到期取消实际I/O，派生实例拥有独立池/dispatcher，关闭不影响共享store。固定 [MinIO源码包](https://repo.maven.apache.org/maven2/io/minio/minio/8.5.17/minio-8.5.17-sources.jar) 与相关Apache-2.0文件头已读，摘要同上。失败先留PG UPLOADING/CLEANUP_PENDING，只有后续受控清理确已完成才记DELETED，不承诺对象存储事务回滚。

## G6 可信授权组件的固定源码对照

下列是阶段作者实际读源记录的汇总入口，root核对了交接报告与摘要；不表示root重读了全部上游源码，也不表示生产IAM已存在或完成全库审计。实现仍为本工程独立代码，保持固定版本及原许可。

- Spring Security 6.5.0（Apache-2.0）：NimbusJwtDecoder的decode/createJwt/validateJwt与JwtTimestampValidator的Clock/期限检查，支持“验签后再按严格profile映射同一JWT”，不能裸解claims拼权限；E1a私有证明采用同client Clock和最早期限。D2作者另核FilterChainProxy的单chain匹配，支持精确独立policy入口。来源：[固定源码包](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-jose/6.5.0/spring-security-oauth2-jose-6.5.0-sources.jar)；具体文件/方法/SHA见[ E1a实际读源清单](evidence/iam-stage-5-e1a/upstream-read-sha256.json)、[E1a报告](evidence/iam-stage-5-e1a-report.md)及[D2读源与边界](evidence/iam-stage-4-d2-report.md)。未把JWS签名成功当成用途、主体、grant或current有效。
- Nimbus JOSE JWT 9.37.3（Apache-2.0）：JWSObject.verify、D2的RSASSAVerifier.verify强调签名字节验证与独立profile/期限/用途检查的边界；同issuer+jti的认证锚不依赖JWT字节表示。来源：[固定源码包](https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/9.37.3/nimbus-jose-jwt-9.37.3-sources.jar)；文件SHA与实际片段见上述E1a清单和[D2报告](evidence/iam-stage-4-d2-report.md)。不声称自定义Grant/current/response profile是OAuth标准自动保证。
- Spring Framework 6.2.7（Apache-2.0）：D1/D2作者核MappedInterceptor全映射匹配、实际MVC覆盖及生命周期，D2核JdbcTemplate参数绑定与单次query。分别见[D1实际读源清单](evidence/iam-stage-4-d1/upstream/source-sha256.json)、[D1报告](evidence/iam-stage-4-d1-report.md)、[D2报告](evidence/iam-stage-4-d2-report.md)。工程全局receiver、startup coverage与metadata-first是本地实现，逐项由对应真实测试和独立审查验证；不能用框架机制替代真实业务资源绑定。

- E1b作者实际核Spring6.2.7的AbstractPlatformTransactionManager.handleExistingTransaction、DataSourceUtils连接绑定、DataSourceTransactionManager/JdbcTransactionObjectSupport/ResourceHolderSupport的rollback-only路径：REQUIRED默认加入不自动保证已有事务隔离，资源必须属于同DataSource；外层捕获异常时仍要设置该连接holder回滚位。材料read显式核真实隔离，createPublished失败在同连接保持回滚，不开启补偿事务。文件与官方固定tag URL/SHA见[E1b八文件清单](evidence/iam-stage-5-e1b/upstream/manifest.json)及[实际阅读说明](evidence/iam-stage-5-e1b/upstream/READING.md)。代码是否完整实现这些约束由该阶段独立审查裁定，读源记录本身不作为通过证明。
- Jackson databind2.19.0（Apache-2.0）：E1b作者核JsonNodeDeserializer._fromFloat/_fromBigDecimal的精确BigDecimal分支；未启用相应选项的浮点node可能经过double。材料store仅在本域私有解析实际PG JSONB text，保持大整数/高精度小数、对象排序、数组顺序与nested null，未全局改变Json配置；这不恢复上游已舍入的值，不声称规范字节等于原HTTP字节。固定tag源与精确文件SHA见同一E1b清单，PG往返与实际测试范围见[E1b报告](evidence/iam-stage-5-e1b-report.md)。

未签收的真实provider/IAM/集群及联合D3/E/RR1/RR2仍按实施台账阻断。E1b及I1已独立双Approved；本次仅按实际读源记录登记，完整Task5与生产验收仍待。

I1作者针对同一固定Spring6.2.7实际核AbstractPlatformTransactionManager.processCommit与DataSourceTransactionManager.doCommit：callback内终检不能替代真实commit返回后的交付检查。最小实现分别在UPDATE后同事务验proof、commit返回后再验proof；若后者到期，保留已提交历史检查而拒绝交付VA。读源方法、SHA、真实PG行锁/commit红绿见[I1修复报告](evidence/iam-stage-5-e1b-i1-fix-report.md)，独立结论见[E1b复审追加](evidence/review-iam-stage-5-e1b.md)。

D3b作者继续实际核固定Spring6.2.7的TransactionTemplate.execute/rollbackOnException、AbstractPlatformTransactionManager.commit/processCommit（commit为final）、DataSourceTransactionManager.doBegin/doCommit/cleanup及DataSourceUtils timeout。生产短事务可写READ_COMMITTED，锁后独立语句重核，execute真实提交返回后再本地验proof；测试先super.doCommit再暂停。四文件URL/精确SHA见[D3b读源清单](evidence/iam-stage-4-d3b/upstream/manifest.json)与[实际阅读说明](evidence/iam-stage-4-d3b/upstream/READING.md)。root核四文件SHA与交接报告，不冒称重读全部上游；组件独立双Approved见[D3b审查](evidence/review-iam-stage-4-d3b.md)，不是全局IAM部署或最后网络字节原子撤权证明。

N1作者实际核固定Spring6.2.7的TransactionTemplate、AbstractPlatformTransactionManager、DataSourceTransactionManager/DataSourceUtils、ResourceHolderSupport/JdbcTransactionObjectSupport，Jackson Databind2.19.0的JsonNodeDeserializer，以及PostgreSQL16.15的MVCC/RR源文档相应方法/段落；同DS rollback-only、顶层真实commit返回、精确JSON数值与run→authority→node锁序有实际测试对照。八原件URL/许可/SHA见[N1来源清单](evidence/iam-stage-5-n1-flow-material-status/upstream/manifest.json)和[实际读源范围](evidence/iam-stage-5-n1-flow-material-status/upstream/READING.md)。root核八文件SHA及六个既有复制原件一致并读说明，未冒称重读全部上游；[N1独立双审](evidence/review-iam-stage-5-n1.md)仅材料/status组件，实际调度/受理/门户链仍需后续验收。

N2作者实际核固定Spring6.2.7的TransactionTemplate.execute、AbstractPlatformTransactionManager.processCommit、DataSourceTransactionManager.doCommit，以及PostgreSQL16.15的MVCC/RR/行锁、SELECT SKIP LOCKED与clock_timestamp相关方法/段落，对照原GraphRules/FlowDriver/RunService机制落实顶层RR、真实commit返回、run→authority→node锁序与锁后实际期限。六原件URL/许可/SHA见[N2来源清单](evidence/iam-stage-5-n2-owned-flow/upstream/manifest.json)及[实际读源范围](evidence/iam-stage-5-n2-owned-flow/upstream/READING.md)；root核6文件SHA与已列复用原件一致、读说明，不冒称重读完整上游。N2 I1仅正向FLOW一行及真实worker回归，当前受影响25/0及[同reviewer双Approved复审](evidence/review-iam-stage-5-n2.md)限本组件；原受理/app client/gateway和全恢复仍按后续N3/N4/Task5验收。

N3固定Apache HttpClient5 5.4.4/core5 5.3.4：真实JDK断连GET重发红后按实例关闭retry/redirect/auth挑战/压缩，保留1秒connect、同一2秒完整deadline、4MiB与取消关闭。I2实际读源HttpClientBuilder→ProtocolExec→ConnectExec（lease/connect/TLS）→MainClientExec.httpProcessor.process→InternalExecRuntime.execute，故私有proof/deadline复核置于LAST请求处理器，异步403透传；同context重载与异常discard/close原机制保持。固定官方source archive/10份源及许可SHA/实际段落见[evidence/iam-stage-5-n3-admission-app-client-fix/upstream/reading-manifest.json](evidence/iam-stage-5-n3-admission-app-client-fix/upstream/reading-manifest.json)，原最小POM授权例外见[N3 scope addendum](evidence/iam-stage-5-n3-admission-app-client/scope-addendum.md)。root核11原件与复用SHA、读完整变更，未冒称完整上游重读或JDK参考tag等于实际Temurin21.0.12+8。两类当前40/0及[I1/I2同reviewer复审](evidence/review-iam-stage-5-n3.md)限本组件；未做TLS等待探针、不承诺已发字节撤回。

N4a实际对照固定Spring Context6.2.7 refresh的BFPP→onRefresh、Boot3.5.0 webserver创建和严格Binder/NoUnboundElementsBindHandler，复用同一validated Environment入口登记mode；按JLS21 §17.1/§17.4.5用一个Class monitor覆盖登记/issue判断/三API共同sign，阻止IAM后再启legacy。来源/许可/SHA/实际方法段落见[N4a reading-manifest](evidence/iam-stage-5-n4a-deployment-mode/upstream/reading-manifest.json)，实现与边界见[独立复审](evidence/review-iam-stage-5-n4a.md)。JLS是规范引用而非重新许可开源；JDK21+35仅参考、运行时仍Temurin21.0.12+8。root核8文件+3来源manifest/archive实际SHA并读生产改动，不冒称完整上游重读。分批48+25有效用例，不是一次73绿；全生产IAM闭锁与真实gateway后续边界保留。

N4b1实现者和独立评审实际窄读Spring Security6.5.0 NimbusJwtDecoder、Nimbus9.37.3 DefaultJWTProcessor/JWSObject/RSASSAVerifier（Apache-2.0），以及官方OpenJDK jdk-21+35 Clock/System instant/nanoTime契约（GPL-2.0-only WITH Classpath-exception-2.0）。root核六源SHA/四exact archive entry；原头/固定URL/范围见evidence/iam-stage-5-n4b1-gateway-run-authorization/upstream/。源入口计时+signed min+<=5s cap是本项目保守推论，不是上游授权功能或撤权SLA；参考JDK不等同Temurin21.0.12+8。独立审查evidence/review-iam-stage-5-n4b1.md通过，仅有限common proof；gateway/实际发送/wholechain retry与close限制不扩大签收。

### G6 N4b2实际门户创建链与异步安装闭环（2026-10-21）

实际对照固定Spring Framework6.2.7 MVC异步生命周期、Security6.5.0真实FilterChainProxy／Servlet3包装、Boot3.5.0实际servlet filter注册、Tomcat10.1.41非阻塞读写，以及Apache HttpClient5 5.4.4/core5 5.3.4的ConnectExec→MainClientExec发送位置、连接/response parser与ChunkedInputStream。Apache相关项目为Apache-2.0；准确原件URL、版本、许可、SHA和实际读取范围见evidence/iam-stage-5-n4b2-gateway-published-chain/upstream/及原报告。项目仅gateway POM获批固定Apache依赖例外，未改全局依赖或common传输。

作者归档47份Java、三runtime JAR、两source archive及两license并窄读；root审计实际核这些原件，另核23个具名archive映射：15原始字节一致、8仅CRLF/LF表示差异（规范化后文本完全相同）。原严格字节假设失败脚本与后续仅剩余entry核验均保留，未改旧归档，不能写23份全部原始字节相同或已重读完整库。详见.local/iam-n4b2-root-upstream-check.json。

maxHeaderCount=1的连接trailer边界与独立正常response parser、maxLineLength8192是固定core5解析机制结合本工程有界JSON代理的实现选择；真实trailer及9000字节合法chunk扩展红后定向绿，不是宣称上游默认安全策略。修复继续对照已归档StandardServletAsyncWebRequest.startAsync/onComplete/onError，晚安装完成与无async失败status/terminate是本项目生命周期约束；实际行为、原诊断和独立复审见两份B2报告及review-iam-stage-5-n4b2-close-install-fix.md。不开新wire，不扩为全生产IAM或全网硬取消保证。

### G6 D4a门户会话与签名时间区间闭环（2026-10-21）

D4a实际窄读固定Spring Framework6.2.7 RequestMappingHandlerAdapter 901–1011、StandardServletAsyncWebRequest 251–330/351–470，以及Tomcat10.1.41 Http11OutputBuffer 176–213；归档原件/精确SHA/读取范围见evidence/iam-stage-4-d4a-portal-session/upstream/和报告。它们来自本工程既有固定source archive，不声称本轮新下载远端字节同一或重读完整库。同步void controller的真实首写门禁和私有proof是本项目结合这些返回值/Servlet机制的实现选择。

signed elapsed session校验复用既有固定Nimbus9.37.3签名路径；放宽当前时钟到期判断后显式保留exp>nbf结构约束，错误503与合法已过期403区分是本工程协议选择，不改普通/pinned verifier接口或过期行为。只对照已实际读取范围，不宣称上游提供本项目完整授权协议。见iam-stage-4-d4a-time-order-fix-report.md及聚焦复审。

### G6 D4b前端门户及四项修复闭环（2026-10-21）

D4b Cookie省略实际窄读本工程固定axios1.20.0 defaults/index.js:42、adapters/fetch.js:217–218与415–459等3份归档文件，路径/精确SHA/范围见iam-stage-4-d4b-portal-frontend/axios-inspection.json；withCredentials=false在实际fetch adapter转为omit，以受控真实HTTP与环境Cookie阳性对照核验。未声称重读完整axios/Vue或真实IAM协议。原SDK五参下载接口、Vue控制器、generation与provider两方法是本工程设计选择。

I1/I2在HTTP helper保留status、组件按scope处理当前401；finally中尽力cancel/releaseLock并保留主要异常。I3/I4在统一失效状态同步暂停/退订、以epoch阻断旧回调，安装固定performance.now寿命上限。这些是本工程基于JS流、Vue响应状态和浏览器计时语义作出的实现，不声称某上游完整实现本项目授权协议。精确代码/测试依据见两波修复报告与最终合并复审；不扩展原固定依赖版本。

### G6 O1a独立运行元数据接缝验收（2026-10-21）

本轮实际窄读固定Spring Framework6.2.7（Apache-2.0）的DataSourceTransactionManager.doBegin、DataSourceUtils.prepareConnectionForTransaction、AbstractPlatformTransactionManager.handleExistingTransaction；原件继承本工程evidence/iam-stage-5-e1b/upstream/，三文件bytes/SHA与官方固定v6.2.7引用、读取方法见evidence/iam-stage-4-o1a-run-metadata/upstream-read.json。不是本轮重新下载或审计完整Spring。

新入口先拒绝ambient再在线刷新、顶层readOnly RR内只读取控制/三根manifest、末尾本地proof，是本工程结合Spring事务机制的实现选择；Spring本身不提供本项目query/metadata授权协议。全SQL监听保留真实连接隔离/readOnly属性；独立评审指出测试投影allowlist仍可增强，不能把当前已读SQL的正确性外推为未来任意SQL扩展皆受阻。详见O1a报告及独立评审。

### G6 O1b独立只读状态及审查修复验收（2026-10-21）

本轮实际窄读固定Spring6.2.7与Tomcat10.1.41归档原件六份：DataSourceTransactionManager doBegin/doCommit、DataSourceUtils prepareConnection、AbstractPlatformTransactionManager processCommit、RequestMappingHandlerAdapter实际包装、StandardServletAsyncWebRequest lifecycle251–474、Http11OutputBuffer首写171–216；完整文件身份、官方固定tag URL与实际段见evidence/iam-stage-4-o1b-readonly-status/upstream-read.json。沿本工程既有归档，不声称本轮新下载或审计整库；许可证Apache-2.0按原台账继承。

窄修复基于Java21标准ByteArrayOutputStream：原countguard并不限制几何扩容，真实红例用writeTo的OutputStream bulk参数观察数组length，而非访问JDK私有字段；private固定预分配+countguard使实际数组2MiB。没有新依赖/通用buffer框架。Jackson2.19 JsonFactory.setOutputDecorator的固定bytecode接口窄核解释既有弃用提示，不宣称读完整Jackson源码或通过迁移；详见evidence/iam-stage-4-o1b-review-fixes-report.md。保留日志、M3/M4/M5局限与独立复审，不将库机制外推为本项目已满足全网关/生产授权协议。

### G6 O1c网关只读状态及取消边界修复验收（2026-10-21）

O1c实际窄读沿固定Apache client5.4.4/core5.3.4原件：CloseableHttpClient response handler/自动close、ConnectExec connect后再proceed、MainClientExec interceptor时序与ChunkedInputStream close排空；完整身份、官方固定tag URL和实际范围见evidence/iam-stage-4-o1c-gateway-status/upstream-reading.json。独立review另窄核官方固定5.4.4 CloseableHttpResponse GRACEFUL close与ResponseEntityProxy先drain实体的链，据此定位提前拒绝遗漏；窄修新读取来源按自身report保留。仅相应段有核查，不宣称读完整库或新版本；许可证Apache-2.0继承原台账，无新依赖/POM迁移。

修复采用既有实际request cancellation句柄，在拥有响应的handler内部截获新observation终止异常，保留读循环内的更早取消与original异常；没有通用HTTP重写、无重试、无全局LEGACY行为更改。真实raw peer stalled反例及有限green证明该具体关闭顺序，不外推物理硬关闭/全网络SLA。原源码归属字段问题单独更正，后续来源和执行身份分开记录。

### G6 O2a A事件响应公共授权及生命周期窄修验收（2026-10-21）

O2a A 按限定范围 Accepted：common 固定 PORTAL events response-current、私有跨线程 Capture 与生命周期资源约束完成。source589=backend397+frontend192，相对O1c为5改4增；后续窄修仅3既有文件。固定签名绑定、fresh current、first-frame单调55秒上限、generation和material前后本地检查有效；历史执行ceiling/origin不强行等同新观察会话，历史材料来源仍由B的真实PG读取建立。receiver继续STATUS-only，本项不代表engine/gateway公开SSE或全局IAM完成。

原fresh review的I1关闭漏交取消、I2未发布reservation泄漏、I3终态task遗留timer全部解决；M1按实际operation<=grant关系准确改名并披露没有独立grant-first覆盖。仍为2workers/queue64/64slots/250ms watchdog；watchdog拒绝时不执行callback，固定slot保留任务待本地派发，普通closer可锁外执行且实际退出后归还额度。非watchdog调用者可能被自有callback阻塞，不承诺物理关闭或即时撤权SLA。

测试按实际阶段保留：原final01八类85方法为72PASS/13ERROR，旧ServiceAuthorizationTest的13项输入冲突逐名待迁移；随后原green04仅Profile22/22。窄修red01为5FAIL/0ERROR，定向green01为5/5，watchdog补充red02为1FAIL/0ERROR，唯一整类final01为Profile28/28。没有将多阶段相加成一次suite，也未重跑旧六类/PG/engine/gateway/POST/SESSION。Loopback未改，ReceiverClosed沿用原实际闭门证据。

代码依据：docs/evidence/iam-stage-4-o2a-a-portal-events-response-report.md、iam-stage-4-o2a-a-lifecycle-fixes-report.md；独立复审docs/evidence/review-iam-stage-4-o2a-a-lifecycle-fixes.md为Approved C0/I0/M0。根原有限审计.local/iam-o2a-a-root-check.json为5phase/2464physical PASS，窄修.local/iam-o2a-a-fix-root-check.json为5phase/2153physical PASS，分别继承而不合并计数，未重测/重放。正式source清单取修复目录source-after.json，正式target取final/target-after.json，logical2023包含历史诊断，不是纯classpath；新compiler class为0。

归因纠正完整保留：原194诊断源中183历史/11本轮新增，旧logical2004已含908历史dump；旧13错误名单应含actorProjection而health通过，按root两份correction与实际XML为准。窄修4新dump独立归档，误吸收的2027及原physical-check作为superseded元数据保留，正式清单缓存组合为2023。作者及root的helper错误、red01人工blocker超时诊断均保留，最终无相同worker诊断；只续行未成功阶段。三个历史/修复私有replay各自实际check/apply0/0，原件不改写，没有工程commit。

下一顺序：先单文件迁移ServiceAuthorizationTest并完整验14方法，恢复真实合法STATUS/cancel/monitor-forward覆盖；再以Accepted A接口冻结B受限PG/material reader（真实writer超过64父、先校材料再读payload），随后C安全SSE、O2b真实父子响应链。RR/CHAT/F/真实IAM及运行容量仍待独立验收。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12保持进行中；不新增时间安排，不改v1.2原设计与四契约。

本轮延续既有JDK21/Spring/Nimbus版本，使用现有FutureTask/ThreadPoolExecutor/ScheduledThreadPoolExecutor完成有界生命周期；没有新增第三方依赖或复制外部实现。实际阅读边界见两份实施报告，未把设计/冻结源码审查冒称为重新完整阅读第三方库。

### G6服务授权测试输入迁移验收（2026-10-21）

新独立工程内ServiceAuthorizationTest单文件迁移Accepted：全部14原方法名和实质断言保留，health原文不变；source589=backend397+frontend192，仅1测试文件修改，0新增删除，无production/fixture/catalog/POM/其他服务改动。合法STATUS使用run-snapshot及可信已知actor的有限fixture登记；这不是PG admission/engine业务存在证明。integration STATUS明确拒绝，cancel通过真实exchange/authorize/resume及POST别名验证origin不洗白；统计positive通过真实gateway→monitor同frame retained/forward→engine secondary HTTP，成功票均非改签制造。

唯一实际命令iam-stage-service-migration-final01完整执行ServiceAuthorizationTest，exit0、Running仅本类、XML14/0/0/0。原13项输入冲突的迁移待办关闭；原A历史85=72PASS/13ERROR保持原件，Profile28/Receiver及其他旧类/PG/engine/gateway/POST/SESSION不重跑，不把新14项拼入另一次全量suite。同步2/async3在线调用计数、双run真并发、工作负载与失败关闭矩阵均保留。

独立审核docs/evidence/review-iam-stage-4-service-tests-migration.md为Spec/Quality Approved C0/I0/M0；root .local/iam-service-migration-root-check.json为5phase/2034physical PASS且首轮审计完成。报告docs/evidence/iam-stage-4-service-tests-migration-report.md及同名证据目录封存唯一源码pre/post、raw/XML、187实际compiler/resource/Running overlay和1833 own leaves。正式source-after.json SHA01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346；target-after.json SHA244535cb02df60585217088e321f2b6ac44aaca20de22821a080d66d41967e20，historical logical2023，不是纯classpath。新dump1单独归档，不吸入正式logical。

私有589重放实际顺序保留为首次LF patch check1、CRLF byte-exact补丁第二次actual check0、首次actual apply0；589名称/bytes/SHA严格相同，apply后无copy。没有忽略空白、修改工程.gitattributes/.git或改变已测试源码；只恢复未成功重放元数据，成功source/target阶段缓存，14方法不重测。此前A的replay和诊断归因纠正全部只读继承。

下一依赖顺序保持B受限PG/material reader→C安全SSE→O2b真实父子响应链。B按已Accepted Capture接口补最小本地initialCursor数据接缝，使用response.requireLive(Capture)而不在PG事务内隐式联网；真实writer大于64父、完整材料摘要及权限先于payload读取、双workspace实际消费退出后归还均须独立验收。receiver仍STATUS-only，公开SSE/PG材料读取尚未交付。17需求/34任务/30验收未全闭环，T01仍唯一完成、T09/T11/T12进行中，RR/CHAT/F/真实IAM和容量待验；原v1.2/四契约不动，不安排时间。

本项仅调整新工程现有common授权测试输入，复用已登记catalog、真实exchange/authorize/resume/forward及受信fixture；没有新增库或外部实现复制，也未宣称重读第三方库全文。

### G6 O2a B受限事件材料读取验收（2026-10-21）

独立新工程的B受限PG/material reader及借用输出修复已Accepted。最终source593（backend401/frontend192），相比前一Accepted589新增4文件，B完整范围8文件；后续借用修复只更新其中3文件。固定public event256KiB、完整row1MiB、metadata/root各8MiB、记录/直接父4096及model64MiB/canonical8MiB/总192MiB相交；原两个permit覆盖准备、pending与实际consumer finally，active逻辑close不提前归还。自建只读RR内先完整元数据/scope/current校验再payload，无authority联网；真实15节点writer样本含67直接父、68scopes/H2180。保守预算不是精确JVM heap或生产容量证明。

独立审核初次C0/I1/M2的三项已修复：输出前同ledger计费独立byte[]副本，调用方不能改写reader原数组；payload/labels共同guard拒绝交叉重入并在异常后恢复；补root字段名1048576/+1机械边界；原报告编译提示归属由追加修复报告纠正。最终review为Spec/Quality Approved，C0/I0/M1仅既有编译提示及路径乱码，原Needs fixes/错误原件保留。报告为docs/evidence/iam-stage-4-o2a-b-material-reader-report.md及iam-stage-4-o2a-b-borrow-fixes-report.md，审核为review-iam-stage-4-o2a-b-material-reader.md。

验证按实际阶段分别成立：原final01六类65=64PASS/1旧Initial setup ERROR，原final02仅B两类31通过；借用修复RED01实际4项2assertion失败，GREEN01为Budget14+新PG3共17通过，最终+24对齐slack后GREEN02仅新PG3通过。没有把阶段拼为一套最终17或全回归，也未重跑旧65/PG18/Service14/Profile28。root原B7phase/5836 physical及修复4phase/2170 physical各自有限审计通过，不加总为全仓覆盖；两代原B和本次修复的新patch均实际首check0/apply0且私有源码593名称/bytes/SHA相同，成功阶段均缓存，无重复运行。

最终源码清单.local/iam-o2a-b-borrow-fixes/source-after.json SHA83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20；最终target-after.json SHAa23b333e7e0efd1f4d74cfac75804d9c791a3f9bbc4905c3ca92b132009e1fac，为历史逻辑2060项、实际新overlay464，不是纯classpath/全target树。原14+3及本修复3个新dumpstream分别归档排除；旧历史诊断只读继承。原内部backing58B测量属于修复前epoch，修复后sink收到copy，其长度不能再解释为内部数组身份；新增输出上界由同ledger预收费证明。

下一依赖先处理InitialMaterialScopePostgresTest单方法输入迁移，再C同RR保护窗口/游标/真实Servlet SSE，再O2b真实父子响应链。Initial失败来自STATUS+app-version在exchange阶段被既有selector拒绝，尚未进入read断言；必须建立合法STATUS/run-snapshot真实frame后验证其不能读取initial材料，不能只改成断言exchange拒绝。此前Service13项迁移已关闭，不因这个独立旧用例重新打开。

C尚未开启：B Optional.empty不提供终态或游标窗口证明，common receiver仍STATUS-only，安全HTTP/SSE与实际getter/write/flush/complete生命周期待验。新增具名风险：历史event物理清理后scope仍在，会触发严格locator/执行完整性拒绝；C只可在明确范围内验证终态保留窗口观察语义，生产retention执行链/活跃run清理兼容仍待独立实现验收，不能用测试DELETE冒充生产清理。C准备与retention风险文档是只读准备，不是已交付功能。

17需求/34任务/30验收继续未全闭环，T01仍唯一完成、T09/T11/T12进行中；RR/CHAT/F/真实IAM及K8s/全容量仍待验。只使用隔离端点，不关联相邻旧实现；v1.2/四契约保持原样，不安排时间。

B作者具名对照Jackson2.19约束/非回收buffer和Spring6.2.7事务/JDBC路径，记录见iam-stage-4-o2a-b-material-reader/upstream-read.json。borrow修复仅JDK clone/OutputStream和原ledger，无新增依赖/外部实现复制；没有宣称root重读第三方源码全文。

### G6 O2a Initial材料测试迁移验收（2026-10-21）

InitialMaterialScopePostgresTest单文件迁移Accepted：12个原方法名/顺序及其他11方法原始字节不变，仅目标方法和7个必要imports修改，生产/fixture/runner/POM/契约0改动。旧STATUS+app-version交换保留403负例；新的阳性前提使用真实已受理run的owner/publicId/已接受snapshotSha、真签JWT、实际GET/三参status HandlerMethod和ServiceAuthorization frame。retained proof在同frame读前后验证，生产requirePurpose先于owner/root比较精确拒绝403/RUN_AUTHORITY_DENIED；payload0，控制SQL允许且完整材料SQL未发生。这个迁移待办关闭；原B历史65=64PASS/1setup ERROR不重写为65绿。

唯一实际命令iam-stage-o2a-initial-migration-final01完整12方法，exit0/0F/0E/0S；没有重跑原65、旧B PG21/Budget14、Service14/Profile28或C。独立review docs/evidence/review-iam-stage-4-o2a-initial-migration.md为Spec/Quality Approved C0/I0/M1，M1仅既有编译INFO/路径乱码/Surefire环境提示；root .local/iam-o2a-initial-migration-root-check.json为4phase/2157 physical有限审计通过，1682自有叶子、593私有源码真实首次check0/apply0且完全相同。source593仅1测试delta，SHAe9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16；target SHA9dd29bc3bd986d324a94dd100fa1c30e46518c715c0215aba555e8a644c4e98d，462实际compiler/resource/Running overlay、历史逻辑仍2060；新dump1单独归档。成功阶段缓存，物理检查/测试/replay不重复。

报告docs/evidence/iam-stage-4-o2a-initial-migration-report.md及同名证据目录保留raw/XML、单文件pre/post、逐方法字节核对和准确执行源身份。测试原raw在执行后一次移入本任务归档，original路径是历史地址，归档bytes/SHA相同；不补造原路径。本次仅handler/security seam用途隔离验证，不宣称完整status HTTP/JSON或SSE。B受限读取/borrow修复结论继承，旧Service13项迁移保持关闭。

下一依赖为C同RR完整state/cursor-window+可选event、唯一2permit owner、真实Servletasync getter/write/flush/complete及共享frame配额，再O2b真实父子响应链。C使用最小本地generation查询，真正superseded才有限重读；不可一般403重试。历史event清理观察仅在明确的终态窗口与完整scope/current校验下处理，原B严格读不暗改；production retention执行及active清理兼容仍单独待验。当前C仍未开放，准备稿不算实现。17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中；RR/CHAT/F/真实IAM/K8s/全容量待验。v1.2/四契约不动，不安排时间，继续使用隔离联调端点。

本迁移仅对照新工程既有TrustedJwtDecoder/ServiceAuthorization/AuthorityContext/retained用途路径；无新增依赖、外部代码复制或上游全文重读声明。

### G6 O2a C真实engine事件流及评审修复验收（2026-10-21）

O2a C限定切片Accepted：真实签名E/trusted decoder/ServiceAuthorization/handler→RunController→PortalRunEventsService→同RunEventReader真实PG，完成engine一跳PORTAL/pure FLOW保护事件流；同client opening，run-snapshot与STATUS/EVENTS双向purpose，严格真实游标来源/请求/默认拒绝和unknown/other-owner404。完整state11字段、state/event父并集和原root/metadata预算在同一readOnly RR核验，terminal/cursor/retention窗口来自同轮完整材料，不拼裸scalar。原A生命周期、B readNext严格borrow/copy与legacy接口保持。

专用Servlet async单owner覆盖实际getter/write/flush/complete；opening真实窗口预检后归还B/frame，queued只持小owner/slot/holder/原cursor，RUNNING完整fresh读避免排队饥饿。原B2permit、共享Cslot和16MiB池，完整262245B backing另8192B scratch先计费，两个阻塞owner逐实际退出才恢复；原55秒/源/grant/op硬界不因续核重置，观察断线不cancel后台run。机械long19/type64边界与PG int/varchar32可达范围分别记载。

独立首审C0/I2/M2的I1/I2已按新修复证据和同一评审人复审关闭：consume入口及最终guard失效的终止complete覆盖实际持有B/frame直到调用返回；健康superseded有限重读保持旧实际退出后再完整读取；fresh RUNNING未提交容量失败走HTTP映射，仅已提交且current live才有界reset。本轮先有入口guard、最终guard、未提交容量三项真实1F RED，focused3绿，再唯一最终四类24/0/0/0（Lifecycle11/Capacity5/Http6/Budget具名2），所有新阶段raw/XML/epoch即时归档。 原C最终六类29/0/0/0为修复前真实冻结历史，未扩大宣称修复后重跑原29或全后端。

作者报告docs/evidence/iam-stage-4-o2a-c-events-report.md及新修复报告docs/evidence/iam-stage-4-o2a-c-fixes-report.md、独立review docs/evidence/review-iam-stage-4-o2a-c-events.md保留全部实际阶段。root原C五phase/2373 distinct物理审计及新修复审计 .local/iam-o2a-c-fixes-root-check.json分别通过，首次私有raw patch check/apply及最终source逐项一致；不重复旧测试/PG/replay或扫描旧target。最新source600（SHA44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54），最新target历史逻辑2096（SHA65646da310fefd754a22b1284854291de3447551ff801a2b8da9f150473eebc9），新overlay/diagnostic按各自报告分开，历史逻辑清单不是当前干净classpath。

M1真实工具/容器负路径日志噪声保留，不能称零警告/零ERROR日志。M2早期red/http-red/http-red-route/http-red-mapped/first-green五阶段仅raw，XML及完整source epoch缺失是已发生的历史归档偏差；root依据后期八阶段完整epoch/XML及新修复针对性证据接受本限定切片，但不认可缺失的早期精确XML/epoch已被验证。禁止补造或重跑冒充原阶段，所有新阶段必须即时归档。

终态测试仅DELETE自己真实writer完成后的event物理行，验证清理后PG观察语义；production retention job、active-run清理可继续及writer/replay/去重全平台兼容仍未关闭。Gateway/O2b G/E关联、TLS/私有完整material packet、真实gateway独立验证和browser SSE尚待后续D→E→F逐门验收；本轮不代表全IAM/CHAT/chunk/skill/model、生产容量或socket物理强断/撤权SLA。旧B65/1setup ERROR等历史不重写。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12进行中；RR/CHAT/F/真实IAM/K8s/全容量待验。v1.2/四契约不动，不安排时间，继续隔离联调端点。

C具名固定版本primary对照：实际Spring6.2.7 jars/javap及StreamingResponseBodyReturnValueHandler源确认null接管/框架getter与flush边界；Java21 ThreadPoolExecutor有限队列/拒绝和PG16 RR以作者primary归档及真实测试为证。仅局部实际读取，不声称上游全文审阅或复制开源代码。

### G6 O2b D common父子授权与私有准入验收（2026-10-21）

限定D Accepted：真实original Portal decode与Q exchange，独立固定G exchange/actual authorize/open，opaque G holder，再经authority一次parent-child association得到E exchange、实际authorize/同receiver frame child open、生产受限receipt导出及G一次真验；双方check分别验证各自fresh current。G固定gateway.run.events.observe/private profile，只允许私有G验证入口，不新增publicroute或放宽requirePurpose。旧A standalone明确拒child marker，新child严格要求实际签名ticket/current一致的私有证据；没有用public DTO/Actor造权。

Opening自decode/Q原时点计5秒及原55秒硬界、自动到期、同64slot有限runtime转移与actual active退出后释放；不复制scheduler、不把短current或新hop重设硬期限。authority有界64parent/一父一子原子状态，过期前不为容量逐出防重放记录；G/E source/subject/run/cursor/完整关联一致、current版本和roles可独立。receipt为actualENGINE_CHILD owner/live只读≤4096ASCII JWS，不是G权限或后续任意字节认证；continuation不跨consumer。

D最终六类只一次实际87 tests=86 passed/1F/0E/0S（exit1），新G18+child22与原A28/closed2/Service14均通过。原失败是继承selector断言，永久保留原87/1F；后续唯一测试方法体迁移另一次完整3方法实际3/0F/0E/0S（exit0），fresh review Spec compliant/Approved C0/I0/M0。仅测试delta1、production/fixture0，三方法名/顺序及其他两方法和方法外bytes保持。不得把两阶段改写成87全绿。D审计5阶段6476 distinct/own6245，迁移审计5阶段1835 distinct/own1622，原始命令/Running/XML/source epoch与单次不同patch隔离回放均有限核对。 独立review docs/evidence/review-iam-stage-4-o2b-d-common.md为Spec/Quality Approved；root有限审计分别为 .local/iam-o2b-d-root-check.json、.local/iam-o2b-d-selector-migration-root-check.json。最新source603（SHAab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4），target历史逻辑2118（SHAf04cd892a8842083b1d546795a5ce146aa3b4edec794f90f3fa0f2e58035a893），有限overlay仅实际common compiler/resources/fixtureJAR/actualRunning报告，新diagnostic分开；历史逻辑不是当前全后端classpath。没有重跑原C29/修复24/旧B65、PG或全后端。

D独立review原C0/I0/M3中的M1继承selector断言已由上述独立迁移关闭；M2原3035B early replay独立check/apply raw未即时落盘，保持原现场和证据缺口，最终不同patch一次603完整回放不补旧记录；M3本轮D compiler清单JSON-Buffer归档格式不一致，冻结原件可无损解码并与overlay原始bytes一致，迁移新清单已按原字节归档。M2/M3仍作为非阻断Minor保留，未重写历史。 原C M1工具诊断/M2早期五阶段raw-only XML/epoch缺口及所有历史Minor继续保留，不补造、不重写历史。

当前只common协议和受控issuer验证，不等于生产authority已支持/部署，也未开放gateway实际EVENTS。下一E：真实engine child→同RR完整材料私有packet，保留原complete-before-release及16MiB池；再F：固定engine TLS身份/CA/必要mTLS、完整packet与独立G材料验证后公开SSE。签名关联/摘要不替代engine来源认证，gateway不把可信PG事实扩大为独立重算所有root payload。production retention/active prune/writer-replay兼容、RR/CHAT/F/真实IAM/K8s/生产容量继续待验，不承诺socket物理强断/撤权SLA。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12进行中；v1.2/四契约不动、时间不安排、新工程隔离继续。

O2b D沿用本工程已记录的Spring Security/Nimbus验签和Java21有界executor/单调时钟实现参照；本轮新增固定私有G/E profile/operation/receipt约束由docs/evidence/iam-stage-4-o2b-d-decisions.md和receipt-export-decision.md明确，不宣称标准OAuth互操作或生产issuer已实现。selector迁移仅对齐Accepted C目的规则，无新生产依赖/开源实现复制。

### G6 O2b E引擎child与完整材料私有报文验收（2026-10-21）

限定E Accepted：真实G/E签名链与engine ServiceAuthorization/filter/frame，固定private Accept只走实际child open，普通表示只走standalone open；双向拒绝/no fallback，非IAM私有在legacy前拒绝，同三参handler/action和debug/v1限制保持。HELLO从actual owner/live/ENGINE_CHILD production出口取得receipt，首次fresh RR全部验证之后与首WINDOW同consume输出。receipt仍不是G权限或任意字节认证。

私有v1固定24B APEV prefix和44B APCM COMMIT，10键header、完整14键WINDOW、HELLO/CAPACITY，header8KiB/metadata8MiB/payload256KiB及checked长度交集。完整initial/provenance/三root refs/state/event refs/revision/all scopes/完整parents/直接父/11键state/5非payload事件字段/window来自同一原RR；历史opaque grant/operation ID保留，可用service票/continuation不出wire。采用同borrow两遍受限count/hash/UTF8，第二遍等长等hash并live才COMMIT，不分配完整大wire buffer。

沿原B192MiB账本对新增模型/scratch/descriptor/隔离copy分配前收费，保留16MiB response池、270437B frame、2reader permit和有限原runtime；原B读者/普通C路径保持。entry/action/final guard失败也先actual complete，再释放B/frame/slot；阻塞get/write/flush/complete仍持额。首/游标reset/必要终态为完整材料WINDOW，后续活动idle可无包，EOF不证明terminal；partial不advance，不跨generation拼包。

原E独立源码epoch的最终7类45项通过（新增21、受影响C24），保持原运行及90459B patch/一次check-apply/608回放冻结。本轮fresh fixes仅Service+HTTPtest delta2：有效RED3/3F；初始有限final4为3pass/1E，write核心断言通过后在postrelease长存探针遇renew generation交错。仅测试尾部改用新正常holder，write-corrected单项通过；生产同字节/另三方法及核心断言未变的继承经原reviewer和root核实，不声称一次4green或最新epoch重跑其余三项。两项I1/I2均关闭。新19201B delta2 patch首次check/apply均0，完整608名称/bytes/SHA一致；新530有限overlay合并历史逻辑target2148，新4份diagnostic另列。 fresh独立review docs/evidence/review-iam-stage-4-o2b-e-engine-packet.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-e-root-check.json、.local/iam-o2b-e-fixes-root-check.json。最新source608（SHA303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf），target历史逻辑2148（SHA30422b7bcf37c2e31ef6c596ced94543d432bf847bde17b513640d6dba403f51）；overlay按真实compiler/resources/common fixtureJAR/actual Running报告有限合并，新dumpstream/dump分离，历史逻辑不是当前全后端classpath。

本轮E修复未关闭问题C0/I0/M0。pre-HELLO失败现为固定有界ApiResponse JSON；CAPACITY输出异常也在beforeRelease内finally等待actual complete，再归还B permit/frame/slot。原E初期TDD顺序/controlled-baseline、HTML400/stale INVALID及本轮0 Running INVALID、初始final1E保留，不补造、不改写。I2为实际已授权borrow上的编码故障及write/flush注入，不是最大BUFFER_LIMIT压力；192MiB为静态保守模型及账本断言，未记录精确heap峰值。F仍待实现，三root大payload不出wire，未验实际TLS来源/网关完整材料/公开SSE/真实外部IAM/K8s/生产容量。 原D87/86pass/1F和独立selector3green永久保持；E受影响C24在新源码epoch回归与历史C阶段严格分开。旧C M1工具诊断/M2早期raw-only XML/epoch缺口、D early独立记录缺口/JSON-Buffer归档及其他历史Minor不补造、不隐去。

下一F仍待实现/验收：复用D Opening的原Q票实际metadata HTTP七字段→固定engine HTTPS身份/CA/必要mTLS与正确workload secret_ref→完整packet decoder/COMMIT/receipt关联/完整scope直接父与state/event摘要及fresh G current→实际公开SSE。E不导出三root大payload，F不能声称重算其payload SHA；engine可信PG负责row membership/latest/同RR选择。当前未开放gateway EVENTS，也不代表生产issuer/TLS/网关材料验证/真实外部IAM/K8s/生产容量/Socket强断SLA完成。production retention/active prune、RR/CHAT/F与全17需求/34任务/30验收继续待验，T01唯一完成、T09/T11/T12进行中；v1.2/四契约/时间约束及新工程隔离不变。

E采用新工程已有B/C guarded borrow、真实PG RR与beforeRelease生命周期；本轮仅局部容量try/finally与既有ApiResponse出口修复，注释说明actual complete退出先于资源释放的不变量。具体固定wire、完整材料和预算依据见iam-stage-4-o2b-e-packet-decisions及wire/header supplements，修复及真实测试限制见iam-stage-4-o2b-e-fixes-report；不新增未阅读外部源码的对照声明。

### G6 O2b F1严格私有报文解码与本地预算验收（2026-10-21）

限定F1 Accepted：仅gateway infra新增strict packet decoder与有限resource budget及两测试。逐段验证24B APEV prefix/44B APCM COMMIT、精确segment/packet SHA、ordinal、长度与严格UTF8/JSON结构；一次只持有一个有界包，无下一包预取。结果明确为UntrustedPacket，不代表engine来源、receipt验签、parent关联、run/floor/current、业务row SHA或公开输出许可。gateway EVENTS继续关闭，STATUS/POST、common/Controller/assembly/main/HTTP/TLS/四契约保持。

实际测试保留五阶段：功能RED2失败；首次green编译失败不计RED；compiled17含预算2失败；纠正及新增focused5通过；唯一final20通过（Budget9+Decoder11，16main/13test真实编译，common无Running）。root六阶段3610个不同物理文件审计PASS；未重跑作者成功测试、PG或重放。原52743B文本patch首check/apply均0但两生产文件末尾CR丢失，原失败现场完整保留；另制22378B二进制patch、独立608基线首check/apply均0，一次完整612字节核对吻合。 独立review docs/evidence/review-iam-stage-4-o2b-f1-gateway-decoder.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f1-root-check.json。最新source612（新增4、SHAb391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4），target历史逻辑2167（SHA86cf879d8893d7b49bcec0e261a262e1339596f2ce5c431e37190cb07c7530f0）；仅合并实际compiler/resources/common fixtureJAR/actual Running有限overlay，新diagnostics另列，不称全后端当前classpath。

M1保留：4份Manifest-JAR跨盘诊断、既有弃用与日志路径提示，不隐藏、不重跑。parser 65667584B静态模型小于64MiB预留，仅固定ReaderBased路径；allocation/backing观测不冒充精确存活堆上界。固定schema使部分JSON深度/token边界不可达，已明确交集限制；UntrustedPacket没有业务授权效力。F2 TLS/HTTP/Servlet资源和来源、receipt/G-current/完整验材与SSE仍待实现；未宣称生产容量。 原E45、fixes初始final3pass/1E及纠正write1pass、两份patch/回放/独立review/审计保持冻结，不重跑或改写；旧C/D证据缺口与所有已记录限制继续保留。

下一F2仍待实现/验收：Opening原Q票实际metadata HTTP七字段、固定engine CA/hostname/origin HTTPS、发送headers前实际child/G live、真实receipt关联/fresh G current、完整nested schema/scope直接父/floor/initial/state-event业务规范化SHA、公开SSE/frame/cursor/terminal及真实upstream abort/close/actual complete所有权。G/E均已有自动renew，不另造scheduler。F1不计HTTP/TLS/Servlet底层buffer；其预算与来源必须在F2另证，E16MiB也不能替代。三root大payload仍不出wire，不假称网关重算其payload SHA。production retention/active prune、真实外部IAM/K8s/生产容量/强断SLA与全17需求/34任务/30验收继续待验。T01唯一完成、T09/T11/T12进行中，v1.2/四契约/时间及独立新工程隔离不变。

F1开源对照限实际阅读的FasterXML Jackson 2.19 ReaderBasedJsonParser、JsonFactory/Builder、CharsToNameCanonicalizer、TextBuffer及约束机制与固定依赖jar；采用局部严格UTF8/JSON扫描、关闭回收与字段名缓存、固定parser预留、带所有权borrow。逐文件范围及固定SHA见F1报告/provider模型/独立复审；未声称完整通读Jackson、JDK或HTTP/TLS。Git gitattributes官方文档用于独立二进制回放修复；仅private info/attributes，不修改全局/工程配置。

### G6 O2b F2a隔离TLS基础限定验收（2026-10-21）

限定F2a Accepted：新增3个gateway infra TLS生产类、两测试类及仅测试PKCS12素材。固定专用CA/hostname/SAN、单literalIP、TLS1.3唯一AES128GCM；每physical连接C65536累计raw输入减唯一已交付应用字节、W131072累计raw输出，全实例共用两物理槽、零等待队列，单调connect/handshake/idle/hard期限与实际abort/退出后归还。TLS只证明固定服务端来源与组件边界，不给业务授权，组件仍未装配；gateway EVENTS继续关闭。STATUS/POST、common/Controller/assembly/main/pom、v1.2及四契约保持。

原F2a final39仅为历史。独立审查I1/I2/I3、M1/M2已关闭；功能修正唯一final实际Debt7+TLS40=47/0/0/0，raw/XML/Running已核。19main/15test在前一eof-collector-green实际编译，final同618源码epoch按up-to-date执行。新的格式head只改两文件具名行首空白，含注释/字符串的token完全等价，未重编译或重跑47。root分别完成原6phase5941、功能修正5phase6296和格式3phase21个实测物理身份+616复制身份继承的有限核对，不能相加成新测试数。原/新binarypatch分别首次check/apply成功且完整source回放一致；最新完整618为明确复制快照。原612source逐字节不变，累计新增6。123份自有(run,PID)真实退出已核，早期cwd仅请求字段、numericPID复用和失败历史如实保留。 独立review docs/evidence/review-iam-stage-4-o2b-f2a-tls.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f2a-root-check.json、.local/iam-o2b-f2a-fixes-root-check.json、.local/iam-o2b-f2a-format-root-check.json。source618（新增6、SHAe9a961138967910fae61a2f611c8fd0f69d1391c4b4c973dc78b79da8e6be1e0），target历史逻辑2202（SHA95313cf613f9b3045eb85b524740a77b2fb36fc6a6148ce83b43cce75d3d8067）；finite overlay只采实际编译/资源/fixtureJAR/actual Running，新diagnostics单列，不冒称全后端当前classpath。

保留两项非阻断限制：M3原13份与功能修正6份Windows Manifest-JAR跨盘诊断及旧deprecation；M4原failed thresholds的较晚input-source观察不能当编译epoch。原plain常量观察声明不成立，由新报告明确纠正，只以新有限raw分类/额外accept负控制建立当前证据，不追溯改写旧报告。 H32768/peer chain4只在全新隔离测试JVM的明确启动flags下实际验证；生产launcher与静态JSSE属性初始化未验证，本轮不修改main。未知DER/X509全局cache/JCE/native费用与完整TLS存活堆/全链192MiB/生产容量继续待验。原F1归一化失败与另制binarypatch完整回放，以及全部E/C/D历史证据继续冻结保留，不重跑或抹平。

下一F2b实际Apache socketFactory/TlsSocketStrategy接缝、原Opening Q事实HTTP及私有E输入、send-header前真实proof与有限HTTP费用；F2c真实receipt关联/fresh G current/完整nested schema及scope父关系/floor/initial/state-event规范化SHA、公开SSE/frame/cursor/terminal和Servlet实际complete所有权。完整跨JVM engine/issuer/PG正例、production retention/active prune、真实外部IAM/K8s及强断/生产容量SLA仍待验。全17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中。时间安排及独立新工程隔离约束不变。

F2a源码对照继续依据原TLS报告、冻结JSSE preparation及原审查所列固定JDK21 InputStream/SunJSSE具名范围，未声称整库审阅。修正ApplicationInput显式持有整个readNBytes(buffer)/transferTo调用ownership，包含目标write阻塞期，内部唯一credit；不以继承InputStream循环替代并发所有权。plain仅分类外层record/非TLS数据，type23保持opaque，不声称解密wire。格式epoch以独立token/行首空白及明确copy lineage复核，不冒称新增编译或测试。下一Apache5.4.4/core5.3.4日志与socket接缝对照仍属F2b准备，尚未实现/验收。

### G6 O2b F2b1原Q HTTPS元数据限定验收

限定F2b1 Accepted：同一实际Opening的原Q仅一次exchange，私有Session固定HTTPS origin/literalIP，通过已验F2a的真实TLS handle接入Apache固定socket/upgrade路径；在实际executor及writer首字节前复核同一请求/原Q/Opening/期限。私有无headers/wire日志Managed adapter、零复用/重试/重定向/代理/压缩/cookie/auth fallback，严格有限head/body及EVENTS流式schema解析。EventsMetadata为绑定同Opening/Q的事实，不能充当G或业务permit。每Owner预留3MiB加128token，持有直至自有连接、活跃读写及worker/timer实际退出；费用仅覆盖具名HTTP组件模型，未覆盖全TLS/JVM/native。当前组件未装配，gateway EVENTS仍关闭。

原F2b1 epoch实际final18（metadata7/HTTP11）；修正epoch新的确定性并发行为RED1F/0E、focused3绿、final HTTP14/0F/0E/0S，focused2真实20main/17test编译、final同完整621 epoch up-to-date；没有重跑旧18或metadata7。真实TLS CL8192及chunked8192成功、chunked8193读取超限/未知尾部主动关闭、原Q一次exchange/七事实与实际退出已补齐。root原审计6阶段/18095 physical，修正审计6阶段/6622 physical，另有限live source名字621独立一致；metadata词法相同和STATUS前6168B exact，18新增child全部实际exit/collector join（17绿0、1RED1），finite overlay143合并历史logical2226。 独立review docs/evidence/review-iam-stage-4-o2b-f2b1-http.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f2b1-root-check.json、.local/iam-o2b-f2b1-fixes-root-check.json、.local/iam-o2b-f2b1-fixes-live-names-root.json。source621（本轮三新增、一已有metadata client修改，其余617不变；SHA7ce27abedc333786bff2c7c886ab6092814b52118bf1fd3fd87f6a52eacf81fa），target历史逻辑2226（SHA4b7643c6b7f82ba96199f3d9b8e934089f94cbeb084e383ad13f5c6cf6ec3f53）；finite overlay仅来自实际compiler/resources/common fixtureJAR/actualRunning，不能当全部当前classpath或新部署。

原I1/I2和M1已闭合，M2 Windows classpath/deprecation诊断留项，修正新增3份344B与原10份分开保留。历史API缺失/harness RED不能说成完整业务guard RED。本轮第二辅助replay-index add与plain apply交叠失败、无预存独立preapply全工作树清单，以及两次高层metadata stderr仅有transcript的限制继续保留。root独立核主baseline621 actual tree、现有raw四个正反向image及blob SHA1、其余617前后与复制谱系、完整after621，确认首次普通worktree check/apply各1次exit0的有限重放闭合；未把辅助index失败或摘要当PASS/raw、未再运行Git apply。3MiB仅固定HTTP组件工程模型。公开EVENTS未装配，G/E、完整材料、SSE/Servlet、真实全链和生产启动/容量仍待。 前置F2a原39、功能修正47及纯空白token/copy-lineage分属既定epoch，不重复合计为本轮新测试；新一轮只以本轮实际Running/XML/编译及端点记录为准。原报告/patch/replay/诊断与失败历史继续保留。STATUS既有流程/POST、common/Controller/assembly/main/pom/前端、v1.2及四契约保持。

下一F2b2在同一私有Session由真实Opening转交G、绑定child E并读取私有包；F2c1真实receipt关联、fresh G current、完整nested schema/scope父关系/floor/initial及state-event规范化SHA；F2c2公开SSE/frame/cursor/terminal与Servlet实际生命周期。真实gateway/engine/issuer/PG/TLS联合正例、生产JSSE启动/完整容量、retention/active prune及真实外部IAM/K8s仍待验。全17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中；继续独立新工程，时间安排不在范围。

F2b1开源机制对照沿已冻结具名补充：Apache HttpClient5.4.4/Core5.3.4的固定socket operator、actual request executor/writer、DefaultB连接（避headers/wire日志hook）、parser/有限head和立即abort；Jackson2.19.0固定流式parser/generator关闭共享recycler/name canonicalization，拒unknown/duplicate/trailing。资料见 .local/iam-o2b-f2b1-log-source-note.md、.local/iam-o2b-f2b1-actual-api-gate.md、.local/iam-o2b-f2b1-http-budget-notes.md及原冻结draft具名官方源码URL。本轮独立复审不声称重新完整审计这些库或所有JDK/provider。

### G6 O2b F2b2同会话私有事件读取限定验收

限定F2b2 Accepted：同一私有Session已验证原Q事实后，原Opening转真实G、恰一次交换同G child；在固定HTTPS/TLS/actual request双guard下请求唯一私有MIME，F1按单包同步借用消费。七事实在Opening移交前私有保存，后续仅同G最新current控制，不把短child JWT用于每包续验。Q物理图和timer实际退出后才E图；原t0的5秒Opening/55秒hard、E head2秒与15秒idle及阶段取消机制保持。纯有界E错误code解析与原STATUS/Q事实方法保持独立。Q-only Owner3211392B；consume前新增1MiB+128token，E-phase4260096B，退出/borrow未完成则持费并拒第三Owner。费用仅有限组件模型，不是RSS/全TLS/JVM/native容量保证。公开EVENTS尚未装配。

最终source623同一epoch真实执行31项（原Http14、新Authority8、新Http9），0失败/0错误/0跳过；实际新编译20main/19test。root六phase有限审计PASS，9551 owned、三改两新及其余618精确字节、原STATUS6168B与原14断言、主before621真实Git树、预存replay before621名字和字节、首次plain check/apply各一次0与完整after623闭合。finite overlay155→历史逻辑target2239；60个实际child均waitFor且collector退出（53成功、7保留历史失败），最终31 child退出0。普通8482B/额外签名前padding8559B child、43原始拒绝向量、21345B CL/chunked、原t0 55011ms/55callback、15134ms idle及实际撤权读退出已按原始证据核对。没有重跑作者成功测试、PG、provider或Git replay。 独立review docs/evidence/review-iam-stage-4-o2b-f2b2-private-http.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2b2-root-check.json。source623（三既有修改、两新test、其他618不变；SHAc2dd5086c9718098dc0957aa7f0dd3e7c809f56793d7eaea1b53e3987262c246），target历史逻辑2239（SHA38cd8356238f4363f10d3bbdf46660b727d10fbcbdf8cf47ad6ed1c68311bfd5）；finite overlay仅actual compiler/resources/common testJAR/Running，不作全部current/classpath/部署声明。

保留2项Minor：12份各344B Windows classpath/编码诊断及deprecation，新增并发测试长单行可读性。初始Python GBK在写入前失败，其raw仅存在工具transcript，owned为准确observed摘要而非伪造raw；stage子进程归属含mtime启发式，实际退出独立核对。费用仅有限组件模型，排除全TLS/JVM/native/common内部图和任意consumer图；G.close不证明common内部任务物理join。HELLO receipt及WINDOW目前仅未授权结构输入，C1真实receipt/material/current校验、C2公开SSE/Servlet、真实全链和生产launcher/容量继续待办。 原F2b1的18、修正14及本轮各stage严格按source epoch保留，不把旧结果改名新跑或合并为虚假同场测试。v1.2/四契约、common/TLS/F1/engine/main/controller/assembly/pom/前端保持；时间安排不在范围。

下一C1在同Session验证真实receipt与parent-child、完整nested schema/scope直接父/floor/initial、state-event业务canonical SHA和fresh G generation，借用VerifiedWindow；C2再接公开SSE/frame/cursor/terminal、Servlet实际生命周期与物理abort。真实gateway/engine/issuer/PG/TLS全链、生产JSSE launcher/完整容量、retention/active prune及外部IAM/K8s仍待验。17需求/34任务/30验收未全闭合，T01唯一完成、T09/T11/T12进行中，继续独立新工程。

F2b2在既有JDK JSSE固定profile与F1 ReaderBased严格解析上，新增私有E响应分期和同Session G/child传递；本次review具名核对PortalEventsResponse holder/slot/task取消回调锁边界、TLS hardDeadline/实际idle与F1 borrow关闭保留。未新增或声称已读其他开源源码。

### G6 O2b F2c1完整事件材料校验限定验收

限定C1 Accepted：同一私有Session的原Q七事实、真实G及其child实际接收链生成的HELLO receipt恰一次验证，交叉核parent/child关联；WINDOW严格14键和完整nested材料、root/state/event与直接父精确并集、完整父数组、initial/floor/代际逐项验证。state/event业务SHA按精确Java String排序、BigInteger/BigDecimal与JSON转义语义流式canonical，数值展开前容量准入。最多两次完整P3重验；同步VerifiedWindow只在同Session/具体packet/同代借用内有效，不产公开cursor/终态/浏览器字节。固定80MiB先收费、真实解析/consumer/borrow退出才退款，并早于下一F1 scan；不是全JVM/TLS/native容量保证。

原C1 candidate epoch的19/19验证与14个最终child实际退出，继承其完整独立机械审计；修复后新627 epoch只新增一次受影响矩阵8/8（Authority3＋原Verifier5），最终3个child实际waitFor并collector join，未重跑原19，不能合称同场27。旧实现的真实并发/重入RED均保留；I1同窗口单writer在任何payload遍历前原子拒绝，第二generator调用0，异常后顺序使用成功；M1 schema/factory仅排版。并发/reentry单Owner88342871B、双Owner retention176685742B/第三拒绝/实际writer退出后退款。root新delta六phase PASS核627源码两修改/其他625 exact、actual Git627 before/after、首次plain check/apply各一次0/fullafter627、finite378→历史logical2269和全部6个child实际退出，未代跑成功测试。 fresh review docs/evidence/review-iam-stage-4-o2b-f2c1-material.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2c1-root-check.json、.local/iam-o2b-f2c1-fixes-root-check.json。source627（一既有修改、四新含classpath资源、其他622不变；SHA6b4a6488efa08b27266ccceff39fd3e79b62b137a3edcc6dfd3b071abd83f68c），target历史逻辑2269（SHA1998bf8be7352c46b2ca2698ca16e90cb092b5a09eb5817401b8bca441caa378），finite overlay仅actual compiler/resources/common fixtureJAR/Running。source resource身份与Maven copy及root E WINDOW提取gate闭合；旧HELLO/JWT/私钥不进入新asset，新test运行不读ignored.local或engine业务JAR。

本次I1/M1闭合，仅原M2编译编码/deprecation诊断保留；原F1 ignored.local whole-branch可移植性仍属后续对应任务。writePayload仍为排序/数值归整后的JSON；C2需最小同window rawpayload共享writer借用接缝以保留浏览器原JSON数值/转义。公开EVENTS/SSE/真实Servlet生命周期、实际enginePG latest/membership/rootpayload与生产issuer、真实跨服务首链尚待C2；不据局部验证签收完整IAM或全JVM/TLS/native容量。 旧E/F1/F2a/B1/B2各stage结果按原source epoch保留，不重跑或合并为虚假同场证据。v1.2原文/四契约、common/TLS/F1/engine/pom/main/controller/assembly/前端保持；时间安排不在范围。

下一C2依实际C1私有API接公开SSE/Frame/cursor/terminal与Servlet生命周期，采用真实WriteListener实际写、同Session同步借用、有限ready-idle等待及精确request CLOSE_NOW/disarm；bootstrap在原请求线程等首个可信window后注册并返回，具体源码/并发/有限退出必须另验。真实gateway/engine/issuer/PG/TLS全链、生产JSSE launcher/完整容量、retention/active prune、外部IAM/K8s仍待。17需求/34任务/30验收未全闭合，T01唯一完成、T09/T11/T12进行中，继续独立新工程。

C1参考定位：Jackson Core受限stream parser/generator、strict duplicate检测与Unicode surrogate写出配置按本工程固定依赖源码/测试对照；C2 Servlet6/Tomcat10.1.41官方实现分析记录见.local/iam-o2b-f2c2-write-thread-gate-notes.md、listener-bootstrap-notes.md和bootstrap-root-decision.md。原payload必须保留词法内容，canonical业务SHA仅用于材料校验，公开SSE不得复用归整后的writePayload。无新依赖/无相邻旧工程复用。

### G6 O2b F2c2a未装配SSE与真实Servlet组件限定验收

限定C2a Accepted：同Session完整receipt/material验证后的原payload以受控同步raw借用写出，仅删除字符串外JSON格式空白，保留原数值词法、转义/Unicode和字符串空白；raw/canonical共享单writer准入。原Q/G initialCursor保持固定，WINDOW.after/event/reset依当前已成功公开flush后的cursor；只有完整write＋requiredflush/readydrain＋最终fresh检查才确认推进。reset优先、status最多一次，终态排完backlog才结束，EOF不冒终态。

真实Tomcat HTTP1.1 NIO中，原请求线程有限等首可信窗后登记listener并返回，首次get在原请求线程完成guard，实际WriteListener执行ready/write/flush guard与有界chunk；ready-idle轻shell有限等待、heavy helper真正返回/slot detach后才进下一F1scan。固定Http11NioProtocol＋公开Processor包装以真实socket锁串行完整IO、CLOSE_NOW与回收，原请求Valve绑定每次REQUEST新epoch；外部stop先释放应用gate再等待socket锁，onComplete及delegate.recycle前失效旧token，late abort不触复用后的请求；SSE实际worker/IO/borrow/重状态/completion gate先于Http旧G/费用cleanup，失败或卡住继续持费并拒第三Owner。这里只签收已实际验证的应用组件，不声称全container/channel/TLS/native同步join。

证据按各自真实字节epoch继承：原C2a最后29方法/38child，后续Q与原REQUEST/分配修正13、7、2各属独立epoch；共享人口/强锚修正保留14与10的失败、corrected7/1fail、action1及elected-join3，mixed首INCONCLUSIVE与改fixture后Handle/Processor通知各1、shared reuse2分列。最终Http修正先5方法GREEN/6child，再对真实c/r均非空见证分别做旧行为stop单项RED及当前stop单项GREEN；其余4方法继承先前epoch，11child全部实际waitFor/collector退出，无GC。两个实际取消/cleanup重试窗口在helper暂停时Owner1/reserved7405824且另一closer未退款，helper和线程真实退出后回3145728基线；light-shell实测9315526→3145728。四轮root各六phase有限审计、各自首次plain check/apply及全native blobs/源码名/有限target已缓存，未重跑已绿测试、原审计或成功Git。 独立review docs/evidence/review-iam-stage-4-o2b-f2c2a-sse.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2c2a-root-check.json、.local/iam-o2b-f2c2a-fixes-root-check.json、.local/iam-o2b-f2c2a-retention-fixes-root-check.json、.local/iam-o2b-f2c2a-http-exit-fixes-root-check.json。source630（3新4修改、其余623 exact；SHA6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d），target历史逻辑2298（SHA304be175bbbcd46f11fa045617b34c456526be83925a0599b3de43877081eeda），finite overlay只actual compiler/resources/common fixtureJAR/Running；旧C1原19/fix8等按各自epoch继承，不重跑或合为本次同场结果。

C2a已无Critical/Important。保留两项非阻断Minor：既有日志/编码/容器关闭诊断噪音；最初prelaunch断言失败helper无独立历史快照，其准确字节不可追补，后续actual source/测试/Git证据独立保存。支持的VM/layout、共享TLS/config/B2归属及非穷尽并发属于固定有限模型；库长期不退出时继续持费，不承诺GC/可用性期限。 未装配component无扫描/自动路由：公开IAM EVENTS仍关闭。下一C2b显式common ingress EVENTS claim、Controller/Assembly/同ControlledProtocol＋Valve接线，以真实独立gateway/engine/issuer/新PG/TLS和生产E encoder≥65父材料做首链；不能以C2a测试Servlet/private peer代替生产接线证据。固定connector每JVM终身唯一，以共享1536个名额同时计数原delegate与独立Handle/State/Session小图，forSse在Session创建前准入，final Session与State锚定唯一Handle；只在实际GC队列通知后归还名额，构造不确定失败烧槽，不靠旧1218估算。GC/通知迟延与烧槽可能降低可用性。预收3MiB全局基线直至JVM实际退出；Owner清零后该基线保留，双Owner候选合计200576852B属于有限工程模型。生产JSSE launcher/全容量、retention/activeprune、外部IAM/K8s和整个17需求/34任务/30验收仍独立待办。T01唯一完成、T09/T11/T12进行中；v1.2原文/四契约/时间与新工程隔离保持。

C2a开源机制对照：固定[Tomcat10.1.41 AbstractHttp11Protocol](https://github.com/apache/tomcat/blob/10.1.41/java/org/apache/coyote/http11/AbstractHttp11Protocol.java)的原delegate创建、公开Processor委托与socket/recycle次序；[JDK21.0.12+8 ReferenceQueue](https://github.com/openjdk/jdk21u/blob/jdk-21.0.12%2B8/src/java.base/share/classes/java/lang/ref/ReferenceQueue.java)与[PhantomReference](https://github.com/openjdk/jdk21u/blob/jdk-21.0.12%2B8/src/java.base/share/classes/java/lang/ref/PhantomReference.java)的自动通知/私有poll身份机制；[IdentityHashMap](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.12%2B8/src/java.base/share/classes/java/util/IdentityHashMap.java)具名固定容量与clear。源事实与范围见各source-only analysis及审查记录；实际代码不反射私有hook、不复制Tomcat/GC框架、不手工enqueuing/生产GC。新Http只加一标量门与两个返回helper，原Owner规则不变。

### G6 O2b F2c2b1精确EVENTS入口与显式生产组件接线限定验收

C2b1已限定 Accepted：IAM 原REQUEST的精确 EVENTS 路由经真实验签JWT、实际Controller/Handler、一次私有capture、实际Spring6.2.7 manager/native响应身份绑定后启动生产SSE。仅该purpose允许唯一Last-Event-ID请求头或单一字面query二选一，默认0、canonical ASCII long；其余RUN/STATUS/session规则不扩大。实际MVC supplied response承担getter/listener/写入，不绕过框架包装，不把proof带入异步。已接管响应后的启动失败执行原清理与持费门，避免普通JSON混写；raw入口仍保持严格facade与抛错语义。

显式import的生产配置提供真实Http/Sse/RunEventsProxy beans，并通过公开Boot API安装同ControlledProtocol/ContextValve。固定最终1024连接/64线程/64cache、内部executor、HTTP1.1；真实独立JVM验证正常启动及5种错误覆盖配置拒绝。共享1536/1792B/3MiB及实际helper/worker退出、未退出继续持费等原C2a门保持。

新Common2及原Common3在其实际epoch通过；真实Boot默认/头/query SSE、framework/54负向、5种最终profile拒绝、原raw helper及最终单请求故障/Q错误/缺组件按各自epoch验证。22个真实child=17成功/5保留诊断失败，全部waitFor/collector完成；原raw child成功与父级UTF8元数据error分开保留。最终head仅窄6child对应JUnit1通过，不包装为最终全套。root六阶段有限审计通过，实际634 before/after/native blobs及8段binary patch/plain check/apply首轮各一次闭合。 独立评审 docs/evidence/review-iam-stage-4-o2b-f2c2b1-events-wiring.md：Spec compliant/Approved，C0/I0/M2；root审计 .local/iam-o2b-f2c2b1-events-wiring-root-check.json 六阶段通过。source634（对C2a为4修改4新、其余626 exact；SHA c91a517192cb8caecf84da5726640d90c759793065dce9cbe0e94291a737d74d）；target历史逻辑2314/finite417/delta48（SHA e8c4ef2cf272f953f4e1282b939d8f6fa055a487da1b16eac9393b0dc150ee3f）。这是限定组件与各真实epoch证据，不是最新全工程测试或部署记录。

保留两项Minor：编译deprecation/unchecked与乱码诊断摘要不完整；原raw JVM缺少两项Tomcat ThreadLocal/RMI leak-detection opens。原始失败、元数据错误和告警均保留，只在未来有功能理由的新runner epoch改善，不为清理文案重跑绿色测试。有限资源模型不等于全JVM/native容量或通用无泄漏保证。

下一C2b2使用独立GW/E/网络issuer、新PG库和正常CA/SAN TLS，验证真实E reader/encoder≥65完整父材料、游标/状态/权限变化及实际退出；C2c随后闭合四个前端消费点的raw payload、精确decimal cursor/watermark。当前公开EVENTS仅在显式IAM装配组件中可用，默认GatewayApplication生产IAM启动尚未验；private peer不替代联合链。外部IAM/K8s、发布部署、全容量与17需求/34任务/30验收仍独立待办。T01唯一完成，T09/T11/T12进行中；原v1.2、四契约、时间安排和新旧工程隔离保持。

本轮机制对照（具名API/窄范围，非完整上游审计）：Spring Boot3.5.0 TomcatServletWebServerFactory公开setProtocol/context customizer及TomcatWebServerFactoryCustomizer属性覆盖顺序；本地Spring6.2.7 javap确认WebAsyncManager/StandardServletAsyncWebRequest响应接缝。原始入口见 https://raw.githubusercontent.com/spring-projects/spring-boot/v3.5.0/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/web/embedded/tomcat/TomcatServletWebServerFactory.java 。root另读固定JDK21.0.12+8 MultiExchange具名重试分支：GET/HEAD在部分连接失败路径可重试，故单请求故障fixture使用Socket并实测REQUEST=1；这不补造历史请求计数。源码 https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.12%2B8/src/java.net.http/share/classes/jdk/internal/net/http/MultiExchange.java ，窄读记录 .local/iam-o2b-f2c2b1-jdk-get-retry-source-note.md。

### G6 O2b F2c2b2真实网关／引擎／PG联合链限定验收

本轮没有新增依赖或扩大“已读上游源码”的声明。沿用已固定的 Java21／Spring Boot3.5.0／Tomcat／PostgreSQL 机制和既有具名对照范围：SQL statement_timeout 的实际服务端预算验证；HTTP 物理清理责任与实际退出门；标准 Lock.tryLock／TimeUnit 的可取消短片等待，并保留真实 socket→io→token 身份排除。原来的 socket、processor 与完成门未被自定义 reaper 或新 executor 替代。

这些局部行为分别由三个新失败回归及独立限定证书验证，再经真实 E／GW／issuer／PG 联调。没有借用相邻业务工程，也没有把依赖使用当作完整上游源码审计。独立评审 docs/evidence/review-iam-stage-4-o2b-f2c2b2-joined-chain.md 为 Compliant / Approved with Minor，C0/I0/M3；root 六阶段物理、编译、运行、原生应用与保护文档核对通过。限定证书：.local/iam-o2b-f2c2b2-acceptance.json。

### G6 O2b F2c2c四前端协议与流式文本修复限定验收

沿用Vue3.5／TypeScript与浏览器原生JSON.parse reviver词元、fetch／ReadableStream／TextDecoder机制，未新增依赖或替代JSON解析器。只在最终原生holder身份上提取精确数值；流事件保持SSE实际类型和原始数据。注释解释精度／快照原文与权限代际边界，未扩大已读上游源码审计声明。实际Chrome155的原生能力和降级错误由浏览器记录核对；项目版本未按技能较新目录升级。

独立评审：docs/evidence/review-iam-stage-4-o2b-f2c2c-frontend-protocol.md（原 C0/I1/M1）及 review-iam-stage-4-o2b-f2c2c-chunk-fallback-fix.md（I1关闭，C0/I0/M1继承）；限定证书 .local/iam-o2b-f2c2c-acceptance.json，绑定原任务 root-qualification。


### 冻结绑定事务预算的框架机制核对

实际使用Spring Framework6.2.7的DataSourceUtils.applyTimeout及ConnectionHolder剩余期限；按每SQL/逐行/回调/清理边界检查，并把JDBC整秒超时截断至2秒。原独立评审核对的是本工程缓存JAR的真实字节码，未宣称阅读框架源码。修补代码及22项验证、合作式时限和执行偏差见 docs/evidence/iam-model-frozen-binding-review-fix-report.md、docs/evidence/review-iam-model-frozen-binding-review-fix.md 和 .local/iam-model-frozen-binding-root-final-qualification.md。


### LOCAL估算机制限定

使用已固定JTokkit1.1.0的默认本地registry及原共享计数路径；+64是保守结构估算，exact=false，不是模型wire校准系数。实际缓存JAR机制核对、本地原字节绑定及31项隔离验证见 docs/evidence/iam-model-egress-local-meter-report.md；harness收尾复核见 docs/evidence/review-iam-model-local-meter-watchdog-review-fix.md。未声称阅读未读开源源码；范围及历史局限见 .local/iam-model-local-meter-root-final-qualification.md。


### Spring AI1.0原生参数转换对照

依据实际缓存SpringAI1.0/Spring6.2.7字节码与API签名实现共享OpenAiChatOptions转换；Integer seed超界明确拒绝，使用真实SDK call/stream及HTTP encoder验证请求体，不借反射访问私有createRequest，也不声称阅读未读开源源码。代码基线与历史执行I1分别见 .local/iam-model-frozen-prompt-root-code-qualification.md、docs/evidence/review-iam-model-frozen-prompt.md。


### 冻结初始stream实际SDK体对应校验

本轮复用Spring AI1.0原生Prompt/options getter和实际call/stream HTTP编码器，守卫只解析、比较已编码体，未通过私有SDK反射或另造请求DTO验证自己。附加源码文件与完整差异见docs/evidence/iam-model-frozen-wire-report.md；独立审查及源/类/资源/JAR/补丁物理核对通过代码门槛，执行I2/M2及历史I1继续保留。未将SDK编码证据写为真实provider发送、usage、校准、准入或部署验收。

### 初始模型流式组件的校验与清理边界

沿用已经核对的Spring AI1.0真实SDK编码/stream、当前材料绑定与凭据解密机制：本轮把tokenizer发送校验放在凭据准备之后，把final输出校验放在账务和客户端清理等待之后，保持provider事实与输出授权分开。隔离端点验证及限定代码复审见docs/evidence/iam-model-initial-stream-fix-report.md、docs/evidence/review-iam-model-initial-stream-fix.md；完整运行/制品来源与纯换行字节等价见.local/iam-model-initial-stream-root-code-qualification.md。实际供应商校准、计费、默认IAM/D3及部署仍待相应验收；历史执行偏差保留。
### 2026-10-23 初始发送 Schema 声明预检与三档登记

最终 source684 的 ModelInputMaterials 使用已固定 networknt json-schema-validator 内置 2020-12 八份资源及现有 RE2/Plan 有界机制，在付费调用前检查声明；独立复审核对 324 节点保守预算与固定正则工作，资源加载/init/validate 共用有限 deadline，无外部解析回退，较大有效声明可能保守拒绝。没有新增依赖或放宽全局限制。实际对照和局限见 docs/evidence/review-engine-initial-sender-fix.md；该对照不等于所有 Schema 兼容。组件三档正式结论与继承条件见 docs/evidence/component-acceptance-register-v1.md；代码通过不授生产运行权限。


### 2026-10-23 首次无会话 CHAT：I1 闭环与 source688 开发基线

本修补沿用既有共享受保护请求gate、PG权威、Spring AI初始流式发送和已登记恢复机制；未新增依赖或开源源码对照声明。仅新增四名单元素及必要测试，不能把旧源码阅读/原cohort改写为本轮新参考或完整回归。保持Java21/Boot3.5.0/SpringAI1.0.0等固定技术版本。
最终源码688，清单SHA256 `2625ce4d7f40fcf40c0d529f8b0d59284ceda1ff40253eca629f9bc75ff18ca7`；相对原688仅3MOD/685same。生产只增加chatSession/chatTurn/sessionHistory/history四个顶层保留名。真实RED后，四GREEN方法均通过：新建与重放各12请求全部403，初建无新增/外发、重放不改变原事实，合法CHAT/TASK/FLOW通过。root核对2752文件身份、721运行文件/686Java21类/158依赖及四制品317生产条目一致；未重跑旧九重点群组。

生产代码C0/I0/M0、Spec Compliant/Quality Approved，仅作为后续隔离开发基线；正式组件保持NOT_ACCEPTED，P6部分早期原输出安全边界UNKNOWN，不能证明全部缺口属于规则§3，亦不认定已泄露。所登记五组件为0项完全验收＋0项条件验收＋5项不通过，不是全平台统计；没有条件档提案/负责人批准。关闭审计准确口径为79实际guard/176有效身份观察，另2metadata/693空身份项，control另列审计后3次真实结束；原81/869记录不涂改。

依据：新工程 `.local/initial-chat-admission-development-baseline.json`、`.local/initial-chat-admission-root-final-qualification.md`、`docs/evidence/review-initial-chat-admission-fix.md`、`docs/evidence/component-acceptance-register-v1.md`。T31/T32/T33及真实IAM/D3对冲按 `.local/initial-chat-admission-joint-acceptance-obligations.md` 逐项执行；T34未覆盖/未批准不得签收，未来条件档暴露功能或安全缺陷立即撤回。T01仍唯一全局完成，T09/T11/T12进行中、T33未启动；默认IAM/D3、公共token/cancel/session不启用，冻结v1.2、四契约及不排工期保持。