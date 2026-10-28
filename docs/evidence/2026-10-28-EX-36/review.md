# EX-36 独立只读评审

- 评审对象：EX-36，`OntologyContextPacks`生产构造的Spring装配补修、关键失败红→绿、engine模块对照与当前正式JAR补验。只关闭本卡构造装配缺口；不关闭PLAT-13/T13.1、EX-01整体或EX-07联合路径。
- 作者：主执行者`/root`；正式engine补验由EX-01实际窗口执行并交接。
- 审阅者：独立代理`/root/ex00_collector_review`；未编写本候选生产代码、测试、采集器或汇总器。前轮仅做只读预审并写`pre-review.md`，本轮仅新增本文件。未使用superpower，未运行Maven、Java、PG、HTTP或产品测试，未启停进程、读取真实环境文件/凭据或改动index。
- 候选：AP分支`feat/v1.2-implementation`，HEAD `0cc967278007343bd5ab09faf9953d86cd573bf8`；冻结43项（2源码/测试＋41证据），manifest/review后附。`candidateTreeBeforeManifest=9f2f380ee689650394fd66bb13f5b1279dae83b7`，`candidate-manifest.json` SHA256 `13d2c53e2735dc822625b872efe36a973f9a663fd2d5496899147aa72068aff3`。前42项树`ba915928e4cbd599db32ce2a18cf350e846ff135`仅增加README收尾及一份文档快照，未改变产品/测试/运行事实。
- 源码身份：生产文件SHA256 `9f462ff6d960fe11db4412e7a7ff08230a7b864125d8c7ac46cf0c68a8b7dd74`；测试文件SHA256 `6c1652fd420aa9d876548d72d2142e7d61df51ceb223109a05d007aac058a652`。产品diff仅`Autowired`导入、既有public两参构造的注解及中文不变量注释3行；测试新增真实Spring方法39行，原3方法保留。
- 证据：[README](README.md)、[候选清单](candidate-manifest.json)、[原产品真红](runs/red-spring-20261028-225550-607/run.json)、[定向绿色](runs/green-spring-20261028-230009-785/run.json)、[完整模块](runs/after-module-20261028-230322-842/run.json)、[逐方法差异](runs/after-module-20261028-230322-842/method-summary/comparison.json)、[异常签名对照](runs/after-module-20261028-230322-842/nonpass-cause-comparison.json)、[正式打包](runs/package-engine-20261028-231712-740/run.json)、[正式补验](formal-engine-startup.json)及[字节交接](formal-engine-evidence-handoff.json)。命令原文/native退出码分别在README与各轮`execution.json`，原始日志/XML本地保留、Git忽略并有摘要身份。

## 实际核查

1. **范围与冻结身份**：逐项重算43个工作区SHA256、索引blob及Git换行归一后的内容身份，均等于manifest；暂存文件集合精确为这43项，无EX-01/EX-35/其它协作者文件混入。独立从完整index条目在内存重建树摘要，等于上述冻结树；`git diff --cached --check`退出0。未修改index或其它候选文件。新文档快照的三入口SHA均与当前文件一致；旧快照明确保留为执行前期事实，不再冒充当前状态。
2. **失败路径与最小修复**：原public两参`@Value`构造与private四参工厂构造并存，原来没有明确注入入口；参数注解不能证明容器会选择该构造。新增测试在`OntologyContextPacksTest.java:100–132`真实`register`/`refresh`/`getBean`，没有供应手工实例或调用测试工厂。实际HTTP验证注入URL、临时Vault引用解析、精确`X-API-Key`及无Authorization；临时目录与系统属性恢复边界明确。生产`OntologyContextPacks.java:82–87`只标识原生产入口，既有缓存、超时、容量、失败不缓存、鉴权/租户、密钥解析规则均未改变。
3. **真红→绿**：只读重解析两轮各1份XML，suite声明计数与case事实相符、mtime新鲜、字节/大小匹配各自manifest、方法CSV完全一致。原红native1、4记录=原3通过＋新1错误，错误正文仅作内部精确匹配，确认为`java.lang.NoSuchMethodException: cn.agentplatform.engine.infra.OntologyContextPacks.<init>()`，并非编译或前提错误。原产品SHA `290495341d58d2574c68912837dda70a36ef279da9916de3cb40731eaaa56550`也等于HEAD原文件字节；同一新增测试SHA贯穿红绿。修后native0、4记录全通过；raw日志安全计数/构建状态与这些事实相符。
4. **engine原基线逐项保留**：独立重读本轮93份XML和EX-00选定`20261028-184530-895`的原93份engine XML，而非只采信`module_comparison_passed`。当前93源类/93报告类无缺漏或额外类，907记录=792通过/35失败/80错误/0跳过；原906条`classname/method/status`多重集完整保留，唯一新增是Spring方法PASS。115条非通过的身份、状态、异常类型及`Caused by`类集合多重集完全相同，无新增/移除签名。原`RunEventReaderTimeoutBudgetPostgresTest`空method、`java.lang.NullPointerException`类级错误原样存在；不能把该类的实际测试方法说成已执行。这里“新增未执行为0”仅指相对原基线未丢失报告身份/类，不表示已补跑原类级错误下的方法。当前开发/PG标志及安全模式缺省证据与基线口径一致，未通过H2、跳过、删类或改变旧状态取得差异门通过。
5. **派生汇总修正**：`analysis-initial-summary.json`仍保留false初版；与最终summary逐字段比较，只有`collection_complete`和`module_comparison_passed`改变，计数、类级错误事件、raw SHA、parser SHA和native码均不变。初版comparison亦保留。修正后将归档完整性与相对基线的新增坏类分开，`summarize-engine.py:43–87`仍输出原类级事件并比较原/新非通过，未删除该事件或改绿。独立XML复核已确认本轮确为同一原类级错误，没有新坏类或缺报告；按DoD②“**不新增**坏类”的本卡门判可接受。`tests_all_passed=false`明确保留，不能称全模块绿。
6. **正式JAR来源与边界**：打包native0，`-pl engine-runtime -am package -Dmaven.test.skip=true`只用于出包，0测试不计通过。记录的154份engine/common生产文件集合与当前精确相同，全部SHA匹配；当前51,930,276字节JAR SHA256为`76ab67c3a72cd1931e2dc64e053cae8a11a86a850cfa9da5eb95fee1132b419c`，mtime在该次构建起止间，与实际补验前后指纹相同。原阻断JAR `b15763c5e81269b7a131d06fd87d5acf43a29accc219f36f103cdaa2ff6ff875`单列。两份交接JSON与EX-01原件逐字节相同，4份实际runner/probe当前SHA与启动记录一致。
7. **正式实际验证与恢复**：补验记录PID50360/6276两次健康200；JMX附加登记PID读配置，窗口97及301/211/101、enabled=true且三阈值0分别通过；原真实PG不限语义方法在本轮XML仍PASS。OR读取由该新JAR提取类路径下的**独立Spring探针进程**注册真实客户端并消费打包YAML/隔离环境，实际读取既有pin和context-pack成功，不是运行PID内的J9发布/运行。启动/停止辅助函数检查本仓JAR命令归属、登记PID和监听占用，回执记录仅停止本次engine、父双凭据/引用恢复、环境前后同SHA及清理成功。审阅者没有接触受限凭据文件或干预当前服务。
8. **公开证据与兼容性**：95份本卡XML的manifest字节/大小/新鲜性均匹配，3轮raw SHA与summary相符；只解析case与异常类别，未导出XML环境属性或失败正文。候选中的JWT/私钥/已签发本体key格式扫描无发现；测试明文是明确的临时合成夹具，不是真实凭据。最终PS5.1静态解析回执对应当前采集器SHA，UTF-8 BOM存在；未将较早脚本的静态回执当作最终脚本证明。README说明独立PS5.1子进程、每轮native码先落盘及完整模块failure.ignore的含义。

## 问题与处置

| 问题ID/级别 | 文件:行号/场景 | 问题与影响 | 处理/理由 | 复核证据/状态 |
| --- | --- | --- | --- | --- |
| R36-01/P2，已关闭 | README原21/27/39行的命令及审阅导航 | 仅链接参数JSON，缺PS5.1调用原文与最终review入口，DoD③/§14.4收尾不足 | 作者只补三条实际调用、候选/review导航；明确Phase名不能在修后伪造原红，并澄清独立客户端探针边界 | 新树README delta已独立核对；源码/测试/执行事实未变 |
| R36-02/P2，已关闭 | entry-documents-check.json及README快照口径 | 原主台账SHA及4进行中/1待验收是早期状态，不能代表当前冻结候选 | 原回执保留，新增带UTC checkedAt的`entry-documents-check-precommit.json`，明确仅该时点有效 | 当前3完成/3进行中/2待验收、39卡及三入口SHA一致；新旧回执不覆盖 |
| 基线事实，非本卡新增问题 | 完整模块原类级NPE及115条非通过 | 仍有35失败/80错误，类级事件不能算方法执行成功 | 保留原身份/类型/状态与初版false分析，按“新增”规则核对，不予豁免或promote基线 | 原906及115非通过签名独立逐项相等；本卡只新增1PASS |

- 未覆盖：本轮未重新执行产品测试或正式JAR，也未读取真实配置/凭据及私有日志内容；独立结论来自源码/候选身份、归档XML/raw摘要、构建物和实际回执的文件核验，不宣称已独立重复全部现场操作。未做J9、真实publish/run、完整EX-07、外部IAM/模型/真实worker或其它模块验收；EX-01 gateway新增SSE红及`wholeCardPassed=false`继续保留。模式/配额配置与组件读取不能替代这些联合路径，PLAT-13/T13.1不据此转整体完成。原存量失败仍按EX-27等既定清单承接。
- 逐卡结论：**EX-36提交候选可接受，无遗留需回改或补跑的本卡阻断。** 源码、关键红绿、engine新增差异及正式engine启动门足以满足本卡限定范围；本卡完成仍以主执行者完成本地提交、后附manifest/review并回填台账提交/回滚记录为条件，当前没有该提交，不提前记DoD⑤已满足。
- 结论依据：DoD①真实关键失败先红后绿已核；②93类/907记录相对原906无新增非通过/坏类/跳过/丢失报告身份，存量红完整保留；③README含命令、数字、证据和导航已补齐；④本独立只读评审完成；⑤本地提交/回滚记录待root收尾。回滚仅反向本卡两个源码/测试差异或其专属提交，保留证据，不整仓reset、不撤销协作者文件；无API/迁移/数据结构改变。
- 作者处置：接受本轮两项文档问题，未覆盖旧回执，仅在新冻结候选增加README说明及当前快照；原42项中除README外全部身份保持。未因汇总修正重跑Maven或改写原XML/native码。
- 审阅者复核：以上43项候选及两个文档增补均已独立确认；本文件新增前后继续核对冻结manifest、HEAD和索引内容。最终结论只适用于上述候选，后续产品/测试/证据事实改变须对受影响部分再审。时间：2026-10-28 23:43（Asia/Shanghai）。

## 2026-10-29 类级FAIL/ERROR分类更正与最终复核

上文保留为43项候选的原接受阶段，不将其表述追认为已涵盖以下新SHA。本次独立复核更正上文第4/5项及问题表中不完整的类级事件描述：先前只列了空method的ERROR，漏列同属原基线的空method FAIL；`collection_complete=true`也过宽。原906身份/状态及115条非通过签名完全相同的事实没有改变。

- 新候选：HEAD仍为`0cc967278007343bd5ab09faf9953d86cd573bf8`；46项冻结文件（2源码/测试＋44证据），manifest/review后附；树`10c625a7b34d8c72461f273be7eee4ee08f17a20`，manifest SHA256 `3923d6faf97d8fe1621b572c57a305a0d2e6cc357574d6f24af2b621dfd6de39`。上一43项树`9f2f380ee689650394fd66bb13f5b1279dae83b7`及manifest SHA `13d2c53e2735dc822625b872efe36a973f9a663fd2d5496899147aa72068aff3`在新清单中保留。
- 有限差异：树间仅本卡README、派生汇总脚本/summary/comparison、文档时点回执及三份历史副本变化。生产/测试、原始XML/raw、native记录、CSV、异常签名对照、正式JAR证据和初版false分析均未改变，未重跑产品。主台账的两事件表述已同步，其当前SHA由新时点快照记录。
- 分类事实：907记录=905条具名报告＋2条原类级事件。`ModelInputMaterialHttpTest`为空method的`failure/java.lang.AssertionError`，11个源`@Test`无具名执行记录；`RunEventReaderTimeoutBudgetPostgresTest`为空method的`error/java.lang.NullPointerException`，3个源`@Test`无具名执行记录。共14个源测试未到达，不计作已执行或通过。合计仍是792通过/35失败/80错误/0跳过，其中两类级事件已经包含在35失败/80错误内，不另加总。
- 实际复核：上轮已独立核原XML中的两事件和两个源类的11＋3方法；本次按授权不重复产品执行或重读已核源，仅将未变的方法CSV与新summary逐事件核对，905具名记录、1F1E、两个去重方法清单及总数14全部一致。脚本现在把failure/error均纳入`class_level_events`，`class_level_errors`仍准确保留1E；当前14方法提取与这两个已核源类一致，不据该局部正则推断其它未来Java测试声明。
- 门的含义：`reports_collected=true`仅表示93份有效新鲜类报告已收齐；最终`collection_complete=false`、`all_source_methods_executed=false`、`tests_all_passed=false`。`module_comparison_passed=true`独立要求报告收齐、native0、无原身份缺失、无新增非通过/类级事件且只新增预期Spring PASS。原两类级事件及115条非通过继续保留，不能以差异门通过声称全部源方法执行或全模块绿。
- 历史可追溯性：`analysis-report-collection-summary.json`、`analysis-report-collection-comparison.json`及`entry-documents-check-before-classification.json`的暂存内容，分别与上一候选对应summary/comparison/快照逐字节相同。初版false、中间版按报告收齐置true和最终分类版均可区分，不覆盖旧结果。新文档快照的UTC时间、39卡/83路径和三主入口当前SHA一致，仅表示该时点。
- 冻结身份核查：逐项重算46个工作区SHA256、索引blob及Git换行归一内容身份，全部匹配；暂存集合精确为46项；从完整index在内存重建树摘要等于上述新树；`git diff --cached --check`退出0。本文件原段未改，原前缀SHA256为`3b06f6d9e0be66456824be27a522a57dc359a7c3a955eed6398082339d3a1ece`。

| 问题ID/级别 | 文件/场景 | 问题与影响 | 处理/理由 | 复核状态 |
| --- | --- | --- | --- | --- |
| R36-03/P2，已关闭 | summarize-engine.py、模块派生summary及本review原分类表述 | 仅列空method ERROR遗漏原FAIL，报告收齐与源方法执行完整混用，可能误读为两坏类的方法已执行 | failure/error统一列事件；明确905具名＋2事件及14未执行，采集完整/全部源方法执行均false；差异门独立保留 | 当前事件/方法清单与已核源及未变CSV一致；旧摘要和原接受阶段保留；无原运行事实改写 |

- 作者处置：只修派生分类、文字和快照，新增历史副本；未改变原失败、执行码、方法CSV或产品代码，也未重跑/豁免两坏类。
- **最终逐卡结论：新46项EX-36候选可接受，无遗留本卡阻断。** DoD①关键路径真红→绿、②相对原基线无新增非通过/坏类/跳过/缺失报告身份、③命令/数字/边界、④独立评审仍成立；DoD⑤仍待root后附manifest/review、本地提交及台账提交/回滚记录。PLAT-13/T13.1、EX-01整体、EX-07/J9及原存量失败均不据此关闭。当前结论替代原段对完整性和类级事件数量的表述，其余已核事实及未覆盖边界保持。
- 审阅者：`/root/ex00_collector_review`；复核时间2026-10-29 00:01（Asia/Shanghai）。本轮仅追加本文件，未运行Maven/Java/PG/HTTP、启停进程、改index或其它候选。
