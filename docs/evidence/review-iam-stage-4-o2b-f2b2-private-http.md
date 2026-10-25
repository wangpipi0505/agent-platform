### Spec Compliance

- ✅ Spec compliant（本任务源码 gate）。以限定 Accepted F2b1 source621 为 base、最终 source623 为 head；未发现缺失、越界新增或错误理解的阻断项。范围限定为同一 Session 的 Q→G→child→私有 APEV/F1 输入，不批准公开 EVENTS、receipt/material 验证、SSE 或 assembly。
- 核查依据：`docs/evidence/iam-stage-4-o2b-f2b2-private-http-brief.md` 完整12165B / SHA256 `943cc0dbc71e575af6ff13ef0c0ca20f212db1b555d431ee52aeeac6b8169e74`；完整作者报告17241B / `ee401601e3d7943589fa4d772a26369573b63559c0aab85061aacc6e8d6974eb`；向量澄清、完整 gate notes、b1 budget、draft 的编号72–82；AGENTS、implementation-brief、contracts/README 与 SDD task-reviewer 模板。
- 核查范围：full-context 包156793B / SHA256 `ec38af73e85060312804a3be0d2e87eaf08266b4595671b20b866ed647dea7d1`，按包行1–250、251–510、511–770、771–1030、1031–1290、1291–1550、1551–1810、1811–2030、2031–2150、2151–2270完整读取到 EOF，各源码段无截断。未另读五份 changed files；文件行号从 unified diff 的新侧计数机械提取，没有运行 Git 重建 diff。
- 同 Session 关联、延迟收费与七事实保存符合要求：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:194` 的包内入口不接受 G/child/facts；`:204` 在 SavedFacts/decoder/input 新图前 reserve 1MiB；`:207` 在原 Opening/Q 检查之间保存七事实及 cursor/snapshot；`:212` 使用该 Session 原 Opening 和同 authority，`:221` 仅交换该 G 的 child；交换及总 finally 清 source。转移后 `:311` 经 G current 核 subject/resource/cursor，未重新访问失效 facts getters。
- Q/E 物理图互斥：`PortalRunEventsHttp.java:165` 完成实际 transport 清理后才在 `:169` 发布 qReady；`:196` 同时拒 active、重复消费及非 transportClean；`:497` 保留失败清理所需引用，等 TLS/timer/client 退出后才置 transportClean。Q-only 保持原收费，私有阶段复用同一 HTTP reservation。
- 请求与期限符合冻结 profile：`PortalRunEventsHttp.java:227` 计算 E head 的2秒/原55秒/G remaining 最小值；`:380` 给 TLS 的是原 lifetime，避免把 head 或短 checked_until 固化为 body 寿命；`:327` 只一次切 body，`:336` 用 alarmEpoch 防旧任务误杀。`:448`、`:474` 对实际 request/connection/context/TLS session 双 guard；固定私有路由、唯一 child Bearer/MIME，32KiB/16头限制保留。
- 响应阶段真正接入底层 parser：`PortalRunEventsHttp.java:680` 选择 privatePhase profile，`:640` 拒未知状态、压缩、歧义 framing 与错误 MIME，仅 E200 放开成功 CL 的8192上限。`:250` 合法 head 最后校 child/G，body 改用 G；`:263` 纯有界错误 code 与 status 白名单配对；`platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java:301` 仅追加复用既有严格 envelope 的短 code 解析，不产生许可。
- F1 与保留费用符合要求：`PortalRunEventsHttp.java:271` 单一 actual entity guard input，`:276` 单 decoder，`:283` 一包同步 callback、前后 G 检查及 try-with-resources packet close；`:587` decoder/borrow 尚活跃则拒绝最终释放，`:602` 才释放 privateFee/HTTP/Owner。没有 receipt 接受、公开 cursor 推进或浏览器输出。
- ⚠️ 跨任务/产物门槛不由本 diff 证明：完整623名字/字节、其余618不变、原STATUS前6168B、原14测试断言字节、全部9551 owned 条目、patch首次 check/apply、target有限155→logical2239、60 child 实际退出及八索引由 root 的独立审计闭合。diff 显示的三改两新与旧测试唯一 ownedRoot 修改符合范围，但不替代完整产物证书。
- ⚠️ 本次只认可冻结的有限组件收费模型；1MiB+128 与互斥3MiB的源码使用未见新增无界容器/第二HTTP图。TLS/JCA/JVM/native/common内部图及受控 consumer 自身图不在全量容量证明内；98715562B/Owner 的后续组合以及 c1 扫描前释放纪律仍待下一任务。`docs/evidence/iam-stage-4-o2b-f2b2-private-http-report.md:62`、`:120` 如实保留这些边界。

### Strengths

- 授权生命周期拆分正确且接口收窄：`PortalRunEventsHttp.java:193`、`:207`、`:311` 将原 Q 的短期读取、真实 G 转移、child 建连及长流 current 检查分开；没有把匹配字段当作外部 proof 注入入口，也没有每包续验短期 child JWT。
- 取消后的迟到结果有归属：`PortalRunEventsHttp.java:213` 将返回 G 保留在 Session，`:300` 在 active 退出后帮助完成已请求的关闭；`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsPrivateAuthorityTest.java:28` 使用真实 G 和时点 barrier，检查迟到 G 不再可 capture、零 child exchange/HTTP bytes。
- 生命周期失败路径覆盖实际工作：`PortalRunEventsPrivateAuthorityTest.java:66` 覆盖真实握手/writer 处失效，`:79` 覆盖正在 read 的 G 撤权，`:86` 覆盖 callback/borrow 保留与第三 Owner 拒绝，`:96` 明示物理 close 测试的机械 socket 门槛；没有把 Future.cancel 当退出证据。
- 私有输入的真实正反路径较完整：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsPrivateHttpTest.java:80` 使用 issuer 签名前有限 padding，`:102` 检查 child 实际到期后新 G generation 继续消费，`:111` 覆盖43个 raw拒绝向量，`:132` 覆盖21345B CL/chunked、坏commit/截断，`:153` 等待原55秒且未改 t0。普通8482B与额外8559B样本遵守 root 澄清，不把历史8495B当协议下限。
- 最终证据与历史阶段分开：final raw 的 `:54`、`:63` 是 gateway20main/19test 的真实新编译；`:73` 至 `:82` 为14+8+9=31，0F/0E/0S。三份最终 XML 的 testsuite（各`:2`）与实际 testcase 数均相符；final source.json 实测 SHA256 为 `c2dd5086c9718098dc0957aa7f0dd3e7c809f56793d7eaea1b53e3987262c246`。旧8/9成功属于不同完整 epoch，没有拼成 final。

### Issues

#### Critical (Must Fix)

- 无。本任务完整 diff 与下述具名依赖检查未发现 Critical。

#### Important (Should Fix)

- 无。未发现应阻断本任务源码 gate 的行为、规格或维护性缺陷。

#### Minor (Nice to Have)

- M1 — 测试输出仍有诊断噪声：`.local/iam-o2b-f2b2-private-http/stages/iam-stage-o2b-f2b2-final/diagnostics/2026-10-21T20-33-00_306.dumpstream:2` 报 absolute classpath / different root；同阶段 `raw.log:55`、`:64` 有 deprecation 且 Windows 文本按 UTF-8 显示乱码。最终 XML/exit 不受影响、作者未隐藏，故非本次源码阻断；后续在 runner 专项统一编码和 classpath 处理并定位 deprecation，保留原始字节，不采用日志提示去绕过检查。作者报告`:101` 记录全轮12份诊断，全部数量/身份仍由 root核验。
- M2 — 新增并发测试的长单行降低失败定位和时序审阅效率：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsPrivateAuthorityTest.java:91`、`:100` 和 `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsPrivateHttpTest.java:156` 将等待、故障注入、多项断言及清理压在同一行。测试确实验证行为，非空断言；建议后续仅格式化为操作/断言/清理分行，保持 barrier 与异常断言不变，无需为此扩大框架或重跑本轮成功矩阵。

### Focused dependency checks and evidence

- 具名风险“G.close/自动撤权与 Session 锁形成反向回调或 join”：读 `admin-common/src/main/java/cn/agentplatform/common/authority/GatewayPortalEventsResponse.java:11`、`:14`、`:23` 及完整35行；`PortalEventsResponse.java:22` 至`:148`；`HttpCurrentAuthorityClient.java:1168`、`:1240`至`:1283`、`:1330`至`:1421`（中间通过具名符号定位）。holder.stop 在`:139` 释放 holder 锁后才取消 task/slot；slot 的 cancel.run 在工作任务中、在 slot monitor 外；EventsTask.run/finish 不在 task monitor 内执行 callback/renewFinished。未发现与 `PortalRunEventsHttp.java:591` 的 Session→holder 路径组成反向持锁循环。G.close 仍不提供 common任务实际 join，未将其算作本任务已证明。
- 具名风险“短 head caller deadline 冻结长流 / idle未落实”：窄读 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java:42`至`:82`、`:260`至`:315`。hardDeadline 固定 callerDeadline，expire 同时检查55秒/握手2秒/实际投递15秒idle，实际read更新 lastDelivery；与新的 E lifetime 参数及独立 head timer一致。未重做 TLS/provider/JAR 调查。
- 具名风险“packet.close 失败后漏掉 borrow 或退费后仍可读”：窄读 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoder.java:21`至`:96`、`:177`至`:222`。readNext绑定同一 input并拒上包/上次read；finally释放scan但保留已交付 wire；packet有borrow时close抛错而保留decoder.packet，decoder.close亦保留，segment.close实际退借用计数/费用。这支持 Session 的保留与重试路径。
- 核对 final raw.log 全文、三份 XML 的 suite属性与全部 testcase、final source.json身份及stage catalog的 selector/epoch/exit摘要。catalog 初次整份输出发生截断，随后仅提取上述摘要重新完整核对；未声称完整审阅其所有 compiler/target条目。final-observations仅作为作者汇总对照，未以汇总替代 root 的60 child独立审计。
- 核对行为 RED 原始材料：`.local/iam-o2b-f2b2-private-http/peers/errors-563a64ae-30d3-4255-a377-f8bb9d3355c8/mapping-0.properties:1` 为真实413→503、8724请求字节；`peers/late-g-6a66b138-1087-489e-b72a-b2fe8659ee84/client.out:1` 为预期关闭异常未出现；`peers/body-expiry-6c954a83-eec8-48a0-a085-a7460a0fc4d6/client.out:1` 为合法head后消费失败并保留关闭后的suppressed诊断。body错误源归因结合最终修复及作者历史说明，未另读旧源码 epoch来重建因果。编译/selector/8482目标断言与Python GBK历史失败没有被当成wire业务RED。
- 未运行测试、Maven、PG、provider探针、Git replay或全量hash；没有读取相邻旧工程。所有shell cwd为固定新工程根；只写本审查报告，未提交，source/target/作者owned证据保持冻结。

### Assessment

**Task quality: Approved**（源码任务 gate，0 Critical / 0 Important / 2 Minor；产物 gate仍由root独立闭合）。

**Reasoning:** 原Opening到同G/child的内部顺序、物理Q/E互斥、阶段deadline、实际parser与一包借用退出路径吻合冻结要求，最终31项原始测试证据支持这些行为。当前范围没有公开输出许可；全容量、common内部任务join、生产全链及后续c1/c2均未被本结论覆盖。

**Review handback:** 本报告保存后冻结，fresh reviewer读审与报告写权交还root；不自行修改作者实现或补跑成功阶段。
