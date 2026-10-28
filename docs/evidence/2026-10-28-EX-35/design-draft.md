# EX-35 修复设计与实施记录

开工草稿保留如下；产品修复与定向/完整类验证已于2026-10-28实施，最终结果以本目录README及当次run.json、methods.csv为准。没有使用superpower，没有操作index/commit。当前HEAD为`20333f35bc9f97dc11df296c04c8028e27fd42c8`。

实测校正：普通终态仍等待在途Servlet操作返回后领取唯一AsyncContext；但真实Tomcat timeout必须在其listener返回前调用complete，否则容器会将其视为未完成timeout。首个锁边界版本已无死锁、但503断言失败，因此最终版本为真实MVC timeout增加锁外完成领取；终态阻止尚在返回途中的writer回调写字节。首次写入前权限过期仍在readiness之后重新检查，最终拒绝status在complete前恢复。后续绿色定向中503、唯一complete、零输出、零上游与原403零成功字节均通过。

独立预审追加的两个实际反例已保留红→绿：close先于active.add时迟到context原空200，已领取合法chunk被并发fail改成503；修复后分别503/零字节与200/完整111字节。强制重放使用与旧module候选相同的产品SHA，真实客户端收到111字节完整成功JSON且503的红事实见`review-races-red-wire-20261028-202145-165`。完整类最终33方法通过。静态审阅随后指出installAsync连续两次读取closed仍有窄窗口；计划在当前固定源码模块采集结束后仅改单读共用结果，经审阅者SHA冻结再运行最终33/27，不能沿用前一候选通过。

日期：2026-10-28；执行者：`ex35_gateway_fix`。当前为只读准备，产品/测试尚未修改，未运行 Maven、HTTP、PG 或前端。等待 EX-00 本地提交与主执行者发放单一测试窗口。

## 范围与现状

- 已读 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`、本卡简报及 EX-00 `gateway-close-deadlock-analysis.md`。
- 已读 `PublishedRunProxy`、`PublishedRunHttp`（只读）、`PublishedMetadataClient`、`GatewayController` 直接调用和 `GatewayRunAuthorization.requireVerified` 的本地检查；已读原 `GatewayPublishedRunProxyTest` 场景及终态/字节/句柄断言。
- 只允许修改 `PublishedRunProxy.java`、`GatewayPublishedRunProxyTest.java` 及本证据目录。不改 `PublishedRunHttp`、授权、限流、5 秒总预算、2 MiB 限制、拒绝状态白名单或出站目标。
- 读取时 HEAD=`ebbfd62ca1d19efc4d172804efb5ac79453ceb64`；EX-00 证据由主执行者暂存，另有既存未跟踪证据/output，不属于本卡。未操作 index 或 commit。
- 开工清单重核为 27 个 `*Test.java`，另有 `PublishedMetadataSmokeMain.java`，不能把后者当第 28 个 JUnit 测试类。

## 最小边界设计

Exchange 监视器只决定状态、唯一 writer 安装权、在途 Servlet 操作和终态句柄归属。所有 Servlet 注册/读取/输出/complete 与 HTTP cancellation、worker cancellation 在退出该监视器后执行。必须同时审视 `installAsync`、读取监听器、`submit` 的 trailer 读取、成功 writer、`fail`、`terminate`，不能只挪 timer 一处调用。

在途 Servlet 操作在锁内计数，终态只摘除 active、清空业务引用、领取清理权；已有注册/写操作尚未退出时，最后退出者领取并完成唯一真实 AsyncContext，避免 close/timeout 与 inline callback 重复完成。writer 在锁内预留一次安装，回调先在锁外判断 readiness，再在锁内检查终态/截止/授权并领取本次有限字节写入，实际输出在锁外。迟到回调不能重新领取输出权。

取消权限一次领取，实际取消锁外执行，防止 fail→terminate 对同一拥有的 HTTP 句柄重复取消。Future 必须仍先安装再 execute；late AsyncContext 若见终态，由唯一安装者在锁外完成。close 的现有实际清理与 3 秒 executor 等待继续保留。

## 有界失败复现设计

新增测试及子 JVM 入口均留在原测试文件，无新依赖。子 JVM 启动相同真实 Spring/Security/Tomcat、签名门户请求、真实代理与传输。请求只送不完整 chunked JSON，避免上游调用。测试包装捕获实际 AsyncContext，并把它的容器 timeout 暂缓至产品 timer 已到达真实 `setWriteListener` 委托窗口；随后由实际 Tomcat timeout listener 进入产品 `fail`，包装立即真实委托 `setWriteListener`。

原实现中 timer 持 Exchange，timeout 回调持真实 Tomcat 处理锁，真实委托建立 EX-00 同型环；不是伪造 close，也不替换产品 timeout。子 JVM 的有界看门狗以 ThreadMXBean 提取线程名、锁类型和必要方法帧，发现锁环后非零退出。父 JUnit 对子进程 `waitFor` 设置总上界，必要时强制回收，确保红阶段不会无限挂住 Surefire/AfterAll。

修复后完全相同入口必须正常完成实际 close，并报告唯一 AsyncContext.complete、无死锁、无上游/额外输出、active 归零及迟到回调无副作用。原始 EX-00 锁环事实保留，不覆盖。

## 待执行验收

1. EX-00 提交后先只新增复现测试，申请单一测试窗口，保存单方法原实现红结果与安全线程/锁事实。
2. 产品最小锁边界修复；同方法绿色后，完整运行原 `GatewayPublishedRunProxyTest`（新增方法另列，所有原方法必须执行并退出）。
3. 在主执行者安排下安装当前依赖、无 `-am` 运行 platform-gateway 全部 27 类回归；以 EX-00 精确方法清单比较新增/消失/失败/跳过/坏类/未执行。
4. 独立只读评审，补证据 README、命令/退出码、方法对比、限制及本地提交/回滚标识。当前草稿不代表修复或任何测试通过。
