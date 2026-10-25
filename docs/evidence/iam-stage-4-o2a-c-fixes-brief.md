# O2a C：两项独立评审 Important 修复

唯一 workdir G:/207/项目/算法重组工具/代码/agent-platform，每个 shell 显式设置。独立新工程，不访问相邻旧代码/配置/数据/进程/依赖；不改 v1.2/四契约/排期，不 commit，不部署，不改 runner/POM/迁移/catalog/全局安全。Java21/Boot3.5.0与原固定依赖不变。应用 TDD、receiving-code-review、systematic-debugging、verification、ponytail，关键不变量写明注释。用户已授权持续实现和隔离测试端点，无外部真实IAM/K8s。

## 唯一目标及要求

先完整读原正式 docs/evidence/iam-stage-4-o2a-c-events-brief.md、补充 docs/evidence/iam-stage-4-o2a-c-admission-order-decision.md，再读独立报告 docs/evidence/review-iam-stage-4-o2a-c-events.md（12610B/SHA eee680644e924ffe7a40f5ed3c2ff7bc2a34ff831521d24dd56223b2b9958ff1，95内容行+LF），严格处理其中 I1/I2 全文，不扩大为一般重构。

- I1：实际已取得 C WindowEmission 后，consume 入口 guard 或 callback 后最终 guard 失败时，当前实现先在 EventEmission.finish 归还 B lease，再由 C finally 归还 frame，最后才 complete。必须给 C 窗口提供覆盖入口guard、callback、出口guard的真实退出接缝：若进入终止收尾，可能阻塞的 complete 必须在当时实际持有 B/frame 的 finally 释放之前返回/抛出；timeout/onComplete/close/shutdown只能stop，不能提前回收。健康superseded且本轮仍允许重读时，旧consume实际退出/close/归还后才完整重读；耗尽第3次或失效必须安全终止。保持原B readNext/EventEmission既定borrow/copy/re入/close语义，不给所有普通借用强加Servlet职责，不加一般ignoreGuard开关。避免在长write/complete持有holder全局锁。
- I2：fresh RUNNING第一次读取容量失败时，必须按真实 output.isCommitted 分流；尚未提交走固定通用HTTP错误映射（413及既有 code/message，不泄漏内部细节），保持当前权限检查、异步owner与complete-before-release。只有已经提交且holder仍live才写有界EVENT_LIMIT/BUFFER_LIMIT reset，失效只关闭。不得因为opening预检曾成功就提交200；预检不能代替实际fresh窗口。

两项都应先有确定性真实失败测试：①readWindow成功后/consume入口前失效，阻塞complete时资源不可复用；②callback返回后/最终guard失效同样保持额度到complete真实退出；③opening预检成功、RUNNING首次读取前真实writer append超限event，未提交响应应是HTTP413；原已提交reset用例仍有效。测试应核原B两permit、Cslot及frame的真实占用/逐退出恢复，不能以fixture清空owner/Future取消/Servlet通知掩盖泄漏。重试成功分支不能被修成提前complete。使用真实签名client/实际PG/受控Servlet或真实HTTP既有fixture；不伪造公开proof/Actor/Retained正例。不得为逼出边界修改生产schema/history/state或共享旧fixture。

## 唯一源码范围

生产仅：
1. engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java
2. engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java

测试允许修改既有 engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java、PortalRunEventsCapacityTest.java、PortalRunEventsHttpTest.java。按必要最少选择，不要求三份都改；不新建helper/新类。若确有具体编译或真实fixture阻碍需其它文件，先向root报告，不自行扩权。无common/ChunkBus/Controller/MaterialScopeStore修改权。唯一source/target writer由root分派后授予，交权前root/reviewer不读半成品。

## 有限验证与证据

先记录真实RED，再实现，再运行同一覆盖修复的focused方法。最终源码epoch固定后只运行一次适用精确集合：受影响的上述三个类（实际未改也可作为service调用回归）及必要具名reader覆盖方法。先窄查已有方法决定必要回归，不跑旧B整类/65/原29全套/全后端。原29保持历史，不宣称修复后的29已全部重跑。使用未改 .tools/run-iam-module-tests.mjs、独立现有C库 agent_platform_iam_o2a_c_events_v12，仅fixture自己新run，禁止碰A/B/Initial库。每次命令前立即保存source epoch；每次实际命令退出后立即复制raw、实际Running对应XML/txt、command/cwd/exit、source epoch，区分compile/setup/功能RED/GREEN。成功不重复；失败定位先查证据。

新local .local/iam-o2a-c-fixes/；新evidence docs/evidence/iam-stage-4-o2a-c-fixes/；helper仅 .tools/iam-o2a-c-fixes-*。新报告 docs/evidence/iam-stage-4-o2a-c-fixes-report.md，不改原C报告/证据/inventory/根审计或review。M1工具噪声与M2早期五阶段缺XML/epoch是历史事实，不补造、不重跑；新阶段不得重复该归档缺失。

当前修复前源码600 .local/iam-o2a-c-events/source-after.json 114375B/SHA 4bf838da425c655524614fa60fbe9eeba9d3cb892d1dd7347899d9cb1ac8cd3e；原C作者完成但未Accepted。目标逻辑2096 .local/iam-o2a-c-events/target-after.json 435939B/SHA e738c822ad54067e44ce505c53c8810f84f445a554c9607914bf2c3287a7e198；有限物理overlay498见同目录target-overlay.json。原C root物理审计 .local/iam-o2a-c-root-check.json 4381B/SHA 00e95d0b5192b9ba3179106ab5e529a75273ddf8e35589a4843763756a08191a：5phase/2373 distinct PASS，仅证据、不替代语义。原C private-replay已完整600物理验证，可作本次私有baseline逐具名复制；不全树重审原source/target。

仅更新批准delta的source manifest、保存pre/post；当前改变文件最终fresh hash须区分源码epoch，不能复用更早同path GREEN cache。target仅实际createdFiles/inputFiles/resources/common test-fixture JAR/actual Running XML+txt，新的dumpstream单归档；不扫描旧大target，不把历史逻辑清单当干净classpath。新私有raw patch首次真实check/apply，600项名称/bytes/SHA同最终source，无apply后copy/no commit。成功物理阶段journal/hash缓存，metadata失败只恢复未完成项，不重跑成功测试/PG/replay。封存新report/handoff/own-inventory，仅inventory自身排除，包含私有Git和所有自有helper。完成后归还全部写权，返回短status/覆盖tests/raw/XML/finalepoch/source/target/report/handoff/inventory identities；随后回原reviewer复审并做root有限新审计。八索引由root在Approved后一次更新。
