# O2a C events independent task review

## Spec Compliance

**Issues found — Needs fixes。Critical 0 / Important 2 / Minor 2。**

唯一评审对象为正式 O2a C；依据 `docs/evidence/iam-stage-4-o2a-c-events-brief.md`、`docs/evidence/iam-stage-4-o2a-c-admission-order-decision.md`，并读作者报告及具名 retention 裁定。采用 code-review 技能的正确性/安全性检查框架，任务限定优先。未运行测试、PG、replay 或 git；未更改 source、target、index、其他 evidence；唯一写入为本报告。

基线 Accepted Initial source593，head `.local/iam-o2a-c-events/source-after.json`（600 项，声明 SHA256 `4bf838da425c655524614fa60fbe9eeba9d3cb892d1dd7347899d9cb1ac8cd3e`）；无 HEAD/commit。完整上下文包 `docs/evidence/review-iam-stage-4-o2a-c-events-package.diff` 实测 226482B / SHA256 `d487b624153d2ebee372b325636f52624493705032f00fa69b429e9524ef86c9` / 3218 内容行。按互不重叠区间 1–400、401–780、781–1170、1171–1510、1511–1870、1871–2220、2221–2470、2471–2700、2701–2880、2881–3060、3061–3218 完整审阅至 EOF。第一次工具输出截去了 17–51，随后只补读该遗漏区间。未另读任何 changed 源文件，也未读取 unchanged source；以下源码行号按 full-context diff 的新侧计数。

规格符合的主要部分：

- `RunObservationSelectors.java:42` 附近实现 run-snapshot 与 STATUS/EVENTS 双向 purpose；`RunController.java:205` 的 IAM 分流早于 Actor/store.run，四参构造与三参 events 签名保留，成功接管返回 null。`PortalRunEventsService.java:67` 严格验证实际 handler/REQUEST/GET/path/body/async-supported，并按选中来源校验游标。assembly 无 stereotype；实际 receiver 将相同 HttpCurrentAuthorityClient 同时注入 ServiceAuthorization 和新服务。
- `RunEventReader.java:74` 的 readWindow 使用原两 permit、原 ledger、独立只读 RR；完整 root/state/event scope 和直接父并集共用预算；state 加入原 root aggregate。state 11 字段投影/UTC 微秒与同包原 writer projection 相符。min(seq) 在完整 state 摘要之后，同 RR 取值；B readNext 不得到 terminal/window。
- 历史 event 缺物理行只在新窗口、非选中 event、真实 child 直接父角色可成为候选；完整 state digest 证明 terminal 后才接受。scope/current/owner/initial/locator 与消费 event 全行校验没有一并放宽，原 B 严格分支保持。
- `PortalRunEventsService.java:45` 完成同步保护窗口后释放 B/frame，PENDING 只留 slot/holder/cursor；RUNNING fresh capture 重新完整读取。默认 2 worker + 2 queue，原 slot 与 16MiB 池共用，单帧 backing 262245B 加 8192B scratch 先计费。B 临时 payload clone 仍由 B ledger 计费。
- getter/write/flush 的回调内失效与正常终态 complete 分支覆盖了实际返回边界；pending CAS、opening stop、断线不 cancel run 均有明确实现和实际测试。两项 Important 说明这些覆盖尚未穷尽全部异常退出和未提交错误路径，因此不能总体宣称规格完全符合。

### 冻结验证证据独立核对

从每阶段 raw 提取实际 `[INFO] Running`，只将这些类对应的冻结 XML 纳入；用 XML DOM 枚举 testcase，包含 self-closing testcase，未以源码方法顺序或编译输入冒充运行。每阶段所有记录的 XML/txt bytes/SHA 与物理文件相符，source-epoch SHA 与阶段记录相符，实际 Running 与记录相符。

| phase | 记录 exit | raw 实际 Running 类数 | XML tests / failures / errors / skipped |
| --- | ---: | ---: | --- |
| focused-window | 0 | 2 | 9 / 0 / 0 / 0 |
| focused-lifecycle | 1 | 0 | 无实际测试；compile 阶段 |
| focused-lifecycle-compilefix | 1 | 2 | 10 / 1 / 2 / 0 |
| focused-physical-fixes | 1 | 2 | 3 / 1 / 0 / 0 |
| focused-remaining | 0 | 3 | 5 / 0 / 0 / 0 |
| focused-renew-and-queue | 1 | 2 | 5 / 1 / 0 / 0 |
| focused-active-queue-diagnosis | 0 | 1 | 1 / 0 / 0 / 0 |
| final | 0 | 6 | 29 / 0 / 0 / 0 |

final 实际六类及 XML testcase 数：PortalEventsReceiverClosedTest 2、PortalRunEventsCapacityTest 4、PortalRunEventsHttpTest 6、PortalRunEventsLifecycleTest 9、PortalRunEventWindowPostgresTest 6、RunEventResponseTest 2。所有 29 个 testcase 均无 failure/error/skipped。`.local/iam-o2a-c-events/final/raw.log:45`、`:84`、`:94`、`:96`、`:1162`、`:1164` 是实际 Running；`:1178` 为 BUILD SUCCESS。最终 source epoch 实测 SHA256 `252050fd62edafca014750183e6c17b56ce97ac3fc7e71077a253fe70a69b1c5`。冻结 result.json 给出的 command/cwd/exit 与所审阶段相符。

早期五份 raw 的物理 hash 均匹配记录：red 中 HTTP 为 DataSource setup failure，PG 实际为 readWindow NoSuchMethod；http-red/http-red-route 为初始化失败；http-red-mapped 有 expected 200 / actual 403；first-green 有两个实际类的成功摘要。它们只支持这些 raw 层事实，不支持已遗失的精确 XML/完整 source epoch 断言，见 M2。

## Strengths

1. 真实受控 receiver 的安全接缝完整：签名票、trusted decoder、filter、mapped interceptor、route coverage、production controller/reader 和真实 PG 共同参与；同 client opening 没有替换成 ready boolean 或公开 proof DTO。
2. 实际 writer/reader 的状态投影、完整父数组、联合预算及 terminal-only retention 例外放在同 RR；旧 B 入口保持严格，边界清楚。
3. 排队顺序补充裁定落实到代码与真实 active/queued 测试，避免 queued 持两 B 结果导致活动轮询饥饿。真实 get/write/flush/complete 阻塞与 shutdown/timeout 测试比只验证 Future cancel 更有证明力。
4. 作者明确区分机械 long19/type64 frame 极限与实际 PG int/varchar(32) 范围，明确区分测试终态 DELETE 与生产 retention job，未以 29 green 扩大范围。

## Critical

无。

## Important

### I1 — consume 入口或回调后的 guard 失败会先归还 B/frame，再进入可能阻塞的 complete

证据：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java:210` 在入口 `live(...)` 失败时调用 `finish()` 后直接抛出；`:212` 在 action 返回后仍会执行 guard，`:213` 的 finally 总会 finish；`:216` 的 finish 直接关闭原 lease。`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:144` 的 callback 内 complete 仅在 callback 真正进入且其内部捕获异常时执行；`:159` 无条件关闭 pending/frame；`:170` 才在外层 finally complete。

触发：RUNNING 已取得 fresh WindowEmission，holder 在 readWindow 返回后、consume 入口 guard 前被 timeout/关闭/失效，或者正常非终态 callback 返回后、reader 最后 guard 前失效。入口情形 callback 根本不运行；出口情形 callback 已返回。两者均绕过 callback 内 complete。若恰为耗尽的第 3 次 supersession，终止路径同样会走到外层 complete。

后果：B permit 已由 reader finish 释放，C frame 又由内层 finally 释放，随后 AsyncContext.complete 仍可真实阻塞；另一连接可复用这些额度，而旧 owner 尚未完成实际退出。C slot 仍被持有，但无法满足正式 brief 的“持有当前结果/frame进入异常收尾时，complete 必须在这些资源实际 finally 之前返回”要求。不是数据泄露指控，而是有限资源/物理退出不变量的缺口。现有 `PortalRunEventsLifecycleTest.java:81` 的 complete 阻塞用例仅覆盖 callback 内正常 terminal 分支，没有覆盖上述两道 guard。

最小修法：为 C window 所有权提供覆盖 consume 入口、callback、最终 guard 的受保护退出接缝，使终止 complete 在原 lease 的 finish 前执行，并让 C frame 也保持到该 complete 返回/抛出。健康 superseded 且允许重读仍应在实际旧 consume 退出后归还，再完整重读；不得把所有 superseded 都变为 complete 或改旧 B 既定生命周期。增加确定性验证：分别在入口 guard 前及 callback 后失效，同时阻塞 complete；断言 B/C/frame 在调用实际退出前不可复用。

### I2 — fresh RUNNING 读取首次容量失败，在尚未提交时仍写 200 SSE reset

证据：`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:137` 捕获 read 的 CapacityFailure，除 429 外一律在 `:139` 调用 capacityReset；`:181` 的 capacityReset 没有检查 `output.isCommitted()`，也没有设置 `failure.status()`，直接取得 stream、write/flush reset。opening 只设置 content type，未设置错误状态；该路径遂以默认 200 提交。与之对应的实际测试 `PortalRunEventsCapacityTest.java:34` 已先输出小的 C_FIRST，再遇到第二条超限 event，因此只覆盖已提交 reset。

触发：opening 预检成功但尚未输出，owner 排队或等待 worker；同 run 随后由真实 writer 提交超限 event（例如原 cursor 已到小事件 watermark，新增 >262144B payload）或导致 root/state 容量越界。RUNNING 按裁定重新完整读取，此时第一次 read 即失败，响应仍未提交。

后果：客户端收到 HTTP 200 stream_reset，而非未提交容量失败的 HTTP 413 映射，违背 brief 对实际 isCommitted 的明确分界；调用方不能依赖 HTTP 错误语义，且预检成功不应替代实际输出轮的判定。

最小修法：在 capacity failure 输出前按真实 isCommitted 分流。未提交时走固定通用 HTTP 错误映射（保持 async ownership、当前权限检查及 complete-before-release），已提交且 holder live 才发送有限 BUFFER_LIMIT/EVENT_LIMIT reset。增加预检后、首次 RUNNING 读取前真实 append 超限 event 的确定性用例，同时保留已提交 reset 用例。

## Minor

### M1 — 冻结 green 带有真实工具/容器诊断噪声，应准确归属

`.local/iam-o2a-c-events/final/raw.log:73`–`:76` 为已有 B/status 编译输入的 deprecation/unchecked 和路径乱码；`:97` 起 timeout/error 后出现 ServiceAuthorization 拒绝及 `/error` redispatch 堆栈；`:1183` 起为旧 RunEventResponseTest Mockito/ByteBuddy 动态 agent/JDK 提示。它们不改变六类 29/0/0/0，但不能称为零警告、零 ERROR 日志。dumpstream 归档由 root 核 inventory，不为消除噪声改 POM 或重跑。归属为既有工具链及明确负路径，不据此增列安全路由故障。

### M2 — 早期五阶段缺 XML 与完整 source epoch，不完全满足逐阶段归档要求

`docs/evidence/iam-stage-4-o2a-c-events/test-results.json` 的 early 五项以及作者报告明确承认 red/http-red/http-red-route/http-red-mapped/first-green 未及时复制 XML/完整 source 快照。所读 raw 确实存在且 hash 匹配，后期八阶段的完整冻结证据也确实存在，故不能把这项历史可追溯性缺口判为最终 29 green 无效；但不能将 raw 摘要补充宣称成精确旧 XML/epoch 已核验。

保留原始材料和缺口登记，由 root 在验收台账作显式裁定；已发生的历史缺失不能靠重跑同名阶段或重构 XML 修补。本项是归档质量偏差，当前阻断来自 I1/I2。

## Assessment

**Issues found / Needs fixes；C=0，I=2，M=2。** 大部分指定安全/材料/容量/排队机制正确，真实最终测试证据可信；两个未覆盖路径仍违反明文规格，需要小范围修复及针对性验证后再审。所有结论来自冻结 package 与证据，不依赖作者设计动机，不声称 reviewer 已复现失败或重跑 green。

### ⚠ 仍需 root 闭环

- ⚠ 本报告不替代 root 的有限物理审计：source593→600/delta14、target overlay、patch/private replay、journal/inventory/self-exclusion 与八索引的完整身份/范围仍由 root 核对。
- ⚠ M2 早期五阶段 XML/完整 source epoch 历史缺口须在验收台账保留并裁定；禁止补造或以重跑冒充原阶段。
- ⚠ terminal event DELETE 只证明清理后 PG 观察语义，不证明生产 retention job、active-run 清理可继续、writer/replay/去重全平台兼容。这是正式边界，跨任务由 root 留账。
- ⚠ 本切片为 engine 一跳 PORTAL/pure FLOW；Gateway/O2b、CHAT/chunk/skill/model、生产全 IAM 与 socket 物理强断/撤权 SLA 不在本次完成结论内。
- ⚠ 29 个实际方法不等于所有精确输入/旧 B 全类/全后端已回归；尤其 I1 的 consume 两端失效与 I2 的首次未提交容量分支尚无所需证明。

报告写入后冻结；没有 source/target 写权。

---

# O2a C I1/I2 修复复审（原 reviewer 追加）

## Spec Compliance

**Spec compliant / Approved（本次有限修复与原 C 评审问题闭环）；未关闭 Critical 0 / Important 0 / Minor 2。I1、I2 均关闭；M1、M2 维持原非阻断事实。** 本段替代原报告的待修复结论，不改写原审计历史，也不宣称原早期归档偏差已被补齐。

输入为正式 `docs/evidence/iam-stage-4-o2a-c-fixes-brief.md` 与新作者报告；作者报告实测 SHA256 `8acfa373aa994a2430913f0ca497da9c07589e766582018f4520ef3d2f3d1625`。原 C 要求和补充裁定承接上轮已读内容。本次只完整读新 package 一次，区间为 1–245、246–460、461–690、691–850、851–950，至 EOF 无输出遗漏。未重读原 226KB package、任何 changed 源文件或 unchanged source，未运行测试、PG、replay、git，未写 source/target/作者证据。

新 package `docs/evidence/review-iam-stage-4-o2a-c-fixes-package.diff` 实测 **89116B / SHA256 a13c47aac7f409f0239b073314ab7a5b1822b9502046800af45cd7679dd47fba / 950 内容行**。base 原 C source600（未 Accepted），head fix source600、delta4，最终 epoch SHA256 `44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54`。下述源码行号为新 package 新侧行号；范围仅两份生产文件和 Lifecycle/Capacity 测试，Http 为实际回归而非源码修改。

### I1 关闭：实际退出接缝已覆盖两道 guard

`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunEventReader.java:104` 新增 WindowEmission 专用 consume(action,beforeRelease)；`:220` 的私有 consumeWindow 在 monitor 内先标记 active/consumer，随后才在 `:226` 执行入口 guard、真实 action 和最终 guard。任一 RuntimeException/Error 被记录为 primary，`:230` 必须先执行 beforeRelease，`:231` 再 cleanup、清 active、finish 原 lease。beforeRelease 自身返回或抛出，都有嵌套 finally 保证释放一次。长 write/complete 不持 holder/emission monitor，active close 仍不能抢先释放。

`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:144` 采用该新接缝；`:152` 起的退出回调在 terminal/reset、普通失败、失效及耗尽 superseded 时先 complete，`:163` 才关闭 pending/frame。仅明确 Superseded、未 done、仍有本轮重试额度且 holder 当前 live 的分支可跳过 complete，旧真实 consume 返回并释放后再完整重读。原 B readNext 与普通 EventEmission.consume 在本补丁完整上下文中无改变；未增加通用 ignoreGuard，也未给 B 普通借用附加 Servlet 职责。

新增 `PortalRunEventsLifecycleTest.java:24`、`:25` 分别验证 fresh read 后/consume 前关闭真实 holder，以及实际 action 返回后/最终 guard 前关闭 holder。spy 仅切断时序，真实 RR/result/borrow/guard/finally 仍执行；出口包装将实际 consume 的 action 包裹后反射调用同一真实 result，不 mint 假 proof 或 stub 许可判断。`:50` 后等待两连接 complete 均真实阻塞，再 shutdown，并用实际 reader/subscribe/frame reservation 证明 B2/C2/两 frame 均仍占用（可用 frame60），逐个 complete 实际返回后才恢复至61/62。没有靠清空 owner、取消 Future 或通知替代退出。

最初两个 RED 都在实际 permit 应不可用的断言失败，XML 为“Expecting code to raise a throwable.”；raw 分别定位 `red-guard-entry/raw.log:1142` 与 `red-final-guard/raw.log:1129`。修正后的负例 probe 使用 try-with-resources，避免 RED 中意外取得的真实结果留存；这不替换已冻结 RED。focused 与最终 epoch 的这两方法均通过。最终还实际运行 getter/flush 健康续核、每轮 bounded retry/耗尽、hard bound、blocked get/write/flush/complete、queued 与 active poller 回归，故修复没有以一律提前 complete 换取资源断言通过。

### I2 关闭：未提交容量失败保留 HTTP 错误映射

`engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java:185` 的 capacityReset 仍以 fresh capture 和 check 开始；`:191` 按真实 output.isCommitted 分流，未提交在 `:192` 设置 failure.status 与 application/json，输出既有 code 和固定通用 message；已提交才构造原有限 reset。`:195` 后继续对实际 getter/write/flush 前后做 holder/live/stop 检查，complete 在仍持有 frame/slot 时执行。失败 RR 已实际退出而没有 B 结果的路径不凭空申请 B。

`PortalRunEventsCapacityTest.java:12` 的新方法先占住两 worker，让真实 opening 预检成功并排队，明确 write0，再由真实 writer append 编码后262145B的 event，释放 worker，触发第一次 RUNNING fresh RR 的容量错误。complete 阻塞期间可用 frame61、C slot 保持占用，B两个 permit可用；退出后 frame62/两个slot恢复。响应断言 HTTP413、application/json、EVENT_LIMIT 和固定“事件观察不可用”，拒绝 stream_reset、EVENT_PAYLOAD dimension 与内部错误文本。

该方法 RED 的 XML 与 `red-uncommitted/raw.log:81` 明确 expected413/actual200；focused 和 final 均通过。final 也实际运行 `realHttpUtf8PayloadAtExactBudgetAndPlusOneResetRemainFinite`，保持已提交响应200与有限 EVENT_LIMIT reset。

### 实际冻结证据核对

独立逐阶段读取 raw，提取实际 Running，再以 XML DOM 枚举实际 testcase（含 self-closing），仅纳入这些类；逐一核对全部归档 XML/txt、raw、source-epoch 的物理 bytes/SHA。五阶段均匹配，command/cwd/exit记录与实际 Running、raw BUILD SUCCESS/FAILURE 相符。三个 RED epoch 的两份生产文件 hash 均与修复前 C manifest 相同；focused 后 service 有新 epoch，最终24覆盖其最终身份，未以 focused 的早期 hash代替 final。

| phase | exit | actual Running 类数 | XML tests / failures / errors / skipped |
| --- | ---: | ---: | --- |
| red-guard-entry | 1 | 1 | 1 / 1 / 0 / 0 |
| red-final-guard | 1 | 1 | 1 / 1 / 0 / 0 |
| red-uncommitted | 1 | 1 | 1 / 1 / 0 / 0 |
| focused-fixes | 0 | 2 | 3 / 0 / 0 / 0 |
| final | 0 | 4 | 24 / 0 / 0 / 0 |

最终实际 Running 为 Capacity5、Http6、Lifecycle11、RunEventReaderBudgetTest2。Budget 实际两个方法仅 `emissionAndBorrowCannotBeMintedPubliclyOrExportLargePayloadReferences`、`getterClosureExpiryAndGenerationFailureAreLocalAndSticky`，与具名命令吻合，不是旧 B 整类。final raw `:77`、`:87`、`:89`、`:1155` 为四类 Running，`:1169` 为 BUILD SUCCESS；最终 source-epoch 实测114375B、SHA `44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54`。原29仍仅历史结果，未并入本次24或声称修复后重跑。

## Strengths

修复局限于两个缺口，C退出语义有专属接缝而B普通借用不变；三个真实 RED 与原问题对应，新增测试核实际资源复用而非内部计数器；healthy retry、已提交 reset 及原 HTTP 安全链均在最终适用集合中得到回归。未发现本次变更新引入的有据阻断问题。

## Critical

无未关闭项，无新增项。

## Important

I1 关闭；I2 关闭。无未关闭项，无新增项。

## Minor

M1、M2 保持原事实与非阻断归属，不新增重复编号。此次 final raw `:95` 起仍有 timeout/error 后 ServiceAuthorization 的默认拒绝栈，`:1174` 起有 Mockito/ByteBuddy 动态 agent/JDK提示；本次新 guard 测试确实使用 Mockito spy，不能将本波次全部 agent 噪声归为上轮旧 RunEventResponseTest。已有编译诊断仍不等于测试失败。

M2 原早期五阶段缺 XML/完整 epoch 没有补齐，也不应补造；此次五阶段均具有即时归档的 raw/XML/txt/epoch，未重演该缺口。原归档偏差仍由 root closure 台账保留。

## Assessment

**Spec compliant / Approved；当前未关闭 C=0 / I=0 / M=2（原 M1/M2），新增 C=0 / I=0 / M=0。** 本次两个 Important 已由实现与对应实际失败→成功证据闭环，可进入 root 有限物理审计及索引收口。该结论不代替 root artifact审计，也不意味着跨任务或生产全IAM已完成。

### ⚠ 继续保留的边界

- ⚠ root 负责新 source600/delta4、target overlay494→逻辑2096、private首次check/apply、inventory/journal及八索引的有限物理闭环；本 reviewer 没有重跑或代审这些阶段。
- ⚠ 原 M2 的五阶段 XML/epoch 历史缺口须保留。原29仅历史；本修复最终适用集合为实际4类24，不扩张为旧B整类/全后端。
- ⚠ 原 terminal retention测试不是生产清理任务、active-run/writer/replay/去重全平台兼容证明；engine一跳PORTAL/pure FLOW范围与Gateway/O2b等未完成项保持不变。
- ⚠ 已进入底层 write/complete 的字节与阻塞不可由逻辑stop追回；本次证明实际应用调用和资源finally顺序，不宣称socket物理强断或撤权SLA。

追加前已确认原报告12610B / SHA256 `eee680644e924ffe7a40f5ed3c2ff7bc2a34ff831521d24dd56223b2b9958ff1`；使用字节追加并在追加后重新校验同一前缀。追加完成即冻结。
