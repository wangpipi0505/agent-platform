# Spec Compliance

- ✅ Spec compliant：本次有限修复关闭原I1和M1。`platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java:143–162` 的 installAsync 在同一Exchange monitor中交接实际句柄、终态与timeout安装：已终态则直接完成迟到context并返回false；正常安装后若closed则fail/terminate；其余路径才登记timeout。`start:92–93` 保持外部MVC startAsync在锁外；`read:164–165` 在同monitor检查READING，阻止关闭后再安装输入listener。
- ✅ I1的两个次序均闭合：close在async未归属时先终止，由迟到installer负责actual.complete；安装先赢时由原terminate负责完成。`fail:360–379` 对async==null只设置允许的失败status并terminal清理，在恒定JSON和writer注册前返回；`terminate/completeAsync:399–419` 保持单terminal清理与实际complete。root已明确允许该窗口安全空503体（`docs/evidence/iam-stage-5-n4b2-close-install-fix/root-async-unowned-decision.json:4`），不是省略普通已安装async的JSON响应，也没有通过扩大锁范围让close等待外部startAsync。
- ✅ M1只准确改名为ordinaryReceiverRejectsAnUnregisteredRouteBeforeGuardChecks；原/unregistered和ROUTE_COVERAGE_MISSING断言保持（`admin-common/src/test/java/cn/agentplatform/common/authority/GatewayIngressBoundaryTest.java:38–56`；修复diff第47–66行）。它不再声称验证disabled catalog service guard。
- ✅ 没有增加生产公开factory/proof/seam或通用生命周期框架。唯一生产文件+两个测试文件满足写入范围；total5000、业务顺序、actual-send/完整buffer/每次成功write复核、Future先赋值、worker finally及自线程cancel(false)在完整上下文中保持（修复diff第193–225、298–397、399–445行；修复brief第19–32行）。
- ✅ 新测试使用真实HTTP、真实Portal/MVC/controller和委托实际Servlet API的有限wrapper；pause位于startAsync前及MVC返回后的getAsyncContext，分别证明close在未释放闩锁时返回、实际context完成且不靠容器timeout（`platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java:1537–1679,1700–1748`）。没有mock完成结果替代真实容器事件。
- ⚠️ 通过范围仅本次I1/M1与修复引入问题。原N4b2整体语义沿用先前完整审查；完整生产IAM、D4门户、独立GETstatus、RR1/RR2、其它执行形态及真实外部环境仍是后续工作。原slowreader73728成功bytes/5500ms主动关闭、proxy16历史app PID/exit缺证、common JDK retry/close限制全部保留（修复报告第64行；原审查报告Spec/Assessment）。
- ⚠️ 物理保护、全部target及overlay真实性不由本次diff单独证明。root已报告本批2435 distinct实际核验PASS，包括完整377source/1888target inventory、3changed baseline/frozen/replay、112封存项、4stage/5XML、28runners/8indexes/design/13anchors；旧123837/16347继承已核谱系，没有重新全扫。我未重复这些全量核验，也没有把root审计当成自己运行测试（root交接`.local/iam-n4b2-fix-root-check.json`，2680B，SHA fc27ecd765977bb0f6e09e637e6e6c6ef6b2ed8d4e3ef2b761276dbfd658790f）。

# Strengths

- ✅ 修复覆盖真实根因：late-install不保留已terminal上下文、不复活状态；timeout安装与terminal互斥，异常从start catch回到terminate。未归属async分支不调用getOutputStream/setWriteListener，避免red02实际观测的Tomcat executeDispatches等待（`PublishedRunProxy.java:93–97,143–162,374–379`；`iam-stage-n4b2-fix-red02/raw.log:71–72`）。
- ✅ 断言区分“代理调用complete”“容器onComplete”“容器onTimeout”“finally手动清理”，并在成功断言前等待真实事件。两个窗口green04均completeCalls1/onComplete1/onTimeout0/cleanupCompleted=false，metadata、engine、authority业务调用和worker均0；不是只验active=0（测试第1580–1625行；`iam-stage-n4b2-fix-green04/raw.log:74–75`）。
- ✅ 新测试晚到回调只重放实际登记的MVC timeout/error listener，没有再次手动执行整个Spring onComplete链；异常不吞，成功后writtenBytes/completeCalls不增长。终于由容器自己的一次onComplete证明回收（测试第1620–1625、1811–1819行）。
- ✅ cleanup在finally释放闩锁，真实context手动兜底独立标记，主异常保留，清理异常suppressed；owned HttpClient和caller executor均有限shutdown/await。历史red02清理失败明确保留，不被当前JVM0改写成历史有序退出（测试第1626–1677行；red02 XML的TimeoutException及两个suppressed；修复报告第19、62行）。

# Issues

## Critical (Must Fix)

- 无。当前修复没有新增信任入口、凭据制造能力或业务目标选择（修复diff第145–184、230–252、461–508行）。

## Important (Should Fix)

- 无。原I1已按代码交接与两个真实窗口证据关闭；没有要求重新运行PG或原48用例来代替这两个精确交错（`PublishedRunProxy.java:143–162,374–419`；green04 XML两个case均0 failure/error）。

## Minor (Nice to Have)

### D1 — 既有测试基础设施/编译诊断仍存在，非本次代码阻断

- 位置：`docs/evidence/iam-stage-5-n4b2-close-install-fix/frozen-target-overlay/platform-gateway/target/surefire-reports/2026-10-21T03-03-59_110.dumpstream:2–4`，以及同批其它4个dumpstream；`iam-stage-n4b2-fix-red01/raw.log:37–38,65–66`、`iam-stage-n4b2-fix-green04/raw.log:64–65`。
- 五个344B dumpstream均记录Surefire Boot Manifest-JAR absolute classpath / other has different root；raw还保留未改GatewayRunAuthorizationTest的unchecked提示、未改GatewayPublishedChainPostgresTest的deprecation提示。部分中文诊断已存在解码乱码，不能据此声称日志完全清洁。该PG类这里只被编译，实际Running/selectedXML没有PG执行。
- 严重性：非阻断Minor诊断观察，不是新生产缺陷，也不削弱已选case的XML和真实生命周期marker。固定runner、POM、argLine本轮未改，不能为消除提示临时加入dumpstream建议的disableClassPathURLCheck或删除失败证据。保留并在获授权的测试基础设施维护中处理；本次不要求扩scope或重跑已绿。

# Assessment

**Task quality: Approved.** 原I1/M1均关闭，修复代码缺陷计数C0/I0/M0；按模板将实际未消失的构建诊断单列Minor观察D1，报告总分类C0/I0/M1（非阻断）。生产改动小且责任清晰；真实测试同时校验close独立返回与context真实完成，没有以计数归零代替生命周期完成。

- ✅ 实际执行证据：直接解析四轮gateway XML testcase及green03 common XML，并读取原命令、raw关键marker与红栈。red01为2 failures，其中before的actual onComplete等待失败且completeCalls0是行为红，after被清理断言覆盖，不重复认定干净红。red02为1 error，主异常Future.get TimeoutException，原栈明确request在proxy.start的getAsyncContext、close阻于Tomcat setWriteListener/executeDispatches；response/caller cleanup两个suppressed均保留（red01 raw第75行、red02 raw第71–73行及对应XML）。
- ✅ green03是5case中的3pass/2error、exit1。两窗已经真实complete1，但人工再次调用整个Spring onComplete后触发recycled RequestFacade.removeAttribute；XML stack包含WebAsyncManager completion handler，不能算5绿。green04仅2新窗pass、exit0，其raw记录真实onComplete1/onTimeout0/cleanupfalse，实际0.810s/0.438s（green03 raw第86–87行及XML；green04 raw第74–75行及XML）。
- ✅ 适用覆盖为green03三个有效case（真实MVC正例、实际engine read中close、common改名）+green04两个新窗，不是最终同源一次5全绿。为独立判断green03适用性，额外只读其历史overlay `PublishedRunProxy.java:360–406` 和历史test `lateCallbacks:1810–1821`：最终将async-null terminal分支移到恒定JSON分配之前，不改变async已安装的正常/engine-read路径；删除整个Spring onComplete人工重放，仅保留timeout/error；owned client cleanup调整只在新race helper。当前3个既有case断言仍在本包完整上下文中，common仅rename。故3+2覆盖有具体路径依据，不由“作者说是无关变化”自动推出。
- ✅ 原chunked raw helper本次formatter展开见diff第2022–2077行：逐字请求响应framing、header上限、超时、异常断言和退出检查保持等价；没有借格式扩大HTTP transport修改或新增PG验证主张。
- ✅ 审阅范围：修复brief第1–50行、作者report第1–66行、handoff全文、coverage ledger和tool-events全文、root unowned-async裁决；唯一fullcontext包连续按150行读取第1–2380行至EOF，3完整文件，无截断，无重复读取current changedfile。未重审原4869行完整B2；沿用原已核Spring6.2.7 async行为，不额外遍历不相关源码或上游库。原review13364B/SHA12281551feffd03506c6b5ce9b07cb87f4dabf98c7473c81f619b7cd55b68f57保持不变；新brief6843B、report12005B、diff125848B均核给定SHA完全一致。
- ✅ 固定起点/终点：before source manifest cdc3f4a1151a57005aed2f9a605226bae7f6456d8c7d1c339af5a2476a11968a；final 6caf11a109ae9812af0d6994cfc11761923c40ecd943dfa793a9b900ea6bbba6。物理overlay组合而非新完整副本；旧ZIP审计15 raw exact/8仅CRLF表示转换结论保留，不在本轮冒称重新全源校验（修复报告第48、54、58行）。
- ⚠️ 本审查没有运行Maven/javac、测试/probe/loop、HTTP/浏览器、DB或进程操作；没有git/index/HEAD操作或相邻旧工程访问。对原命令/测试/资源结果的读取不是重新执行。一次只读诊断汇总命令空输出exit1后改为具名小段读取，未影响源码/证据；首次rg未包含ignored evidence，随后仅对本批目录用hidden/no-ignore定位5个dumpstream。
- ✅ 唯一新增物为本报告。未改原审查或任何source/target/已有证据。封存后归还本报告写权并停止写入；bytes/SHA通过交还消息提供。