# Spec Compliance

**✅ Spec compliant for the focused O1b fixes；Task quality: Approved。** 原 I1、I2 已修复，M1 的证据解释已更正，M2 的夹具 dispatch 生命周期已修复。Critical 0 / Important 0；原 M3、M4、M5 的剩余部分继续作为 Minor，不宣称全部清零。批准范围是修复后的有限 engine IAM status 切片及本次两文件改动，不是全工程或生产 IAM 验收。

## Scope and frozen inputs

沿用原报告 `docs/evidence/review-iam-stage-4-o1b-readonly-status.md`（14773B，SHA256 `79725fd0fff67252035ced317372bf6a2903a35127b4db57acd3ac1418714590`）对未改授权/proof/RR/material/内容关系/POST 接缝的审查。本次只读两文件 full-context fix diff，共两处 source 文件修改，无新增/删除；没有重新阅读原10文件大差异或爬取未改业务文件。唯一 shell cwd 始终为 `G:/207/项目/算法重组工具/代码/agent-platform`。没有运行 git、编译、测试、应用、浏览器或资源探测；没有改 source、target、原作者证据或旧 review。

以下输入 bytes/SHA256 均已实际核对：

| 输入 | bytes | SHA256 |
|---|---:|---|
| iam-stage-4-o1b-review-fixes-brief.md | 8417 | cc58054a19a989595b4c5a56a61cf021ae67fffc5fa55d376243b3818dc45314 |
| iam-stage-4-o1b-review-fixes-report.md | 11000 | c08043623434c9beb8123bb01b20e0bd37c5a50c64a75cfd700cb3df0f91cc6d |
| historical-evidence-supplement.md | 2623 | c0fdde570952f1e3301b621ad282cab4cd6b74f47736f18caf76900b52358218 |
| review-iam-stage-4-o1b-review-fixes-package.diff | 103041 | 92d2d762924c94b75d4e9e51f79b52bcfa262f12c939dc78aedd8ac29e9ec1fa |
| iam-stage-4-o1b-review-fixes/handoff.json | 4096 | 069d538bb7dddae1c4568766d37c2fd8bffb81957660c0c9fee4f92e73c124e0 |

完整新差异按不重叠 bounded batches 读至 EOF；引用行号由该 diff 的新文件 hunk 位置计算。新 source579 SHA `d5fd1b495a0a447b6a786d47869c590dca18908f488401dddb149b73afc88648`、target1972/16delta、inventory86/3073742B、实际 replay 与资源归因属于 root 的独立有限 delta 审计；本 reviewer 未重复扫描原799物理项或旧 target 树。

## Strengths and closed findings

### I1 closed — 实际 backing allocation 有界，测试不再把 size 当容量

**证据：** `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java:119–140`；`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java:280–326,366–411`。

私有 StatusBuffer 构造现在 `super(STATUS_MAX_BYTES)`，一开始只分配 2,097,152-byte backing array；原 count guard 在每次单字节/批量 write 进入父类前拒绝越限，因此父类不再有扩容所需的 minCapacity 超过该数组的机会。构造及常量仍私有，没有额外公共诊断 API、JDK 内部反射、临时文件或输出框架。

backingCapacity 使用标准 ByteArrayOutputStream.writeTo 的 sink，观察实际传入数组 length，而非 size()/copy；反射只打开本工程私有 buffer 构造。新增测试分别覆盖8000批量、单字节、31/8000/8191混合写入以及 exact 后的单字节/批量 +1；真实 PG/HTTP 场景也改为观测这个实际容量，并保留 UTF-8非ASCII、转义、完整 envelope exact/+1 和业务 status 零输出拒绝。

原缺陷的真实 red 原件 `.local/iam-stage-4-o1b-review-fixes-red01.log:80,105–112` 明确 count=2,056,000 / backing=4,096,000；新 final 原件 `.local/iam-stage-4-o1b-review-fixes-final01.log:86,300–302` 明确三种写法 backing=2,097,152，HTTP exact=2,097,152 / over=2,097,153，错误码 RUN_STATUS_TOO_LARGE。不是编译或反射失败冒充行为红。

代价说明准确：小响应也预分配2MiB，toByteArray 最多另有2MiB副本；已读材料对象图、Jackson 内部和整个请求堆并未因此有界（作者 report :38）。这不是原规范之外的全请求堆保证。

### I2 closed — 共同 FLOW 夹具符合实际 from/to，证明到真实持久化前边界

**证据：** 新测试 :210–242 的共享 snapshot/create；:328–365 的 production finite-flow/admission DAG 检查；沿用原已读 `RunService.java:91–94,491–505` 的实际路径。

共同快照使用 `from/to`；负例 unsupportedFlow 仍单独修改类型，没有放宽生产图格式。新增检查先调用真实 GraphRules.requirePublishedFlow，再以相同 snapshot 走已有 createReservedSessionTurn 的 legacy/reserved 路径，覆写 TraceStore.atomic 只在入口抛出唯一 sentinel，停止在任何持久化 supplier 执行之前。正确图必须到达该 sentinel；旧 source/target 图必须抛具有 UNKNOWN_EDGE code 的 PlatformException。这个差异断言会因真实图校验的缺失/失效而失败，具有实际约束价值。

测试还明确拦住 scheduler.start/reserveInteractive/created，断言 SQL 空、provider计数与业务表计数不变；没有新增 public IAM 受理能力。red 原件 :84–100 显示旧快照在实际 RunService.createInternal:502 提前失败，未到 sentinel；final 原件 :84 和完整类通过结果提供修复后的证据。

这一检查证明有限纯 gate 加真实受理前 input/DAG 接缝，不证明完整 IAM/public admission 成功链。历史补充 :12 和作者 report :40,45 明确纠正旧 store-created fixture 的证据范围，没有将旧结果倒改成完整受理通过。

### M1 closed as evidence correction — 错置 XML 保留并明确排除

**证据：** `docs/evidence/iam-stage-4-o1b-review-fixes/historical-evidence-supplement.md:5–8,10–14`；fix commands.json 的两个实际阶段记录。

补充说明原 green01 raw 是1项通过，但没有有效 green01 XML；原 green01 目录下文件实际与 red02 的2项/1error XML 相同，继续保留但排除作为 green01 证据。green02 的 raw-only 情况另列，未由新运行填补历史缺档。size/capacity、旧图格式和 partial-I/O oracle 演进也均准确分开。

这里的“closed”只表示解释及继承规则已纠正，不表示历史 XML 缺档被补回。新 red01/final01 各自有独立 command/source/raw/Running-class XML，不归属旧阶段。

### M2 closed — 完成 forward 后停止原 dispatch

**证据：** 新测试 :117–137 的 requestBoundary，:570–607 的实际 raw query/body/dispatcher 测试。

测试在 forward 返回后标记 `o1b.forward-complete`；preHandle 依据该标记返回 false，结束原 dispatch，避免继续读取已关闭请求流。测试断言 forwarded 和 completed 都为 true、403 且无 SQL。仅修改 fixture，没有吞掉生产异常或增加 global advice。对新 final 原始日志作具名诊断检索未发现旧 Stream closed/Servlet.service ERROR；旧日志保持原样。

### 原安全边界未被修复顺手改变

`RunController.java:71–117` 的本次改动是格式化及私有 buffer 构造；仍先实际 admin handler/template/URI/dispatcher/query/body 检查，完整序列化，再本地终检、headers、实际 getOutputStream、同 proof 再终检、首次 write。IOException 的 best-effort close、primary/suppressed 与 actualBytes=unknown 保留。最终新类仍包含真实stream后失效、单RR并发writer、scope先验、完整status、POST目的及部分IO等原方法；proof mint/消费、SQL/事务和内容关系代码没有进入本次 source delta。

## Critical

无新增或未解决的 Critical。

## Important

无未解决的 Important。原 I1/I2 按以上生产代码、有效测试及实际原始失败/通过证据关闭；原作者报告自评未代替独立判断。

## Minor — carried, not silently resolved

1. **M3：弃用/unchecked/编码诊断仍存在。** 新测试 :168,377,534,541 仍使用固定 mapper factory 的 setOutputDecorator；新 final raw :55–58,66–69 保留该测试与 RunAuthorityStore 的 deprecation、ContextProjection/旧 FlowMaterialStatus 的 unchecked 提示及中文编码损失。作者 report :48 如实保留。partial-I/O WARN（raw :102）是新测试 :681–697 明确断言的注入失败，不当作产品成功写出；Flyway 既有 schema 提示不等同迁移失败。后续可在独立窄范围改善诊断/编码，但不为此次通过替换全局 mapper 或改写旧 raw。

2. **M4：各 post-digest app/version 内容关系分支仍非逐项动态覆盖。** 新测试 :487–501 仍是原 candidate/root/refs 损坏场景；未改 TraceStore :420–429 的 appCode/root、双 versionId、appVersionNo/key 等校验按原静态审查继承。直接改变 immutable payload 通常先触发 digest 拒绝，不能声称已命中更后面的关系检查；作者 report :49 给出具体限制，未协调改写完整可信历史来制作误导测试。继续登记 coverage gap，不据此关闭所有分支覆盖，也不在无对应放行缺陷时提升为新阻断项。

3. **M5：本次触及部分已格式化，原压缩方法仍保留。** Controller :71–140 和共享fixture/budget/dispatcher主要新增方法更清晰；未改 TraceStore :386–460、RunService :1590–1611 的可读性问题继承原 review。此轮只批准两文件所有权，不要求为格式化扩大 source scope。新测试增加至700行主要来自有效两项回归和触及方法格式化，未发现需要阻断的通用抽象或重复生产算法。

旧 O1a staticHttpClient/looseSQL 的有限评审债务及原跨切片限制继续继承；不借本次 Approved 声称其已全部处理。

## Verification, ownership and assessment

- **Root 独立有限 delta 审计已 PASS。** 已读并核 `.local/iam-o1b-fix-root-check.json`（3655B，SHA256 `6ba96ffc35cc1351e9b48f7a56eed823bae08c553d89a38e45cd6bff80739ccf`）及 `.local/iam-o1b-fix-root-diagnostic-notes.json`（1002B，SHA256 `cc63900a7660d50bf983b3583be0cd43853734bfca89b54d14190acd38cb606c`）：122不同物理文件、6阶段，继承原799；source579/delta2、target1972/delta16（4new）、own86/3073742B、两条实际命令及对应XML、first check/apply 0/0 和逻辑579、两自有PID/port关闭、保护索引/设计/README均记录通过。这是独立证据审计结论，未替代上述代码审查。
- 审计过程的解析假设失败保留：把纯文本 O1B_ADMISSION_GATE 当 JSON、把 no-index diff 的正常 exit1 当作失败、期待 raw 直接含 UNKNOWN_EDGE 符号。实际 red raw 只有 PlatformException 与 RunService:502；UNKNOWN_EDGE code 由该冻结源码位置及后续具名断言识别，不能冒称原日志打印了符号。修正后复用物理缓存，只续未完成阶段；没有重跑测试/probe/patch apply，也没有改写原失败记录。

- 未重跑任何绿测试。实际只核新 red/final raw 的有限诊断/结果行，读 commands.json、coverage 和 handoff；XML/source/target/replay 全量归因由 root 的有限审计负责，没有重复旧库存扫描。
- 当前实际新增适用覆盖为 **fix final01 一次19/0/0/0**（raw :309,313,322），前置 **fix red01 两项2F/0E/0skip**（raw :83,141）。不是原27合成套件，也不是两次19全绿；原 common3/GraphRules1/原status2/O1a4 仍属于其原 final01 独立命令与未变接缝。原 final04 17项只作原阶段历史，不替代纠正共同夹具后的19项。
- 原 partial-I/O red03/final03 的 delegateClosed=true 与后续 suppressed-close oracle 仍严格区分。允许已发128业务字节后 EOF，不宣称追回字节、一次阻塞write内持续在线撤权、物理硬断或最终 gateway hop 已受控。
- ⚠️ 未验证外部真实 IAM/模型/本体/K8s、完整 public IAM admission/全Boot、O1c、O2、RR1/RR2 或全工程验收。本次无新增生产端点、公共proof、依赖、迁移、版本/契约/部署变更；未读相邻工程。
- 本 reviewer 唯一新增写入为本报告；source、target、原证据、原review均只读。报告封存后所有写权交还 root，请完整读至 EOF 并核 bytes/SHA，再结合独立 delta 审计闭环索引。

**Task quality: Approved。** 本次以最小生产改动关闭实际容量缺陷，以真实受理前校验证明修正共同 FLOW 夹具，并保留历史证据及剩余 Minor 的准确边界。批准仅覆盖明确的有限 O1b 任务。

EOF — frozen O1b focused-fix independent re-review.

