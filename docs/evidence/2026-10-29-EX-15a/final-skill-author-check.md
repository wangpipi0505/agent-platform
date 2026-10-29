# EX-15a 作者自查、网关独立复核与证据矩阵（草稿）

2026-10-29；编写者 `/root/ex04_pg_scope`。**整卡尚未接受**，等待网关完整模块实际结果、非作者产品复核与代码管理收口。本报告不改变产品、测试、采集器、旧证据或三份主入口。

## 审阅身份和结论边界

本编写者实际参与了 ArtifactStore、MultipartFormMapping、SkillAdapters 和两个 skill 测试文件的修改，亦编写过 skill/live/PG 采集文件。因此这些内容在本文件中仅为**作者自查与现有证据核对**，不能列为独立通过；相关非作者意见见 [review-multipart.md](review-multipart.md)、[review-upload-boundary.md](review-upload-boundary.md)，最终 skill 产品复核由主线程负责。采集器及 ACL 的独立审查也由主线程负责，本报告不自批。

本编写者未参与 GatewayController 与 ArtifactUploadGatewayTest 的编写。下述网关生产/测试差异是对这两个文件的**独立静态复核**，结合其既有真实定向原件，当前未发现需阻断该最小产品修复的缺陷。该结论不替代尚未结束的完整网关相对回归，也不替代正式 live 原件的非作者核验。

本轮复核只执行 Git diff、源码/证据读取、SHA、CSV/原 XML 解析，没有运行 Java、Maven、PG、HTTP 或产品进程。

## 七个源码文件及固定指纹

范围为4个生产文件、2个既有测试文件加1个新测试。没有改 ArtifactController、鉴权、异步、PROCESS/ALGORITHM、迁移或生命周期策略；旧 V306 已承载本轮需要的事实。

| 文件（仓内路径） | SHA256 |
|---|---|
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java` | `967849596ae6179a2b0858fb39d50a365c65f2370d4e2ad0658dfee44a0569b1` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java` | `d58ac69edce07090d3c3800da2e2785318d00dcf8acb5badbdc0572fb0fb95d9` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `7e4b33db18435af0429b7ea483be3d74c9bf73151912e0eeaf16ab7385c8935a` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ArtifactPersistenceTest.java` | `16cbd8fd8abe960ccc6b72fe7fb1c41dd5883fbd0a3f15f0298a1f46cab75d66` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java` | `64a981a93e22351645fa3d9bd77671a0d1f84db0740e6585737114bd6046eb1e` |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | `75ef9a91d893c20d727e1251123901488719fb81c1e38621ee0171ec51f52837` |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java` | `b4fcd5ab65dc80f0f509c31c1f5bd9ce56d1dd57942ed9d802f8198d99994f32` |

## Skill 作者自查

- **引用与身份**：MultipartFormMapping 仅将原受控相对下载路径中的32hex扩为可带生产实际 `art_` 前缀；仍用完整 `matcher.matches()`，不能把协议/host/query/fragment或外部地址变成可信下载。opaque `art_<32hex>` 与旧受控路径保留。解析仅得到 artifactId，实际下载仍经 ArtifactStore 的已验证 Actor、tenant/principal、READY及有效期过滤。同步 multipart 明确允许同一拥有者复用此前运行的产物，调用 `download(id,null)`；不能误称 URL 文本中的 runId 被重新授权或直接信任。
- **生命周期**：build 已有异常路径会关闭先前打开和刚拒绝的流；新 closeFiles 仅遍历本次 multipart 的 FilePartBody，走其已有 CAS 幂等关闭。SkillAdapters 只在原同步 HTTP 方法保存本次 parts 并在 finally 释放，包括尚未被 writer 消费的后续流；其它适配器与异常分类未改。
- **上传完整性**：MinIO put 返回后、关闭 checked 流及更新 READY 之前，要求实际计数等于声明长度且原 source 已到 EOF。SDK预读超声明和仅读声明长度后停止的两种情况都被覆盖；尾探测字节不上传、不纳入成功摘要。正确64MiB在EOF处通过；多余字节按400 `ARTIFACT_SIZE_MISMATCH` 经原有补偿精确删除登记对象，不发布READY引用。原64MiB硬上限和 PG归属/状态约束保持。
- **断言稳定**：MultipartStreamingTest 原7例和新增4例在修复期间 SHA 相同。Persistence 红v2中短流/断流只因已知SDK包装层解包错误而失败，v3严格增加 CompletionException 层后仍要求 ErrorResponseException 与精确 NoSuchKey；不是容许任意异常。v3真实15P1F后才改产品，16绿未改测试。原固定 `unrelated-tenant` 改成本例 UUID tenant 派生值，原0条清理及READY断言保持，避免触碰既有固定tenant数据。

## 网关独立复核

GatewayController 的生产 diff 仅为 MediaType import、请求限额选择函数，以及原 copyBounded 的上限参数。只有方法精确POST、原请求 URI 精确 `/admin/artifacts`、可解析且type/subtype精确multipart/form-data时给65MiB总wire额度；其它方法、路径、无效/相似/通配媒体类型仍2MiB。copyBounded继续使用8KiB缓冲和实际累计读取字节，在超限块写向上游之前拒绝；不依赖 Content-Length 声明，计数在65MiB附近不存在int溢出通道。下游64MiB文件上限未改变，原内部票据、header转发、错误信封和 finally disconnect 未修改。

新测试以流生成请求，真实 loopback 上游读取全部wire/file摘要；覆盖64MiB、总wire65MiB、65MiB+1以及普通2MiB/2MiB+1，均包括已知/未知 Content-Length。PUT、其它路径、下载路径、媒体类型误匹配仍拒绝；拒绝侧核上游收到字节不超过上限且最多多读一个8KiB块。新测试对用户token和出站内部token实际验签，核tenant/principal/audience/token_use/roles并拒绝用户token透传与伪造身份头；**入口前提是已认证 Servlet 上下文，测试自己已明确不声称生产 FilterChain 覆盖**。正式 live 的401与成功上传另补该层。

定向原红11=7P4F：四个合法大请求均在 `expected 200 but was 502` 失败；原负例7P。最小生产修复后同一测试 SHA 的11例全P、native0、0E/0S，source/context稳定。没有改大请求期望200，也没有把原错误502变成被接受的成功。

## 已取得证据矩阵

| 范围 | 实际证据与结果 | 本文件可支持的结论 |
|---|---|---|
| 引用和资源关闭原红→绿 | [red-20261029-1240](red-20261029-1240/summary.json) 11=7P2F2E → [green-20261029-1247](green-20261029-1247/summary.json) 11P | 原URL断链与后续流漏关均有实际红，测试未放宽 |
| 真实上传边界 | [upload-red-v3-20261029-1257](upload-red-v3-20261029-1257/summary.json) 15P1F → [upload-green-20261029-1305](upload-green-20261029-1305/summary.json) 16P；[后续multipart](multipart-green-upload-20261029-1305/summary.json) 11P | long声明不符为唯一产品红；短/断流精确清理、声明超限不读/不建行、64MiB链通过 |
| skill完整相对门 | [full-skill-20261029-ex15a-01](collector/runs/full-skill-20261029-ex15a-01/summary.json)：71XML、698=582P6F106E4旧S | 原689身份/状态全部保留，新增9均P；**不是全绿** |
| skill相邻扩面 | [adjacent-20261029-ex15a-01](collector/runs/adjacent-20261029-ex15a-01/summary.json)：5XML、32P、native0、无ignore/skip | ArtifactBound/Reference、FormMapping、HTTP async及PathParams async原承诺保持 |
| 网关定向 | [gateway红](gateway/gateway-red-20261029-ex15a-01/summary.json) 11=7P4F → [gateway绿](gateway/gateway-green-20261029-ex15a-01/summary.json) 11P | 最小wire限额改动和原负例通过；不能代替全模块 |
| 正式gateway/skill JVM | [ex15a-live-20261029140249-7cb555ea](live/runs/ex15a-live-20261029140249-7cb555ea/run.json)：complete=true，3probe native0 | gateway38756保持；skill43488退出→59752；新PID同artifact 64MiB/SHA读回；两服务最终退出（采集器独审另由root负责） |
| 正式入口/清理 | 同run [upload](live/runs/ex15a-live-20261029140249-7cb555ea/upload.json)、[restart](live/runs/ex15a-live-20261029140249-7cb555ea/restart.json)、[cleanup](live/runs/ex15a-live-20261029140249-7cb555ea/cleanup.json) | 缺/坏Bearer各401且0新行；伪造身份头不改归属；精确一行到期、正式scheduler DELETED、原对象NoSuchKey |
| 网关完整模块 | **待主线程实际回执** | 必须保留原27XML/288=273P7F8E，新增11P，原15原因/49子进程/SSE安全门齐；不能预先通过 |

对 skill 原件的纯文件复算：71份 XML SHA全部匹配各自 manifest，逐 testcase 得582P6F106E4S；相邻5份XML SHA全部匹配且32P。两个run的150源 before/after字节相同，150源与当前文件均无漂移。CSV再比较原689→当前698，missing=0、原状态变化=0、当前唯一身份698，新增恰为本卡9方法。原116非通过原因中115条message/detail原文相同；唯一迁移断言按已有 [bounded-volatile-proof.json](collector/runs/full-skill-20261029-ex15a-01/bounded-volatile-proof.json) 的两个UUID/两个时间戳、八处计数恰2作有界说明，未改原XML。full native0来自failure.ignore采集，不作模块全绿依据。

live三阶段safe记录的 artifactId摘要一致，字节数均67,108,864，内容SHA同为 `efd6d21bab5ebabd6d298ee79ffe965f1b23ae1ca4314a7899c0f1541c3e08eb`。cleanup明确 `expiredExactlyOne / schedulerDeleted / exactNoSuchKey=true`。前两轮采集器自身失败必须保留；新run成功不覆盖旧run或将旧原件改成成功。本文件不再次自审我参与的live/prepare采集器实现。

## 整卡剩余收口条件

1. 主线程取得网关完整模块实际方法/原15原因/SSE49子进程与安全门，以及四个既有库只读After历史证据；保留原288失败，允许的表示差异须独立、有界解释。没有实际结果前此项待完成。
2. 非作者完成 skill五源实质复核；主线程完成采集器/ACL/live原件独立核验，记录作者与审阅者，不能把本作者自查当独立通过。
3. 按七源白名单审最终index及证据范围，检查原始环境/日志敏感项、忽略目录和未相关用户文件，精确提交，不把当前其它 EX-06/EX-17/output 等路径混入。本报告不提前证明最终index或本地commit。
4. 将三主入口及本卡README中“完整网关/formal live仍待”等历史表述按最终实际结果更新，保留相对门≠全绿口径、V222非clean构建债务和各原失败。EX-15a完成前，EX-15b仍不能因部分11P/16P/32P自行解除依赖；PROCESS files与EX-16共享文件另行串行推进。

目前没有从已核差异发现需要放宽断言或增加架构/迁移的理由。待完整模块与独立收口完成后再修订本草稿结论，不预填整卡完成。
