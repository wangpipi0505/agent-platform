# N1 root decisions

2026-10-20, root message (verbatim):

main proof用于新run/初始材料时仍必须同frame+实际原Grant/initial/origin，fresh main历史read不能借此改旧run。新run初始dynamic scope只在createPublished拥有的实际INSERT后私有写入；不要暴露给任意旧run补scope入口。节点同理，优先由现TraceStore一个具体方法拥有实际新node INSERT+其初始scopes（测试也走真实INSERT，后N3接该方法），而不是公开initializeExistingNode(nodeId,proof)给历史缺scope节点补权；不用xmin/txid/receipt证明新行。后续受保护mutation只走original local proof+实际成功current CAS/无阻断与已有材料覆盖。原legacy无authority仍保留。

Runner artifacts reserved before first test: exact .local/iam-stage-5-n1-{preflight01,red01,red02,green01,green02,green03,green04,green05,green06,green07,green08,green09,green10,final01}.log and corresponding exact -fresh-secrets/ directories. Original before remains immutable; this addendum records authorized test artifacts, which did not exist at capture.

2026-10-20, root transaction decision (verbatim):

protected worker 公共写方法只接受顶层自有RR；ambient RC报REPEATABLE_READ_REQUIRED，已有RR/Serializable也拒绝PROTECTED_WRITE_REQUIRES_TOP_LEVEL_TRANSACTION。这样方法交付前确实能做真实commit后同proof终检。status刷新前拒绝任何ambient，内部一个顶层只读RR。createPublished/拥有实际INSERT的createNode初始材料继续加入原admission业务Tx、局部requireBound/rollback-only；其真实外层commit后检查归未来N3原RunService受理拥有者，不冒称初始化方法已经完成外层commit。不要为通用join能力新增ThreadLocal/receipt/框架。root已知未来N2 FlowDriver.prepare及RunService.aggregate/DEFAULT_VALUE有原组合事务，N2必须在那些具体外层拥有者补持同proof的真实commit后终检/必要具体组合API，不能简单嵌套调用顶层writer；该接线留明确依赖，不算N1完成。

Before additional behavior-reds: reserve exact .local/iam-stage-5-n1-red03.log and .local/iam-stage-5-n1-red03-fresh-secrets/ (no broad stem exclusion).

Before metadata relationship red: reserve exact .local/iam-stage-5-n1-red04.log and .local/iam-stage-5-n1-red04-fresh-secrets/.

2026-10-20, root VA-only trust-boundary decision (verbatim):

保留旧 read(MaterialRef, VerifiedAuthority) 签名以兼容编译，但所有受保护 initial/dynamic 读取在此入口立即拒绝；新的 read(ref, RetainedAuthorization) 使用不可伪造私有证明，保持三种 initial 原有 owner/root/scope/精确值语义。原 InitialMaterialScopePostgresTest 正负例最小改传真实 p 而非 p.current()，确保每个原 scope/错权/RC/精确值负例仍到达对应校验，并另增伪造 public VA 零 payload 拒绝，不能把旧负例全部变成入口拒绝。普通 fresh-main 同 owner/root/current covers oldscope 的历史读取仍允许，不得要求旧 originalGrant 存活或未 block；原 grant/initial/origin 不改。局部 worker 仍走原委托绑定/持久化 current CAS/当前 block 检查。Root 窄 rg 已确认其他生产模块无旧 read 调用，不涉及四契约/HTTP/部署变更。冻结 brief 不重写；记录 root-decisions 与报告，作为 N1 内部信任边界强化，不改写旧 E1b 结论。

Before public-record behavior red: reserve exact .local/iam-stage-5-n1-red05.log and .local/iam-stage-5-n1-red05-fresh-secrets/.

Before original SQL NULL versus explicit JSON-null display red: reserve exact .local/iam-stage-5-n1-red06.log and .local/iam-stage-5-n1-red06-fresh-secrets/.

Before persisted-block/current CAS race red: reserve exact .local/iam-stage-5-n1-red07.log and .local/iam-stage-5-n1-red07-fresh-secrets/.

Before correcting red07 observation's closed backend row: reserve exact .local/iam-stage-5-n1-red08.log and .local/iam-stage-5-n1-red08-fresh-secrets/. red07 is preserved as observation noise, not a pure behavior-red.
