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
