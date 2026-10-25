# O2b-D common 作者交付（未 Accepted）

限定的 8 个源码文件已实现并冻结。最终精确六类仅执行一次：**87 tests，86 passed，1 failure，0 errors，0 skipped，exit 1**。新 G/E 两类 18+22=40 条全部通过；旧 A 28 条、closed purpose 2 条、ServiceAuthorization 14 条通过。不能把本轮称为全绿或 Approved。

唯一失败为未修改的 `RunObservationSelectorsTest.statusPurposeIsBidirectionalAndOnlyAcceptsFreshPortalSnapshot:15`：其拒绝列表仍含 engine EVENTS，而 Accepted C 的公开 `requirePurpose` 已明确接受 STATUS/EVENTS。该测试 bytes/SHA 与继承 600 清单一致；D 的公开 `requirePurpose` 方法至文件末尾与 Accepted C 字节完全相同。归因证据为 `.local/iam-o2b-d-common/inherited-selector-conflict.json`。root 已裁定：原 87/1F 永久保留，不改旧测试、不重复六类；D fresh review/有限审计后另做仅 selector 测试迁移及独立 review/三方法验证，最后统一索引收口。

## 范围与固定协议

仅改 `admin-common` 中 CurrentAuthorityClient、ResponseAuthorityClient、HttpCurrentAuthorityClient、RunObservationSelectors、LoopbackAuthority；新增 GatewayPortalEventsResponse、GatewayPortalEventsAuthorityTest、PortalEventsChildAssociationTest。source 从 600 到 603，delta 恰为 8。未修改 POM、四契约、v1.2、业务源码、AuthorityContext、PortalEventsResponse、ServiceAuthorization、catalog、ingress 或任何旧测试/报告/索引；没有 commit 或部署。

采用正式 brief、decisions、receipt-export 补充裁定，以及旧决策稿仅 §3 API/§8 矩阵。完整具名输入身份见本目录 `iam-stage-4-o2b-d-common/inputs.json`。G action/profile、E 原 action/child profile、五个固定 open/check/exchange 操作与各 continuation/child-open/receipt typ/use/aud 均按固定协议实现。普通 exchange/client/resume/forward 不能取得 G 本地能力；G 使用私有固定 ticket validator 和真正 authorize，公开 route allowlist 与 selector 仍不接受 G。

begin 在真实 Portal decode 前固定单调锚点并保留原有有限 runtime 的一个 slot；Q 从真实 subject 构造且只供查询。G 重新以同原 Portal 独立 exchange+actual authorize，核 Q/G subject、source digest、origin authentication 和不同 grant/operation；其上界来自真实 Portal ceiling，不以 Q candidate roles 代替独立 G 的上界。snapshot 只是输入事实，authority 仍以 trusted observation registry 独立批准 owner/run/SHA。

G opaque wrapper 只接受 HttpCurrent 私有 parent proof，复用原 holder 的 capture/material/current/代际语义。wrapper 无 delegate、Actor、VerifiedAuthority、Retained、continuation 出口。isSuperseded 先核 current/owner，再按同 holder 代际分类。

E child marker 同时存在于真实签名 service ticket 与 actual authorize current，必须逐项一致才进入私有 Retained。child open 要求 actual engine owner、同真实 receiver frame/current、原 first anchor 和一次 frame claim；原 standalone 显式拒绝 child evidence 和任何单边 child marker。包内 bindRetained 只模拟 receiver seam，未声称实际 engine HTTP 已通过。

## 一父一子与摘要闭环

正常 authenticated Loopback endpoint 原子维护每 parent 唯一 child：未分配→DChild RESERVED→已签 child-open 的 OPEN。客户端也只允许一次 child reservation，但跨 frame/并发重放由 issuer 独立拒绝。父状态容量 64，每父最多一子；消费记录只在严格 hard 过期后清理，无为腾容量提前逐出。诊断 request 列表仍有限（2048，以容纳真实 64-parent HTTP 验证）；原 A 的路由、身份、origin/source 和 grant gate 未放宽。

关联完整绑定双方 profile/response/call/target/caller/ticket/grant/operation/initial ceiling、同 subject/source digest/origin authentication、snapshot、logical request SHA/cursor、各自 opening decision/nonce/raw request SHA 及不增加的 hard。G/E 各用自己的 fresh current，允许不同版本/roles；新的观察认证可不同于历史观察认证。

为遵守原 **16384 字符 JWT/decision 上限**，child exchange 的外层携 association digest，child continuation 签完整 immutable 的 canonical SHA256，避免在外层与 continuation 中重复嵌入完整关联。完整 marker 来自已验签 ticket 和 actual current；逐项核对 association 后才接受其摘要。没有扩大 HTTP/JWT 限制，也没有接受 caller 自报的 digest 代替完整字段。

生产 `portalEventsChildReceipt(response)` 是只读、本地出口：前后 actual owner/live guard、slot.response 同对象、私有 ENGINE_CHILD mode、仅返回该正常 child_open 已验签的 receipt。G 的 acceptChildReceipt 限一次尝试，ASCII compact JWS ≤4096 字节；实际验固定 typ/use/aud、预期唯一 parent/child 和 association。G 从先前已验 E ticket、child-open digest、固定 association 与 receipt 新 receiver decision/nonce/raw request SHA **重建完整 E immutable 并复算 canonical digest**，而非仅接受一串签名或独立摘要。对有效格式但错误 E raw request hash 的测试已真实 RED→GREEN。receipt 既不是 G 授权，也不认证任意后续 manifest 字节。

## 生命周期和实际验证

Opening 总预算 5 秒，包含 Q/G 网络；自动到期/close/异常清除原 token，Opening→G 原子转移原 slot。G hard=min(begin+55、原 source、G grant/op)，E 不晚于 G 已签 hard、自身 source/grant/op 和 receiver 原始 55 秒。续核只缩小本地单调预算，服务 JWT 到期不能新受理，但原合法 holder 可按专用 check 续核到原 hard。过期 proof/holder 在 wall rollback 后仍终止。

复用原 64 slot、2 worker、64 queue、单 timer；未复制 scheduler。实际阻塞任务在 cancel/close 后保持物理 slot 到 run finally，queued-never-run 不发 HTTP，late completion 不安装回已关闭 holder，关闭观察未取消 run。没有承诺硬断阻塞 peer 或撤权 SLA。

所有物理阶段都先存 source epoch，结束立即存 command/exit/stdout/stderr/raw、actual Running XML/txt、有限 target 和 journal。`test-results.json` 保留全部 16 次实际阶段，包括：初始真实 Portal/Q 后缺少 G API 的 1F；child envelope 重复嵌套超限的 1 error；两次新增测试编译错误（均非功能 RED）；32 条中的 1F；Q/G 独立 ceiling 与 child proof rollback 的真实 RED→GREEN；receipt 字段一致性 RED→GREEN；单边 marker RED→GREEN；40 条中的阻塞计时假设 1F 及闩锁修正 focused GREEN。任何早期失败均未重写成成功。

最终唯一命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-o2b-d-final admin-common GatewayPortalEventsAuthorityTest,PortalEventsChildAssociationTest,PortalEventsAuthorityProfileTest,PortalEventsReceiverClosedTest,RunObservationSelectorsTest,ServiceAuthorizationTest
```

最终 XML 为实际六类：18/0F、28/0F、22/0F、2/0F、3/1F、14/0F。没有跑 PG、engine、全仓、旧 29/24/B65、旧 replay 或旧审计。

## 可复核交付与回放 incident

- before source 600：`.local/iam-o2b-d-common/source-before.json`，SHA `44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54`。
- after source/final epoch/replay source 603：`.local/iam-o2b-d-common/source-after.json`，SHA `ab4a97cab7548de056a5548e129b49c6e19c0c4692ac82fa8f4857e19ec0087c`。
- before target 2096：`.local/iam-o2b-d-common/target-before.json`，SHA `65646da310fefd754a22b1284854291de3447551ff801a2b8da9f150473eebc9`。
- finite target overlay 213：`.local/iam-o2b-d-common/target-overlay.json`，SHA `7715e5ae8b3523de15000645314250818ed14a05baa36fe44f930ea419737796`。
- after logical target 2118：`.local/iam-o2b-d-common/target-after.json`，SHA `5f425586d177676f2a205b5330ef9ffa63ef21ce9fd1cdd2405529aef6ce1191`。
- final 原始八文件 patch 132241B：`iam-stage-4-o2b-d-common/final-source.patch`，SHA `1a9da0768f3a780a7060f361ee24fdd9b450ddca2dc5a6c105f03988c01b515f`。
- final replay commands：`iam-stage-4-o2b-d-common/final-replay-commands.json`，SHA `c7565a75582f7fbbd7236b172c10acfe9d77ba3730089c9a06ba11ef9b36b5da`。

target 仅覆盖 actual common 两阶段 compiler lists/created classes、具名资源、fixture tests JAR 和最终 actual Running 六类 XML/txt；未扫描旧 target 大树，未以继承 engine 记录声称当前编译。14 份新增 dumpstream/dump 单独归档于 diagnostics，未纳 logical target/overlay。全部 runner raw 已具名移到自有目录，relocations 保留来源对应。

早期首次 3035B raw patch 存在必须保留的 incident：初始 600 参数超过 Windows 命令长度后改 NUL pathspec；我随后在 init/add session 尚未真实退出时过早启动 patch。实际控制流已经过 check/apply 到后续 add 128，但 independent apply commands 未即时落盘。该缺陷不能补造为完整首次 raw 证据。原 patch、当时 helper、init 记录、session/chunk/exit、applied test 与原 Git/index 现场已冻结在 `.local/iam-o2b-d-common/incident-first-patch/`；没有删除 lock、创建第二个早期 baseline 或重复该首次 check/apply。

root 另行批准稳定最终 epoch 的**不同最终 patch**在全新 Accepted C600 具名 baseline 上做一次真正 check/apply。该最终回放已串行完成，逐命令立即持久化，check=0、apply=0，603 项名称/bytes/SHA 与 final epoch 完全一致，apply 后没有源码 copy，创建 commit=0。见 `.local/iam-o2b-d-common/final-replay-complete.json`。它没有修补、覆盖或冒充早期 incident 的首次证据。

## 交还与保留门槛

handoff 为本目录 `iam-stage-4-o2b-d-common/handoff.json`；own-inventory 为 `iam-stage-4-o2b-d-common/own-inventory.json`，只排除其自身，包含所有自有 helpers/local/evidence/report、原 incident 和最终私有 Git 证据。交还后作者冻结全部授权写权，等待 fresh independent D reviewer/root 有限审计；作者交付不等于 Accepted。

仍待外部生产 issuer 真实支持与后续 E/F：本轮没有真实 Q metadata HTTP resolver、gateway EVENTS ingress、PG/SSE、TLS/mTLS/secret_ref 私有材料 wire 或 Servlet writer 验收；这些入口仍关闭。继承 selector 测试冲突和原始 early replay 记录缺陷都明确保留供独立 review，不以本报告消除。
