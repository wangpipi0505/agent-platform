# O2b F2a TLS复审修正任务

先读独立审查 `docs/evidence/review-iam-stage-4-o2b-f2a-tls.md`（16561B/SHA29bf1098be6afdf1b41b31470a323cd1bc4e66e75b4e3ff6e121cada90684e39）和原正式brief/boundary clarifications。原审查C0/I3/M4、Needs fixes，未Accepted。原39green及6phase/5941物理产物审计只证明原epoch证据，不能代替本修正或授权验收。用户已授权持续开发与隔离端点测试，无需再次询问。

## 绑定边界与唯一写权

所有shell workdir固定 G:/207/项目/算法重组工具/代码/agent-platform，先AGENTS.md、docs/implementation-brief.md、contracts/README.md。完全不访问或关联相邻旧工程。v1.2/四契约/时间、Java21/既有依赖、CA/SAN/fixed origin/literalIP/TLS1.3 AES128、C65536/W131072/全实例两物理槽/无queue/期限与真实退出规则不变；EVENTS继续关闭，不改common/assembly/controller/main/pom。

仅修改2个已有source：

1. platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java
2. platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java

其余616个source（包括TLS factory/debt/DebtTest/PKCS12）逐字节不变，不新增source，最终仍618。原source-after `.local/iam-o2b-f2a-tls/source-after.json` 118270B/SHAb64fbebf0a650bb8d9cc6988c78752560f657679e3f6bce3f157b17370a339c2；完整原私有source baseline取 `.local/iam-o2b-f2a-tls/replay/` 的618个manifest列出文件，仅copy source、不复用其.git。原target-after2200（459636B/SHA2c0dc655b7664f3e1dd4efe965a71cd10590edea1ab3ec06c4df69a4566450b8），有限历史逻辑lineage，不是完整当前classpath。

你是唯一source/target writer。owned新 `.local/iam-o2b-f2a-fixes/`、`docs/evidence/iam-stage-4-o2b-f2a-fixes/`、`.tools/iam-o2b-f2a-fix-*`、新报告 `docs/evidence/iam-stage-4-o2b-f2a-fixes-report.md`。不改原作者artifact目录/原报告/原审查/旧raw-epoch-patch-replay-库存/八执行索引/root notes。原manifest中live source/target在新epoch会合法变为历史值，原immutable snapshots+root审计已保存；不得为了让旧清单重新匹配而改写原证据。所有新target/diagnostics另归本修正epoch。

## 必修内容：完整一波修正

- I1：所有允许的公开读取入口必须持有覆盖**整个调用**的单reader ownership，包括readNBytes(byte[],off,len)及transferTo。内部已持owner的计量read路径不能再次抢CAS或重复credit；transferTo卡target.write时同向第二读取仍立即拒绝，active ownership直到实际bulk调用退出才归还。保持readAllBytes/readNBytes(int)、正skip的有限拒绝政策；参数、EOF/0/异常/stop/close及唯一credit不退化。新增确定性latch测试在transferTo的target.write暂停时探第二reader，而非靠高次数调度碰运气；再验证bulk实际数据/credit和blocked bulk退出前槽不释放。
- I2：plain rawServer的application=0/accepted=1必须由实际有界观察产生。捕获有限客户端字节，区分失败握手允许记录与非TLS/应用标记，不能把ClientHello算应用，也不能假称解密了未知TLS内容；有限accept窗口真正检查额外连接。给负控制：仅测试发送器故意发应用明文/额外连接，观察器必须发现，原硬编码helper应出现真实功能RED。正常组件的plain拒绝case仍须零应用、无fallback/额外连接的实际证据。所有peer仅loopback临时端口、有限字节/时间、finally关闭，不自造生产crypto。
- I3：ProcessBuilder.start成功的下一步就要建立异常清理责任。launch metadata/Child/collector建立失败都destroy/有限waitFor实际退出，保留原始失败，不留端点或收集线程。新增窄测试把本测试owned launch目标设成目录等确定写入失败，取得该自有新进程PID/Process后断言真实退出。RED时也在测试finally收拾被复现的泄漏；不枚举/操作全系统或既有服务进程。
- M1一并修：SafeSsl在清delegate引用前保存实际已知closed/inputShutdown/outputShutdown状态，避免拿slot terminated标记代替socket状态。保留关闭失败不归还槽策略；用实际bottom已关闭而后续close失败的受控路径断言状态。测试替身只机械状态/退出，不伪装验证TLS成功，不提供public verified工厂。
- M2一并修：仅展开Connection/Test中含锁、计数、异常、finally、阻塞测试的复杂单行方法，规范常规缩进和注释；简单一行委托getter可保留。不重构无关对象、不新造框架，不升级格式工具/依赖。
- M3/M4永久保留：旧13份Windows跨盘诊断/deprecation不在本轮扩修；不关闭JDK检查。failed thresholds source epoch与较晚input-source observation不得合并证明。原报告冻结不可改，**新修正报告明确纠正原plain“已独立观察”声明的适用范围**并指向新证据；不抹原错误claim/失败历史。独立原review将追加复审结论，原16561B前缀须保留。

## TDD与验证（新epoch，不重跑历史包装）

按TDD/SDD/ponytail/verification技能，先关键失败测试再最小实现；实际functional RED区别于编译/脚手架失败。测试helper为I2/I3本身被修正对象，可先加观察/测试接缝但保持旧错误逻辑得到RED；说明真实失败因果，不宣称所有最终case都独立RED。

阶段唯一 `iam-stage-o2b-f2a-fix-*`；沿既有offline `.tools/run-iam-module-tests.mjs`，语法comma/#支持，plus不支持，JDK21/project-only m2。开发只跑新失败与直接受影响methods。最后一次有限新epoch验收可跑DebtTest+TlsTest两类，因为ApplicationInput/退出语义改变且格式涉及Connection/Test；用唯一 `iam-stage-o2b-f2a-fix-final`，实际数以XML为准。此为新source修正后的必要矩阵，原final39日志/结果保持历史，不以相同旧字节重跑美化。final成功后不再改source/追加重跑，失败则只针对新改动必要修复验证。

parent/child cwd固定工程root；沿原新JVM H32768/chain4/AIAfalse/正常profile，所有新peer写**新fix owned**绝对路径，不能往原peer目录追加。只读复用原测试PKCS12。无需重复keytool生成/原fixtures/旧provider probe/原TLS测试历史/PG或原Git replay。

## 证据、一次补丁与交还

原evidence contract `.local/iam-o2b-f2a-evidence-contract.md` 的记录、有限target、成功阶段缓存/元数据恢复、无循环handoff/owninventory规则继续适用，以上before618/2existing changes/新owned覆盖原before612/6new。写出原source618→新source618、delta2/new0，所有source snapshots/current编译epoch及原序manifest含names/bytes/SHA。

每实际stage退出立即归档command/cwd/exit/stdout/stderr/raw/actual Running XML与txt/source-epoch/编译原始inputFiles与createdFiles；不在测试进程运行中改source。finite target仅实际common/gateway列表、实际resources/fixtureJAR/Running报告，不扫旧target全树，新diagnostics独立。after-target以原逻辑2200加本轮真实finite overlay合并，明确历史语义。出现失败立即保留；metadata问题只恢复未完成部分，不重复成功测试/phase/hash/replay。

从原完整618 source建本任务新的完整private baseline/Git；add前private info/attributes `* -text -diff`及实际检查、隔离system/global Git配置，闭合Windows长参数pathspec。新different binary raw patch只含2修改文件，首次check/apply各一次，full after618字节一致，无after-apply source copy，无commit。命令/结果先持久化再断言；原patch168795B/5750a5a...保持。

新报告写实现/精确API/Actual RED-GREEN/命令/计数/源码对照/所有修正与限制，不直接称Accepted。新handoff/control manifests/source-before-after-delta/new/finite-target/patch-replay/owninventory含所有owned+privategit+新stage/peer/源码快照/helpers/report，inventory只排自身；handoff不嵌inventory自身SHA，最终回消息提供两者身份避免循环。冻结归还全部source/target/owned写权后只回DONE/真实tests摘要/report-handoff-inventory字节SHA/concerns。

root将做新两文件complete-context fix package→同原独立reviewer复审→修正epoch有限审计，原六phase5941审计继承为历史证据且不再hash旧live source/target。全通过才限定F2a Accepted/更新八索引，之后按计划F2b1原Q HTTP→F2b2私有E→F2c1验材→F2c2公开SSE；生产启动/完整TLS容量仍待验。
