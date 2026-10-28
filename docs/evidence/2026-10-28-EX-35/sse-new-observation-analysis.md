# 最终模块新增SSE失败的只读归因与独立承接建议

本报告不修SSE，不把额外单方法通过覆盖完整模块失败。最终`frozen-module-20261028-205724-595`的[gate.json](runs/frozen-module-20261028-205724-595/gate.json)保持`gatePassed=false`。EX-35主项及模块放行仍待独立问题真实闭环。

## 已观察事实

- 完整模块27类287方法，271PASS/8FAIL/8ERROR，0SKIP。原254方法无缺失，原代理30＋新增3方法全PASS；15个原非通过身份/异常类型不变，但新增`PortalRunEventsSseLifecycleTest#forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion`从PASS变FAIL，同时原TLS负对照从FAIL变PASS。总非通过数相同不能抵消方法替换。
- 真正失败是测试`:695`第一个`entered.await(5s)`预期true、实际false；该行后续`recyclesForTest()==0`断言未执行。另一组expected0/actual1是外层`:84`子JVM退出码，不是回收计数。
- 仅做一次、同`.env`/argLine/逐类新JVM的准确方法复验：[lifecycle-repeat-1-20261028-211206-488](runs/lifecycle-repeat-1-20261028-211206-488/run.json) exit0/1PASS。此后没有继续刷绿。
- [安全phase提取](runs/frozen-module-20261028-205724-595/sse-forced-release-phase-facts.json)：失败两trace各28行，before-write各4、ready-false各2，均无recycle-invalidated、worker-body-exit或completion-entry；成功复验各30行，前28阶段相同，各追加recycle-invalidated→worker-body-exit，且成功properties存在。没有自然完成/过期的phase证据，不能用该假设豁免。
- [SSE源身份](runs/frozen-module-20261028-205724-595/sse-source-comparison.json)确认测试与`PortalRunEventsSse.java`的Git blob均与EX-00原HEAD相同。该场景自己的子JVM直接构造Tomcat、SSE和HTTP；`:149–200`无Spring装配，不实例化或调用PublishedRunProxy。此隔离事实排除本卡直接调用链，不能单独证明夹具抖动。

## 固定上游入口与可检验交错

本仓已有固定Tomcat10.1.41的NioEndpoint归档与URL/SHA台账；m2没有sources.jar。另只读官方同tag的三个具名入口，未运行Java/javap或本地HTTP测试；不声明审计整个Tomcat：

- [SocketWrapperBase](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/tomcat/util/net/SocketWrapperBase.java)，`:381–394`：close先将closed置true，再同步调用handler.release，最终关闭通道；该入口自身不先领取socket处理锁。
- [AbstractProtocol.ConnectionHandler](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/coyote/AbstractProtocol.java)，`:757–774`、`:971–974`、`:1025–1055`：process临时取走关联的processor，返回前才存回；release若取到null便不调用processor.recycle。
- [SocketProcessorBase](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/tomcat/util/net/SocketProcessorBase.java)，`:35–51`：处理受同一socket锁保护，已closed时直接退出。

本项目`SSE.writeSlot:275–278`在真正callback/process尚未返回时就记录ready-false；测试`:685/690–695`观察该phase后，使用新平台线程裸调用socket.close。因此存在明确的源级交错：

1. Tomcat处理线程暂时持有processor，wrapper关联引用为空；回调已经发布ready-false，但仍未返回处理范围。
2. fixture外部closer未先取得socket锁，调用close把wrapper设为closed；handler.release这时可取到null，跳过本项目WrappedProcessor.recycle及其hook。
3. 原处理范围退出后又把processor存回wrapper；后续SocketProcessor看见closed可直接退出。失效/回收hook因而可能始终未执行。

这条交错能解释现有phase缺口，并证明“看见ready-false等于processor已重新关联、可以立即强制回收”这个前置推断不足。**当前失败没有记录当时的currentProcessor、closer栈或锁所有者，尚不能声称已实测锁定唯一原因。** 也不能据此确认SSE产品回收缺陷或给予模块豁免。

## 调度与锁范围

- fixture给Tomcat固定64最大线程、2个最小备用线程；本场景两连接，closer另起平台线程。裸close的release是调用线程同步路径，不能仅据两条callback占用推断执行器已满或排队造成此次失败。
- 本项目`WrappedProcessor.recycle:608–620`先取得旧socket锁，然后读`slot.state`，只有retiring非null才发recycle-invalidated。hook缺失也可能发生在进入该方法之前，不能直接等同于retiring为空。
- 本项目生产`RequestSlot.abort:405–439`先取真实socket锁，重新核验原token/processor/socket，再执行CLOSE_NOW；它与fixture裸close的同步条件不同。本报告未发现该生产路径有同一裸close调用。

## 最小独立诊断/修复白名单建议

原能力归属为PLAT-18的公开SSE入口/生命周期。本观察可交EX-27测试归因承接，但须由root把准确类/反例补入白名单；不把它继续塞进EX-35两文件产品修复。

第一阶段建议仅允许`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java`及独立证据：在ready-false尚处处理范围时用有界同步点固定窗口，记录wrapper的currentProcessor是否为空、实际socket锁所有者、closer进入/返回与hook先后，保存原裸close的确定性负例。无需新依赖、拉长5秒等待或取消原断言。

若证实仅是该回收正例前置不足，正例应在真实socket.getLock排除处理中间窗口后执行close；由该原锁决定真正回收入口，不用sleep猜测“callback已返回”。仍须保留原hook、等待processor回收及资源Owner断言，并跑完整37方法和网关模块，原失败继续归档。

若上述独立诊断反而证明合法回收路径卡住/资源不释放，先另立产品卡，白名单再限定`PortalRunEventsSse.java`、该对应测试及新卡证据；不得沿用EX-27纯测试授权修改SSE产品，也不得改动PublishedRunProxy来掩盖此观察。
