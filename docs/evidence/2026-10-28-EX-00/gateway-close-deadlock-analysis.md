# EX-00 网关关闭死锁只读归因

日期：2026-10-28；执行者：`ex00_source_inventory`。源码 HEAD 为 `ebbfd62ca1d19efc4d172804efb5ac79453ceb64`，分支 `feat/v1.2-implementation`。本次只读取指定源码、必要直接调用方和采集者提供的脱敏线程事实；没有读取原始线程dump/原始日志、真实环境配置或密钥，没有运行 Maven/HTTP/PG，没有修改产品或测试代码。未使用 superpower。

## 结论

本轮已证实的是**产品组件 PublishedRunProxy 的 Exchange 监视器与真实 Tomcat 请求处理锁反序**：产品定时器持 Exchange 锁安装 Servlet WriteListener，等待 Tomcat 锁；Tomcat 异步超时回调持其处理锁进入 Exchange.fail，等待 Exchange。关闭线程随后也等 Exchange。它不能归为仅需调整合法默认配置的 Y-4 夹具红，也不能通过放长等待、跳过测试或改HTTP取消逻辑消除。

`close()` 中的3秒等待位于该同步清理之后，因此不约束此处死锁。EX-27可以承接归因登记和相关测试整理，但其当前“AP测试侧、产品规则只读”白名单**不能承接产品修复**；应由主执行者登记独立产品修复范围后实施。本文没有修复或通过结论。

## 本轮安全证据

来源均为 `baseline/20261028-164553-272/`，未读取其中raw文件：

- [platform-gateway-deadlock.json](baseline/20261028-164553-272/platform-gateway-deadlock.json)：捕获于 `2026-10-28T17:02:54.9818410+08:00`，测试类 `GatewayPublishedRunProxyTest` 的 AfterAll stop 阶段，JVM报告一个Java级死锁。
- [platform-gateway-deadlock-thread-facts.json](baseline/20261028-164553-272/platform-gateway-deadlock-thread-facts.json)：采集者从同一dump脱敏提取的线程/持锁关系及必要frames。
- [platform-gateway-deadlock-timeout-business-frames.json](baseline/20261028-164553-272/platform-gateway-deadlock-timeout-business-frames.json)：补齐产品定时器尾帧，确认 `installWriter:319 ← fail:396 ← installAsync lambda:158`。

| 线程 | 已持有/等待的锁 | 关键调用链 |
| --- | --- | --- |
| gateway-published-timeout | 持有 Exchange 监视器；等待 Tomcat `ReentrantLock$NonfairSync` | Exchange定时器 → fail → installWriter → Spring/Security输出包装 → 测试包装的直接委托 → CoyoteOutputStream.setWriteListener → Response.setWriteListener → AbstractProcessor.executeDispatches |
| http-nio-127.0.0.1-auto-19-exec-4 | 持有上述Tomcat锁；等待同一 Exchange 监视器 | CoyoteAdapter.asyncDispatch → AsyncContextImpl.timeout → StandardServletAsyncWebRequest.onTimeout → start注册的lambda → Exchange.fail:361 |
| main | 等待上述 Exchange；不是锁环的第三个持锁端 | Spring销毁bean → PublishedRunProxy.close:445 → fail:361；测试调用入口为stop:429的context.close |

锁环是 **Exchange → Tomcat处理锁 → Exchange**。main是关闭时被该环阻塞的线程。安全证据没有证明“main开始close才生成锁环”，不能把关闭触发时序作更强推断。

## 源码闭环

以下代理文件统一指 `platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java`：

1. `:111`定义5秒Exchange deadline；`:85`把MVC异步超时处理器注册成直接调用 `exchange.fail(503)`，`:88`～`:91`用同一deadline设置容器超时。
2. `installAsync`为同步方法（`:143`），`:156`～`:160`还给独立 `gateway-published-timeout` 调度器安装同期限的 `fail(503)`。两个真实超时路径可以并发，源码没有将其串行到同一个执行上下文。
3. `fail`是同步方法（`:360`）；准备错误状态后，`:396`在仍持 Exchange 锁时调用 `installWriter()`。`installWriter:319`直接调用外部Servlet输出流的 `setWriteListener`。线程尾帧与这条源码路径完全一致。
4. Servlet注册调用在本轮进入 Tomcat `AbstractProcessor.executeDispatches` 等待其锁；另一方面，容器超时回调在该锁内调用产品MVC timeout handler，再竞争 Exchange 锁。反序发生在产品同步边界与容器回调边界之间。
5. `close:443`～`:449`先同步遍历active、调用fail/terminate；只有到`:450`～`:455`才shutdown并awaitTermination。当前main卡在第一段，尚未到3秒等待。HTTP hop超时或Maven fork timeout也不会打破Java监视器/锁的这个循环。

同文件另有需要在修复时一并审视的外部调用边界，但本次不声称它们已形成第二个实测死锁：成功路径`:296`～`:303`同样持Exchange调用installWriter；`:399`～`:416`持Exchange执行AsyncContext.complete；写回调`:322`～`:334`在Exchange内调用输出流。只改定时器的一次调用点，可能留下同类跨框架持锁风险。

## 为什么不是纯测试夹具问题

测试文件为 `platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java`：

- `:169`～`:182`装配真实 `PublishedRunHttp`、`PublishedMetadataClient`、`PublishedRunProxy`，Spring按 `destroyMethod="close"`关闭产品对象；不是伪造的close实现。
- 输出观测包装 `:136`～`:138`的setWriteListener只做 `out.setWriteListener(l)`直接委托，未引入自己的锁/等待条件。已观测锁为真实Tomcat锁，另一端为产品Exchange监视器。
- `:426`～`:435`的AfterAll先释放测试后端latch，再关闭context；当前卡点`:429`已进入产品close。改变后续测试线程池/IAM清理顺序无法释放已经互相等待的这两把锁。
- 夹具会制造延迟、慢读和close竞态，可能增加窗口命中率；但“测试触发了竞态”不等于锁序由夹具创造。`PublishedRunProxy`自身同时设置两路超时并持锁调用Servlet API，足以解释已捕获路径。

产品直接调用方是 `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java:64`～`:81`：IAM入口取得可选PublishedRunProxy后调用start。当前只读搜索未在src/main找到该组件的具体bean构造；是否在某一实际部署装配、命中率和线上影响仍未验证，不能把本轮真实容器测试扩写成已部署环境事故。

## PublishedRunHttp的归因边界

`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java:121`～`:160`确有同步取消句柄，`:236`～`:240`还有HTTP deadline，`:327`～`:334`另有关闭/等待。但**本轮锁环与必要frames均未经过这些HTTP取消/Apache锁**；产品定时器已经走到Servlet安装writer阶段。

最初基于源码考虑的“Exchange/HTTP cancellation反序”已被线程事实排除为本次根因；不能据该初猜扩大修改PublishedRunHttp。HTTP超时、连接池配置、数据库或凭据也没有本轮证据支持为这次Java锁环原因。

## 最小后续修复范围与验收建议（未执行）

建议独立产品修复卡先限定：

| 文件/范围 | 用途 |
| --- | --- |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java` | 修正Exchange状态协调与外部Servlet调用的锁边界，覆盖fail/terminate/安装/完成/close；不更改授权、超时拒绝或错误信封规则 |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java` | 保存并确定性重现两路超时、writer安装与close竞争；验证无锁反序/无残留线程及迟到回调幂等 |
| 该修复卡自己的证据/台账范围 | 当次失败场景、定向/模块回归、方法集合差、独立评审与回滚 |

`PublishedRunHttp.java`和metadata/授权类先只读；需要新增生产改动时，以新失败证据重新核准白名单。无需数据库迁移。

设计评审的重点是：在Exchange锁内完成状态决议、唯一writer/终态所有权和必要句柄取交，在锁外调用会进入容器的API；同时保证close/超时/迟到回调不能重复安装writer或完成context。这里给出边界目标，不把“简单把installWriter挪出synchronized”当已验证修复。

建议先加入确定性并发失败场景，再跑手册定向入口 `Test-AP platform-gateway 'GatewayPublishedRunProxyTest'`。保留并覆盖现有：

- `stalledActualMetadataHandleIsCancelledAndWorkerReallyExits`（`:559`）；
- `closeDuringRealEngineReadCancelsOwnedHandleAndLateCallbackIsTerminal`（`:1266`）；
- `closeBeforeActualStartAsyncCompletesItsLateContext`（`:1538`）；
- `closeAfterMvcStartAsyncBeforeHandleInstallCompletesItsLateContext`（`:1543`）；
- 完整正常JSON、拒绝/超时无成功字节泄漏、worker/handle计数、重复close/迟到回调和客户端慢读。

新增场景应使用有期限的同步点证明“定时器已进入安装窗口、容器timeout并发、close可返回”，避免只靠随机sleep；失败清理本身也要有期限并保存安全线程事实，不能让AfterAll无限挂住而丢失用例结果。

定向之后按EX-00手册先安装当前依赖，再对 `platform-gateway` 无-am全模块采集，比较本轮精确方法集合及坏类/跳过/未执行，确认27个现有源码测试类按当时清单得到覆盖；不能以测试类部分断言执行过或fork最终退出替代该模块通过。若后续确实改到共享HTTP，再补 `PublishedMetadataClientTest`、`RunObservationGetTransportTest`等直接消费回归，并重新说明范围。

## EX-27承接判断与尚未证实项

《执行卡片-统一补差-20261028.md:185》～`:188`将EX-27限定于测试侧夹具/坏类归因，回滚只恢复夹具、不放宽产品校验。它可以登记这次失败、保留复现和指出责任任务；**当前纯测试范围不能修改src/main的同步实现**，也不能把本死锁统称Y-4后只改夹具。

尚未证实：本轮最先触发残留Exchange的是哪一个测试方法/输入；锁环先于还是同时发生于AfterAll关闭；在未包装输出流但同版本真实容器中的复现率；拟修复方案是否保持全部授权/终态不变量。上述不确定性不推翻已捕获锁环，但禁止声称已经修复、全量通过或EX-00完成。当前只将其作为基线产品缺陷交主执行者登记承接。
