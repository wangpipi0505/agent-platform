# Spec Compliance

**❌ Issues found — C1 尚不完全符合固定费用模型。Task quality: Changes required。Critical 0 / Important 1 / Minor 2。**

本次为冻结 C1 的独立 task-scoped spec + quality review，不是 whole-branch/merge review。基线为 Accepted B2 source623，候选 source627；没有 HEAD/commit 区间。正式 brief、三份完整冻结要求、Accepted API supplement、test-observation clarification、generation-checkpoint clarification、具名风险输入及 SDD task-reviewer-prompt 已读。完整作者报告已读，17803B / SHA256 `5250dd03f99d6f20af36aee0605099c92d89eaa457bd57e1c8b8b961771592ad`。

完整代码包 `docs/evidence/review-iam-stage-4-o2b-f2c1-material-package.diff` 实核 147492B / SHA256 `35e7bdd21a754d16e6138c2b4ec4275b63f03eed5b7647809d956efcdfab0456`，依次读取不重叠行段 1–350、351–700、701–1050、1051–1400、1401–1750、1751–2050、2051–2300、2301–2550、2551–EOF。PowerShell 返回 2774 个内容行，末尾换行产生 metadata 所记 2775 个 split 项；没有遗漏代码。四个 Java 文件均为完整上下文，未另读 changed files 或重建 diff。第五项 fixture 只读完整身份/提取 descriptor，不把 base64 当成已审代码或已验证语义。

下列简称均指实际源文件行号，由上述完整 diff 的新文件行号定位：

- V：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifier.java`
- H：`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java`
- A：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsMaterialAuthorityTest.java`
- T：`platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifierTest.java`

## 已核实现要求

- **同 Session 的真实来源：符合。** H:197–234 的唯一新增入口先 reserve，再构造私有 MaterialContext；context 读取本 Session 的 saved/G/packet，没有外部拼接事实的 public permit 工厂。H:249–266 保存原 Q 七事实后真实转移 G、exchange child。V:145–149、223–265 交叉核原 Q、G subject/resource/cursor、owner/root/version/两 SHA；历史 initial/provenance 保持独立，不替换成 observer 来源。
- **HELLO receipt：符合。** V:114–144 对同一 ASCII 原串恰一次调用同 G.acceptChildReceipt，然后才解码完全相同的串、严格解析 claims、比较 parent/child 并固定 child。H:222–224 与 common 实际一次性 receiptAttempted 路径相合；F1 已有 HELLO 序及原串结构限制仍继承，不能把解析 claims 代替签名验证。
- **严格 schema、精确 union、完整 parents 与 floor：符合已审代码。** V:154–220 固定 14 键并逐 scope 解析，V:681–858 定义完整 nested shapes/type/duplicate/unknown 规则。V:267–310、499–510 按三 roots + state/event + 二者直接父核精确集合、完整 parent 顺序和 revision，不递归扩张闭包。V:279–310、536–581 重算所有 scope digest、root provenance/initial、两目标独立 floor；全部 scope 都进入 V:65–83 的同 Capture.requireMaterial。三 root payload 未被声称重新取数或全部重算。
- **顺序 pass 与本地 profile：除 I1 外，结构与准入模型基本吻合。** V:51–53、56–112 为 P1/P2/P3 加至多两次 P3；V:426–489 固定 primitive 4096 项/8192 slots，探测有界并定期 live。V:169–175、196–201、513–534、732–784 为局部 scope/initial/persistent/parser 容量，V:859–897 先扫描 payload 后构建精确值图。没有全 metadata DOM、charOffset 冒 byteOffset、按 scope 重扫全 metadata 或 64 父截断。P1 实际保留当前一个受限 Map/List scope 源模型，并非零临时模型；其生命周期对应 C 块，未观察到全 scopes 常驻。
- **业务 canonical：符合所核 E 语义。** V:312–326 核 state/event material.payloadSha256；V:918–1043 在展开前限 number lexeme/scale/output cost，以 Java String 排序、数组保序、精确 BigInteger/BigDecimal、stripTrailingZeros、零归整、流式 digest 实现。V:733 启用补充 Unicode surrogate 合并，和已核 E Writer→UTF8 路径一致；T:26–48 使用手写字节/JDK digest，不使用待测 helper 生成 oracle。V:706–708 将 output_ref 当 nullable string；不存在 Java UTF16 256 长度误限。event type 不裁 64 UTF8B，A:275–293 的 32 CJK/96B + null 正例实际通过；这不是 C2 SSE 注入策略验收。
- **代际：符合。** V:56–112 每次重新取得真实 Capture，完整重走 scope digest/current/floor/business；旧结果没有重贴标签。H:219–221 的 checkpoint 在调用前后 live，调用 hook 时不持 Session/Owner/holder monitor，不传权限对象，不新增任务。A:364–415 分别在实际 scope slot0、slot34 和 business digest 后调用同真实 client 续核，观察 superseded；两次换代成功、第三次持续换代有限失败。A:234–273 的 consumer 后失效另测。
- **内部借用/退出：实际退出保费路径正确，但 writer 并发准入有 I1。** V:346–424 私有句柄、close 后拒访问；close 用 wait 释放自身 monitor、拒新调用，并等待 activeWriters 实际归零，interrupt 不冒充退出。H:197–206 的 material token 在 consume 返回后关闭，早于 H:319–328 下一次 F1 readNext。未新增 public SSE、Servlet/controller/assembly、公开 cursor 推进或公开终态。CAPACITY/HELLO/EOF 不回调 window；reset/drained 仅内部描述。

## Cannot verify / root 必须独立闭合

- fixture 262079B 包装、198285B 冻结 E 来源、195843B metadata/58B payload 的真实提取、段身份、Maven copy 与语义：代码只核常量身份；本 reviewer 未解码/读完整 fixture。根审查应闭合 package descriptor:9–44 对应资产链。
- 完整 before623/after627 名字字节、其余622不变、五源码副本及 epoch 对应、finite target/历史 logical overlay、private Git blob/index/属性、首次 plain check/apply 与 replay、全部49历史 child及最终14 child实际退出、owned inventory 和八保护文档：交 root 机械 gate；本报告未运行这些检查，不以源码通过替代结果。
- 当前 raw/XML 的 19 项可证定向矩阵通过，不能证明全部 profile 最大组合、全部 IAM/backend 矩阵、生产容量或真实 engine PG 最新行/RR/membership/root payload。common/JDK 的全部对象布局、mapper 全局缓存、GC 垃圾、TLS/JCA/native/线程栈/全 heap/RSS 均不在该 80MiB 工程模型证明内。
- C2 每次真实 write/flush guard、Servlet/async complete、浏览器字节和完整双 JVM/PG 链仍未验收。保留旧 F1 ignored.local 可移植性及 B2 diagnostics 至最终 whole-branch review；本轮不扩写旧测试/runner。

# Strengths

- **拒绝点有实际分层。** A:138–159 先重算变异 scope digest 和引用，A:644–662 重封段 SHA/COMMIT；A:693–705 同时断言具体拒绝 code/detail，避免把坏 wire 的早期拒绝当作 floor 或业务摘要覆盖。
- **真实 receiver/授权链保持完整。** A:473–494 的 Proxy 只原样委托并观察真实 G，解包 InvocationTargetException；A:590–632 使用实际 Bearer→TrustedJwtDecoder→已认证 token→ServiceAuthorization→retained initial→真实 E response/receipt，并在 finally 恢复 request/security。未见私有反射或自签/预消费 receipt。
- **生命周期修复有有效回归证据。** A:295–362 将阻塞放在 writePayload 内部的 writeString，consumer 返回后两 Owner 仍持费，第三请求被拒，实际 worker 返回后归零。等待使用释放 monitor 的 wait，控制线程无需固定亲和。
- **canonical 的独立 oracle 有价值。** T:26–48 覆盖高精度整数/小数、指数、负零、Unicode/control、null、排序和数组保序；最终原始 XML 与实际 Unicode RED 相互对应，未用 wire SHA 偷换业务摘要。

# Issues

## Critical (Must Fix)

无。

## Important (Should Fix)

### I1 — 同一 window 的并发/重入 writer 没有准入上限，单次费用不能覆盖其临时图

**位置：** `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifier.java:394`（尤其397–402），同文件:1001–1027；`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java:201`。

`writePayload` 在短 synchronized 块中仅 `activeWriters++`，随即在锁外执行 `write(generator, v.payload)`，既未拒绝已有 writer，也未额外 reserve。一个仍有效的 consumer 可以把同一 window 同时交给两个或更多受控线程；generator 回调也可以重入同一 window。每条执行都会重新建立 object key/sort 数组和 BigDecimal/BigInteger/字符串转换临时对象。这些是 **V 自己的 canonical 工作图**，不是被排除的调用者 generator 或线程 native 开销；它们可以同时存活，而 H 只持有一份80MiB，F 预算明确只覆盖一次遍历/一个 scalar 转换。

`activeWriters` 的计数等待解决了提前退费，但不能限制 live 图的倍增，故固定输入/固定次数的收费前提不成立。A:295–362 现有 retention 正例是每个 Owner 一个 writer，只证明退出保费；它不回答同一 window 多 writer。该缺陷属于 C1 当前实现，不需等 C2 公开发布才成立。

**建议：** 保留跨线程使用能力，在进入任何 payload 遍历前原子限定单个 active writer，并对并发/重入调用 fail closed；或实现确切有界、逐 writer 预收费的准入。不要通过线程亲和或持 window/Session monitor 跨 generator 回调来修复。closing 之后仍须拒绝新 writer，已有 writer 的 token 必须留到实际返回。

**供 root 安排的必要定向验证：** 一个真实 window 内让首 writer 阻塞在实际 writeString/writeNumber，再尝试第二 writer，证明第二条路径在遍历/临时图分配前拒绝或已另行准入收费；另用 generator 回调尝试重入并验证有界拒绝。保留首 writer 跨线程合法使用、close/interrupt 保费和实际退出后归零断言。本 reviewer 未执行新测试。

## Minor (Nice to Have)

### M1 — 新 schema/factory 配置过度压缩，增加容量与字段审计成本

**位置：** `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifier.java:689`、同文件:733。

新增完整 schema 声明横跨多组不同对象，但多数构造被压缩在连续长行；factory 的 Unicode、recycler、duplicate、全部 profile 也集中在一个长表达式。这里直接承载本任务的安全字段集合和容量策略，后续改一个字段/限制容易漏看。建议在本文件内按 Shape 分行、将 factory 链按单项配置展开；无需扩大文件 allowlist 或建立通用框架。本项不因1045行文件本身或计划要求单文件而升级为 Important。

### M2 — 最终编译输出仍有乱码和 deprecation 诊断

**位置：** `docs/evidence/iam-stage-4-o2b-f2c1-material/stages/final-candidate/raw.log:55`、同文件:64。

实际 javac 信息中的中文路径/说明不可读，且有 deprecation 提示，不能称输出 pristine。它们指向既有 IntegrationPolicy/GatewayPublishedChainPostgresTest，不据此声称 C1 新增弃用调用；本轮按 Accepted supplement 保留证据，后续单独处理 runner 编码/具名弃用诊断，不为美化日志重跑已成功阶段。

# Named dependency checks

仅围绕以下具名风险读取 unchanged dependencies；未爬全仓库、未读取旧工程或其它业务 JAR：

- **真实 receipt 是否一次性关联而非解析授权：** `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:770–790`，确认 receiptAttempted 在验证前消费、同已交换 child association/immutable 校验；`GatewayPortalEventsResponse.java:1–35` 确认委托及 owner 身份。
- **旧 Capture 是否真实失效、当前权限是否核每个 scope：** 同目录 `PortalEventsResponse.java:20–48,190–201`，确认 evidence/generation/holder 精确匹配、superseded 分类与 MaterialAuthorization.validate/cover 前后 live；`MaterialAuthorization.java:1–50` 确认 labels 是义务，roles/entitlements 的 coverage 不被误当 floor。
- **scope/common helper 是否保完整 parents 且不同于业务 numeric canonical：** 同目录 `AuthorityProtocol.java:39–47`、`MaterialAuthorization.java:27–44`；`admin-common/src/main/java/cn/agentplatform/common/Json.java:56–80`。List 保序、Set 排序，scope fields 包含完整 parents；common Json 只递归排序，不做 E 数值 normalize。`ResponseMaterial.java:1–6` 是 record，无隐藏验证能力。
- **E 真正 canonical 路径及 Unicode：** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:606–630,723–730`；`RunEventReader.java:372–379,387–398`。实际为精确节点 normalize→ROOT.writeValueAsString→Digest UTF8；与 V 数值/Java String 排序/合并 surrogate 的实现比对。未调查 Jackson/JDK 全内部图或另运行 compatibility probe。
- **F1 是否负责 noevent/payload bound/退出与 borrow：** `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketDecoder.java:72–85,153–175,194–222`，并具名检索 MAX_PAYLOAD/SCAN_BYTES。确认262144B header准入、hasEvent/header/payload空值关系、扫描 finally 退出，以及每段 reserve256+token/close 后才返还。C1 的 parser 顺序使用与该实际 API 一致。

# Existing execution evidence checked

- `docs/evidence/iam-stage-4-o2b-f2c1-material/stages/final-candidate/raw.log:54,63,73–80,89`：真实 javac release21 main/test 均21输入，真实 Running 仅 AuthorityTest/VerifierTest，14+5=19，零 failure/error/skipped，BUILD SUCCESS。对应两个实际 inputFiles.lst 均21行。`result.json:4–8` 的 epoch为 `0802f48d8e4071cf3f80ec667bd262e7cd7cb992411f6c4217487f401f06c84a`；完整字节绑定仍由 root 核。
- 同 stage `reports/TEST-cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.xml:2,66–79` 和 `TEST-cn.agentplatform.gateway.infra.PortalEventsMaterialVerifierTest.xml:2,66–70`：实际 testcase及计数对应最终源码。未将编译21/21说成仅编译两文件，也未将19项扩称全backend验收。
- `stages/red-real-boundary/reports/cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.txt:7,28,49` 三处确为“Expected PlatformException ... nothing was thrown”；`stages/canonical-expanded/reports/cn.agentplatform.gateway.infra.PortalEventsMaterialVerifierTest.txt:6` 为 Unicode 独立 digest 不符；`stages/active-material-red/reports/cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.txt:7,14` 为 retention expected false/actual true。它们支持具名行为 RED 分类；没有把环境/编译/fixture 失败算业务 RED，完整历史 child/epoch仍交 root。
- `.local/iam-o2b-f2c1-material/peers/generations-cc6e6ef2-5df3-4bc7-a271-6616c31f6d05/{first,middle,digest,exhaust}/generation.properties:1–4`：实读分别starts3、visits71/139/207/207、changes2/2/2/3、callbacks1/1/1/0；`.local/iam-o2b-f2c1-material/peers/retention-9b91e3d3-087f-4084-bbaa-996858dd8db8/retention.properties:1–3`：held176685742、thirdDenied、actualWorkersExited。后者只是有限场景观测，不是全heap峰值或任意 writer 数目的容量证明。
- 未运行测试、Maven、PG/provider/probe、Git diff/replay或source/target全量hash；没有重跑任何已成功阶段。全部 shell cwd 固定新工程；唯一写入为本审查报告。机械 gate 不能代替 I1 的实现修复和必要定向验证。

# Assessment / Reasoning

**Spec compliance: Issues found。Task quality: Changes required。C/I/M = 0/1/2。**

同 Session 授权链、严格材料关系、完整父集合、真实 generation 重验和业务 canonical 实现有实质证据支持；窗口退出也确实等待重图工作返回。I1 使同一个80MiB token 可以对应无上限数量的同时 canonical 工作图，违反本任务固定费用/先准入约束，因此不能批准该候选；其余机械 cannot-verify 项由 root 独立闭合。结论仅适用于 C1 私有同步借用材料验证范围。

报告写毕冻结并交还写权；后续由 root 派发修复与复审。

# C1 I1/M1 修复复审（追加，2026-10-21）

**最终 Spec compliance：✅ Spec compliant（所审 C1 源码与本次修复范围）；Task quality：Approved。剩余 C/I/M＝0/0/1，唯一剩余为原 M2 诊断。** 原段落记录原候选判定，保留不改；本段是修复后判定，限定范围及 root mechanical gates 如下。

## 复审范围与输入

继承本人已完整审过的原四 Java full-context package 及原审查报告16492B prefix / SHA256 `84ad781944f9029ef0547c4f4b2774bfc247dbc032307616a3ecb98699d778e1`。本次不重审未变 full files，不重新计算旧 prefix 身份。

完整读取 `docs/evidence/iam-stage-4-o2b-f2c1-fixes-brief.md` 与修复报告 `docs/evidence/iam-stage-4-o2b-f2c1-fixes-report.md`（实核10675B / `21f63ce3a0446df6ae67d6dc6aef196460b185ac321bbf061ade646cd3a677a9`）。完整 delta 包 `docs/evidence/review-iam-stage-4-o2b-f2c1-fixes-package.diff` 实核24094B / `dd0656d588a429b0be2abc0dc8202d4f5366c41d2805dd2392a8e23cde171a4a`；按1–200、201–EOF两个不重叠范围读取全部改动及上下文。396个内容行加末换行空项对应397 split lines。未另读 modified 源码或重新生成 diff。下列 V/A 仍指本报告前述实际文件路径，但行号为修复后新行号。

## 原问题 closure

### I1：Closed

`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsMaterialVerifier.java:399–401` 在既有 window monitor 内增加 `if(activeWriters!=0)throw denied("window writer active")`，紧接着才计数成功准入。检查与计数同锁，且都在 `write(generator, v.payload)` 之前；没有任何新增排序、数值转换或 payload 递归先于该检查。该判断同时覆盖不同线程并发和 generator 同步重入。

失败准入位于原 try/finally 外，不会错误递减首 writer。成功准入只进入原 finally 一次，减计数并 notifyAll；此次 delta 未改这段释放逻辑。generator 执行保持在窗口锁外，没有新增 Session/holder monitor、排队、额度、线程亲和或公共接口。原 live()/closing 拒新调用和 close 的 wait 释放 monitor、等待实际 writer 返回的行为继承，单份 F canonical 预算因此重新对应最多一次活动重遍历。异常、consumer返回、close/cancel 不能将仍在工作的一份图提前退费。

定向测试的强度与 I1 对应：

- A:302–377 的 `singleWriter` 使用真实 fixture/Session/receiver链，首 writer 在另一线程的实际 writeString 内等待，第二线程尝试同 window。A:331–345 在2秒上界内断言特定拒绝码/原因、第二 generator 首次 writeStartObject 调用数0、首 future未完成、Owner费用不变；没有等待首 writer释放后再让第二 writer成功的假通过。A:346–355 随后释放并等首 future实际退出，再以实际 IOException验证成功准入的异常清理，最后顺序写入成功。
- A:378–422 的 `reentrantWriter` 在真实外层 writeString 回调中尝试同 window，再断言相同拒绝、内层 generator首次调用数0和费用不变；外层正常返回后再次顺序写入成功。没有递归生成更多 writer或伪造 window。
- 原 retention测试内容未改，本次受影响矩阵实际再次运行该方法：每个 Owner一个阻塞 writer，consumer返回与Session关闭后仍持费，release后实际退出归零，补充证明单writer准入未破坏原close生命周期。

最终原始 `writer.properties` 已实读：并发和重入分别 heldBytes88342871、generatorCalls0、外层/跨线程及顺序写入完成；retention原始属性为176685742、第三Owner拒绝、实际worker退出。此为具名有限场景，不外推为全heap或所有并发交错的实测证明。源码同锁准入与原finally使 I1 闭合，无需增加更广矩阵。

### M1：Closed

V:691–791 的14组 SCHEMA按Shape及字段对展开，V:809–826 的factory链按选项展开。逐段对照 delta 中删除/新增内容，字段名、类型、顺序、Unicode surrogate选项、禁recycler/canonicalize/intern、启用duplicate、禁source-location、depth/token/string/name/number/document各限制均保留。没有增加schema条目或改变规则。`.local/iam-o2b-f2c1-fixes/format-only.json:2–11` 的14 entries、非空白序列相同记录与所读diff一致；本判断来自实际diff核对，不只采信该摘要。原M1可读性问题已解决，未引入框架或额外source。

### M2：Retained — Minor

`docs/evidence/iam-stage-4-o2b-f2c1-fixes/stages/final-candidate/raw.log:55–56,64–65` 仍有原IntegrationPolicy/GatewayPublishedChainPostgresTest中文乱码及deprecation提示，不能称pristine。与原审查相同，非本修复新增业务缺陷；保留至对应runner/诊断整理阶段，不要求本次扩改旧代码、关闭检查或为消噪重跑。

## 新 delta 风险与 Strengths

两路径 delta 的生产行为变化只有准入 guard及其费用注释，schema/factory是排版；未改Session/G/receipt/P3代际/原deadline或业务canonical算法。A:62仅将测试输出迁到本修复专属peers，A:296–301新增两个测试入口，main新增对应case；原receiver和资产读取逻辑继承。未发现额外Critical/Important/Minor回归问题。

修复复用了原window锁且保持锁外调用generator，准确解决费用图倍增，同时保留跨线程使用。两个新用例均从真实授权窗口触发，并以零第二generator调用区分“遍历前拒绝”和“已经工作后失败”；异常后顺序成功进一步检查了准入释放。针对性强，未倒跑原19项或扩成全suite。

## 实际证据核对（不重跑）

- `docs/evidence/iam-stage-4-o2b-f2c1-fixes/stages/red-single-writer/reports/cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.txt:7,16` 为真实generator重入处 Expected PlatformException but nothing was thrown。该stage的并发异常被转换遮蔽，不将其当精确拒点证据。
- `docs/evidence/iam-stage-4-o2b-f2c1-fixes/stages/red-concurrent-observable/reports/cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.txt:7,18` 明确第二writer已遍历、generatorCalls=1，cause为未抛预期异常；是缺失准入的行为RED，不是编译/环境/selector失败。
- `docs/evidence/iam-stage-4-o2b-f2c1-fixes/stages/final-candidate/raw.log:54,63,73–80,89` 实际main/test各21编译输入，Authority3、Verifier5，8项零failure/error/skipped及BUILD SUCCESS。对应两个XML的`:2,66–70`可核实际testcase：并发、重入、原retention和原Verifier5。原19属于旧epoch，本8属于新epoch，不能合成同场27。
- 同stage `result.json:4–8` 的epoch为 `6b4a6488efa08b27266ccceff39fd3e79b62b137a3edcc6dfd3b071abd83f68c`。本次只检查现有raw/XML及metadata，不自行重编/运行任何测试；完整source字节绑定交root。
- 实读 `.local/iam-o2b-f2c1-fixes/peers/single-writer-03e4471e-f7ce-4c45-95c7-571f785c7586/writer.properties:1–4`、`reentrant-writer-43519aba-e391-4a01-981e-7e3a9fafe6e6/writer.properties:1–4`、`retention-57a808b8-56e6-46c9-98d3-7092d1b3a9a7/retention.properties:1–3`，与上述行为观测一致。最终3个child、全部历史6个child的身份与实际退出机械门槛仍交root全面核对，不用属性摘要代替完整退出证据。

## Cannot verify 与最终限定

source627中两修改及其余625 exact、source/target epochs和副本、binary patch/private Git/replay首次check/apply、finite378→logical2269、Maven copy/common fixture JAR、全部6个child/collector/peer实际退出、owned inventory及八索引保护，由root独立delta audit闭合；本复审未运行这些机械门槛，未改source/target/八索引。没有新增unchanged dependency检查，原具名窄核及原边界继承。

80MiB仍只是一组固定类型/输入/次数的工程收费模型，未证明全JVM/TLS/native/GC/RSS；writePayload仍为归整后重序列化JSON。真实enginePG latest/membership/root payload、生产issuer、公开EVENTS/SSE/Servlet/浏览器字节、C2每write/flush检查与async complete均未验收。fixture语义与原完整机械gate继续由root继承或独立闭合，不能从修复后8项通过推出完整IAM或生产容量。

**最终结论：本次 I1/M1 均已闭合；源码 spec compliant，task quality Approved。C/I/M＝0/0/1（保留M2），无新增问题。** root完成独立机械gate后可作限定C1结论；本报告不代签公开功能或部署验收。

本追加段写毕冻结，交还review写权。旧16492B prefix逐字保留；最终整文件和追加段身份记录于 `.local/iam-o2b-f2c1-fixes-review-return.json`。