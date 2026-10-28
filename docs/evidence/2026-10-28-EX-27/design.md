# 原第一阶段设计（历史记录）

以下保留实施前设计语境。其后447实际诊断红已归档，root与独立技术门批准第二阶段仅测试候选（FD34未运行，静态修正后为E175）；当前状态见README、diagnostic-result及safety-candidate。下方原阶段的未运行及尚未授权前提修正属于历史状态；第二阶段仍未编译/运行。

# EX-27 前置子步：网关回收夹具有界诊断设计

状态：设计已形成第一阶段测试专用候选，尚未编译或运行。HEAD=`0cc967278007343bd5ab09faf9953d86cd573bf8`。执行者`/root/ex35_lock_boundary`转做本子步，不使用superpower。仅允许修改`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java`及本证据目录；SSE/HTTP/PublishedRunProxy/Tomcat依赖只读。

源码/测试窗口分开：起初EX-01后续gateway完整test及四服务package会读取本测试，因此未获源码窗时只写设计。之后EX-01明确gateway模块已结束并归档，package均使用`-Dmaven.test.skip=true`，已交还唯一测试源码写窗，才写第一阶段诊断。Maven/Java/本地HTTP/PG窗口仍由EX-01独占，root另行协调，不能据源码窗口推断可运行测试。

## 保留的输入事实

EX-35最终模块`frozen-module-20261028-205724-595`已保存新增SSE方法FAIL；该方法在EX-00和此前两个模块中PASS。唯一独立准确方法复验`lifecycle-repeat-1-20261028-211206-488`为PASS，仅额外观测，不覆盖模块失败。EX-35 gate=false、待验收。

失败为`:695`第一个`entered.await(5s)`实际false，第二个recycles断言未执行；外层child退出码expected0/actual1不是recycles值。失败两trace均已ready-false各2次，但均无recycle-invalidated/worker-body-exit/completion-entry；成功复验各新增recycle-invalidated→worker-body-exit。本次未记录当时currentProcessor值或closer栈，尚未捕获唯一原因。

原始/安全证据继续引用[EX-35只读分析](../2026-10-28-EX-35/sse-new-observation-analysis.md)、[phase事实](../2026-10-28-EX-35/runs/frozen-module-20261028-205724-595/sse-forced-release-phase-facts.json)、[最终模块gate](../2026-10-28-EX-35/runs/frozen-module-20261028-205724-595/gate.json)。不复制或改写原XML/日志。

## 真实上游调用契约：不能先把裸close判成非法

固定Tomcat10.1.41的本仓NioEndpoint归档身份已核对原manifest。Poller.events的`:686/701/711/714`、processKey的`:805–854`在停止、无效key或派发失败等分支直接调用socketWrapper.close；外围没有取得该socket处理锁。因此裸close确实存在于真实内部容器关闭路径，不能仅凭close方法自身不锁就说该场景不合法，也不能先用加锁close替换掉原恶劣关闭观察。

上游具名机制与来源：

- [SocketWrapperBase](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/tomcat/util/net/SocketWrapperBase.java)`:381–394`先置closed，再同步handler.release，最后doClose。
- [ConnectionHandler](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/coyote/AbstractProtocol.java)处理时暂取processor，返回前才重新关联；release取到null时不调用processor.recycle（`:757–774/971–974/1025–1055`）。
- [SocketProcessorBase](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/tomcat/util/net/SocketProcessorBase.java)`:35–51`持socket锁运行处理；closed时退出。
- [NioEndpoint](https://raw.githubusercontent.com/apache/tomcat/10.1.41/java/org/apache/tomcat/util/net/NioEndpoint.java)上述Poller分支为真实关闭入口。本轮沿本仓固定归档窄读，不新增依赖或把web视图宣称为字节相同的源归档。

本项目ready-false在SSE.writeSlot内`:278`发布，callback/process此时可以尚未退出；WrappedProcessor.recycle的hook又必须实际进入该方法并取得socket锁、且retiring非null才发。故“已经ready-false”不足以证明processor已经重新关联或回收入口已经到达。source一致的交错仍需新诊断实际固定，不能先写成原失败已被解释或夹具已经修复。

## 第一阶段同步点与安全事实

保留现有37个JUnit方法身份，原方法、所有原断言、原5秒等待、原裸socket.close调用语义不变。建议新增显式测试专用诊断flag，选择原forced-release的一种子JVMmode；默认不带flag仍运行原场景。诊断开关不是生产配置，不改业务预算，不用于跳过断言。

1. **P1，两连接仍在处理范围。** 利用现有probe的首次ready-false，在每条原socket上只暂停一次；记录currentProcessor是否为空、实际socket身份、callback线程、该线程是否持真实ReentrantLock。双连接同步点确认到达后才继续。只用公共getter和锁状态读取，不调用takeCurrentProcessor、不反射修改上游状态。
2. **P2，保持原外部裸close。** 启动原来的平台closer线程，不在close外新增锁。只在调用前后记录相同socket的closed/currentProcessor/锁状态和事件序号，记录实际返回或异常类型；不得把观察代码的锁扩展到close或等待。
3. **P2b，先解除人工暂停再观察原5秒。** 裸close的实际入口/返回事实取得后先释放ready-false暂停点，不能故意占住socket处理锁贯穿原5秒来制造平凡失败。额外记录处理范围退出的见证：例如close已经发生后，以有界真实socket锁排除确认先前处理已退出，并记录closed/currentProcessor；锁只用于调用后的诊断，不包围或重试close，立刻释放。若实际回收hook已先进入并有意持该锁，则记录hook先后，不把采样拿不到锁误报为处理卡死。无法区分处理仍在运行和真正丢失回收时，要报告证据不足。
4. **P3，原回收断言。** 原`entered.await(5s)`及后续全部Owner、token、waiting processor、completion断言原样保留。人工callback暂停已解除后仍没有hook，才可能构成待解释的确定性红；不转为期望失败/成功，也不补造recycle计数。
5. **P4，有界退出与清理。** 新的callback暂停点在finally无条件释放；原release latch及closer有限join保留，父JUnit既有95秒子JVM上界和强制回收保留。清理前/后事实分段，不能把清理产生的hook冒充原5秒窗口内的hook。

记录有界少量安全事件：阶段、顺序号、相对nano时间、socket身份、线程名、processor是否关联、closed与锁状态、hook/worker/completion计数。仅打印`EX27_`前缀的安全JSON；不输出请求、票据、头、完整对象toString或原异常消息。原日志/XML继续放忽略目录，另RunId归档，不覆盖EX-35原件。

如果P1没有实际观测到“当前callback持socket锁且关联processor为空”，必须报告诊断前置未命中，不把其他失败当该交错证据。若命中后仍有hook/close差异，按实际顺序报告，不能只按预测解读。

## 决策门与后续范围

第一阶段只保存有界原反例/事实，不修改正例同步条件。取得事实后由root与独立审阅者核对：目标到底是processor确已进入回收后的Owner屏障，还是要求真实容器允许的处理中裸close也必须失效原token/释放相关工作。

- 如确证仅是正例前提不充分，必要的最小前置修正必须保留原off-lock观察，不能以加锁正例缩掉原关闭目标；先技术复核再改。
- 如证明真实恶劣关闭属于必须支持且产品失效，另立准确产品卡，不沿EX-27修改src/main。
- 批准的修正之后才依次跑准确方法、完整37方法、gateway全部27类；可与EX-01/EX-35在同一冻结范围共享一次明确条件的模块回归。新增非通过/缺方法/跳过必须为0，独立review及本子步提交齐全后，也只关闭这个子步，不关闭整张EX-27/Y-4。


