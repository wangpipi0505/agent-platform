# 第一阶段诊断静态复核

独立审阅者：`/root/ex35_independent_review`。首轮通过团队消息给出只读结论，所审SSE测试SHA为`ED5E597582ABDDBC4453590A5239B4FCA45F8E6543E2A163CCE497F96D67497A`；首轮未运行Java/Maven/本地HTTP/PG，也没有修改文件。下列追加记录为本次计数delta的独立复核。

2026-10-28追加独立复核：实际读取当前测试文件并核对SHA为`4470434C459F900B9CC85AD96D4F7D8953A17D7BCC7B37C3EAE64FBBD9D5C53D`；本轮对新增计数delta单独确认，没有将ED5审阅直接外推。相对已审ED5，增量仅为`waitEnded`及Tomcat.stop前的`write`接收既有protocol，并读取`recyclesForTest()`/`getWaitingProcessorCount()`到`processorCounts`；新增两个计数字段和参数传递，没有新增断言、暂停、重试或显式锁范围。`recyclesForTest()`原有短同步getter只读取计数，不调用Servlet或回收逻辑；这些读取发生于原5秒等待结束后及stop前，不能制造该等待期间的缺hook。

该SHA的静态准入结论：**可进入root授予运行窗口后的实际诊断编译/运行**。独立枚举仍为37个原方法，无新增、删除或重复；默认forced-release、原裸close、原5秒等待及后续全部断言保持，`git diff --check`无错误（仅既存CRLF转LF提示）。本轮仅更新此评审记录，未运行Java/Maven/HTTP/PG/浏览器，未修改测试或产品。没有作编译通过、确定性红、责任归因或EX-27/EX-35验收结论。

计数解释限制：hook、recycled、waiting是依次取得的观察值，不是跨字段原子快照；wait-end与stop前两组必须分别保留。计数可辅助区分无hook与无recycle，不能单凭其数值确认唯一原因或用清理后的变化覆盖原窗口事实。

静态结论：可进入root发运行窗口后的诊断编译/运行，不代表已经编译、测试通过、夹具已修或归因成立。37方法身份、default forced-release、原裸close、原全部断言和5秒await保持。P1/P2能固定callback持真实锁/processor暂空并令off-lock close在resume前实际返回的目标窗口；P2b先resume再采样，不包围close、不跨原5秒持锁，没有显见人为占锁制造平凡红。两个gate的finally释放及原95秒父进程上界保留。

结果解释约束：

- 若记录processing-exit-not-observed且没有已进入的recycle-hook，只能说明处理退出尚未证实，不能把0hook直接当丢回收。
- 两个最长1秒的调用后fence会使实际close到wait-end可能大于5秒；本模式只作诊断，绿不能替代原默认方法。
- 必须从实际EX27 JSON和child mode确认诊断真正命中；未命中同步点、编译失败或缺少facts不能计作确定性原反例。
- 真实Poller也存在off-lock close。任何修正正例前置的建议仍须在实际诊断后另作需求/技术复核，保留原恶劣关闭观察；本静态结论没有批准加锁close正例。
