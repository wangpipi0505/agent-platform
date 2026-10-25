# HTTP SUBMIT_QUERY 独立评审

评审日期：2026-10-20。**最新定向复核：原 H1（UNKNOWN 对账授权收窄）与 H2（准备事实/outbox 竞争）已关闭，限定修复范围通过。** 修复清单 4/4 SHA 匹配，评审者独立运行 AsyncHttpExecutionTest **21/21 通过，0 failures/errors/skipped，05:35:57 +08:00**。不据此关闭全部 T11；非 UNKNOWN 既有事实再次交付的授权语义另列待核对项。原始发现与证据保留在下文，后附本轮关闭依据。

## 首轮范围与证据身份

- 唯一工程根：`G:/207/项目/算法重组工具/代码/agent-platform`。只读取本工程源码、测试、冻结契约和已有日志；唯一写入为本文。未读取相邻旧工程，未改业务代码，未运行 Maven、探针、服务启停或 Git 提交。
- 使用 code-review、requesting-code-review、verification-before-completion 的要求组织独立规格与质量检查。工程无 HEAD，评审对象为当前源文件，不宣称评审了 Git diff。
- 已读 AGENTS.md、README.md、docs/implementation-brief.md、contracts/README.md、技能调用契约、冻结详设第三部分及原技能章 §3.2/§3.3/F6、调用恢复与认领规则，并沿本工程 ToolCalls、ExecutionRecovery、SkillOutbox、OutputGate/ArtifactStore 检查必要上下文。
- 实际执行 `Get-FileHash -Algorithm SHA256`：冻结快照摘要为 `3C72B2062BEB2E8B377F2384843EA935A0074B50C0063E5106566BBD8DCD0C4F`，与 freeze.json 一致；skill-async-http-sha256.txt 中全部 **7/7 文件匹配**：SkillCatalog、SkillExecution、SkillAdapters、HttpAsyncAdapter、AsyncHttpResponse、V307、AsyncHttpExecutionTest。下列行号及结论绑定该清单版本。

## 首轮阻断项（关闭依据见末尾定向复核）

### H1 / P1：守卫收窄后，对账仍采纳原任务的宽范围结果

位置：`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:571`（至 589）；相关 `skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java:122`（至 168、183）。

具体触发：原调用 `arguments.region=[A,B]`，原主体同时获准 A、B，提交时 `request_snapshot.guardedArguments.region=[A,B]` 已持久化。外部任务尚未完成，执行事实为 UNKNOWN。相同 tenant/principal 后续权限缩为 A，对账时 `SkillRules.guard` 正确得到 `secured.region=[A]`，但 reconcile 没有对比原 guardedArguments。它把 secured 传给适配器后，`queryOnly=true` 分支完全不使用这些参数：只以原 jobId/key GET 查询，接收的仍是原 A、B 任务结果。job/key 与 Schema 校验全部可以通过，随后结果被写为 SUCCEEDED 并交付，其中 B 数据已越过当前授权范围；大结果路径同样可能生成受控 artifact 引用。

该问题不是“守卫没有调用”，而是当前守卫通过被误当成旧任务结果也已被收窄。原调用参数及事实有足够证据进行拒绝，但目前未使用。相邻 LAST_REAL 路径已比较历史 guardedArguments 与当前授权参数；HTTP 对账未做对应约束。

修复方向：在发起原任务结果查询、物化结果或采纳结果之前，确认当前允许范围仍覆盖原任务的持久 guardedArguments；无法证明时拒绝本次复用，不把旧结果放入 structured/fileRefs。保留原 UNKNOWN 外部事实并记录授权拒绝的对账留痕，不将当前权限不足伪记为外部业务 FAILED，不重发提交。新增真实 PG/HTTP 定向断言：原 `[A,B]`→当前 `[A]`，submit 仍为 1、无宽结果/新 artifact 暴露、原事实不改成 SUCCEEDED；保留当前权限覆盖原任务的正常恢复用例。

### H2 / P1：预登记 UNKNOWN 立即被 outbox 投递，可抢先取消唯一提交机会

位置：`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:236`（至 263）。相关：V300 的 callback_state 默认 PENDING；V304 的 next_delivery_at 默认 now；SkillOutbox.pulse/drainTenant；`engine-runtime/src/main/java/cn/agentplatform/engine/infra/ToolCalls.java:334` 与 `:265`。

可执行时序：

1. execute 已取得引擎 claim，engine.tool_call=EXECUTING。
2. 新增事务提交 external_job_binding REGISTERED 和 execution_fact UNKNOWN。INSERT 未设置 callback_state/next_delivery_at，因此该占位事实立即为 PENDING 且到期可投递；此时 send_started=false，外部请求尚未发送。
3. 常驻 outbox 在 execute 调用 `/submitted` 前认领这行，将占位 UNKNOWN 交给引擎。ToolCalls.fact 允许合法同代 EXECUTING→UNKNOWN，正常接受。
4. execute 随后请求 `/submitted`；该 CAS 只允许 EXECUTING→SUBMITTED，现为 UNKNOWN，返回 CALL_CLAIM_LOST。外部提交数量为 0。
5. 重入 execute 发现旧 UNKNOWN 只 deliver；reconcile 只查询原 key/job，send_started 不会再设为 true。对该平台从未创建过的外部任务，正常协议无法查询到完成结果，调用被困在 UNKNOWN。ExecutionRecovery 也要求确定的 SUCCEEDED/FAILED 事实，不能恢复这个未发送任务。

这是新增“先落 UNKNOWN 再提交”的事务与既有自动投递器组合产生的正常线程调度竞争，不需要恶意输入或数据库破坏。登记先行本身正确，错误在把登记占位同时当作可投递的执行事实。即便临时缩短窗口，也不能消除竞争。

修复方向：将持久登记占位与可投递事实分开表示或使用明确的投递资格条件，只有实际得到未知/完成事实才开放 outbox；同时为登记后崩溃保留可判断的恢复路径，依据持久 send_started 决定安全处理，不能简单永久隐藏记录。新增可控屏障测试：登记事务已提交而 `/submitted` 尚未执行时运行 outbox，证明它不会先把引擎变为 UNKNOWN，随后唯一一次提交成功；另覆盖登记后崩溃恢复和 ACK 丢失仍不重发。

## 规格与实现核对

| 要求 | 本次核对结论 |
| --- | --- |
| PG 唯一事实、创建前原子登记 | binding 与 UNKNOWN execution_fact 在同一事务，V307 唯一键仲裁；但投递资格存在 H2。 |
| 唯一执行权与单次发送 | execute 先认领并请求 submitted；send_started CAS 先于 HTTP POST。恢复 queryOnly 不触发 POST；未发现另一路隐式重新提交。 |
| ACK 非最终结果 | ACK 最多读 65537 字节以判断 64 KiB 限额；校验 jobId 非空/≤128 和原 idempotencyKey，不用最终 outputSchema 验 ACK。 |
| 归属绑定 | 绑定记录 tenant/principal/run/call/attempt/skillVersion、请求快照和 contract digest；入口核对引擎登记，正式版本契约不可变。绑定读取限定 tenant/principal；query 再比 job/key。 |
| ACK 丢失恢复 | 原 job 缺失时仅按原 key 查询/lookupEndpoint；配置不足保留 UNKNOWN，不发第二次提交。 |
| key 查询竞争 | job 首次绑定 CAS 后再次读取 PG 赢家；观测 job 不等于赢家则拒绝，已修正日志中的 losing-job 误采纳。 |
| 当前授权 | 原调用先守卫；对账虽再次 guard，但不限制历史任务结果，H1 未满足。 |
| 最终结果与资源边界 | AsyncHttpResponse 流式抽取 result 临时文件，输出字节计数硬限 64 MiB；协议解析有 64 MiB document、250000 token、8 MiB string、100 depth、1024 name 限制，严格重复键检测与尾随 JSON 拒绝。metadata 有 65536 的字符/标量预算（并非严格 UTF-8 字节预算），另受结构闸约束。未将整个协议响应无界 readAllBytes。 |
| OutputGate 与临时资源 | result 文件经既有 SkillAdapters.read：阈内 Schema/returnSpec，超阈既有 MinIO artifact 分支及原始 Schema 验证；临时文件在解析异常和 try-with-resources 结束时删除。MinIO 失败沿既有 CLEANUP_PENDING/精确键清理；本次未注入进程崩溃，也不声称证明了崩溃后临时文件回收。 |
| 未知与旧租约 | query/job/key/完成标记/输出异常均保留 UNKNOWN；查询时间只入 reconciliation_note。事实先落 PG，回调走既有 outbox；引擎对旧 owner/attempt/lease 留痕但拒绝直接逻辑提交，受控 proof 恢复另核对精确合同、代次及 WAITING_EXTERNAL 状态，不直接复活取消终态。 |
| 未支持组合 | 注册明确拒绝 CALLBACK、MCP 异步、HTTP SUBMIT_QUERY 非 POST/混用 httpMapping、查询非 GET 等；这些不被误报执行成功。 |

## 已有测试日志与实际断言

本评审只读取原始日志和测试源码，**没有重新运行 85 项套件或其他动态探针**。

| 记录 | 实际读取到的结果 |
| --- | --- |
| skill-async-red.log → skill-async-green.log | 首轮 2 项、0 failures、2 errors（注册层抛异常）；随后 2/2，05:12:12。首轮不是两条 AssertionFailedError。 |
| skill-async-stream-red.log → skill-async-stream-green.log | 5 项中 1 failure：18 MB 结果 expected SUCCEEDED / actual UNKNOWN；随后 5/5，05:13:56。 |
| skill-async-protocol-red.log → skill-async-protocol-green.log | 7 项中 2 failures：DELETE 未抛拒绝异常、HTTP403 expected FAILED / actual UNKNOWN；随后 7/7，05:15:42。 |
| skill-async-binding-race-red.log | 1 项失败：expected UNKNOWN / actual SUCCEEDED，05:18:13。当前源码已有 PG 绑定重核验。 |
| skill-async-regression-final.log | 85 项，0 failures/errors/skipped；AsyncHttpExecutionTest 8 项；BUILD SUCCESS，2026-10-20 05:18:58 +08:00。历史前一轮为 84/84，不能混称同一次。 |

8 个新增测试实际断言覆盖：提交前有 binding、原 key、一次 submit、原 job 实际输出值 7、异 job 拒绝、丢 ACK key 恢复、错误 Schema 保持 UNKNOWN、18 MB 转成一个 fileRef、403 权限分类、非 POST 注册拒绝、查 key 期间被另一 job 抢占的拒绝。源码接真实专用 PG、测试 HTTP 服务，并构造真实 ArtifactStore；大结果断言验证 SUCCEEDED 与一个引用，未逐字节下载比对整个 18 MB 对象。engine clients 为 Mockito，`/submitted` 固定成功、`/fact` 固定 accepted；未跑真实引擎/outbox 交错，也没有当前权限 `[A,B]`→`[A]` 的测试。因此绿色记录不能排除 H1/H2。

## 首轮结论与明确边界

规格符合性：单次发送、ACK/最终结果分离、原 key/job 恢复、流式解封和竞争赢家核验有实现及已有证据；当前授权约束和预登记至提交的并发正确性未通过。

质量结论：请求修复 H1/H2，并以能失败的定向回归证明修复；当前评审没有发现需要额外列为确定 P1/P2 的输入路径/资源无界问题。该陈述限定于上述源码路径检查，不是全工程或生产安全认证。

CALLBACK 签名回调、取消三态、NOT_APPLIED 的引擎授权重试、UNKNOWN 人工审计产品闭环及真实 MCP/K8s/外部供应商验收是已有明确后续边界，本次不把它们重列缺陷；即使修复 H1/H2，也不能关闭全部 T11。本文未执行任何修复，未宣称新的测试通过。

## 定向复核：H1/H2 关闭依据（2026-10-20 05:35）

本轮仅复核原两项阻断及修复引入的准备恢复栅栏，未扩大 CALLBACK 或全工程范围。已读 skill-async-fix.md、skill-async-fix-sha256.txt、四份实际源文件与相关原始日志；四文件 SHA 均匹配：SkillExecution=`B47B5B8F0223FA2AE606857A4235EE0875DF354D27FED4CBE14D6A31CDC3542D`，HttpAsyncAdapter=`AF0994D0943AF033300949C2BFCD70A3D995F350AD4AFABECA47A277F04E1A53`，V308=`4A2229C9C0DF0BC95C6E782306ABF01FC60A7B7BCB3F9A825BE6B0EE15390DAF`，AsyncHttpExecutionTest=`464736B6A2D287A165A211F92BC249389C687E2CA5377FFAC5318DD3B6C17B26`。

### H1：原 UNKNOWN 对账缺陷关闭

SkillExecution 575–590 先执行当前 guard/inputSchema，再要求冻结 guardedArguments 的属性范围被当前有效请求范围覆盖；757–774 对 guard 集合采用 containsAll，集合顺序无关，非守卫字段精确相等。缺冻结证据、角色撤销、属性缺失和非空范围收窄均在适配器查询前返回 403。finally 写 AUTHORIZATION_DENIED 审计，原 UNKNOWN 不被伪改 FAILED/SUCCEEDED；没有先创建 artifact 再拒绝的窗口。

已逐条核对新增测试断言：AB→A、角色撤销、当前授权缺失、冻结证据缺失均要求 query=0、submit=1、无 fact 回调、原 fact UNKNOWN、structured=null/fileRefs 为空且一条拒绝审计；正例允许冻结 `[B,A]` 重排与当前 `[C,B,A]` 授权扩展，也允许原 AB 请求实际获准发送 A、后来当前有效范围扩大为 AB。测试身份为已验签测试 Actor 的同 tenant/principal，权限变化通过切换当前 Actor 构造。独立定向运行包含这些用例并通过。

### H2：原准备事实提前投递缺陷关闭

SkillExecution 235–255 在原短事务中显式登记 PREPARING；V308 扩展约束并隔离旧版仍 REGISTERED/send_started=false 的 PENDING UNKNOWN 占位。SkillOutbox 既有查询只选择 PENDING，直接 deliver 810–811 也拒绝投递 PREPARING，所以重复 execute/retry 不绕过投递门禁。实际完成或未知事实落库才转 PENDING。没有事务或数据库行锁跨 submitted RPC/外部 HTTP。

新增屏障测试真正停在 `/submitted`，此时持久绑定已存在，真实 SkillOutbox.drainTenant 被执行，断言 fact 回调为 0、引擎夹具仍 EXECUTING、重复 execute/retry 不外发、reconcile 尚被状态门禁拒绝；释放屏障后 submit=1，并能交付真实 UNKNOWN 和后续 SUCCEEDED。另一个测试以独立 PG 连接锁住 execution_fact，证明 SKIP LOCKED 返回 0，释放后可交付一次且不重做业务。测试使用真实 SkillClients/HTTP 与真实 PG/outbox；引擎权威仍为受控 HTTP 状态机夹具，不冒称真实引擎进程验收。

准备崩溃恢复也做了必要保守约束：只有引擎已独立判 UNKNOWN 才进入恢复；未发送 REGISTERED/false 被短 CAS 转为 NOT_SENT，发送 CAS 同时要求 status=REGISTERED。两者以同一行状态仲裁，恢复先赢时迟到 submitted ACK 不能重新发送，发送先赢时只查原 key/job。NOT_SENT 重复对账直接返回，既不查询伪造 DONE 也不自行重发。丢 submitted ACK、迟到 ACK 屏障、已发送/丢外部 ACK 的 PREPARING 持久图像恢复、完成事实 late 交付和取消准备记录都存在具体断言。已发送崩溃用例是持久图像重建，不是 OS kill。NOT_SENT 后的业务自动重试仍需后续引擎明确授权，这一边界没有被本轮实现冒充完成。

### 本轮实际验证与证据区分

评审者在当前唯一工程根执行：

```text
mvn -B -ntp -pl skill-registry test -Dtest=AsyncHttpExecutionTest -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

命令仅从本工程 .env 加载进程变量、不打印密钥，使用 Java 21，不带 -am。退出码 0；21 tests、0 failures/errors/skipped，BUILD SUCCESS，05:35:57 +08:00。连接专用 agent_platform_skill_async_test，Flyway 确认当前 schema=308，未执行新迁移。未启停或部署服务、未改业务源码；本轮只编辑本文，测试构建产生正常 target 输出。

另读取原日志确认红绿链：fix-red 为 14/5 failures，恢复红为 17/6 failures，首次绿 17/17，中间完整 97/97（async20），send-fence-red 两项各失败（重复 NOT_SENT 查询误采、迟到 ACK 仍发送 1 次），send-fence-green 为21/21；最终作者完整日志 skill-async-fix-full-final.log 为98/98、async21/21、05:33:24。后者是作者已有完整回归证据，评审者本轮没有重跑98套件。格式化 AOSP dry-run exit0 是作者报告，本轮没有独立重跑，不能写成评审者亲测。

### 单独待核对项：已确定历史事实的再次交付

当前 reconcile 564 对非 UNKNOWN 事实在 guard 前直接 deliver；execute 的既有事实快捷路径同样可能返回已确定结果。具体待核对场景为：同一 tenant/principal 曾在 AB 授权下完成并持久化宽结果，后来其已验签 SERVICE 身份仅有 A，再调用 reconcile/重复 execute，收到此前保存的信封。该路径不同于原 H1 的 UNKNOWN→查询→首次采纳，新增授权拒绝测试没有覆盖它。

冻结技能管道 §3.2/§6 要求权限重校验，也规定重复 DONE 调用直接采纳既有信封；历史回放与既有结果读取是否要求当前技能范围、以及内部 fact 再投递与面向用户重新外发是否同一授权语义，需要结合原调用/历史读取契约进一步判定。不能只因代码位于 guard 前就直接把所有幂等历史读取判成新 P1，也不能声称本次修复已覆盖这一通道。已交根任务单独核对；本项不改变原 H1 UNKNOWN 具体缺陷和 H2 准备/outbox 竞争的定向关闭结论。

最终规格/质量结论：原两项触发已由源码与独立定向测试共同证明修复；本轮限定范围未发现新的确定 P1/P2。T11 后续边界及上面的历史再次交付待核对项继续保留。
