# O2b F2b1 HTTP 复审修正报告

状态 **DONE_WITH_CONCERNS**。I1/I2/M1 的四文件修改与有限验证已完成，交 root 独立复审；这里不作 Accepted。HTTP 最终全类实际 **14 tests / 0 failures / 0 errors / 0 skipped**。新增确定性行为 RED 实际 1F/0E，保留原记录。封装时第二个私有 replay Git 索引初始化与普通 worktree apply 发生交叠，其 before-index 断言失败；主 baseline621、首次普通 check/apply、最终完整 after621 仍分别有身份，不能把失败的辅助 index 称作 before PASS。详情见下文。

## 范围、冻结与 source 身份

所有 shell cwd 均为 `G:/207/项目/算法重组工具/代码/agent-platform`，依本目录 AGENTS、implementation-brief、contracts/README、fixes brief、正式 b1 brief、具名实际 API/log/budget 补充及复审报告实施。未访问或关联相邻旧工程。未提交、暂存主仓库、重置、部署、改 pom/common/TLS/F1/assembly/controller/main/前端/设计/契约/八执行索引。原 b1 report/handoff/inventory17113/13 stages/18 green/27 peers/patch/private Git/target 清单未写或重跑。root 原 b1 全量身份审计的 PASS 不在本报告中改称原复审通过。

只改四个既有 source，总数621、新增0、其他617逐 bytes/SHA 相同。完整名字集合另经 `rg --files --hidden` 限定七模块及 web（排除 target/node_modules/dist/.git）与 pom.xml 比对，恰621，零新增/缺失，见 `.local/iam-o2b-f2b1-fixes/source-live-name-closure.json`。四路径与前后身份见 `source-delta.json`，完整 source-before/after/snapshot 与复制物保留。

- before：原 `.local/iam-o2b-f2b1-http/source-after.json`，621 / 118929B / SHA256 `73d316639100f8be3aa99924fd5cb4cd6ca860a729b1bc3df7d5a753ede185f0`；本轮 before 逐文件核对后复制。
- after、focused2 与 final 的完整621 manifest：118930B / SHA256 `7ce27abedc333786bff2c7c886ab6092814b52118bf1fd3fd87f6a52eacf81fa`。三个 after-stage 的 source 字节相同；selector 拒绝阶段未启动 Maven。
- metadata 两文件的全部 Java token（包括完整注释、字面量）不变，分别3771与2080 token；原 STATUS 前6168B完全相同。证明见 `metadata-token-and-status-prefix.json`、`source-closure.json`。不因纯格式再次运行历史 metadata7。

## I1：唯一清理执行者

`PortalRunEventsHttp.java:322` 的 `finishTransport` 在同一个 Session 状态锁下选举 `cleanupActive`，同时拍取 handle/request/alarm/timer/client/manager 六个引用。别的 caller 以自己的剩余期限 timedWait，不能进入同一 transport 的物理清理。物理 handle.awaitClosed、timer.awaitTermination、client/manager.close 均在 Session/guards 锁外，abort 和 deadline worker 可取得状态锁。

失败/超时/打断不清 transport 引用或退款；清理的 finally 必定交还执行权并 notifyAll，允许后续接续。InterruptedException 保留中断状态，运行时清理异常返回 false。确认真实 handle 退出、timer termination 与库 close 后才一次发布 transportClean、清引用。lookup 的 finally 另用嵌套 finally 清 active/operationThread/通知等待者，即使 finish 异常也不会遗留活动 operation。最终 awaitClosed 先等 lookup active 退出，才同步一次关闭 Opening/facts/fee/Owner；已完成后的重复 close 保持幂等。

测试 `concurrentClose`（HTTP test:655）先以现有同包 socket-close gate 卡真实 acquire 的 handle，持两 Owner/6422784B、短等待 false、第三 Session 被拒；释放 socket 后确认真实 handle.awaitClosed，再卡真实 timer worker 的终止。第一 close 正在 cleanup 时，第二短 caller 按40ms预算返回 false且未重复调用 timer awaitTermination（调用计数1）。第三个长 await caller 与第一 close 释放后正常返回，确认 timer 实际 terminated、只退一个 Owner/3211392B；重复 close 不再退账，另一个 Session 关闭后归零。

`TerminationGate`（HTTP test:635）只延迟一个真实 ScheduledThreadPoolExecutor worker 的退出；其 awaitTermination 仍委托标准实现，不制造 termination。测试使用真实 common Fixture/Opening/Q，不制造有效票、TLS已验状态或 facts。socket gate 在真实 acquire 的 handle 上安装机械未连接 socket，只测试物理 close 时点，不把它称 TLS 正例。生产没有新增 gate/API。附加 `cleanupRetry`（:726）分别打断清理线程、在第一次 termination wait 抛机械 RuntimeException，确认 cleanupActive 已交还、原 timer 引用仍在、费用仍持有；释放真实 worker 后新 caller 正常完成。现有真实 lookup/cancel/blocked-close 出口在最终全类重验。

本轮行为 RED 于旧生产实现上先执行，HTTP test 已先把 child 输出迁到本轮 ownedRoot；实际 timer await 被两个 caller 进入，断言 expected1/actual2。这是可观察并发行为失败，不是 API 缺失/NPE 已复现的宣称。RED 的测试 epoch 随后增加真实 socket 阶段与 retry/body 用例；原 RED source与class/报告保留，没有回退旧实现或重跑旧 RED。历史 b1 的 API 缺失/环境故障 RED 仍不等同业务行为 RED，本轮不补写历史。

## I2：真实 TLS body 上限

`bodyBounds`（HTTP test:776）通过受信真实 TLS peer、实际 Session/begin/Opening/原 Q/Apache读取/严格 parser 路径运行三个向量；合法七字段 envelope 后补 JSON 空白，UTF-8精确8192B。

| 向量 | 实际 wire 策略与结果 |
|---|---|
| CL8192 | Content-Length:8192，完整8192字节成功；逐项断言 runId/owner/root/appVersionId/appVersionNo/acceptedSnapshotSha256/initialCeilingSha256，原Q引用不变 |
| chunked8192 | 无CL，chunk `2000`，完整8192字节与合法终止chunk成功，同样逐项断言七字段 |
| chunked8193 | 无CL，chunk `2001`，完整8193字节后再发未知后续chunk头 `1000`，永不提供其payload/结束chunk；503且facts=null、stopped=true，退出少于1500ms（早于2秒hop deadline） |

三者均通过既有 beforeOutput mechanical观察接缝只读取已安装的真实 handle/timer。lookup 后实际 handle.awaitClosed(Duration.ZERO)=true、timer.isTerminated=true；peer在测试侧close之前读到EOF/reset，确认由客户端关闭，才返回测试。peer未供尾部是测试策略，peer实际观察close与子进程有限返回是观测事实；未声称测量了每一次TLS内部read或所有潜在drain。源码 handler finally 仍在Apache entity/response close前stopTransport；无CL的8193加未供尾部及提前返回共同覆盖bounded read超限，不能混称CL8193预拒或parser-only8192。

每向量断言同一个原Q只发一次Authorization、一个connect、grantCount不增、第二lookup被拒无第二exchange；Session close前HTTP Owner仍3211392B，显式close后Owner/费用归零。结果记录在两份 body-bounds child summary。strict schema/请求响应头/deadline不放宽。compact data保守2080、4096合法样本不可达与4096 hard sink的原结论保持。

## M1 与费用边界

四文件仅本轮可写部分按普通缩进拆开状态、异常和资源释放步骤；metadata STATUS prefix不动，metadata非空白token（含注释/字面量）不动；HTTP两文件增加上述行为/测试并局部格式。`format-transformation.json`与metadata token证明保留。不增加生产抽象、依赖、调度worker或容器。生产仅增加一个布尔状态与六个栈局部引用，不复制payload/Opening/TLS图；按原8B reference模型，额外固定状态/局部引用保守远小于1KiB，可由原 G131072与未分配655360 margin覆盖。仍是3MiB有限HTTP组件工程收费模型，账本不能代表RSS/全JVM堆。

## 实际命令、测试与编译

各测试均通过既有 `.tools/run-iam-module-tests.mjs`，子进程 cwd固定新工程、自有m2/JDK21、短 Unix-domain tmpdir `V:/.local/tmp`；HttpTest child仍固定 handshake32768、chain4、禁AIA/session tickets/status extension，不绕过JDK classpath检查。完整argv/cwd/exit/stdout/stderr在每stage `command.json`与 wrapper raw，Running/XML/txt与sourceEpoch在 `stage-catalog.json`。

| stage 后缀（完整前缀 iam-stage-o2b-f2b1-fixes-） | selector | 实际结果 |
|---|---|---|
| red-concurrent | PortalRunEventsHttpTest#concurrentClosersHaveOneCleanupExecutorAndRespectWaitBudgets | Running HTTP，1 test / 1F / 0E / 0S；新行为RED |
| focused | 同类三个方法以 `+` 连接 | wrapper grammar拒绝，exit1、无Maven/Running/XML/child；原 stderr保留 |
| focused2 | 三个 `PortalRunEventsHttpTest#method` 用逗号连接：concurrentClosersHaveOneCleanupExecutorAndRespectWaitBudgets、cleanupInterruptionAndExceptionRetainReferencesForRetry、realHttpsBody8192AndChunked8193RespectReadAndCloseBounds | Running HTTP，3/0F/0E/0S，exit0 |
| final | PortalRunEventsHttpTest | Running HTTP，14/0F/0E/0S，exit0 |

执行形式：`node .tools/iam-o2b-f2b1-fixes-evidence.mjs stage <stage> <selector>`，其内部 `node .tools/run-iam-module-tests.mjs <stage> platform-gateway <selector>`；完整Maven `-o -B -ntp -pl platform-gateway -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dtest=<selector> -Dsurefire.failIfNoSpecifiedTests=false`。

red实际仅gateway testCompile17，main up-to-date；focused2在完整最终621 epoch实际main20/test17重新编译；final同一621字节四处up-to-date，实际跑HTTP14。final不是新编译宣称，focused3与final14是17次绿色测试执行、14个最终独立HTTP用例；未再次跑成功focused、旧final18或metadata7。原STATUS兼容由纯token等价继承，不新增运行声明。原始compiler input/created lists与sourceEpoch绑定见 `compilation-proof.json`、`final-epoch-compilation-binding.json`。

本轮4stage、实际18 child：RED1 child exit1，focused2的3与final14均exit0；所有18个(run,PID)具名、请求cwd、实际waitFor exit、alive=false、collector join/alive=false完整记录于 `peer-exits.json`。selector拒绝没有child，不能计作运行失败测试。

继承before target logical2223（464896B/SHA db710f41df507b401ce2230fd43c95c4015959b5b3ca3df109e48d5c974d72cc）。本轮实际compiler created/input lists、实际资源复制、固定common test-fixture JAR与实际Running报告形成overlay143、logical after2226；源class真实创建与最新compiler epoch的对应见 `target-overlay-basis.json`。每stage保留的list-derived有限capture包含继承common列表快照，不能据它宣称本轮重编common或整个classpath；正式overlay只取实际编译/复制/Running路径。新增3份344B Windows classpath dumpstream与既有deprecation独立保存，不计入logical target，不称日志pristine。未复制整个target或扫描依赖缓存。

## raw binary patch 与辅助index顾虑

两私有目录均源于本轮verified source-before621；add之前设置 `.git/info/attributes` 为 `* -text -diff`，隔离system/global/template配置。主baseline的完整621 index blob字节与before完全一致，先于patch形成；`baseline-index-blobs.json`及journal `2026-10-21T11:51:39.483Z baseline-index621-byte-verified`保留。新patch67158B/SHA256 `c3a1cdb45089b0c00364ca3f680f8b3175b4d13e12241725eb429f3f87b63eb0`，仅四既有source、四个GIT binary patch。

首次 `git apply --check <patch>`（11:51:47.912Z）与首次 `git apply <patch>`（11:51:47.963Z）均exit0，**没有--index或--cached**；它们只作用于worktree。11:51:48.718Z已核完整621 after字节与名字；apply后没有复制源码补齐。`replay-after.json`与after621同SHA，名字闭合单列。

编排错误：第二个replay私有Git的add仍运行时误启动了普通worktree replay。逐621 copy循环在replay-init（11:51:40.045Z）之前完成，源为已核本轮source-before，但apply前未另存replay工作树完整bytes/name manifest，**不能声称有该预先独立PASS**。replay-add直到11:52:14.876Z完成；其后write-tree与cat-file索引核对读到四个after images，辅助baseline高层进程exit1。因此无 `replay-index-blobs.json` before-PASS，完整实际index对象见 `replay-index-observed-after-overlap.json`。没有重跑或改写check/apply，没有重建第二次成功历史。

独立闭合：`patch-worktree-proof.json`在内存解码既有raw patch正向与反向块（含一个reverse delta），四个preimage逐bytes/SHA与before相同，forward image与after相同，old/new Git blob SHA1与patch header相同；另外617来自固定复制谱系且最终逐字节不变，完整after621名字/bytes已核。此只读解码没有执行新的git check/apply或文件replay。root可据实际worktree证据判断，辅助index失败仍留顾虑，不以其替代主baseline证明。

另保留两类metadata诊断：第一次target身份组装按stage字母序，把live最新class身份误附较早RED标签；仅纠正provenance，原basis-v1与143个overlay身份、logical2226不变。首次只读patch inspection regex提前截取base85行导致Z_BUF_ERROR，修正绝对EOF断言后续接完成，未重跑测试/编译/Git replay。详情 `.local/iam-o2b-f2b1-fixes/metadata-corrections.json`与`control-diagnostics.json`；后续controls-resume有精确stdout/stderr。两个初始高层辅助node进程的stderr未重定向到owned raw，仅保留工具transcript中的原输出与诊断摘要；Git每子命令JSON与完整观察身份均保留，不能把摘要冒称原raw全集。

## 交还

最终control为本报告、`docs/evidence/iam-stage-4-o2b-f2b1-fixes/handoff.json`、同目录 `own-inventory.json`；inventory只排自身，包含其他全部owned叶子。source-before/after、stage完整621 manifest/四文件快照与继承617、有限target、所有新child、privateGit、patch、control/helpers/报告与实际外部wrapper log分别列明。live source与有限target另按after/overlay核对，不隐式纳入owned。

交付后 frozen=true，四文件source/target/owned写权全部交回root。原报告/冻结证据与八索引不改，等待fresh独立复审及有限产物审计。仍未开放EVENTS、装配生产main、实现G/E/材料/SSE，未验真实engine/PG全链、生产启动、完整TLS/JVM堆/容量；3MiB不是全进程上界。
