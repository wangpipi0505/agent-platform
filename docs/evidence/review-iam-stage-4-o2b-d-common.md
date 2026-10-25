# O2b D common 独立任务评审

结论：**Issues found（非阻断的继承测试/过程证据问题）；功能规格 Spec compliant；Quality Approved；D task gate Approved，C0 / I0 / M3。** 此结论仅针对冻结的 D common 切片，不是全分支合并批准、全套测试通过或 E/F 上线批准。最终实际结果永久保留为 **87 tests，86 passed，1 failure，0 errors，0 skipped，exit 1**。

## 审查身份与范围

本 reviewer 是作者交还写权后的 fresh independent reviewer。先完整读取 `.local/iam-o2b-d-review-instructions.md`、正式 brief、decisions、receipt-export supplement，再读作者 report；旧 decision draft 只读取 §3 API 与 §8 矩阵。作者报告作为待核主张，不作为通过依据。

完整上下文唯一代码输入：`docs/evidence/review-iam-stage-4-o2b-d-common-package.diff`，**249885 bytes，SHA256 d132a74008e98e777bf3aae412e1523558f0535b515c5c4811bd811ef91b7134，2940 split lines（含末尾空项）**。按 1–350、351–700、701–950、951–1250、1251–1600、1601–1840、1841–2090、2091–2340、2341–2550、2551–2750、2751–2940 不重叠区间完整读至 EOF；没有另读八个 changed 源文件，没有 git、测试、PG 或 replay 执行。

包内是 5 production + 3 test，新增 1 production + 2 test；source manifest 600→603、delta 8。对 immutable/生命周期/fixture/测试的判断来自完整 package。物理名称、bytes/SHA、私有 Git、target、inventory 和索引边界采用 root 独立有限审计交叉支持，不把编译 inputFiles 当运行证据。

为具名风险只做以下 unchanged 接缝窄查：

- `AuthorityProperties.java` 的 audience 配置/校验邻域：确认 `validate` 第 47 行强制 `urn:agent-platform:service:<serviceId>`，因此 continuation 验证使用配置 audience 仍满足固定 G/engine audience，外层 decision 使用配置 audience。
- `AuthorityContext.java` 的 `requireFirstBound` / `claimPortalEventsOpening` 邻域：确认真实 current mapping 与首锚点绑定、每 frame 一次 claim。
- `PortalEventsResponse.java` 的 `isSuperseded` / owner-bound `remainingNanos` 邻域：确认 wrapper 复用原 holder 锁内 current/owner/generation 分类，receipt 的前后 owner/live guard 是真实接缝。
- 未修改的 `RunObservationSelectorsTest.java:1–26`：仅核最终失败的继承断言；其 before/after manifest 与实际文件一致，5714 bytes，SHA256 f5224ed46f0673cecc30f511c8f67e112f4eb8323ea2ec819c40c092aa9d8d78。

## 规格核对

**Spec compliant（功能/范围），过程偏差见 M2/M3。**

1. G 固定 action/target/caller、delegated/PORTAL/PORTAL、严格 run-snapshot、两个新 profile、五个新 operation，以及 continuation/child-open/receipt 的 typ/use/aud 均与正式决策一致。G 只走新的私有固定 validator 和 actual authorize。公开 `requirePurpose` 与旧 A 规则在 package 中保持，未添加 catalog/ingress route；普通 exchange/client 不产生 G 本地能力。
2. begin 在真实 Portal decode/Q 前建立单调锚点；subject 来自实际验签和 mapper。Q 是查询授权，G 使用原 source 单独 exchange + actual authorize；同 subject/origin/source digest、不同 grant/operation 经私有 immutable 与正常 issuer 状态闭合。G 上界来自真实 source ceiling，不被 Q 候选 roles 错误收窄。观察者认证不必等于历史 run 认证。
3. wrapper 私有 parent 证据只有正常 open 可 mint，未暴露 delegate、Actor、VA、Retained、continuation 或 caller 可填写的 deadline。capture/material/generation 继续使用原 holder；fresh G/E current 各自有效，不强制两 hop 的 roles/version 一致，也不相互挽救失效。
4. child marker 在已验签 E ticket 与 actual authorize current 上逐项一致才保留到私有 Retained。standalone 明确拒 child，单侧/缺失/错配 marker fail closed；child open 要求 actual engine owner、真实 frame/current、first anchor 和一次 claim。正常 authenticated fixture 以同步状态维护每 parent 唯一 reservation/open，跨 frame 和并发 raw authenticated replay 的拒绝不依赖 client 锁。64 parent、每 parent 一 child，只在严格 hard 过期后清理。
5. association 绑定父/子 response、双方 profile/call/ticket/grant/operation/ceiling、subject/source/origin、resource/request/cursor、decision/nonce/raw request SHA 与 hard。child-open 验固定签名与 association；E continuation 签 immutable digest；G 接 receipt 时从已验 E ticket、association、child-open digest 和 receipt 的 receiver decision/nonce/raw hash 重建 E immutable 并复算 digest，没有将任意 caller DTO 或摘要直接视为 capability。
6. receipt 导出前后用 actual owner/live guard，slot.response 同对象、私有 engineChild mode、正常 child_open 存储的唯一 receipt；本地只读、无网络/重签/续期。G 只接受其正常 reservation 对应 receipt，限一次尝试。先长度/ASCII compact JWS 结构检查，最多 4096 bytes，再解码验签；原 envelope 限制未扩大。
7. Opening 五秒自动过期/close/异常及同一 slot 原子转移；G hard 取 begin+55/source/grant/op 最小，E 受 parent hard、自身 source/grant/op/receiver 首锚点约束，预算不能刷新增加。共用原 64 slots、2 workers、64 queue、单 scheduler。active task 在实际 run finally 才归还 work，Future.cancel 不是物理退出；queued-never-run/late return/close/shutdown 保持 fail closed。服务 JWT 到期只禁止新受理，已合法 holder 可用专用 check 到原 hard。

## Strengths

- 正常成功路径是实际签名 HTTP/JWKS、actual authorize 与包内真实 Retained frame；反射/重签仅用于诊断、状态观察或负例，没有用 currentOnly/裸 VA 制造正例。
- 原 A 的 28 项生命周期/签名/续核回归、closed purpose 2 项、ServiceAuthorization 14 项在同一个最终 epoch 真实执行并通过；public selector 的唯一失败可追溯到未修改的旧断言。
- 新测试实际覆盖独立 current、receipt production export、跨 parent/child/frame、issuer 与 client 双重单次限制、容量、自动 Opening expiry、真正阻塞退出和晚到结果。没有用 fixture 放宽任意 owner 或签名 gate 取得成功。
- 作者保留了编译失败、协议 envelope error、功能断言失败和最终遗留失败，未把错误结果重写为全绿；最终不同 patch 的真实回放与 early incident 清楚分开。

## 真实执行证据

逐阶段读取 `command.json`、本阶段 `raw.log` 的实际 Running、对应 XML 的 DOM testcase（包含 self-closing testcase）及 source-epoch manifest；XML testcase 数与 suite counts 一致，方法名与冻结 test-results 的本阶段对应记录一致，XML/source SHA 均核对。编译两次失败无 Running、无本阶段测试报告，计为 0 executed，绝不计入功能 RED。下表 SHA 为实际 source epoch SHA256；各 stage 位于 `.local/iam-o2b-d-common/<stage>/`，均以 `iam-stage-o2b-d-` 为前缀。

| stage 后缀 | 实际执行及 T/F/E/S | exit | source epoch SHA256 |
| --- | --- | ---: | --- |
| focused-red | G: realPortalAndQueryMustOpenIndependentGatewayEventsAdmission，1/1/0/0，缺 begin API | 1 | 8eeb7ff2d93ece6ab3080b5df4f1fe69e5eb64e1271c9f8d528c9d62d9259868 |
| focused-green-1 | 相同 G 方法，1/0/0/0 | 0 | 0335797dd266e80ea72491cd3693f8eec90b55611a914025d305279e81ffd196 |
| child-focused-1 | E: actualChildOpenExportsReceiptThroughProductionSeamAndBothHopsRenew，1/0/1/0，unavailable error | 1 | d2b01ff9bcb0966e1ff9437b962b4370276bca6d90515cb078e1c25640347127 |
| child-focused-2 | 相同 E 方法，1/0/0/0 | 0 | 31cd89b535dc1fc72a50c96b23aeae0a565a36619559b67e2d74c686e7904b28 |
| matrix-1 | 无 Running，test compile error，0/0/0/0 | 1 | b72483c01b3f27bfb6992fc0ae90dcd47f269a6ddcfcf9688d135599c118863e |
| matrix-2 | 无 Running，test compile error，0/0/0/0 | 1 | 8c7bc4f96a3d70b1cbff65c921a27a9aea34afbfef825098460e5fe9a6212ade |
| matrix-3 | G16+E16，32/1/0/0；失败为 internalSignatureOutageAndEnvelopeTypeDoNotBecomeBadOriginalPortal 的 503/403 分类 | 1 | 1764482782068c332506a76ceb48070fac165cef4e7e827aac1883a04cb414a1 |
| hardening-red | gatewayGrantMayDifferFromItsIndependentQueryCandidate 为 1E；expiredChildExchangeProofStaysTerminalAfterWallRollback 为 1F；合计2/1/1/0 | 1 | 7ba83af73a6d864fed645a432a29b8799d14bbc9c4365cb8d237bc6b38e93b2f |
| hardening-green | 相同两方法，2/0/0/0 | 0 | 00ee5140bbc0d82d1c9f29d4a6a7005b98fc37eb32cb60eeedf602ef1bf6d498 |
| receipt-binding-red | receiptCannotChangeValidAdmissionHashWhileKeepingItsImmutableDigest，1/1/0/0 | 1 | 6ff7d5789dd958cf0a92ca7b518995f3d9abe5264f56b1ad25c10fc82da94e93 |
| receipt-binding-green | 相同方法，1/0/0/0 | 0 | 5d8ad029afea93489f6fab9e6b54b32da008e0ea156120eb7bea8e1c44f4dfe1 |
| marker-red | standaloneCannotIgnoreAnySignedChildMarkerEvenWhenCurrentOmitsIt，1/1/0/0 | 1 | e189d99c9c150980e2eead1c67427ef882b09ff3e38c5e739c209ccd1b171bdd |
| marker-green | 相同方法，1/0/0/0 | 0 | 51628ee3f9b3082239bb795816a0a4861280df1baa27d41f509bd58c686718a3 |
| matrix-4 | G18+E22，40/1/0/0；lateActualChildOpenHoldsItsSlotUntilExitAndIssuerDoesNotReopen 为 TimeoutException/ExecutionException 断言差异 | 1 | a5e6f8fd7d1333542e2179f0b4ec0467cd55336b954fc364da39334ec984c4c3 |
| late-child-green | 上述相同 late child 方法，1/0/0/0 | 0 | ab4a97cab7548de056a5548e129b49c6e19c0c4692ac82fa8f4857e19ec0087c |
| final | 实际六类，87/1/0/0，86 pass | 1 | ab4a97cab7548de056a5548e129b49c6e19c0c4692ac82fa8f4857e19ec0087c |

最终六类实际 XML：GatewayPortalEventsAuthorityTest 18/0F、PortalEventsChildAssociationTest 22/0F、PortalEventsAuthorityProfileTest 28/0F、PortalEventsReceiverClosedTest 2/0F、RunObservationSelectorsTest 3/1F、ServiceAuthorizationTest 14/0F，所有 E/S 均 0。新两类 40 项通过不等于整个 87 项通过。final raw SHA256 为 1c892e8efd85f29b7dd4a99f7bca2a3ca9342454659bb05d7b32d4ae204d1555。阶段 source 数分别为 601、602、其余 603；final 与 late-child-green 同 epoch。

## C / I / M

**Critical：0。Important：0。Minor：3。** 以下均非本 D task gate 阻断；M1 是继承问题，M2/M3 是证据过程缺陷，不能因批准而隐去。

### M1 — 继承 selector 断言与 Accepted C 目的规则漂移

位置：`admin-common/src/test/java/cn/agentplatform/common/authority/RunObservationSelectorsTest.java:14–15`。

触发/后果：拒绝列表含 `proxy.engine.get.RunController.events`，但 package 中未改变的公开 `requirePurpose` 明确接受严格 engine STATUS/EVENTS。最终实际 XML 在该方法第15行报 Expecting code to raise a throwable，导致最终精确六类 exit 1。before/after manifest 和实际 SHA 相同，package 也显示 D 只新增 private G validator，未改变 public method，故不是 D 放宽权限造成的回归。

最小修法：按 root 已划定的下一独立任务，仅迁移该旧 selector 测试以匹配 Accepted C 的严格 STATUS/EVENTS 规则，保持所有错误 caller/source/resource 等负例，另做独立 review 与三个方法的具名验证。永久保留原 final 87/1F，不改本 D 旧测试、不重复六类或补报全绿。由于范围冻结明确不许本 D 修改旧测试，且归因已由实际源码/manifest/XML 交叉核实，列为非阻断继承 Minor。

### M2 — 首个 raw patch 的独立 check/apply 命令证据未即时落盘

位置：`.local/iam-o2b-d-common/incident-first-patch/helper-at-incident.mjs:34–37`；事件记录 `.local/iam-o2b-d-common/incident-first-patch/incident.json:1`。

触发/后果：helper 先在内存记录 check/apply，再执行后续 add，直到最后才写 apply records；init/add 尚未真实结束时开始后续 patch，add 的 exit128 使最后持久化未发生。所留 helper 控制流与 incident 中 f02208 / line35、原 3035B patch、init 记录、applied test 的叙述相容；但缺失的独立 apply stdout/stderr/exit 不能由这些材料替代或事后重建。后一次空 diff 断言发生在 check/apply 前，也不构成第二次有效回放。

最小修法/处置：原事件只保留并明确证据边界；未来 helper 必须等待 init 真正退出，并在每条实际命令完成后、任何断言/后继操作前持久化。不可重跑原 patch 来伪装补齐。最终 132241B 的不同 patch 有独立 check=0/apply=0 记录，final-replay-source 与 final epoch 一致，由 root 有限审计确认 603 项物理精确、无 apply 后 copy/commit；这是新的验证，不补旧记录。当前最终状态可复核、缺口公开且未用于宣称首次证据完整，故按实际剩余影响为非阻断 Minor，而非无偏差。

### M3 — compiler 清单按 JSON Buffer 保存，归档格式与 .lst 扩展名不一致

位置：`.local/iam-o2b-d-common/incident-first-patch/helper-at-incident.mjs:13,48`（同类 write(Buffer) 调用模式）；最终归档 `.local/iam-o2b-d-common/iam-stage-o2b-d-final/compile-default-compile-createdFiles.lst:1` 及其余三个 compiler 清单。

触发/后果：write helper 对非 string JSON.stringify，Buffer 被保存为 `{type:"Buffer",data:[...]}`。直接以行数/文本解析会把 JSON 数字行误当编译候选，不能拿归档 bytes/SHA 当原始 compiler 文件 bytes/SHA，也不能把 input 列表当实际 Running。

最小修法/处置：新阶段归档使用原始字节复制或显式 Buffer 分支；本次冻结归档不改写，消费者按 data 精确恢复字节再比较。root 有限审计已确认四份最终列表解码后与 target-overlay 对应原始 *.lst bytes/SHA 全等，得到 production created106/input58、test created89/input31；213 overlay→2118 logical 和14 diagnostics分离通过。因此当前证据可无损恢复，列非阻断 Minor。上述物理恢复核验来自 root 审计，本 reviewer 不重复遍历 target。

## Root 有限审计交叉支持与未验证边界 ⚠

已读取 root 的 `.local/iam-o2b-d-root-check.json`（8764B，SHA256 854a6785b5bf8d0a83a4018c9da6195f3556442669c14a67fc68eb41ee71250d）。其五阶段 PASS 涉及 own6245 仅自身排除、603/delta8 与最终不同 patch 单次回放、16阶段真实执行、213 overlay/2118 logical/14 diagnostics、八索引未变。root 的 6476 distinct physical hashes 是 root 的有限审计结果，不冒称 reviewer 独立重做。

- ⚠ 早期 init 与 patch 交错的具体时序/工具 chunk 只依据保存的 incident/init/helper 材料；原首次 check/apply 独立 raw 缺失仍不可验证，见 M2。最终新 patch 不能证明早期记录完整。
- ⚠ 不验证生产外部 issuer 的部署或真实多实例原子存储；当前正常 HTTP fixture 验证固定协议与单 parent 唯一 child 状态，生产 issuer 支持仍是前置。
- ⚠ 实际 Q metadata HTTP、七字段解析、gateway EVENTS ingress、真实 engine HTTP receiver、PG/SSE、TLS/mTLS/secret_ref、完整私有 wire/manifest/字节预算、Servlet writer 未由 D 验收，留 E/F。签名 association/receipt 不是后续任意 material 字节认证。
- ⚠ 新测试对 receiver 使用实际 Retained 的包内 bindRetained seam，不能外推成真实 engine HTTP 成功；阻塞测试证明本地物理任务退出所有权，不保证对任意 peer 强制断开或撤权 SLA。
- ⚠ 本报告为 task gate；M1 独立迁移及统一索引收口仍由 root 后续处理。本 reviewer 未编辑 source、target、索引、作者证据或旧 report，仅写本报告。

最终判定：**Approved（D common task gate，C0/I0/M3）**。保留原最终 87/86pass/1F/0E/0S 和 early replay incident；不要求本 D 重跑已成功阶段或擅自修改超范围测试。
