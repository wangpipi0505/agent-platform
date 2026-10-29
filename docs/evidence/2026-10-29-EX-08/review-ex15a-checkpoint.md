# EX-08：EX-15a闭环三主入口检查点独立复核

2026-10-29；复核者 `algorithm_interrupt_readonly`。只读三主入口、当前/历史快照、增量说明、README、安全运行回执与Git提交对象，唯一新增为本报告。未运行产品、Java/Maven、Node、API、PG、HTTP或服务，未读真实凭据，未改主入口、快照、产品、旧证据或index，未使用Superpower。

**结论：接受本次文档检查点，未发现实质矛盾。** EX-15a/PLAT-09/T09.1已闭环；EX-06、EX-15b、EX-27及EX-08整卡仍进行中。此结论是当前状态/证据映射复核，不重跑已通过业务，不替代后续精确索引审，也不将本检查点写成EX-08整卡完成。

## 原位置、版本与精确快照

新[manifest](main-entry-snapshot-20261029-ex15a/manifest.json) SHA256 `5a64b8417af6605df4dfd88e55e10e9226f0d560cbba6d1dbd1c9680bf83d37f`。逐项重算原位置及副本字节数/SHA，三者均与manifest一致：

| 原位置（均在`G:/207/项目/算法重组工具/文档/优化方案/`） | 版本 | 原文与副本字节 | 原文与副本SHA256 |
| --- | --- | ---: | --- |
| `优化台账-统一-20261028.md` | v2.17 | 222,621 | `8864506e4189d447e730da29d86355dd250a24f7d2a2e7f16ac9457d1c570a54` |
| `执行卡片-统一补差-20261028.md` | v1.17 | 93,365 | `6c486e6693dbdfb11855a019faff31b020c899a30a5e60ae0eb614d67476dc62` |
| `联合验收与回归执行手册-20261028.md` | v1.17 | 63,641 | `a47d5bcb5f8ffdcbb791c938184bb380ac106be792bd375f2e5154eb13c8d61f` |

manifest明确snapshotOnly=true、executionEntry=false、relativeLinksBelongToOriginalLocation=true及wholeEx08Complete=false；副本没有改写相对链接冒充新的主入口。本次新增于三原入口、相对上个EX-05快照的6个Markdown证据链接，全部从**原文档目录**解析并存在；[本增量](incremental-20261029-ex15a.md)的7个Markdown链接和README现有本地链接也无断链。正文裸路径涉及的模型/父路径两轮回执另按下文核实，没有去快照目录错误解析仓外路径。

旧`main-entry-snapshot-20261029`、`…-ex04`、`…-ex05`三组共9份副本逐字节复核原manifest的长度/SHA均相同；相对当前HEAD的只读Git diff为0。旧版本及原历史“进行中”措辞保留，README前部将当前增量与旧7/9/10卡阶段明确分开，不拿旧数覆盖当前台账。

## 独立逐表计数与状态联动

本次解析台账实际行，未只转抄总览：

| 口径 | 独立复算 |
| --- | --- |
| EX执行卡 | 39个唯一ID，11完成/4进行中/24待启动；进行中精确为EX-06、EX-15b、EX-27、EX-08 |
| 能力项 | §4、§6、§7合计48个唯一ID，10完成/20进行中/12待验收/5待启动/1暂缓 |
| AP22能力 | 1完成/13进行中/8待验收 |
| OR26能力 | 9完成/7进行中/4待验收/5待启动/1暂缓，未被本次改变 |

PLAT-09主表及T09.1转完成有本卡适用验收/独审/实际提交支撑。T09.2此前已按异步400 `ASYNC_MULTIPART_UNSUPPORTED`拒绝语义完成；PROCESS files明确归PLAT-21/EX-15b，不再误作PLAT-09未完条件。EX-15a关闭且EX-15b从待启动进入小步实施，形成11/4/24，并非把一个子步重复算两张完成卡。EX-07、PLAT-21、PLAT-13等联合/运行残项没有随之关闭。

## EX-15a已提交事实与原红边界

只读Git对象核定AP提交`1cc453388950ec90eba51a1484994208c78d67fd`，parent=`1fe99d67b9f72fad576d5e3a424b24d550dbf0b2`，tree=`692364b3f4d45de32bc769c75ece491bbe15fbb4`。实际294路径=7源＋287本卡证据/属性；七源为4生产＋3Test，四生产实际37插入/5删除，与增量说明一致，不是294处功能修改。OR仍指既有f1929968，不声称本次又提交OR产品。

增量引用的[最终实质审阅](../2026-10-29-EX-15a/final-independent-review.md) SHA `9fd18ef5649eeb3168e9d5e2b03fd19faf554b3c3a76d6ba247bf4dfa7ade41d`及[索引审](../2026-10-29-EX-15a/index-review.md) SHA `c2572a775bf8a7d9260192371b1b6b6d02393f290cd4311fb9b64d8a611be3c4`当前仍相同。七维表正确保留skill698原116非通过/4旧S、相邻32P、gateway299=284P7F8E、SSE38/49子进程、正式64MiB新skill JVM及精确scheduler清理；它们对应既有实质独审，本次没有重新运行业务。

gateway原raw/regression/allTestsPassed=false与[综合门](../2026-10-29-EX-15a/final-acceptance.json)并列，3原样/11精确表示/1旧子JVM classpath技术归因分别处理；首次缺类解析顺序未复原、V222/旧CP债务继续EX-27，未按相同失败总数宣称原文同因或299全P。12份被忽略log及私密raw XML留本地；已准入的84份安全XML可公开提交，两者没有混淆。回滚限本卡准确源码差异并保留证据/持久事实，不reset或清旧桶队列。

## EX-06与EX-15b局部事实

**Ontology阶段。** 重读[安全归档](../2026-10-29-EX-06/ontology-20261029-1438/README.md)及前后安全partial，实际49个actions、resources、context内容相同，pending均null、stage均ONTOLOGY_READY、complete均false；两个root run回执均native0/sourcesStable。4份原位置安全admission/run与归档副本的长度/SHA逐项匹配。六个credential及不同principal由归档比较记录；`credentials.env`与其before副本的SHA相同，本审阅未打开凭据文件。不能把journal无新动作写成无读审计/消费副作用，也不能把此partial视作三应用/All成功manifest。

初次协议第二次File.Replace失败、NullString唯一修复后25协议/32准备绿在[原协议归档](../2026-10-29-EX-06/protocol-ontology-20261029-1428/README.md)分列，不倒填旧红。新增模型协议01实际62项已达、最后进程退出门失败；02同63项通过、native0、两child及端口均退出、businessValidationPerformed=false。02 protocol-checks SHA与summary pin相符；01原protocol-checks仍passed=false，recovered-summary独立保留driver native1和**模型子进程原exitCode未采集**限制，不能以事后已退出追认为子进程原0。仅缓存Handle的修复不代表真实模型、AP评估/发布或J/S1业务通过。

**父目录段小步。** EX-15b red为同两例1P1F/native1、精确nothing-thrown、collector0仅采集成功；最终green02同冻结7B363测试为2P/native0/0F/E/S，接受的只是父目录段拒绝。green01的沙箱identity rename AccessDenied导致握手失败原件保留，不计产品红，也不与02拼成一次运行。完整files、受影响模块、真实artifact授权/归档/重启、链接竞态与X-1/EX-16仍待。本次增量没有将这两例绿提升为EX-15b整卡或PLAT-21完成。

三个主入口仍要求AP三应用真实创建/评估/发布、独立DB/Redis及JAR/V222前置，TestFixture有DEMO标识；没有为了J9降低Prepare门，仍要求新候选换replacement后真实changed=true再同请求号重放。EX-10/16的静态准备亦未被当作产品验收。

## 本检查点结语

复核时仅发现增量中“6凭据文件”容易误读为六个文件各自有SHA；root已改为“含6凭据的credentials.env原文件SHA相同”，修改后的[incremental](incremental-20261029-ex15a.md) SHA为`c4e3fe952352a37553651bee474981ecc7f217fb443792664619cd391b9cd7b1`，三主入口及快照未改。[README](README.md)当前SHA为`7501d5c7d5c04d0953cec2a04df883645dc325bdaf9f17a4c7d8ccd3da5e70e7`。该澄清后状态、原位置、字节身份与证据限制一致，无剩余实质阻断。

本报告完成后停写。此次文档/快照应独立精确提交，不夹入EX-06或EX-15b等未完产品/脚本；索引候选由root另行提供和核验。EX-08仍缺48能力/45原子项及其余历史主项的完整七维覆盖，保持进行中，不用本批独审替代全卡终审。
