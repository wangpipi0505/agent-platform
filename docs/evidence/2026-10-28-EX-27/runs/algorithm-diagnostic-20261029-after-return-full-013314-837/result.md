# 事后模式单次Full：未捕获原首次false

2026-10-29。本轮依据root及独立静审准入（algorithm-after-return-static-review.md SHA `58F5312C4AD9CAA75D346966FF846F3D6CCFCCB17EB3E4D19634E584D17B4C18`），仅执行一次原Full70类双flag诊断。Maven和独立PS采集进程结束后已立即明确交还实际窗口；此后只有文件/XML分析，没有追加定向、同源循环抽样、新模式或实际运行准备，没有改源码、断言、预算、主入口或index。未使用Superpower。

## 实际范围、准入与退出

- 冻结HEAD `ae2d1860071c9e49d07e80ecace331e41f898c9d`，Test=`155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1`、ArtifactStore=`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`；运行前核其余148源与上一Full完全一致，149源采集末drift=0。
- 最小复用上一Full采集器；沿原默认fork/reuse/顺序/JDK21.0.12.8、本仓m2、Xmx1024m、unix tmpdir与原failure.ignore采集参数。command-context.json核从原EX03 Full命令只增加同一argLine的两个flag：`-Dex27.algorithmInterruptDiagnostic=true -Dex27.algorithmInterruptAfterReturnOnly=true`，无-am或-Dtest删选。
- 安全PG前置沿上轮精确判据：本仓PID15204/25432/data_directory，algorithm_test既有库/schema/history owner及17条合法SCHEMA/SQL分类；两精确既有迁移专库和ontology_import原owner/标记门均通过。两迁移测试只执行root已授权的原skill schema重放；collector无DDL、不创建缺库、不reset主算法库、不接管未知库。
- preflight与Full collector先经PS5.1.26100.9711 ParseFile，0错误；SHA分别`35353D55E66C976B84E4AF10B0549E9C8F71D27FEF9C9F1CB4C435F72A4AFFCA`、`69BBE4A48DD7F28032B9FCA716C87F0608DC6A4EEBA64CB06A81C2A623B2318A`。本次无准备静态或采集异常。
- 真实Maven起止01:35:19.144～01:38:25.738（+08），native exit=0（原failure.ignore仅采集），collector exit=0。native-exit先持久，后归档新鲜XML并用XmlDocument.Load按UTF-8声明读取；raw.log与xml沿原ignore本地保留。

command-end-fingerprints.json在01:38:25.740（native持久之后、XML采集之前）保存真实结束指纹：HEAD仍AE2D、Test155D、Store9DEB、.env `AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A`、common Jar `1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87`，均同运行前。安全环境字段DEV_AUTH/ENGINE_PG_TEST true、security mode未显式设置；未输出凭据或任意异常消息。

## 完整集合与原116原因

70个源类/70份新鲜XML/673记录=557P、6F、106E、4原SKIP。原664＋导入新增9全齐，缺原方法/缺新增方法/新非通过/状态变化/非选择类/XML计数异常均0，原116非通过状态保留。当前方法差异门true，allTestsPassed=false；native0不是全绿。

窗口归还后复用同一有限XML原因比较器：116项状态、异常type、完整Caused-by类链与首项目栈方法变化0；115项message摘要完全相同。唯一摘要变化仍为AlgorithmMigrationPostgresTest的v310Upgrade…：旧/新均`:123` canonical JSON比较失败，长度1371、各2 UUID及2timestamp；仅替换这两类已定位的易变值后全文精确相同。retained-nonpass-reasons.csv、reason-comparison-summary.json、message-delta-checks.json保留原始hash和具体比对，未对全部异常泛化归一化，未打印message。4个旧SKIP准确身份与原因保留。

相对原EX03 Full，唯一状态差异仍是GET_BODY `[3]` FAIL→本次PASS；该原失败XML和当时gate=false原件保持，不被本次P覆盖。

## 三份事后JSON与解释边界

mode-validation.json确认3份JSON均`diagnosticMode=AFTER_RETURN_ONLY`、`peerEnteredCaptureOmitted=true`、`clientStageAtInterrupt=UNKNOWN`，peer-entered样本数0；没有事前worker栈、反射或其它代替采样。每参数只出现D64既有的before-peer-release、after-original-peer-wait、after-finally-peer-release三phase，均无观察错误。

| 参数 | joinReturnMs | 首次原quiescent相对interrupt纳秒 | 首次值 |
| --- | ---: | ---: | --- |
| PUT | 23 | 23,914,200 | true |
| GET | 23 | 23,390,300 | true |
| GET_BODY | 22 | 22,528,400 | true |

这是原首次固定布尔，不是后续样本回写；join后已有观察耗时与随后首次判断时间保持分开。本轮没有首次false，也没有把客户端阶段从UNKNOWN补猜为await或正文读。

三个更晚before-peer-release样本均worker已退出、caller中断flag true、输入close计数1、stage0/Call0、executor shutdown/terminated、active/queue0、stopping/interrupted true、stopNotConfirmed=false。三个异常图均根IllegalStateException/固定OUTPUT_ARTIFACT_UNAVAILABLE、cause InterruptedException，无未确认固定码；own随机tenant/call PG均rows1/CLEANUP_PENDING/OFFLOAD_FAILED/READY0。它们支持本次正常确认退出分支；因是更晚分项，不能反推历史首次false时的原子状态，尤其不能由cause反推中断时客户端阶段。

释放前routes5/6/6、DELETE0、peer.release/finished剩余1/1；原release/finished/40ms及原无迟到/无READY断言后仍5/6/6、DELETE0，PG与静止分项同前。before-peer-release只表示显式release前，有限peer可能自然超时，不能把phase名称或release计数1等同于仍阻塞。之后原共享store复用和cleanTenant完成，originalBodyCompleted=true，最终routes14/15/15、DELETE3、PG state OTHER包含主动测试动作，不作迟到危险请求差值。

## 停止结论

本次去掉事前观察的受限模式仍未捕获原Full首次false。它不证明原失败由事前观察、合法未确认分支或产品缺陷中的任何一个因素唯一导致；不同轮次的cause/栈也不能拼接为同一现场。原失败轮异常码、PG码和当时分项仍缺失。

当前Full方法差异门及模式完整性通过，仅是本次可复核事实，不关闭原SDK问题、EX03硬门、EX27子步或整卡。本代理到此停止；没有再次修改Test/产品、准备新模式或追加实际运行。后续由root与独立技术审阅决定，不以反复取P替代原失败归因。
