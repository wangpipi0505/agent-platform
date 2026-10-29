# EX-15a 全链独立最终复核

2026-10-29；复核者 `algorithm_interrupt_readonly`。只读当前源码、Git差异、原XML/JSON/CSV、JAR ZIP项目与指定安全指纹，唯一新增为本报告。未运行Java/Maven、Node、PG、MinIO、HTTP、服务或仓库测试；未读取真实凭据文件，未修改产品、测试、历史证据、三份主入口或index，未使用Superpower。下文实际运行均为root的原件，本复核仅重新解析和计算。七份产品/测试均非本复核者所写；本人参与过gateway模块采集器准备，本报告不将该采集器的自审充当独审，而直接重读其原始XML/原因与外部事实。

**结论：接受本卡实现及适用回归，可另立综合验收门；无剩余实质阻断。** `final-acceptance.json`的分门设计合理：原gateway raw原因门、原regression门和allTestsPassed继续false，另将3项原样、11项精确表示变化及1项独立旧classpath债务分别纳入综合无新增回归判断。此判断允许root在精确索引审和本地提交闭合后关闭EX-15a，不把它改写成模块全绿，也不由本报告自行关闭卡片。

## 七文件与断言范围

| 文件 | 本次核对的当前SHA256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java` | `967849596ae6179a2b0858fb39d50a365c65f2370d4e2ad0658dfee44a0569b1` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java` | `d58ac69edce07090d3c3800da2e2785318d00dcf8acb5badbdc0572fb0fb95d9` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `7e4b33db18435af0429b7ea483be3d74c9bf73151912e0eeaf16ab7385c8935a` |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | `75ef9a91d893c20d727e1251123901488719fb81c1e38621ee0171ec51f52837` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ArtifactPersistenceTest.java` | `16cbd8fd8abe960ccc6b72fe7fb1c41dd5883fbd0a3f15f0298a1f46cab75d66` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java` | `64a981a93e22351645fa3d9bd77671a0d1f84db0740e6585737114bd6046eb1e` |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java` | `b4fcd5ab65dc80f0f509c31c1f5bd9ce56d1dd57942ed9d802f8198d99994f32` |

四处产品变化均是已实红后的最小差异：受控相对URL只补生产生成的`art_`形状；同步HTTP finally关闭整批已装载文件，复用原CAS单次关闭；上传在READY前校验实际计数与EOF；gateway仅精确POST `/admin/artifacts`且解析为精确multipart/form-data时给65MiB总wire，其它仍2MiB。未新增执行器、服务、状态表或迁移；原ArtifactController、鉴权/票据、主体归属、异步拒绝、PROCESS和ALGORITHM未扩义。

两个既有Test的原断言未放宽。Multipart原7方法正文保持，新增4方法实红后字节不变；Persistence原11方法保留，原无关tenant只改为本例UUID派生名，0行及自身READY断言不变，避免操作旧固定tenant。v3只按SDK实际精确包装链验证NoSuchKey，保留短流/断流/长度、READY、DELETED、摘要及关闭断言。新增Gateway Test同一B4FCD5AB跨红/绿/Full保持，所有11身份都走完；定向调用Controller的已认证上下文与正式入口FilterChain证据分开，由live补齐真实验签。

## 原件重新解析与完整相对门

本次重新使用XmlDocument.Load读取实际XML节点并复算身份/状态，未执行采集器：

| 实际运行 | 本次复算 | 适用结论 |
| --- | --- | --- |
| `red-20261029-1240`→`green-20261029-1247` | 同11身份7P2F2E→11P，0skip | 两条生产URL与两条真实HTTP资源关闭缺口；原红及停点保留，具体独审见[review-multipart.md](review-multipart.md) |
| `upload-red-v3-20261029-1257`→`upload-green-20261029-1305` | 同16身份15P1F→16P，0skip | 唯一产品红为长流仍被接受；精确失败清理、64MiB和原用例保留，[上传独审](review-upload-boundary.md)已对v2包装夹具错误与v3产品红分开 |
| `multipart-green-upload-20261029-1305` | 11P | 上传修复后的multipart同源回归 |
| `collector/runs/full-skill-20261029-ex15a-01` | 71XML、698唯一身份=582P6F106E4S | 对EX-04最终689逐身份/status比对，缺失0、状态变化0，恰新增9个通过；原116非通过保留，native0仅ignore采集，allGreen=false |
| `collector/runs/adjacent-20261029-ex15a-01` | 5XML、32唯一身份全P | 无ignore/skip，相邻artifact/reference/form与异步拒绝边界保持 |
| `gateway/gateway-red-20261029-ex15a-01`→`gateway/gateway-green-20261029-ex15a-01` | 同11身份7P4F→11P | 四个合法大请求红均为200期望得到502；65MiB±1及普通2MiB±1、未知长度、其它方法/路径/媒体类型负例仍执行 |
| `gateway/runs/gateway-module-20261029-ex15a-02` | 28XML、299唯一身份=284P7F8E，classEvent=0 | 原EX-27的288身份缺失0/状态改变0，新增11精确等于定向绿身份且全P；nativeMaven=1、collector=1、无failure.ignore，原raw/regression/allTestsPassed=false |

本次还逐项核验skill/adjacent/gateway三个Full/回归XML manifest的文件SHA，没有不符。skill原116的status/type/cause/首个项目帧均不变；仅精确AlgorithmMigration旧方法的message/detail变化，115项原SHA相同。对该一方法本次额外提取原XML中的各2 UUID/2 timestamp，检查message+detail联合字面映射双向唯一（两个唯一映射），替换这些准确字面后其余完整文本相同，没有改原XML或用它豁免其它方法。SDK默认5项仍全P，四outcome齐、两个诊断flag未开/诊断JSON0；本轮handoff为已确认退出，不能冒称每次命中未确认分支或覆盖旧Full现场。

## 网关15项原非通过：分三类接受

复核输入固定为run02的[bounded-representation-proof.json](gateway/runs/gateway-module-20261029-ex15a-02/bounded-representation-proof.json)，SHA `e6817b0da75e6681886ffad7861ef80ee5ddd4f088f61e4d13326a07b77a4f78`。独立重新加载其指向的两个原因原件并核输入SHA；15唯一身份及13字段schema均齐，九项身份/状态/type/有序cause/首帧/classEvent/XML元数据逐项相同，60个原message/detail SHA全部重算一致。不是仅采信proof内的true。

- **3项完全原样**：GatewayRunStatusChainPostgresTest的`realGatewayQueryAndStatusReturnOriginalBytes`，SseAutomaticCleanupTest的`actualCompletionAutomaticallyRefundsAfterTransportHelperReturns`，SseResetCleanupTest的`terminalResetAutomaticallyRefundsWhenReaderAbortOverlapsCompletion`；13字段原文一致。
- **11项精确表示差异**：仅已冻结5个Portal方法的focused-/epoch-路径UUID，及6个IntegrationSecurity方法的两个指定Spring类对象identity。22字段共34个准确字面替换，逐个核旧/新各出现一次、反向字面缺席、UTF-16偏移相同、字段字节数/SHA与路径锚点正确。替换后完整其余文本按Ordinal相同；没有删除栈/行号/换行或泛化所有UUID/hex。message+detail及跨方法联合检查7个唯一old→new映射和反向映射均唯一，保持重复/别名关系。Portal源码第120/155行确实自行生成对应focused/epoch UUID。
- **1项真实缺类变化单独归因**：`PublishedMetadataSmokeLauncherTest#launchTwoIsolatedJvmFixturesWithFiniteDeadline`明确排除在上述表示差异之外，按[独立classpath技术复核](gateway/metadata-classpath-change-review.md)精确接受为该旧smoke的固定子JVM CP债务。它仍FAIL，不是该metadata业务已执行通过。

最后一项本次交叉核对了launcher→固定smoke-classpaths.json→SmokeMain→PublishedMetadataClient.ownedTransport→PublishedRunHttp的实际链。固定gateway CP的106项均为本仓现有路径，逐个目录/ZIP查两类均未命中，也没有Manifest Class-Path补入；正式gateway JAR实际包含httpclient5-5.4.4、httpcore5-5.3.4及httpcore5-h2-5.3.4。相关3 POM/传输源码/client/launcher/Main/固定JSON对基线HEAD `0cc96727…`的只读Git差异为0，当前固定CP SHA `b4d0eda0440917306161a0a2fc29c1eefa07bdd86efdfe1256419be6c0ba388b`；本卡四生产修改不进入这条metadata子JVM依赖组装链。

**明确限制**：历史基线没有冻结所有相关运行字节码/JAR，不能还原旧进程为何先报client类、新进程先报core接口，不能声称首次解析顺序或client已加载成功。本项接受的是准确方法/本卡差异范围下的独立非回归技术归因，不是把缺类符号当随机量归一化；不能推广为未来缺类错误豁免。原两种异常名称、原raw门false与未到达的后续业务断言必须继续保留。后续旧smoke修复仍属独立准确夹具工作。

网关SSE38项通过及49条peer子进程记录保持；本次检查49条均exit0、alive=false、collectorAlive=false。forced/off-lock安全属性对照无固定不变量变化；recycled只作观察，未将其当完成证明。四个原fixture库Before/After均为同一本仓PID15204/V:/.local/pgdata、agent_v12 owner、historyVerified且待迁移0，历史JSON全文不变；没有“gateway业务history”或采集器DDL。既有V222源码缺失/target残留如实记录，cleanBuildVerified=false，不因本卡做clean/repair或宣称干净构建。

## 真正进程链与资源后置

第三轮[正式live原件](live/runs/ex15a-live-20261029140249-7cb555ea/run.json)完整native0/complete=true，upload/restart/cleanup三个外部探针均native0。核源码可见探针通过正式18080调用而非直接new ArtifactStore；64MiB payload按8KiB块写盘/发送，下载按8KiB读流，并真实读PG。missing/坏签名Bearer各401及无新artifact行；伪造身份头不能改变local-v12/developer-v12的持久主体。

upload/restart安全回执为同一artifactId哈希、67,108,864字节及SHA `efd6d21bab5ebabd6d298ee79ffe965f1b23ae1ca4314a7899c0f1541c3e08eb`。旧skill43488已退出，新skill59752具有不同创建时间，gateway38756保持；同一artifact再次下载长度/SHA相同，持久身份不变。这补齐真正JVM重启，未把同JVM fresh-store当作重启。

cleanup探针只把本次完整ID/tenant/principal/run/call/bucket/object_key/SHA/size且READY的一行置到期，断言更新1行；没有cleanTenant、直接DELETE行或直接removeObject冒充scheduler。随后真实scheduler置DELETED/cleanup事实，MinIO精确登记键按固定ExecutionException→CompletionException→ErrorResponseException→NoSuchKey链确认不存在。新专库及事实保留，最终本轮两服务均记录退出；328项输入指纹前后一致，JAR记录全文相同，HEAD保持1fe99d67。前两轮采集失败与首module前置失败原件仍在，不能拼接其局部通过取代第三轮。

## 综合门、状态与提交条件

[final-acceptance.json](final-acceptance.json) SHA `844d2ed8a406ab48ac6a2f5a3ae36850d98658b3169df7ab5523374bb5620b61`的8个输入pin本次逐项复核全部相同。它保留native/collector1和三个原false，明确combinedNoNewRegressionAccepted、separate attribution、rawEvidenceModified=false、cardClosedByThisRecord=false，并要求indexReview/localCommit；这种并列综合门可以接受，不允许回写原summary或将299/698改称全P。

原台账§4 PLAT-09及§5 T09.1剩余工作正是本卡；T09.2已定案为异步400拒绝并完成，PROCESS files在PLAT-21/EX-15b，不能再把它追加为本卡未完成条件。因此**本卡精确索引审、本地提交及三入口事实回填闭合后，PLAT-09与T09.1可以完成**。48能力统计应为10完成/20进行中/12待验收/5待启动/1暂缓；AP22能力为1完成/13进行中/8待验收，其余仓数不变。原9/21及AP0/14不应机械保持。本报告未改主表、未越级关闭EX-07、PROCESS或其它能力残项。

最终索引审须保证仅七源授权差异及本卡准确证据/属性入选，原日志/XML、env、密钥、payload、JAR仍留本地忽略范围；不夹入EX-06等并行材料。回滚仅反向四产品授权差异并保持测试一致，原证据和已登记持久事实保留；不得整仓reset或清旧桶/队列。真实算法worker、PROCESS files及联合业务链不是本卡此次接受的结果。
