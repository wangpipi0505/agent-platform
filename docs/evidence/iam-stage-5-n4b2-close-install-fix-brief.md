# N4b2 I1 / M1：异步上下文迟到安装修复

本 brief 由 root 按现有持续开发授权派发。唯一工程 G:/207/项目/算法重组工具/代码/agent-platform；不访问相邻旧工程。修复完继续独立复审，不自签 N4b2 或 full Task 5。

## 已核实问题与固定起点

独立报告 `docs/evidence/review-iam-stage-5-n4b2.md`，13364 B，SHA256 `12281551feffd03506c6b5ce9b07cb87f4dabf98c7473c81f619b7cd55b68f57`：Spec 未通过，Task Quality Needs fixes，C0/I1/M1。root 已完整读取报告，并亲读 PublishedRunProxy 69–110、331–435 行确认 I1。

I1：active.add 之后，mvc.startAsync / exchange.async 安装之前，close 可先 fail/terminate。请求线程恢复后创建 AsyncContext，但终态短路使迟到上下文没有被代理完成。实际 startAsync 返回与字段安装之间也存在同类窗口。active=0 或 Future 完成均不能代替 AsyncContext 已完成。

M1：common 测试 `ordinaryReceiverStillRequiresItsServiceGuardOnDisabledCatalogRows` 实际注册 /unregistered 并断言 ROUTE_COVERAGE_MISSING。将名字改为准确描述现有行为，不扩大为另一项 service guard 迁移或额外大测试。

原 N4b2 已冻结并由 root 独立核对：source377、target1879；8新增5修改 source、65新增20修改 target、业务JAR0；当前/frozen/replay source及before均一致；16347自有证据、28阶段35实际XML、48个当前用例逐case最后有效green已核。原 source/target/patch/报告/所有失败与review不得重写。

不可变 before 可直接引用原 `docs/evidence/iam-stage-5-n4b2-gateway-published-chain/source-after.json`（77685 B，SHA `cdc3f4a1151a57005aed2f9a605226bae7f6456d8c7d1c339af5a2476a11968a`）及同目录 target-after.json（386918 B，SHA `fbe26616ad550f9a78bb16700d815fc868d0053e6d99ebc177ca4b61924f5fb0`），物理原件在 frozen/ 与 target-final/。修改前核当前完整 manifests 与该起点相等，不必把未变化的全部 source/target 再复制一遍。

## 唯一写入范围

生产：`platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java`。

测试：`platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java`；`admin-common/src/test/java/cn/agentplatform/common/authority/GatewayIngressBoundaryTest.java`（只 M1 名称准确化，保留原断言）。

自有目录：`.local/iam-n4b2-close-install-fix/`、`docs/evidence/iam-stage-5-n4b2-close-install-fix/`；新报告 `docs/evidence/iam-stage-5-n4b2-close-install-fix-report.md`。

其它源码、POM、SQL、配置、前端、fixture main、旧证据/报告/台账、runner 均不改；不 package/deploy/启动切换主服务，不接业务服务JAR。全部命令显式唯一 workdir。单一 implementer，不再委派。

## 最小实现责任

- 所有 async 句柄安装必须与 Exchange 终态形成可靠交接：如果 late install 看到终态，立即实际完成那个新上下文，不能保留孤立上下文或复活状态；正常安装后 close 则由原 terminate 路径完成。
- 同时防止已终态后再安装 timeout 任务，不能靠迟到定时器最终运行来掩盖资源遗留。句柄/状态在同一 Exchange monitor 下受控；不要把外部 mvc.startAsync 整体放进全局或 Exchange 长锁，令 close 等待框架/测试闩锁。
- 仅再读一次 closed 不能关闭交错；不要只调整 active 计数、吞异常或缩短容器超时作为修复。保留回调非阻塞、原 total5000/hop2000/connect1000、私有 Portal proof、真实发送/写前检查、HTTP cancellation.install、自线程 cancel(false)、其它线程中断及 worker finally。
- 写注释解释 terminal 与资源安装的责任，不重写代理框架、增加通用生命周期库或新的生产公开测试工厂/proof 构造能力。

## 定向真实红绿

先只写测试，在原未修复生产上取得真正行为红。使用 test-only ServletRequestWrapper / 现有 fixture 的有限闩锁，分别暂停：①实际 startAsync 前，已 active.add；②实际 startAsync 已创建上下文、返回后安装前。可在实际 Servlet API 包装处暂停，不新增生产 seam，不使用伪 PortalRequest 或同FQN假 controller。

每个交错：启动真实 HTTP 请求，等特定闩锁，close，释放，再核真实 AsyncContext onComplete、有限返回、无容器超时兜底，metadata/main/engine业务调用为0，active/worker最终归零；迟到回调不能再动已完成响应。只核 active=0 不够。闩锁有有限超时，finally 可靠释放/关子资源；不跑高次数循环、危险慢读或未知进程清理。若初次只能得到 setup/编译错误，如实保留并修测试，不能称行为红。

绿阶段只运行这两个新增安装窗例、受影响普通真实 MVC 正例、现有实际 engine read 中 close 的定向例，以及 M1 准确名称例。使用不变 `.tools/run-iam-module-tests.mjs`、唯一 stage、合法逗号/井号 selector；不使用加号。确有受影响原因才增加具名验证，不重跑旧48、PG25、common/B1/N3/全962。完整 PG 源码/协议未变，不再据此虚称新PG绿。

## 证据与交还

每次命令前冻结完整当前 source SHA manifest；只物理捕获本次允许变动的文件，未改内容引用上述不可变 before，明确恢复方式/采集时间。不要每轮复制全部377源码或历史target/JAR。保留实际原命令/cwd/exit/raw、actualRunning与真正选中XML；区分setup、真实红、绿、compile-only，不拼轮次数。

原123837文件完整物理保护已实际通过。保持其 before/after不变；用完整基底descriptor继承、明确本批路径例外、实际关键runner/8索引/原设计和本批变更检查，不无理由再扫描10.56GB旧封存证据。标清复用与本次物理核范围，不能冒称重新全量hash。source/target delta 全列、业务JAR仍0，已有tests JAR若因test阶段生成如实列出。复用旧 immutable source与本批差异做完整上下文patch，实际root cwd check/apply，完整有效replay377与current相等；frozen如采用原before加本批overlay，必须如实写物理引用，不声称另有完整拷贝。

现有失败、slowreader73728bytes/5.5s主动close、proxy16历史退出缺口、common JDK retry/close限制原样保留；此次不新增生产IAM、D4/RR1/RR2或外部环境验收结论。所有新测试资源实际关闭，不追杀未知PID。

交还 compact paths/bytes/SHA、baseline/current/frozen-overlay/replay/target manifests、patch与actual apply、真实红绿XML/适用范围、当前资源/保护证据和报告。明确归还全部写权，再由原独立reviewer对修复差异复审；原未通过报告保持不变或由reviewer按保留prefix的受控追加处理。
