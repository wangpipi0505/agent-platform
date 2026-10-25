# Spec Compliance

✅ Approved。本次窄复审关闭原 I1/I2/I3/M1；本轮新增/未解决问题 C0 / I0 / M0。仅针对冻结的三文件生命周期修复及其影响；A 的最终 Accepted 仍由 root 完成有限证据审计后决定，不表示全局 IAM、SSE、PG 或 B/C 完成。

## Strengths and original finding disposition

**I1 — Resolved：停止发布不再替代取消交付。** `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:1030` 在 slot 锁内只创建一份 cancellation task，先增加 work 再发布 stopped，并于锁外调用 `executeCancellation`（`:1046`）。`EventsRuntime.close` 在 holder.stop 之外无条件调用 slot.stop（`:991,1001`），之后才 shutdown workers（`:1003`），覆盖原 holder 已标记停止但尚未提交取消的空隙。所有 closer 共享 task，`:955` 在 task 锁内协调 submitted；worker 拒绝时不 cancel/finish 未执行的 callback，普通停止调用者于锁外 run 同一 task（`:971`），callback 实际返回后才经 `:1088,1095` 回收额度。

对应确定性测试为 `PortalEventsAuthorityProfileTest.java:683`：stop-published barrier 后执行 client.close，等待 worker shutdown，再恢复原 holder.close；`:713,716` 分别核 callback 运行中仍占一个 slot、实际退出后归零及 executor termination。`:725` 的真实 queue64 饱和用例进一步检查 caller 回调在所有生命周期锁外运行、运行时不归还容量、恰好一次实际返回，并确认64个 filler 退出。不存在把 rejected callback 当作已完成交付的原路径。

**I1 的 watchdog 约束 — Resolved。** `HttpCurrentAuthorityClient.java:899` 保留唯一 watchdog 的真实 Thread 身份；`:965` 的拒绝分支在 watchdog 上既不标 submitted，也不执行 callback。未交付任务仍是有限 slot 的一个字段并保留 work；`:974,976` 的后续 tick 仅帮助本地取消派发，不发起隐藏 authority retry。close 先停 scheduler（`:992`）再帮助所有 slot 派发，不增加线程或队列。`PortalEventsAuthorityProfileTest.java:790` 在两 worker 阻塞、64 filler 入队时，安排实际 scheduler 调用 stop，`:813,818` 核下一 deadline barrier 可达且 submitted=false；随后真实同一 scheduler 驱动 tick，`:828,831` 核 callback 不在 watchdog、运行中占额、退出后释放且64 filler 完成。

**I2 — Resolved：可失败期限检查先于 reservation。** `HttpCurrentAuthorityClient.java:833,837` 先取得 runtime、检查 previous.remainingNanos 并计算 deadline，`:838` 才创建 task/addWork；此后到返回仅写 deadline。`PortalEventsResponse.java:68,69` 立即发布 pending，发布后的异常路径 `:77` 明确 cancel 同一 task。`PortalEventsAuthorityProfileTest.java:761` 在第一次 holder current 检查通过、第二次 deadline 检查前设置 barrier并推进时钟6秒；`:777,780` 核 callback 实际完成后 slot 归零、无续期 HTTP、后续合法 response 可重新使用容量。这覆盖原未发布 task 泄漏，而非只复用已排队超时用例。

**I3 — Resolved：timer/提交与取消/finish 使用同一 task monitor。** `HttpCurrentAuthorityClient.java:928,932` 在 task 锁内检查 finished/isCancelled/submitted、创建并交付 timeout、提交 worker；`:1076,1079` 的 Future 取消状态发布和 queued remove 同锁协调，`:1088,1092` 在同锁内标 finished 并取消已交付 timer，再于锁外通知 holder 和 slot。于是取消已完成的 task 无法再登记 timer/排队，timer-created 到 timeout 赋值的窗口也不能由另一个线程 finish 穿过；不是单次无同步 isDone 检查。

`PortalEventsAuthorityProfileTest.java:840` 的 pending-published barrier 在 slot 已释放后恢复 execute，`:861,863,865` 核 worker/scheduler 两队列均0、work=0、重复取消不重复归还；`:875` 的 timer-created barrier 保留原 schedule 返回到字段交付之间的位置，`:898` 等待真实 cancel 线程到达阻塞点，`:903,905,906` 核两队列0及幂等回收。两例均核续期 HTTP 为0及旧 capture/closed holder 拒绝；原真实 authority HTTP 阻塞后迟到结果测试仍保留在 `:350`。

**M1 — Resolved，采用准确命名及限制披露。** `PortalEventsAuthorityProfileTest.java:461,462,463,471` 将测试及分支改成 elapsed hard boundaries、past-source/past-all-boundaries，并明确长跳跨越多个期限。`HttpCurrentAuthorityClient.java:420,430` 在真实 ticket/current 验证中拒绝 operation expiry 晚于 grant expiry，因此合法关系不能构造 grant 严格早于所有其他边界；`:533` 仍将原 grant expiry 纳入 hard min。冻结实施报告 `iam-stage-4-o2a-a-lifecycle-fixes-report.md:41` 准确披露默认 current5秒、binding30秒、source120秒先于 grant300秒，没有独立 grant-first 覆盖。无需修改 Loopback 或放宽生产规则造此正例。

## Repair impact and quality

- 三个既有文件修改、0新增/删除；生命周期实现维持 `HttpCurrentAuthorityClient.java:897,900,909,912` 的2 workers、queue64、250ms watchdog、64 slots。pending/stop/slot/task 的交付路径已检查；slot 锁仅负责本地状态，callback 位于所有生命周期锁外；task finish 于锁外进入 holder/slot，未发现新增相反锁序或 watchdog 直接执行用户 callback 的路径。
- 真实权限与时限约束仍在 `HttpCurrentAuthorityClient.java:628,533,693,724,762,777,807`：private mint、owner/frame、首次单调 anchor、fresh signature/current、专用 continuation 和固定不可变关系未因修复改变。`PortalEventsResponse.java:84,167` 保留终态拒绝 late install 及 material 前后 local live 校验。receiver/RunObservationSelectors 不在本次改动，沿用原已审 STATUS-only 闭门范围。
- 包内每 client 的默认无动作观察接缝 `HttpCurrentAuthorityClient.java:65` 只接收阶段名；没有新增公开权限工厂、证据导出或全局 hook。测试在真实 holder/authority HTTP 已成立后驱动交错，`PortalEventsAuthorityProfileTest.java:640` 暂停原 periodic tick 后使用真实 executor、task、queue 和额度；`:621,626,663` 的有限等待用于 barrier/退出观察，不以概率循环代替竞态位置。
- 普通调用者遭取消提交拒绝时可能同步等待其自有 callback；这是当前有限资源方案的明确行为，不承诺物理关闭/即时撤权 SLA。callback 实际退出前的 work 保持有效，测试 `PortalEventsAuthorityProfileTest.java:725,790` 对此有断言。Session.close 额外实际检查 worker/scheduler terminated（`:113,116`）。

## Issues

### Critical — 0

本次修复及其可见影响未发现 Critical 问题。

### Important — 0

原 I1/I2/I3 均已关闭，未发现新增阻碍。

### Minor — 0

原 M1 已按准确命名与披露关闭；没有以新 fixture 或非法签名事实伪造独立 grant-first 结果。

## Verification and scope limits

- 完整读冻结 brief 8303B/SHA256 `b1f5742e3b128df0dc71499c7b6d0dd34a4937e70153647813fcbcb18955c1e4`；report13443B/`0bdb36435d7e1323c2818d18e779edbd6e59a62f662a5420ba8cd09b911e6f55`；handoff4037B/`07a486af75168b5fa598dd332210eb153936ee3fdb034075b650488149388840`。三者均到EOF，身份匹配root交接。
- 完整上下文 diff 按互不重叠批次1–400、401–800、801–1200、1201–1600、1601–1950、1951–2288读到EOF；2288内容行和尾LF对应splitLines2289。首尾核对均136177B/SHA256 `39d5c93ac10f8f2900f72bd0152f432a753d72facdf2439a0217276bcdc2aaba`。未另读改变的源文件；后续仅解析同包映射源行号。
- 阶段事实按冻结 report `:49–52` 与 handoff分开记录：red01新5方法5FAIL/0ERROR；green01定向5/5；red02 watchdog方法1FAIL/0ERROR；final01仅Profile整类28/28一次。六个新测试的行为和交错位置已由上述diff验证；实际raw/XML、executedSource与finalSource身份及物理库存归属由root独立有限审计核验，本review未运行验证命令，也未把不同阶段拼成一次suite。
- 历史 red01 的两项人工 blocker超时诊断已在 report`:56` 明确保留；最终 blocker 使用有限10秒等待并finally释放（测试`:663`），最终结果按独立阶段记录。Surefire Boot Manifest-JAR四个新dump提示及旧diagnostic历史归因保留为证据边界，不当作新增compiler产物或未披露的业务异常（report`:65,67`）。
- 正式source589 SHA256 `3ad9c38b06e8ddc637f6ef4a36a5c2e474d15cf306ba1af341a3d6fad01ae7e4`；正式target为修复目录 `final/target-after.json`，419611B/SHA256 `b5bedc4fe8a66f1e4ee171adafd823d72cae3fb576e2e8e3358b82a827e2a335`，logical2023。顶层2027及原physical-check明确superseded，不是新正式库存；本review不重复哈希inventory/target或重做replay。root原diagnostic/service-method纠正继承，旧Service13项仍是独立迁移待办，历史85方法保持72PASS/13ERROR。
- 无测试、浏览器、probe、replay、git、邻接工程扫描或源码/target/index/作者材料修改；仅写此新review文件。原review及原封存件保持不动，交还此输出文件写权。

## Assessment

**Task quality: Approved。** 原三个资源生命周期竞态已由协调后的状态转换及六个针对性交错测试覆盖，M1已准确缩小测试声明。本结论是代码窄复审通过；root的最终有限证据审计、独立旧测试迁移及后续B/C继续按各自范围处理。

EOF
