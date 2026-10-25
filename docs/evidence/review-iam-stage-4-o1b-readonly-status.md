# Spec Compliance

**❌ Issues found；Task quality: Needs fixes。** O1b 的授权分离、私有 proof、单只读 RR、全部 scope 先验、内容关系和实际 stream 后终检在本次冻结差异中有实现；但新增 buffer 未满足补充裁定的“累计保留不超过 2 MiB”，且联合链正例 FLOW 夹具不符合真实受理的连线格式。两项 Important 必须修复。此次不批准 O1b，也不扩大为 O1c/O2/RR1/RR2 或全工程验收。

**审查基准。** 唯一 shell cwd 为 `G:/207/项目/算法重组工具/代码/agent-platform`。已读本工程 AGENTS.md、docs/implementation-brief.md、contracts/README.md、指定 SDD task-reviewer 模板。未读相邻旧工程；未运行 git、测试、浏览器、应用进程、package/deploy；没有改 source/target/index 或作者证据。完整差异按 bounded batches 阅读，实际输出截断处补读；最终 source 行号从该 diff 的 hunk 位置计算，没有另读更改文件。

四份输入的实测 bytes/SHA256 与派发一致：

| 输入 | bytes | SHA256 |
|---|---:|---|
| iam-stage-4-o1b-readonly-status-brief.md | 19771 | c2749821ad0ff71ccf08c72f35b0dce4308f5a325ed8a925ce8594c7609b260f |
| iam-stage-4-o1b-output-budget-supplement.md | 4772 | 88c895386a77977920f31af324865cd6eb6d159bbcbb79f4bcdbf7985e68b335 |
| iam-stage-4-o1b-readonly-status-report.md | 16075 | 6a4665f2807788b4a1c6c2938e6d43b278bdb0fc3a945bf6f07d260b105ab33c |
| review-iam-stage-4-o1b-readonly-status-package.diff | 422049 | 928bb381c9de3c85a0703e766b3752568e66a8f68b7e4904cfcf221a0256a10b |

**⚠️ 范围限制。** 差异为 1 新增、9 修改，无 commit/HEAD；579 source、target 谱系及封存归因由 root 独立有限审计，本 reviewer 未重复全量扫描。没有真实外部 IAM/模型/本体/K8s 环境；新 fixture 是 gateway 客户端，不能证明 O1c 实际 gateway 最后一跳。legacy 只接受已报告的有限构造及原 POST/status 相关回归，不据此认定完整 Boot/生产部署通过。

## Strengths

- **用途与资源双向约束明确。** `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java:23` 的 snapshot/status 双向分支限制 gateway→engine、delegated、PORTAL/PORTAL、规范 runId/SHA；原 Q tuple/digest 保留。测试 fixture 的 `LoopbackAuthority.java:261` 附近 exchange/authorize 分支要求独立 status 原 call，可信登记按 owner/run/SHA 匹配，未将 Q 当成读能力。
- **只读能力没有向 public 数据对象开放。** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:351` 的 StatusReadProof 构造私有，唯一 mint 在 :401，发生在真实 PG binding、全部所需 scope 及当前覆盖校验之后；:360–369 核 thread、同 retained/frame、目的、owner、签名 snapshot resource 与 initial digest。ObservedView :372 与 RunService.ObservedStatus :1590 也使用私有构造，不公开 raw retained/grant。
- **Q、POST、observer 分开包裹共享读取。** `RunAuthorityStore.java:105` 的 package-private loadObservationBinding 只有 PG 控制/manifest 读取；Q wrapper :82–102 仍自行限定 metadata purpose。TraceStore.statusPublished :323 与 statusObserved :386 共享 statusPlan/statusData，不把 observer proof 交给 writer；MaterialScopeStore :520 的固定 readObservedValue 读前后都校验 scope 与 proof，原 public VerifiedAuthority reader 仍拒绝。
- **事务和 payload 顺序可核。** `TraceStore.java:386–418` 先拒 ambient、事务外刷新同 M，再在 MaterialScopeStore.snapshot 的顶层 readOnly RR 中加载控制、完整 scope/locator 集合并预检；snapshot/refs 是首批 payload。:420–429 核 app/root/candidate/version/refs 内部关系，:409 调用迁移的 domain 纯 FLOW 规则。每份 payload 摘要仍使用 MaterialScopeStore 的 EXACT/normalize 路径；RR 返回后 :418 再终检，没有清 blocked、resume 原 grant 或执行动作。
- **原完整输出和最后写出窗口保留。** `RunService.java:1597–1665` 共用原字段组装，`RunController.java:81–100` 在完整序列化、本地终检、headers、实际 getOutputStream 之后再次终检，紧接 write；IOException 保留主异常和 suppressed close 异常，日志明确 actualBytes=unknown。这里的一次 write 不构成持续在线授权。
- **测试观察具有实际价值。** 新 `RunObservationStatusHttpPostgresTest.java:162–181` 监听真实 JDBC connection/isolation/readOnly/autoCommit 与 SQL；:466–480 使用完整 SQL allowlist。:359–380 的另一真实连接 writer、:268–275 的 stream 获取后失效、:382–398 的 manifest/payload/commit/serialize 窗口、:511–527 的真实 peer 部分 JSON/EOF，均比仅返回码或 mock 断言更强。下述夹具和容量缺陷仍影响验收，不能由这些优点抵消。

## Critical

无已确认 Critical。未将隔离测试噪声或历史证据缺档夸大为生产越权。

## Important

### I1 — buffer 限制的是 count，继承的 backing array 仍可超过 2 MiB

**位置：** `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java:102–105`；`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java:252–266`；`docs/evidence/iam-stage-4-o1b-output-budget-supplement.md:11`。

StatusBuffer 的 capacity(amount) 只检查 `count + amount`，然后委托 ByteArrayOutputStream.write。父类 backing array 按旧 capacity 的两倍扩张，并不会按 STATUS_MAX_BYTES 裁剪。因初次批量写入可能令 capacity 成为非 2 的幂，count 未越限不代表保留的数组未越限。例如合法的 8,000-byte 批量写入从 8,000 逐次扩张到 2,048,000；下次写入令 count=2,056,000（仍小于 2,097,152），但 backing array 可扩张到 4,096,000。这个反例是 inherited growth 的静态推导，不冒称已在本审查运行 JVM 内存 probe。

现测试的 sample 读取 `bytes.size()`，这就是有效数据 count；它既没有观测实际 backing capacity，也不能支持作者报告 :72 的“buffer实测最大2,097,152”。完整 envelope 的字节上限、超 1 byte 返回 403 的行为可以成立，但补充裁定另要求新增 buffer 累计保留不超过上限，这一条未成立。

**最小修复条件：** 使用明确限制实际 backing capacity 的窄 buffer（固定上限数组或有上限的增长均可），仍复用 Json.mapper()、保留完整响应和 RUN_STATUS_TOO_LARGE；增加能触发非幂次扩张并检查实际保留容量的定向断言，同时保留真实 HTTP 的 exact/over/non-ASCII 场景。更正报告的 count/capacity 和有界副本内存描述。无需临时文件、通用输出框架或全套重跑。

### I2 — 所有联合链正例共用的 FLOW 不能被实际 RunService 受理

**位置：** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java:197–207`；同文件 :216–220 的 completeRun；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:491–505`。

create 的 snapshot 把 edge 写成 `{"source":"start","target":"end"}`；实际 RunService.createInternal 在 :501–503 读取的是 `from` 和 `to`，缺少 from 会在 out.containsKey("") 处抛 UNKNOWN_EDGE。fixture 直接调用 RunAuthorityStore.createPublished/createNode，绕过该受理校验，因此 status 正例确实经过真实 PG/HTTP，却没有使用 brief 要求的“完整真实可接受 FLOW”。GraphRules.requirePublishedFlow 只做原有限类型/依赖 gate，也不能补上这项保证。

这不是要求 O1b 额外实现 full Boot 或 gateway admission 集成；它是当前共同正例输入与真实受理格式不一致，影响正例、边界、并发和窗口验收的前提。直接生产持久化 seam 可以保留，但必须输入实际可接受的快照。

**最小修复条件：** 修正共享 fixture 的 from/to 和必要 FLOW 结构，用现有生产纯校验/具名受理规则证明其可接受；将 intentionally unsupported fixture 明确保留在负例。修复后只运行被该公共夹具和 buffer 改动影响的新类/具名回归，记录真实新结果，不覆盖原 17 通过原件，也不声称原结果已验证有效 FLOW。

## Minor

### M1 — green01 留存 XML 实际属于 red02，交还报告未披露该错配

**位置：** `.local/iam-o1b-readonly-status/green01/TEST-cn.agentplatform.engine.infra.RunObservationStatusHttpPostgresTest.xml:2,75,86`；`.local/iam-stage-4-o1b-green01.log:82,92,96,105`；`docs/evidence/iam-stage-4-o1b-readonly-status-report.md:43,56`。

XML 明确是 tests=2/errors=1，方法为 outputBudgetRejectsOneByteOverAndAcceptsExactUtf8Envelope 与 expiryAfterActualStreamAcquisitionWritesNoStatus；green01 raw 明确是正例 tests=1/failures=0/errors=0。root 报告此 XML 与 red02 原件 bytes/SHA 相同；reviewer 只补看上述 XML 三行与 raw 结果，未重跑/重拷贝。

应将 green01 标记为“真实 raw 通过、无有效该阶段留存 XML”，与 green02 未单独保存 XML 分开说明；保留错置文件和修正沿革。它不推翻 final04 的独立运行，也不应被继承为 green01 XML 证据。因原 raw 可追溯、当前适用覆盖不依赖这份 XML，按证据精度 Minor 处理；本次修复交还应补充更正。

### M2 — negative forward fixture 继续执行原 handler，留下未捕获异常噪声

**位置：** 新测试 :432–433 的 beforeController.forward 与 :118–123 的 requestBoundary.preHandle；`.local/iam-stage-4-o1b-final04.log:261–268`。

forward 完成后 preHandle 仍 return true，外层原请求继续进入 RunController.status，已关闭 input stream 的 read 在控制器 :79 抛 IOException；final04 留下 `Servlet.service ... Stream closed` ERROR 栈。403 和 payload=0 断言证明拒绝，但测试未区分这次额外 handler 生命周期错误。应让专用 fixture 转发后结束原 dispatch，或明确捕获/断言这个预期副作用；不应为消除测试噪声扩大生产异常吞咽。按隔离夹具问题 Minor，未据此认定可读取业务 payload。

### M3 — 编译/迁移噪声与编码损失仍存在

**位置：** `.local/iam-stage-4-o1b-final04.log:61–64`，`.local/iam-stage-4-o1b-red01.log:76`，作者报告 :90。

新增 status 测试触发 deprecated API 提示；旧 FlowMaterialStatusPostgresTest 的 unchecked 提示仍在，中文诊断已损失编码。red01 的 Flyway 警告为 schema engine 已存在而跳过，属于 fixture 建 schema 与迁移重复声明，未发现迁移失败。final04 :95 的 partial-IO WARN 是被 :511–527 具名断言的预期行为，应继续保留并明确归类。后续应改善编译输出编码、消除新测试弃用调用、对预期诊断做准确说明；旧 unchecked 不由本切片静默宣告解决。不得改写 raw 文件营造无噪声结果。

### M4 — 内容关系负例没有逐项覆盖新增 app/version 分支

**位置：** `TraceStore.java:420–429`；新测试 :343–357。

现有三项 mutation 是 snapshot.candidate payload 损坏、authority.resource_digest 单点替换、refs payload 损坏。它们证明摘要拒绝和 candidate/root 关系，但没有直接覆盖 appCode/root、appVersionId/versionId 同时存在不一致、appVersionNo/appVersionKey 的新增分支。实现静态检查存在，未发现相应放行缺陷，因此这是 coverage Minor。随 I2 修复时补充可由隔离库合法控制元数据单点破坏实现的具名案例，明确区分 payload digest 拒绝与内容关系拒绝；不要协调重写整套历史 scope 去伪造证明。

### M5 — 安全关键新增方法过度压缩

**位置：** `TraceStore.java:386–460`、`RunController.java:71–105`、`RunService.java:1590–1611`。

新逻辑多行包含数个校验/赋值/返回，POST 共用逻辑的重排也压缩了已有可读格式，令 proof mint、scope 预检和事务后终检更难逐行审查。按 Minor 建议只格式化本次触及的方法，使安全边界和语句清楚；不要求拆通用框架、扩大重构或因为旧文件很大而新造模块。

## Verification and inherited limits

- 没有重跑作者 green tests。最后适用覆盖按 `docs/evidence/iam-stage-4-o1b-readonly-status/last-applicable-coverage.json:1` 和 commands.json 记录为 **final04 新17**，另加 **final01 common3 / GraphRules1 / 原status2 / O1a4**；它们是独立实际命令，绝不写成一次27全绿。root 审计负责其 XML、command/source 冻结归属。
- red03/final03 的 delegateClosed=true oracle 与 final04 的 suppressed-close/actual-unknown oracle 不同；新的断言虽合理，不能称旧断言实现红转绿。现代码及新测试 :511–527 明确承认 MVC 拒绝 delegate close，真实 peer 已收到128业务字节，随后 EOF；不能称已追回字节或物理强制断开。
- 仅作两个具名 unchanged-code 窄读：①风险是 requireBound 是否真的检查 expiry/frame identity、refresh 是否替换 capture：`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:29–72`，确认 requireBound 调 requireVerified，refresh 限同 call/delegation/operation/version 后替换 mapping；②风险是容量缺陷是否能用固定 JDK 做不污染封存的最小 probe：`.tools/run-iam-module-tests.mjs:1–48`，只确认现 runner/JDK 环境，最终未启动 JShell/JVM，以免产生额外文件。除此未爬取 unchanged 业务代码。
- O1a 的旧 static HttpClient / loose SQL Minor 不作为此次已经修复的结论：新 fixture :53 也为 static client，但 :157 显式 close；新正例 SQL 采用 :466–480 精确 allowlist。没有重审旧 O1a 测试或据此签收旧债务。
- 原 v1.2/契约、六服务与 common、Java21/Boot3.5.0/Security6.5/PG、跨业务 HTTP 的全工程状态由 root 的冻结清单审计承担。本差异未添加跨业务 JAR、反射兄弟 target、迁移/POM/前端/部署改动；不把差异未改等同于本 reviewer 已重验完整环境。
- report 写入后冻结，本 reviewer 将 source、对应 target、作者 evidence 与本 review 报告写权全部交还 root；只新增本报告。请 root 对本报告完整读至 EOF 并核 bytes/SHA，再开始窄修复。

## Assessment

**Task quality: Needs fixes。** I1 是补充裁定要求的实际缓冲保留上限缺口；I2 是联合验收正例的真实受理前提缺口。修复这两项、补充 M1 的准确证据说明并保留既有历史后，可对新的冻结窄差异和实际受影响命令做复审；无需扩展到 full Boot、O1c/O2 或真实外部 IAM。

EOF — frozen O1b independent review.

