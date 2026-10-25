# N4b2 I1/M1：迟到 AsyncContext 安装修复

本批按root正式brief完成有限修复并冻结，交还后等待原独立reviewer复审。原独立报告C0/I1/M1、Spec未过/Task Quality Needs fixes保持原样，本作者不自行签收N4b2或full Task5。唯一工程G:/207/项目/算法重组工具/代码/agent-platform；未读/复用相邻旧工程，没有子代理、package/deploy/push、主服务切换或业务服务JAR引入。

## 已读依据与不可变起点

完整读修复brief至EOF：6843B SHA4ee98c9637bac111efb5c038c3d3913f541d6212a4a50b93c816bd041536fba2；完整读独立review至EOF：13364B SHA12281551feffd03506c6b5ce9b07cb87f4dabf98c7473c81f619b7cd55b68f57。按receiving-code-review/TDD/systematic-debugging核实代码及固定Spring6.2.7 StandardServletAsyncWebRequest已有归档的startAsync/onComplete/onError方法，先真实红再修改生产。

修改前完整current source377/target1879与原B2 source-after/target-after相等，且核了原frozen/target-final物理SHA。原source manifest77685B SHAcdc3f4a1151a57005aed2f9a605226bae7f6456d8c7d1c339af5a2476a11968a，target386918B SHAfbe26616ad550f9a78bb16700d815fc868d0053e6d99ebc177ca4b61924f5fb0。原物理封存直接引用，不复制旧全target/JAR，不修改原报告/证据。每个新stage前记录完整source377 SHA，物理只快照本批3文件；未改文件复用原immutable frozen。每次真实运行保留原command/cwd/time/exit/raw/actualRunning/选中XML及完整target manifest。

唯一生产位置PublishedRunProxy；唯一测试位置GatewayPublishedRunProxyTest及common GatewayIngressBoundaryTest。common只把ordinaryReceiverStillRequiresItsServiceGuardOnDisabledCatalogRows改成ordinaryReceiverRejectsAnUnregisteredRouteBeforeGuardChecks；freeze脚本逐字核除此名称外原断言/字节完全相等，未伪称disabled catalog service guard覆盖。其余374 source、POM/配置/SQL/前端/fixture main/runner均不改。

## I1实测及最小闭环

旧代码active.add后在monitor外mvc.startAsync、再写exchange.async；close先terminal会留下后来创建的实际context。此次使用test-only ServletRequestWrapper与委托真实AsyncContext，分别暂停实际Servlet startAsync前、以及MVC startAsync已返回后代理getAsyncContext返回前。所有请求均走真实Tomcat/Security/MVC/GatewayController及实际Portal proof，无新增生产seam/公开factory/假PortalRequest/同FQN controller。有限闩锁finally释放，记录实际onComplete、proxy complete调用、容器onTimeout及cleanup手动完成的不同责任。

red01 beforeStart取得真实行为红：代理completeCalls0、1500ms内onComplete=false，active0/worker0不能证明请求完成。finally手动actual.complete仅作清理，cleanupCompleted=true、其后onComplete1，不当作代理通过。red01 after窗最终异常被executor await清理覆盖，保留原源码/raw/XML，不能称其为干净的完成红。

只跑after窗red02后，有限栈确认请求线程为AsyncInstallWindow.getAsyncContext→Security Servlet3 wrapper.getAsyncContext→PublishedRunProxy.start:93，确在mvc.startAsync返回后；不是把闩锁放在Spring stateLock里制造问题。关闭线程实际阻在Tomcat AbstractProcessor.executeDispatches ReentrantLock→Response.setWriteListener→Exchange.installWriter→fail→close。主异常是close Future.get1s TimeoutException；response/caller清理失败作为suppressed保留，未谎称该轮caller按时退出。其fork/Maven随后结束，当前精确本工程JVM读证据为0；不补造历史PID/线程有序退出。

root依据实际栈追加同scope裁决（root-async-unowned-decision.json保留）：async尚未归Exchange时，fail只设置安全失败status并terminal清理，不能注册writer或触外部dispatch；迟到安装者实际complete。这个窗口允许安全空错误体，不声称完整ApiResponse；已安装async时原JSON/guard/error/writer语义保留。

实现保持外部mvc.startAsync在锁外，不让close等待框架或fixture闩锁。新增private synchronized installAsync：看到TERMINAL直接完成实际新context并返回，不保留/复活资源；正常句柄写入、closed检查及timeout安装都在同一Exchange monitor内。若close后active登记竞态令前一遍未看见此Exchange，安装时closed也会安全fail并terminate。已有正常terminate完成已安装context，completeAsync统一保持原IllegalStateException处理。

fail在async==null时先设原安全status，再terminate、清空source/proof/output/input并取消原资源，且在构造恒定错误JSON之前返回，不安装WriteListener。read安装入口也用短Exchange monitor并在state不为READING时返回，防止安装后close赢得terminal再继续装输入listener。未锁外部mvc.startAsync，未改变原5000ms总monotonic/2000ms hop/1000ms connect、私有Portal proof、actualsend/完整buffer/实际write前复核、HTTP cancellation.install竞态、Future先赋值、自线程cancel(false)/其它线程中断或workerfinally语义。

## 四轮实际执行与五个选择器的适用覆盖

不变runner为.tools/run-iam-module-tests.mjs；新stage wrapper在ownfix目录，所有shell显式root cwd，离线Maven仅platform-gateway -am test及合法逗号/井号selector。没有旧48、PG25、旧common/B1/N3/全962重跑，也没有新PG结果声明。

|stage|实际Running|实际XML tests|pass/failure/error|exit|责任|
|---|---|---:|---|---:|---|
|fix-red01|GatewayPublishedRunProxyTest|2|0/2/0|1|before真实完成红；after清理覆盖|
|fix-red02|GatewayPublishedRunProxyTest|1|0/0/1|1|after真实close1s超时+原生栈，清理suppressed|
|fix-green03|common边界+gateway proxy|5|3/0/2|1|三个有效green；两个不合法whole-framework completion重放oracle错误|
|fix-green04|gateway proxy|2|2/0/0|0|两个最终新窗实际全绿|

Green03两个新窗已实际观测completeCalls1/onComplete1/onTimeout0/cleanupCompleted=false，但随后人工重复整个Spring onComplete链，WebAsyncManager移除已回收RequestFacade属性抛IllegalStateException，因此仍明确算2 errors，不合称5绿。修正test oracle：真实onComplete仅由容器一次观测，迟到只直接重放实际已登记MVC timeout/error对象；异常不吞，若Spring不支持仍会令测试失败。green04这两条合法重放通过，response写字节/complete调用/上游计数不增。没有为了适配重放增加生产工厂或大框架。

最终两窗均证明close在闩锁未释放时1s内独立返回；释放后实际context完成一次，1500ms内收到onComplete（不依赖5s容器timeout），HTTP有限503且无成功r-finite、容器onTimeout0、metadata queries/engine posts/authority业务calls均0、active/workerStarts/workerExits均0、cleanupCompleted=false。fixture自己拥有HttpClient，finally shutdownNow及awaitTermination通过；caller executor也awaitTermination通过，所有Spring context、有限peer HTTPserver和LoopbackAuthority由既有关闭路径释放。

本批五个当前selector的最后有效green是：两个新窗在green04；realMvcAsyncProxyUsesSeparateTargetTicketsAndPreservesExactLegalBody、closeDuringRealEngineReadCancelsOwnedHandleAndLateCallbackIsTerminal、common准确名称在green03。最后源码相对green03只改新race helper的oracle/owned client cleanup，以及将async-null terminal分支放到constantJSON之前；正常已安装async和engine-read路径不进入该分支，common文件之后未动。因此是3+2适用覆盖，**不是最终同源码一次5全绿**。stage-ledger/coverage-ledger给精确XML、case、source SHA和限制。

## 冻结、patch和全部target归因

最终有效source377，0新增/3修改/0删除；target1888，9新增/21修改/0删除，业务JAR变化0。common tests JAR因原test阶段绑定重生如实归因；其余变化是本代理/测试及格式行号相关class、选中XML/text、compiler createdFiles、5个344B dumpstream。dumpstream为Surefire Manifest-JAR绝对classpath的other has different root提示（G:classpath与V:tmp），原样保留，没有改runner/argLine/POM迁就；实际Running/XML/exit均完整。原PG测试源码编译deprecation提示不代表执行PG。

source-after/current、source-frozen-effective、source-replay-effective均完整377逻辑manifest，未改374内容物理引用原B2 frozen，改动3文件分别在本批frozen-overlay/replay-overlay。target-after/current/frozen-effective完整1888，未改原target物理引用B2 target-final，只有30个新增/修改文件拷入frozen-target-overlay；无tombstone。overlay-reference明确采集时间、实际路径和组合规则，不虚称另有377/1888整树新副本。

完整上下文source-fullcontext.patch由3文件before-overlay/frozen-overlay形成，own evidence先有* -text、不改root gitattributes；实际工程root cwd运行git apply --check和apply -p2 --directory=<own replay-overlay>，两者exit0。再将旧immutable base与实际replay3组合，完整377逻辑names/bytes/SHA逐项等current；有效frozen source、target也等current。freeze/source-delta/target-delta/target-attribution/replay-check全列，无隐藏测试fixture JAR、诊断class或编译metadata变化；冻结后没有再Maven或source/target编辑。

## 本次保护物理范围及继承边界

原123837/10.56GB完整物理SHA pass、28runners/8indexes及原B2 16347新证据的root独立核验按原不可变descriptor继承，不再全扫或冒称本批重新做过。当前fullsource/target和有效overlay/replay本次实际逐SHA核对。另本次物理核28 runners、8最新索引、239660B原设计（SHA3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f）及13个具名锚点before/after：0 mismatch。包括旧B2 handoff/seal/protected manifests/report、原review、新brief及root只读审计check，旧文件均未改。

起初猜测授权设计路径少了智能体构建子目录，文件不存在；在任何source编辑/Maven前由原B1 design-reference定位真实原件并物理SHA，保留原before及独立phased design supplement，未重写before。保护结果是上述具名physical+原全量descriptor继承，不宣称全仓新增扫描或又一次123837物理核。

root并行只读固定ZIP核的授权mutable路径精确为.local/current-work-state.json、.local/check-iam-n4b2-root-upstream.ps1、.local/iam-n4b2-root-upstream-check.json，以及后续明确追加的.local/check-iam-n4b2-root-upstream-rest.ps1。本批另只有两个ownfix目录、新报告、4个精确stage log/fresh-secret目录例外，没有宽排.local/stem。root报告旧core-AbstractHttpEntity归档与ZIP仅CRLF表示不同，保留原失败脚本并续剩余entry；本批未修旧归档或transport，也不把该root检查算作作者新全库源码阅读。

## 交还与既有限制

resource-check当前精确own Maven/Surefire/已知fixture JVM为0，未启动新PG子进程或处理未知PID。red02清理曾失败并非全部历史资源有序退出；最终green04实际客户端、caller、context结束证据与当前JVM0分别记录。所有命令/raw/XML/失败/diagnostic保留，未把setup或oracle错当行为绿。

原slowreader73728成功bytes、fixture5500ms主动close、proxy16历史app PID/port/exit缺口、common JDK retry/close限制均保留，不重跑、不补造证据。本批不承诺恶意peer公共Servlet API硬关闭、whole-chain0retry/全部网络硬取消，不新增普通生产IAM、D4、独立GET status、RR1/RR2或真实模型/本体/IAM/K8s验收。交还全部source/target/ownfix写权后，由原reviewer复审本差异；原未通过报告由reviewer按root受控追加规则处理。

root在收尾阶段明确授权另新增docs/evidence/iam-stage-4-d4a-portal-session-brief.md，仅为后续阶段准备；本批未读、未改、未将该实际文件收进own evidence清单，也未执行后续阶段。该具名外部新增不计本批source差异。
