# EX-27 响应所有权子步运行结果独立终审

2026-10-29，审阅者 `/root/docs_reader_check`，未编写本次Java补丁/测试或采集器。本轮只读已完成原件并用Python标准库重解析/计算摘要，只新增本报告；未运行测试、runner/helper、Java/Maven、PG、HTTP或服务，未读取凭据内容，未改源、HEAD、index或旧原件。不使用Superpower。

**结论：响应所有权子步本轮运行验收无阻断，真实定向红→同断言绿、SDK6、Adjacent83及Full701相对回归门闭合，可交root做精确索引/本地提交收口。Full仍585P/6F/106E/4旧S、allGreen=false；不是整模块全绿，也不关闭EX-27其它范围或追认原Full700唯一根因。** EX-15b path的最终模块回归证据在此覆盖，其功能审阅继续引用原独立记录。

## 1. 实际窗口和冻结身份

审阅主目录：`response-ownership-full/runs/full-701-20261029-01`。实际Maven UTC 2026-10-29 09:11:38.6708478至09:15:31.6268031；before-storage在开始前、after-storage在结束后。Full command为默认模块test，无-Dtest筛选、无新增fork/reuse/order/诊断参数，保留Xmx1024m/unix tmpdir及surefire timeout900；failure.ignore=true只为完整采集。

admission、command和command-end的HEAD均`acad6bdee223b23f331420c6de50045cb5fa8941`；这是该HEAD加未提交的精确152源候选，不声称acad提交树已含75F89B修复。env摘要AF038F68…及installed common JAR摘要1B0D7B40…前后相同。native-exit=0、collector-exit=0、failureIgnore=true、defaultForkReuseAndOrder=true；原生0不等于测试全绿。

复制入run的approved manifest及collector与已独审候选逐字匹配：

| 项 | SHA256 |
|---|---|
| runner | 5498a0679e7f1ac4d97f9961bf66a1ed15b91e044d3d5e0622968eee7f40aab2 |
| manifest | bbfe4a1ad8282b5a010696a03cb83452187738c8c983ae294db2a2c2262549f6 |
| ArtifactStore.java | 75f89b938a054be6ac385c7152f49a9525d25e696bce3634c42c7bd3201ae907 |
| Ownership测试（红/绿/Full未改） | 40474f829debd58d0b1d689d8f139f5bd89e2b2a07b298470d90c870960397f6 |
| 原五SDK测试（未改） | 43ec56726c4dd04f3b4e4553590b80316d87bd2b619230c6f728a52d9376bd9d |

## 2. 73原XML与完整701身份重算

逐个核本run全部73 XML的字节SHA和bytes，目录集合与xml-manifest完全一致。重新按原完整classname/method/display_name/suite_path身份、error/failure/skipped优先级、cause链/首项目帧及message/detail SHA解析，701项与methods.csv逐项一致；所有testsuite声明与实际testcase计数相符，0 XML issue，73准确类名无重复。

正式方法基线仍是EX-15a原698/71 XML；该基线XML摘要及CSV/698身份也独立核同。加manifest中的两个ProcessFiles和一个Ownership唯一新增身份后，与当前701完整集合完全相同，0缺失、0额外、0状态变化。

| 结果 | 独立解析值 |
|---|---|
| 记录 / XML | 701 / 73 |
| passed / failure / error / skipped | 585 / 6 / 106 / 4 |
| 新增三个身份 | 全P，无新skip |
| 原四skip | 准确原身份/原因保留 |
| Full相对门 / allGreen | true / false |

没有把原Full700失败目录替换成新“全绿基线”，也没有以汇总数字代替参数化/类级身份。新测试源不改变旧五身份或预算。

## 3. 原116非通过细节与唯一动态proof

从原698 XML和当前Full XML独立重建116项非通过，不仅信任collector CSV：全部status、异常type、cause链和首项目帧相同；115项message/detail原SHA完全相同。唯一原文变化为既定精确方法：

`cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest#v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity`

它仍为原AssertionFailedError。独立从前后原XML提取message/detail，UUID、timestamp共八个计数均恰2；两种精确替换后message和detail剩余全文分别完全相同。没有其它方法或额外结构差异。与nonpass-reasons.csv的116项原前后SHA、structureSame/reasonPreserved及唯一bounded标记逐一核同；与bounded-volatile-proof.json的精确身份、八计数和两个归一化结果也一致。

因此保留`rawReasonComparisonPassed=false`，仅原窄scope解释后`reasonComparisonPassed=true/unexplainedReasons=0/boundedVolatileAccepted=1`。未改写原XML或把raw门改成true。动态proof元数据不携带实际UUID值，不能只凭其passed字段认同；本审阅已从两套原XML重新计算该结论。

## 4. SDK真实见证与前置两组实际结果

Full XML中原AlgorithmOffloadInterruptionTest准确五身份全P，新Ownership一身份全P。独立从原system-out重解析四条outcome，并与sdk-default-outcomes.json逐字段核同：

| stage / handoff | returnMs | interrupted / inputClosed | firstQuiescent |
|---|---|---|---|
| PUT / false | 23 | true / 1 | true |
| GET / false | 21 | true / 1 | true |
| GET_BODY / false | 22 | true / 1 | true |
| GET_BODY / true | 25 | true / 1 | true |

四条explicitUnconfirmed均false；diagnostic原始前缀计数0，未用诊断flag跑绿。新Ownership XML中唯一完整安全行与JSON完全相同：original await interrupted、同original future成功、同response、caller尚未stopping、stages=0、calls=0、retainedOriginalCall=false。新测试的原Call/executor严格q断言通过，不以server finished、CAS、stage计数或正常HTTP返回替代真实退出。

前置`runs/sdk-six-20261029-01`为6P/2 XML、`runs/adjacent-20261029-01`为83P/10 XML，native0/collector0/noignore/0skip，source/context stable。同manifest、collector、HEAD和152源核同，Full保存的dependency summary/admission/source-after SHA逐个指向这些原件且一致，两组结束均早于Full开始；没有重跑或换栈借绿。

前序独审`response-ownership-independent-review.md`中的真实RED资格、局部once三路径、固定FilterInputStream/Okio非通用并发安全及caller interrupt源核查仍适用。Full未改40474F测试，worker interrupt严格断言和JUnit caller cleanup/外层恢复逻辑均保留；没有虚构额外测试外线程状态探针。本轮验证的是原内部await/close资源边界，非所有AutoCloseable或完整业务链的通用所有权证明。

## 5. 152源、库和存储边界

重新比较Full source-before/source-after/manifest：均152个唯一路径，逐SHA完全相同；审阅时当前152份技能源也仍匹配。相对旧EX-15b 833 manifest的151源，无删除，只允许ArtifactStore 967849→75F89B和新增Ownership40474F，其余150源保持。全部50输入pin也仍字节一致。await之外产品源在前序实质独审中已核完全相同，未改原stage/Call/200ms drain或其它取消框架。

独立解析before-storage和after-storage：同PG25432/PID15204、knownDataDirectory=true；同五库owner/schemaOwner/historyOwner均agent_v12；每库17行history=1个合法SCHEMA+原16 SQL，version/checksum/installedBy/success逐项满足manifest并且前后完整history相同。五库集合与原白名单完全一致，两个originalReplay仍仅algorithm_migration/digest。MinIO29000前后healthy，pg.raw.log为0字节；collectorReadOnly=true、collectorDdl/unknownDatabaseCreation/queueCleanup=false。

这核的是已冻结collector留下的只读归属/历史/健康证据，不是审阅者在线重查，也不声称Full里的原迁移测试完全不写库。原两测试的已授权schema重放与collector无DDL分开；前后history相同不能扩展成整个数据库所有业务行零变化。

## 6. 原Full700保留与后续提交口径

旧`EX-15b/collector/runs/full-skill-ex15b-20261029-01`全部72份原XML SHA仍逐项相同，旧raw.log存在且与其raw manifest的bytes/SHA一致；summary仍700=583P/7F/106E/4S，regressionGate=false/allGreen=false。原五SDK中的handoff方法原q失败XML仍在，未被当前通过覆盖。旧失败证明发生过，当前确定性红绿不追认为其唯一竞争顺序或唯一cause。

root计划随后仅提交另经独审的124个EX06阶段文件；这属于本轮结束后的Git事件，不改变本run的实际acad HEAD和152源指纹，也不能回写为“新HEAD已跑相同Full”。本报告不认证124文件index内容，不进行提交，不将EX06提交误记为EX27/EX15b代码已提交。后续产品提交需root另做精确索引/来源审阅；本报告可以作为其最终运行证据。

## 7. 关键原件摘要

以下均相对Full run目录，独立重算：

| 文件 | SHA256 |
|---|---|
| summary.json | 58ab80fd4a1bd83bcf0da5a6ce5c59b846eb89c1c87e503b1e544c7736f1f32b |
| xml-manifest.json | 0557a57d5804704b6bbff4de05d993edc5ee5996d6c875d2006c932ec85f73cb |
| methods.csv | a94750bbdfe28679f2142e119944778955e51fab439cff778ed353c8a3df7910 |
| nonpass-reasons.csv | ab8dccba35645d5356cc096868bef0454ef769771924db67b4cdfe49a544dc35 |
| bounded-volatile-proof.json | c05049ec2d7da11be2a391c2138b8828a12ce026ef76b0dc049f45b4ce398775 |
| source-after.json | 04afb26b6520126d396ebf4f6c1606bf02e412551cb095f0ac22573484dd6a91 |
| before-storage.json | f02c4ee2be0f00f315fd4d480a83f897a855f9518c6c16f5315a44d44c46d782 |
| after-storage.json | 25e67bf79e41ba33fb5493eb486dbbf42f6b3412bc8cf184d557a367e73eb085 |

本报告至此停写；确认本子步相对回归闭合，保留原116与原失败原件，不宣称全模块全绿或整卡其它能力完成。
