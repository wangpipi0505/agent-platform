# EX-08 增量：EX-15a闭环与后续卡真实阶段

2026-10-29，root。AP `1cc453388950ec90eba51a1484994208c78d67fd` 已本地提交；parent `1fe99d67b9f72fad576d5e3a424b24d550dbf0b2`，tree `692364b3f4d45de32bc769c75ece491bbe15fbb4`。OR仍为 `f19299681a2a54d60cace3672a56cf4bf8fef7e6`。未push/tag，未使用superpower。

本批关闭第11张整卡EX-15a，PLAT-09与T09.1同步完成。T09.2已经按异步multipart显式400拒绝定案，PROCESS files属于PLAT-21/EX-15b，不能误当PLAT-09残项。48能力为10完成/20进行中/12待验收/5待启动/1暂缓；AP为1完成/13进行中/8待验收，OR原9/7/4/5/1不变。39卡为11完成/4进行中（EX-06、EX-15b、EX-27、EX-08）/24待启动；EX-15b仅完成路径红绿子步，不能称文件链完成。

## EX-15a七维闭环

| 维度 | 准确事实与证据 |
| --- | --- |
| 范围 | 七源，四生产为ArtifactStore/MultipartFormMapping/SkillAdapters/GatewayController，三Test为ArtifactPersistenceTest/MultipartStreamingTest/ArtifactUploadGatewayTest。URL匹配、流关闭、READY前实际长度、精确POST multipart总wire上限分别最小修复；V306原有，本卡无迁移。 |
| 实红与定向 | 原11=7P2F2E→11P；上传v3同16=15P1F→16P；网关同11=7P4F→11P。原断言保留，上传早轮SDK包装前提错误单列，不当产品红。详情见[README](../2026-10-29-EX-15a/README.md)。 |
| 受影响模块 | skill 71XML/698=582P6F106E4旧S，原689+新9齐、原116原因门通过；相邻32P。gateway 28XML/299=284P7F8E，原288+新11齐、SSE38/49子进程、1321指纹及四库Before/After通过。原raw/regression/allTestsPassed=false保留，不称全绿。 |
| 独立归因 | gateway原15中3原样、11仅34个精确字面/22字段变动，root及独审重新验证全文；1个缺类变化单独按旧固定子JVM CP缺client5/core5接受。首次解析顺序未复原；[综合门](../2026-10-29-EX-15a/final-acceptance.json)并列，不覆盖原summary。旧CP/V222债务继续EX-27。 |
| 契约/浏览器/正式链 | 无新API或页面，浏览器N/A。正式第三live通过真实18080验签：缺/坏Bearer401且零行、伪造身份头无效；同64MiB/SHA在skill43488退出后59752新JVM读回，gateway38756保持；精确一行到期/scheduler DELETED/原对象NoSuchKey，本次两服务均退出。前两失败run保留。 |
| 独立评审 | [最终实质审阅](../2026-10-29-EX-15a/final-independent-review.md) SHA `9fd18ef5649eeb3168e9d5e2b03fd19faf554b3c3a76d6ba247bf4dfa7ade41d`、[精确索引审](../2026-10-29-EX-15a/index-review.md) SHA `c2572a775bf8a7d9260192371b1b6b6d02393f290cd4311fb9b64d8a611be3c4`，均由非七源作者algorithm_interrupt_readonly完成。其参与的采集器未以作者自检替代对原XML/事实的独立复算。 |
| 提交/回滚/联合依赖 | 精确294文件=7源+287本卡公开证据/属性；12被忽略的原始log和私密raw XML/凭据/payload/JAR仍本地。仅反向本卡准确源码差异并保持测试一致，保留证据/持久事实，不reset/清旧桶队列。真实worker、PROCESS files/取消、EX-07联合均未因本卡关闭。 |

最初候选误纳12个被忽略log，Git拒绝这些路径并只暂存其余293；随后精确剔除、核原件保留，未force-add、未清理日志。独审逐路径/OID/raw SHA后只加索引报告成294，原293 OID不变。291个原候选blob原字节一致，只有ArtifactStore与本卡.gitattributes准确CRLF→LF；证据-text逐文件、源码没有空白检查豁免。7源中产品实际仅37行新增/5行删除，新增行大多数为实测证据与测试，不是294处功能变更。

## EX-06与后续阶段

EX-06实际Ontology首跑和同会话Resume均native0/sourceStable。49 actions/resources/context完全相同，含6凭据的credentials.env原文件SHA相同，pending=null、ONTOLOGY_READY/complete=false；本次两网关均已停止。协议层先发生PS5第二次File.Replace原子替换失败，仅修NullString参数后原协议25/准备32通过。安全归档见[Ontology回执](../2026-10-29-EX-06/ontology-20261029-1438/README.md)与[协议红绿](../2026-10-29-EX-06/protocol-ontology-20261029-1428/README.md)。这些阶段尚未作为整卡提交或关闭；AP三应用、真实评估发布、All与J/S1仍待。Resume会产生读审计/消费事实，不能写零副作用。

主手册保持三应用均真实发布后才可完成Prepare；J9以pinApp的新候选换replacement重新评估/预检/commit，要求真实changed=true后同请求号重放，不能用未发布pinApp降低准备门。后续业务窗先完成独立AP数据库/Redis endpoint、六服务环境和JAR/V222准入；TestFixture按fixture命名和DEMO标识，不能绕标记冒REAL。EX-10、EX-16目前只有静态准备。

EX-15b在本卡提交后已实际首红：[path-red-20261029-01](../2026-10-29-EX-15b/path-red-20261029-01/summary.json)为2=1P1F/native1、0E0S，正常无files通过，父目录声明应抛异常却成功返回；source/context稳定，原CF485采集器和7B363测试保存原字节。collector0是预期红采集成功，不是产品通过。现只准入原ProcessAdapter.success中父目录段拒绝，原两断言不变；原测试7B363在path-green-20261029-02已2P/native0/0E0S，独审path-fix-review接受小步；green01的sandbox身份文件rename AccessDenied失败原件保留。受影响回归与完整files链均未由本检查点取得，不能外推X-1/EX-16。

## 主入口、检查点与边界

三主入口为v2.17/v1.17/v1.17，当前状态只读根统一台账。原v2.15等快照及本卡历史“进行中”回执原样保存。新的精确副本与SHA在本批main-entry-snapshot-20261029-ex15a/manifest.json；副本是仓外原文的逐字节证据，内部相对链接仍按原位置理解，不是新增执行入口。

本记录不重跑产品、不替代后续每卡独审；EX-08尚未覆盖48能力/45原子项全部七维及历史完成项，保持进行中。本批文档与快照单独本地提交，不混入EX-06、EX-10/15b/16未完源码，也不把文档提交冒充新产品修复。

EX-06新增模型协议的01真实62/63失败与02同63P分别保留；只在两次Start-Process后缓存Handle，退出码0断言不改。02 native0，两owned child及端口都退出，业务验证仍false。01未记原退出码，不能追认0；采集器初始文件名错误在执行前停止，PS5 stderr中断采集已以recovered-summary补留，不覆盖原protocol-checks。
