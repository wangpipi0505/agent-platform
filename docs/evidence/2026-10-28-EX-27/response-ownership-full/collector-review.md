# EX-27 响应所有权 Full 采集器独立静态审阅

2026-10-29，`/root/docs_reader_check`。未编写本runner/manifest；本轮只读三份候选、新旧采集器/基线XML/CSV/pin及已结束SDK/Adjacent安全回执，只新增本报告。未运行runner、helper、测试、Java/Maven、PG、HTTP或服务；没有读取.env内容、改源/manifest/HEAD/index。WindowsPS5仅Parser.ParseFile/AST文本比较，无函数执行。不使用Superpower。

**结论：无阻断。准入以下准确runner/manifest的Full701唯一窗口，须显式传已完成同栈SDK和Adjacent的RunId；这不是Full运行结果或整卡完成结论。** 此准入已在Full前先向root回报，原Full700失败继续保留。

## 1. 精确候选与比较基线

| 项 | SHA256 |
|---|---|
| 本runner run-regression.ps1 | 5498a0679e7f1ac4d97f9961bf66a1ed15b91e044d3d5e0622968eee7f40aab2 |
| 本manifest.json | bbfe4a1ad8282b5a010696a03cb83452187738c8c983ae294db2a2c2262549f6 |
| 旧EX-15b collector/run-regression.ps1 | 87d0b0a35920ac843de065dc4a57009723f9110a5daca732622fca795b9ea957 |
| 旧EX-15b collector/manifest.json | 833ee0f815dab47fdd22f6f672e23b7ee31e295389a8be761055ca4c50511c9f |
| 被测ArtifactStore.java | 75f89b938a054be6ac385c7152f49a9525d25e696bce3634c42c7bd3201ae907 |
| 新Ownership测试 | 40474f829debd58d0b1d689d8f139f5bd89e2b2a07b298470d90c870960397f6 |

manifest全部50 pin存在且字节SHA匹配；152源无当前漂移，原151路径无删除，唯一差异为ArtifactStore 967849→75F89B与新增40474F测试，其余150源相同。knownSqlHistory的原16项与833 manifest完全相同。精确HEAD为acad6bdee223b23f331420c6de50045cb5fa8941；runner结束仍检查同HEAD/env/common。PS5.1.26100.9711新旧runner Parser均0，BOM存在。

## 2. 身份与XML集合独立重建

用Python标准库只解析原文件，不装载/执行collector/helper。逐个重算EX-15a正式698基线的71 XML SHA；从XML重建完整classname/method/display_name/suite_path身份，698项与冻结methods.csv完全相同，status/message_sha256/detail_sha256也逐项相同。原698状态582P/6F/106E/4S，116非通过不丢弃。

三个新增身份（两个ProcessFiles与一个Ownership）均不与698重复。静态重建：

| Mode | 准确身份/XML | 目标状态 |
|---|---|---|
| SDK | 原SDK五身份+新Ownership=6 / 2 | 6P、0F/E/S |
| Adjacent | 原十类=83 / 10 | 83P、0F/E/S |
| Full | 698+3=701 / 73 | 585P、6F、106E、4旧S |

Adjacent十类与旧EX-15b/adjacent/manifest.json的classes完全一致，包含ProcessFiles；没有把旧254项入口混入。SDK保留三条参数化PUT/GET/GET_BODY身份、原handoff、原unconfirmed，共五个原身份，新增一项；不是只比较总数。runner同时比较缺失/额外身份、所有status、准确class集合/XML计数、XML声明一致性及新skip=0，意外类级事件不因方法名空而被丢掉。

## 3. 沿用守卫与原116原因门

以下八helper的AST Extent.Text与旧87D逐字相同：Write-Fact、Hash、Assert-Pin、Sources、Identity-Map、Read-Pg、Check-Storage、Read-ExactReasonText。没有执行这些函数来验证。

从`$uuid`至旧原因CSV/proof写出的完整20行比较块也与87D逐字相同：116项逐status/type/cause/首项目帧/message/detail原SHA；唯一migration解释仍限精确旧方法、AssertionFailedError、message/detail前后UUID与timestamp分别恰2、其余全文一致。raw比较和有界解释门分开，原XML不改；没有扩大volatile字段、方法、计数或把原失败删成通过。

五库PG25432/PID15204/V:/.local/pgdata/agent_v12、库/schema/history owner、原16 SQL/合法SCHEMA分类、ontology marker、MinIO loopback29000健康门完全沿用。Read-Pg固定只读PGOPTIONS/有限statement timeout，collector无DDL/建库/repair/清队列；原两迁移测试在Full沿原专库白名单重放，与collector自身只读分开。SDK/Adjacent不选迁移测试。

原独立进程env加载/finally恢复、JDK21、本仓m2、Xmx1024m/unix tmpdir、默认fork/reuse/order、原测试预算均保留。四个额外JVM options非空拒绝。Full沿原failure.ignore=true只为完整采集；native0/collector/逐方法相对门/allGreen分开，Full若相对门通过仍保留116非通过、allGreen=false。SDK/Adjacent无Full ignore参数，必须native0且全绿。

## 4. SDK/Ownership见证与阶段依赖

SDK与Full不仅看五项passed，还要求四条默认outcome精确键PUT|False、GET|False、GET_BODY|False、GET_BODY|True唯一齐全，interrupted/inputClosed/firstQuiescent或显式unconfirmed语义成立，diagnosticCount=0。没有打开两个旧诊断flag。

Ownership从唯一准确新类XML读取安全行，要求恰一条完整字符串：同original future成功、同response、caller尚未stopping、stages0、calls0、retainedOriginalCall=false。调用成功、server finished、单一stage计数不能代替此见证和新测试内原严格q。Adjacent不含Ownership，应为0条对应行，不冒充其已覆盖所有权。

每阶段分别Require ExecuteApproved、准确manifest SHA及新RunId目录；没有自动下一Mode。Adjacent先需显式SdkRunId，Full再需SdkRunId与AdjacentRunId。Assert-PreviousRun拒空/同当前RunId，要求前一Mode native0/noignore、准确6或83且全P/0skip、regressionGate/allGreen、collector0，同manifest/runner/HEAD及152源；成功依赖的summary/admission/source-after摘要复制到本轮。不是只看summary一个计数。pin中的单例Ownership Green及红原件也全在，当前同源Green是先决条件。

本审阅已只读核实际已结束的两组回执：`runs/sdk-six-20261029-01`为6P/2 XML、`runs/adjacent-20261029-01`为83P/10 XML，均native0/collector0、0skip、source/context stable。同manifest/runner复制字节及HEAD/152源逐项一致，failureIgnore=false；SDK四outcomes及calls0完整见证已独立核JSON值，diag0。没有重跑这两组。Full结果仍不在本静态报告范围。

## 5. 旧失败与index边界

原Full700目录summary/XML manifest/source/native/command/raw摘要，以及原SDK handoff失败XML均pin并未覆写。只读核旧summary仍700=583P7F106E4S、regressionGate=false/allGreen=false；旧raw.log仍存在且与raw-log-manifest SHA相同。新701正式比较基线是原698加三个精确新增身份，不能把700失败现场替换成“旧基线原本通过”，更不能据本确定性所有权用例追认其唯一根因。

EX06的124暂存/index tree仅在manifest/README作上下文记录，明确不是产品运行证据。runner没有staged/index读取或写入、没有clean-index前置，没有Git add/reset/checkout/commit；Git只读HEAD。该记录不放宽被测152源冻结，也不让本审阅认证124文件内容或其实际index状态。

本报告只准入准确采集器及依赖已齐的唯一Full实际窗口。最终701的真实missing/unexpected/status变化、原116原因/proof、SDK/Ownership见证、环境/库守卫与原生/采集状态仍须实际采集后另审；不提前写PASS、不关闭EX-27/EX-15b。
