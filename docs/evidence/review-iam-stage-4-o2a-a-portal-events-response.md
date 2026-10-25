# Spec Compliance

❌ Needs fixes。O2a A 的授权、签名 profile、first-anchor、local Capture 与 STATUS-only receiver 边界符合冻结要求；但停止回调交付、未发布任务预留回收及取消任务的 timer 注册存在三个可由源码推导的竞态，尚不满足冻结 brief/seam 的生命周期与有限资源要求。代码问题统计 C0 / I3 / M1；本报告不是全局 IAM、SSE、PG 或 B/C 验收。

## Strengths

- 真实权限入口有明确来源：`AuthorityContext.java:16,54,60` 保存首次 mapping、先验证当前 mapping 再取得历史 anchor、每 frame 只 claim 一次；`HttpCurrentAuthorityClient.java:626,634,680` 限制 minting owner、真实 frame、首次/当前 call、operation、grant、ceiling、origin、ticket 身份。`HttpCurrentAuthorityClient.java:525,538,550` 保留首次真实 authorize 的单调时钟和最多 55 秒边界，并在真实 open 前后检查当前 mapping 与 admission JWT。历史短 current 到期不被当作当前许可，见测试 `PortalEventsAuthorityProfileTest.java:171,554`。
- 新签名 profile 独立于旧 ResponseLease：`HttpCurrentAuthorityClient.java:691,722,751,760,775,805` 分别校验真实 HTTP request digest/nonce、完整 immutable 关系、专用 continuation、fresh current 与续期；私有 evidence 构造、单消费见 `:570,603`。`PortalEventsBinding.java:18` 将逻辑 observation 摘要与外层 HTTP digest 分开；current roles/attributes/version 由新签名决定，见测试 `PortalEventsAuthorityProfileTest.java:137,254,417`。
- Capture 的构造私有，holder/owner/evidence/generation/stop/本地期限在本地检查；`PortalEventsResponse.java:30,40,162` 的 material 检查前后均执行 live 核验，未把历史 execution ceiling 当作新 observer ceiling；历史 provenance 正例见 `PortalEventsAuthorityProfileTest.java:608`。它仍不证明 PG 行来源、digest 或 floor。
- `RunObservationSelectors.java:41` 的原 receiver purpose 方法未改，新的 response-call verifier 位于 `:22`；真实 Servlet 测试 `PortalEventsReceiverClosedTest.java:94` 对签名 E 的 aliases、方法、另一 run 和错误 route 断言 403，authority authorize/frame/handler 不进入。scope 为六生产加三测试文件，无 engine/gateway/POM/PG/SSE 开启。
- `HttpCurrentAuthorityClient.java:892,897,902` 明确配置 2 workers、queue64、250ms watchdog、64 slots；`EventsTask.run/finally`、queued cancellation 与同步等待 exited latch 已区分 Future 逻辑完成和实际 worker 退出（`:855,1019,1030,1041`）。下列问题集中在该机制尚未封闭的交错，不否定这项设计方向。

## Issues

### Critical — 0

未在本任务 diff 中发现可直接绕过签名、私有 mint 或 receiver 闭门边界的 Critical 缺陷。

### Important — 3

**I1. 并发 holder.close/client.close 可永久跳过拥有的取消回调。**

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java:117`（提前返回）、`:119`（发布 cancellationStarted）、`:126`（锁外调用 slot.stop）；`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:950`（runtime.close）、`:954`（再次 response.stop）、`:960`（workers.shutdown）、`:986`（slot.stop）、`:999`（提交回调）、`:931`（拒绝处理）。

触发顺序：线程 A 关闭 holder，先发布 failure/cancellationStarted，离开 holder 锁，但尚未进入 slot.stop；线程 B 执行 client.close，snapshot 中看见该 holder，response.stop 因 cancellationStarted 已 true 返回，随后关闭 workers；A 恢复后才提交 cancellation task，executor 拒绝，execute 对未启动 task 调用 cancel/finish，取消回调从未执行。slot.work 可以因此归零并释放，但底层拥有的取消动作没有发生；A 的 close 还会抛出 unavailable。slot.stop 内的锁只能保护已进入该方法的提交，不能保护此前 holder 标记到 slot 提交之间的空隙。

这违反“关闭所有自有 handles/资源回调”和“实际 callback 返回前占用”的要求，不是允许的物理 I/O 退出延迟。应让 client shutdown 与所有 slot 的取消提交形成同一可验证顺序，区分“停止已发布”和“取消已交付”，且拒绝路径不能把未执行 callback 当作已完成交付；callback 本身仍须在生命周期锁外运行。补充确定性并发用例：停在 cancellationStarted 发布后、slot.stop 提交前，完成 client shutdown 再恢复该线程，验证 callback 恰好实际执行一次、容量在其实际返回后归还、close 路径收尾完整。现有 `PortalEventsAuthorityProfileTest.java:343` 是 renew/holder.close 后顺序关闭 session，`:490` 也是先关闭全部 holders，均未覆盖此交错。

**I2. 续期在预留 work 后过期，会遗失从未发布的 task，永久占用 slot。**

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:833` 创建 task，`:913` 的 task() 先 addWork，`:843` 随后才调用 previous.remainingNanos；`admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java:66` 先检查 current，`:68` 调用 renewEvents，`:69` 才发布 pending。

触发顺序：首次 requireCurrent 成功，runtime.task 已把 slot.work 加一；剩余 current/hard 时间在第二次 remainingNanos 前耗尽，或 client 此时变为 closed，第二次检查抛异常。task 尚未返回给 holder，pending 未发布，也未进入 execute 的拒绝回收或任何 cancel/finally。watchdog/close 随后正常执行取消回调只能回收它自己的预留，遗失 task 的 work 永远为一，slot 永不移出容量集合。重复临界到期可逐步耗尽 64 slots。

应在取得 task reservation 前完成可能抛出的期限计算；任何 reservation 创建之后、提交或发布之前的失败都必须对这一个 task 做一次有保证的取消/回收。补充确定性过期交错用例，验证未发续期 HTTP、取消回调实际完成后 slots 归零且后续合法响应仍可占用容量。现有 queued-opening timeout `PortalEventsAuthorityProfileTest.java:574` 的任务已经发布并提交，不能证明此未发布分支。

**I3. 已取消并完成的 task 仍可随后注册 timer、排队，失去与 holder 容量的绑定。**

位置：`admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java:69,72` 分离 pending 发布和 execute；`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:918` 无 task 终态协调的 execute，`:920` 注册 timer，`:929` 提交 worker；`:1030` 取消 queued task，`:1043` 的 finish 幂等返回，`:1046` 只取消当时已有的 timeout，`:1022` 的 run 对 finished task 直接返回。

触发顺序：renew 发布 pending 后、实际 execute 前，close 取消该未启动 task，finish 完成并见 timeout=null；slot 的取消回调随后可完成并释放 slot。原 renew 线程继续 execute，却仍创建 timer 并把已经 finished 的 task 入队。run 直接返回，不会补做 finish；重复 cancel 的 finish 也提前返回，所以后来赋值的 timer 只会等到旧 deadline 自然出队。slot 已经释放复用，滞留的 timer 数量因此不再受 64 holders 约束，scheduler 的延迟队列没有对应有限额度。任务被取消发生在 scheduler.schedule 返回和 timeout 字段赋值之间，也存在同样的“finish 看不到新 timer”窗口。

应把 timer 注册/交付、任务提交和取消/finish 协调起来：终态 task 不再生成 timer 或排队，timer 与 finish 交错时必须可靠取消；单独增加一次 isDone 检查不能消除检查后的竞态。补充确定性“取消后才恢复 execute”以及“注册 timer 与 finish 交错”的用例，检查终态 task 不留下 scheduler/worker queue 项、预留只归还一次。当前容量测试 `PortalEventsAuthorityProfileTest.java:490` 验证稳定状态的 slots/worker queue，但未检查这种已释放 slot 的滞留 timer。

### Minor — 1

**M1. 名为 grant expiry 的测试没有隔离 grant 期限。** `admin-common/src/test/java/cn/agentplatform/common/authority/PortalEventsAuthorityProfileTest.java:454,463` 在默认 source120秒、binding30秒的 session 上推进301秒，source/current/local hard 均先到期，因而该分支即使删掉 grant 边界仍可通过。生产 `HttpCurrentAuthorityClient.java:531` 确实把 grant expiry 纳入 hard min，未发现相应实现错误；建议以后用真实签名且短于 source/binding 的 grant 分支单独验证，或准确调整现有测试名称，不把此用例算作独立 grant 边界证据。

## Evidence and inherited limits

- 完整读取冻结 diff，互不重叠批次为 1–380、381–780、781–1180、1181–1580、1581–1930、1931–2280、2281–2600、2601–2911；EOF 后 LF 对应 split('\n') 2912项。首尾身份均为196931B、SHA256 `54df9be546e89c1a79eed83d2d73260d62e1a0b58558bf7fbf3dbdde6f338030`。逐行证据取自 diff 的完整上下文，未另读改变的源文件；后续只解析该包计算源行号。
- 正式 brief 13055B/SHA256 `1b1de0bb861a1996d08ab2fa30358233176004ace0aee58f9be951b893298cad`、seam2939B/`2a82a5415e6f34a62bc84b10a906d0489f39a73fa199e067d4e6f2db0a47042d`、reviewer prompt7919B/`2eb9d54373420de25bc0bd00635d3a3123a6c0eb30c881168e6f3348e2387331` 均在准备阶段完整读至EOF；未触发 API notes 的 named ambiguity 扩读。
- 最终作者 report 12758B/SHA256 `2f00b5db4edb6913d98798e3a8eb923cc8b29098d859ce3545fc42064cba603a`，final02 handoff6797B/`abff6217d7d8dfa0ec77345a34ab0a58efb885c8b9e22a9b6779e3d770f1fbf6`，均完整读至EOF且匹配root冻结身份。它们是作者声明，源码结论以diff为依据；实际 XML/raw、589源清单、2023逻辑target、重放和物理资源归属由root有限审计核验，本review不重复运行。
- `iam-stage-4-o2a-a-portal-events-response-report.md:45,49` 与 handoff 如实保留 final01八类85方法、72 PASS/13 ERROR；最后语义修复后 green04只Profile22/22通过。未将其称为85全绿，也不要求重复已绿七类。此前按root授权核对的三份冻结历史基线证明旧 ServiceAuthorizationTest 的 run/r-test+保留STATUS action 已与旧 fixture/production 不兼容；13项仍属独立迁移待办，不是A放宽receiver的理由。
- ⚠ E1（root独立证据归因修正，非本review自行重扫结论）：root通报 final02 diagnostic-closure 的 ownedNewDumpstreams194 中183项已在旧O1c logical target2004且bytes/SHA相同；旧2004本身含908 dumpstream。`iam-stage-4-o2a-a-portal-events-response-report.md:59` 的“所有owned新增dumpstream”因此应由独立correction明确区分本轮复制的归档文件与其历史源日志。其余11项仍须对baseline-physical核名。该清单体现历史logical lineage，不能解释成纯classpath或本轮新增产物；保留原封存件，不为此重测/重封。该事项交root处理，未纳入上面独立源码I3计数。
- 本次审查无测试、浏览器、probe、replay、git或源码/target/index修改；未扩大工程搜索或读取邻接旧实现。仅创建本review新文件，交还其写权。

## Assessment

**Task quality: Needs fixes。** 真实权限与闭门边界有较强的签名及行为验证，但三个生命周期交错会遗漏取消交付、泄漏容量或留下失去容量约束的 timer。修复并提供针对交错的确定性证据后再做窄复审；当前不给 A Approved 或 Accepted。

EOF
