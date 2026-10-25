# O2 游标与 scope 预算：预正式化窄核

只读结论，未实现、未测试。原 O2 candidate 40892 B / `4b8014b2081d7e7a73783b16fcfe3da6e4df423a6520445ec351d66a7d5c59da` 保持冻结；本文只补正两个正式化选择，不重新设计 O2。唯一工程/全部 shell workdir 为 `G:/207/项目/算法重组工具/代码/agent-platform`。未读 O1c 活动源码或 target，未启动服务/测试/构造数据，未改源、索引或旧文档。

## 1. 游标：保持 query 优先，无需改四契约

`contracts/trace-model.md`：85 行规定持久 seq、Last-Event-ID 补发与消费端去重；440 行列 events API；526 行要求断线补发、页面关闭不终止任务；542 行指出 WHATWG SSE/Last-Event-ID。对这些具名条款及全文件精确关键词检索，没有发现 header/query 同时存在时的优先规则。未重读四契约全文，因此结论限定为所定位的 trace 条款。

最新冻结 `RunController.events` 197–208 行同时声明 header String 与 query Long；205–207 行明确：query 非 null 则用 query，否则 header 非 null 则解析 header，均无则0。query 优先是已有实现行为，不能将“契约未明写”解释为可以静默改变它。原候选的冲突400建议不进入正式 brief。

建议正式要求：

- 保留单个 query `Last-Event-ID` 优先于单个同名 header；两者数值不同仍选择 query，不新增冲突400。
- 单个 query 存在时严格解析并校验其非负 long；失败不回退 header。query 合法时不对被遮蔽的 header 数值再作一致性要求。无 query 时解析单个 header；两者都无则0。
- 新 IAM 边界拒绝重复 query key/重复 cursor header、未知 query 与歧义解码；此处是 root 倾向的确定性边界，不假称旧 Spring 重复参数行为已验。空字符串、编码形式等应在正式 brief 固定，不能根据框架偶然的 null 转换绕过规则。
- private request binding 绑定**选出的有效 cursor 数值**与精确 run/resource/action/原 source；gateway 与 engine 共同采用同一规范形式。不能 gateway按header开lease、engine按query读另一位置。原输入由 ingress 防重复/歧义，后续内部请求只发送单一规范 cursor，不透传被遮蔽的 header。
- 错误保持既有 EVENT_CURSOR/400 类别，测试须含两个合法但不相等的值（query胜）、query非法但header合法（拒绝）、无query及无两者、long溢出/负值和重复参数。本文没有执行这些测试。

**root 已裁定的 carrier 边界。** 外部 header/query 的选择只在首次 ingress 完成：保存本地不可伪造 capture 与原始请求的关联，选出一个有效cursor，随后不把原header和query继续交由另一个框架重新选择。跨hop应固定唯一内部载体（候选：单个规范十进制query，无Last-Event-ID header），engine严格验证该形状并核数值与已签绑定相等。G/E共享的观察requestSha须由固定profile定义同一规范逻辑请求，例如schema、GET、公开events route/publicRunId、run-snapshot、原source/SubjectKey关联和有效cursor；接收方身份/实际HTTP目的地另在各自签名call与response绑定中校验，不将gateway和engine不同的host/receiver或外部carrier直接拼入这个共享摘要。最终精确字段与规范序列化必须在正式profile固定，不能两边各自实现“差不多”的URL hash。

原始浏览器请求/载体的本地capture绑定保留，不能因为逻辑摘要相同就允许换frame、换Portal、换run。若既有受核接口的requestSha实际上定义为原始HTTP bytes，便不能直接借用该字段再偷偷改语义；须明确保留raw ingress绑定与另一个固定观察请求摘要的关系，并在双方签名profile核定。本notes没有读取或假定O1c活动API已支持这种规范化。测试必须将“header-only输入”“query-only输入”“合法query遮蔽不同header”送过真实两个hop，断言它们各自选择的有效cursor、engine实际请求唯一载体及G/E已验共享摘要相符；不得只断言抽象DTO相等。
这保留当前公开语义，不修改 trace 四契约，也不要求更改已冻结 candidate；root 在正式 brief 中明确覆盖该候选意见即可。

## 2. 真实 writer 的直接父集合不是固定小集合

冻结 `MaterialScopeStore` 的类别数：三根 KINDS；四个 RUN_KINDS；每 node 四个 NODE_KINDS；每 attempt 四个 ATTEMPT_KINDS；每 event 一份 run-event。

`currentScopes` 255–267 从三根开始，再取每个 `(kind,material_id)` 最大 revision；不是只取当前 node，也不是全部历史 revision。`TraceStore.requireComplete` 495–514 对照全部现存 node/attempt/event 的精确集合；`writeView` 546–581 检查这些 scopes 并读其材料，最后将整个列表放入 WriteView。

`TraceStore.append` 628–637 把整个 `view.scopes()` 传给 `MaterialScopeStore.append`，随后把新 scope 追加回列表。后者 421–433 将三根和所有 consumed scopes 按 scopeSha 去重，重新读真实 PG 元数据；446–479 合并 initial 与各 parent 的 roles/entitlements/labels并写出完整 `parentScopeSha256`。同动作中新 revision 不替换旧列表项，因此一个动作后面的 event 会保留此前刚写的 revision 作直接父。`appendEvent` 640–658 先写 event scope，再写 run-state scope；不是只父向上一事件。

`createOwnedNode` 679–709 也从整个 currentScopes 取得 parents，为四份 node 材料写 scope。attempt 开始时 802–810 先追加 node-state、四份 attempt scope，再追加 NODE_STARTED；说明同动作的额外 revision 不是理论情况。

### 2.1 是否递归全历史

实际 `dynamic` 303–352：校验当前 scope 的 row/revision/owner/initial/hash/locator，按每个 parent hash 查询同 run、revision 更早的唯一记录，核 parent 自身 scopeDigest/owner/initial，然后 `requireFloor` 合并直接父要求。它没有递归调用 `dynamic(parent)`，没有对所有祖先 payload 做读取。

因此，新事件读的最小验证可沿现信任模型采用：三根+选中event的真实直接父metadata+floor/摘要，随后才取选中payload；不要擅自增加“全历史递归 payload 读取”。但“一层”不等于“小”：直接父包含当次全run最新材料及同动作新增revision，父scope本身还携带祖先hash列表。该模型依赖可信受理、append writer和原immutable约束；不能声称它能发现任意历史数据库被攻击者一致重写后的所有关系。gateway仍需独立current覆盖检查，父metadata是可信engine数据事实，不自赋读权。

## 3. 计数与字节公式：64/64KiB不能作通用总上限

设 writer 动作进入时完整 run 有 N 个 node、A 个 attempt、E 个已有 event，当前完整材料数为：

`C = 3 + 4 + 4N + 4A + E = 7 + 4N + 4A + E`。

设该动作在写待观察event之前已 append U 个不同新 scope revision（相对于动作入口集合去重后的增量），则该 event 的直接父数量为：

`D = |roots ∪ current-at-entry ∪ newly-appended-before-event| = C + U`。

三根已在 C 中，不能重复再加3。相同逻辑材料的新revision是不同scopeSha，可能使U增长；不能只按 `(kind,id)` 合并掉历史直接父。传输选中event自身还需1份；若同时携带当前status/watermark材料，则另算它及其真正需要的父集合。

以 root 提到的 N=25、A=40 为计数示例，入口 C 已为 `267+E`；即使 A=0，也为 `107+E`。所以64份不仅可能偏小，按现writer结构不能覆盖这些规模。这里不声称构造/运行了对应合法样本，也不声称25/40是允许范围硬上界。

`trace-model:427` 中25是maxSteps、40是modelCalls。最新冻结 RunService.initialBudget:901–914 将它们作为默认值，并接受config中的正数覆盖；40并不是attempt总数限制。GraphRules.requirePublishedFlow:156–198 限FLOW/START-END-CODE-CONDITION及依赖来源，没有在该入口给node数、attempt数或event数一个25/40硬cap。不能从两个默认数推出总父集合硬上界，更不能用52份样本证明所有合法run≤64。

字节预算需要额外维度。若某份scope的parent数组有p个64hex字符串，紧凑JSON的该数组仅值部分占 `67p+1` bytes（p>0；空数组2 bytes），尚未计字段名和其他scope字段。若需要传m份完整scope，令 `H=Σp_i`，则只这些parent数组已约 `67H` bytes。因此即使把scope数量上限改成512，也无法据此证明metadata≤64KiB；历史父列表会重复出现，可能随run增长产生二次量级元数据。

更具体的结构示例：按现 createOwnedNode 依次新增25个节点，假定此前节点记录均保留，某节点初始 node-definition 的直接父至少有 `7+4(i-1)` 项（忽略已有events只会低估）。25份这样的definition scope内parent引用合计至少 `25×7+4×(0+…+24)=1375`，仅hash数组就超过92KB；尚未包含其他75份node scopes和event自身。这是对冻结writer的条件性公式推导，不是实际PG样本结果，也不反过来证明任意run都满足该计数。

精确wire公式应为：

`B = binding/control bytes + selected-scope bytes + Σ(required parent-scope serialized bytes not legitimately cached) + root/initial bytes + payload/framing bytes`。

实际父hash列表、roles、entitlements、labels、source字段长度以及私有JSON转义开销都必须计入。本文未证明这些字段和合法run总规模的固定最大值，所以不能给“所有已支持FLOW均能通过”的一个替代总常量。原O2候选的64份/64KiB仅候选参数，应撤销其作为总依赖上限的适用性，不改旧文档。

## 4. 最小预算/缓存选择建议

1. **固定根缓存值得保留但不解决增长。** 本响应只缓存三份已验证immutable根manifest和必要initial事实，key包含本地/engine response、owner、run-snapshot、scopeSha。current更新后重新做覆盖判断，缓存不是allow。它只节约常数3份；不得将其包装成对D或H的全局上界证明。
2. **不要新建全历史递归cache。** 基线dynamic是一层验证；按event读实际直接父，保留原hash列表与floor。缓存全部历史父scope虽可能减少重复wire，却会无界增长；不能为避开64KiB创建无限Map。固定容量LRU只能优化，miss必须重发/重验，不能省略依赖或绕过current。
3. **64/64KiB至多可作传输分片预算候选，不能直接作整个事件依赖预算。** 若要求覆盖较大已合法run，可在固定内部协议分片送metadata，gateway逐片验证/累计floor与摘要，完整event依赖检查完毕前不放行payload。每片、每连接在途buffer、总处理时限均有限。某个单scope自己的parent数组也可能超过片限，因此仅“每页64份scope”仍不够；需要对超大单scope的既有规范序列化安全分片/增量验摘要，且不能发明替代scope digest。现MaterialAuthorization接收完整record，没有已验流式验scope API；这属于O2正式scope需要另评估的窄实现，不是假称现有分页接口可直接用。
4. **若首切片选择总metadata预算而非分片，必须明示容量失败。** 不截断parents、不抽样scope、不改history、不把超限伪装授权失败，更不能声明所有合法FLOW均覆盖。root需在正式brief中选定容量错误/停止语义与支持范围；这项有限容量取舍不能隐含在64常量里。本次不替root新增公开reset reason。
5. **先公式后样本。** 正式实施应记录每个合法代表样本 N/A/E/U/D/H、scope原始/规范wire bytes与实际最大buffer capacity；至少包含超过64直接父的正例、长parent数组、同动作多个revision、不同历史sourceAuthorizationVersion。真实PG/HTTP证据用于验证公式与分片，不用小样本代替最大值证明。没有全局硬界时可证明“容量内完整验证，超限安全失败”，不能证明“任意合法run均可流出”。

**完整scope与可信floor事实是两种不同的证据成本。** 默认完整方案将直接父完整scope（含它们的parent数组）跨hop传输，gateway可重算每份scopeDigest、独立核owner/initial/resource/locator关系并做当前覆盖，付出上述重复hash数组成本。另一个待root评估的最小数据可能性，是engine仍完整验证真实PG直接父/floor，跨hop仅传本事件真实MaterialRef/内容绑定、scopeSha、实际必要roles/entitlements/labels/initial事实与响应关联；gateway用自己的签名current独立做覆盖及最后一跳检查。后一种仍有实际材料权限判断，但若省略parentScopeSha256或其他被scopeDigest覆盖的字段，gateway就不能重算原完整scopeDigest，也不能独立证明所传floor正是该原scope的完整floor，只能依赖可信engine在本固定响应中正确抽取和绑定事实。

因此它不能自称与完整digest方案证据等价；仅给一个scopeSha字符串或allow=true不解决缺失字段。当前authority签名响应关联只绑定来源/资源/响应，未替engine签未来材料floor。若root采用事实压缩方案，必须明确其可信生产者边界、哪些关系由engine实际验证、哪些由gateway自己重验，并仍禁止丢失任何必要权限/义务；未获这种具体裁定前仍以完整scope验证为默认，不能以预算为由偷偷降低既定逐帧材料终检。两种方式都不能改写历史scope或DB schema来凑64份/64KiB。本文不定任何最终预算数字。
此处只澄清预算风险，不把分片或新的cache擅自派给当前O1c作者。O2a仍可先闭合engine逐事件当前材料读；O2b固定私有协议必须基于本节重新确定预算，公开SSE格式和游标不变。

## 5. 实际读取证据与范围

所有源码均为immutable物理原件，SHA与O1b fix source579精确条目相符；未读current。前缀 B=`docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/`，F=`.local/iam-o1b-review-fixes/final01/source/`；均相对本文唯一工程根。

| 物理文件 | bytes / SHA256 | 本次范围 |
|---|---|---|
| `contracts/trace-model.md` | 43889 / `7d0fe91476d6336877b00ece046d5e2daf1a239bf316a7d0b2f6c4507eaacb5f` | rg Last-Event-ID/query/优先/25/40；80–86、424–431、438–442、523–527、539–543；未全文重读 |
| F `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 27185 / `06ee90387fb6fb47e82695f59d288a14c56783a34d53cfd3c8bb7ba3f46ba366` | 194–209 |
| B `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | 33599 / `7674bd68fb1dcb17a150d4a93dba68ada1f43c56b4546bac3fc59f7a89836e29` | 具名rg；20–46、196–211、250–269、286–379、410–480 |
| B `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java` | 76375 / `2187eea017f65173ce01e23bf268647445d845a3533ca7a43234967734f58516` | 具名rg；489–517、541–588、615–660、674–713、785–824 |
| B `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 20003 / `b49d3408c70f3ca553a1a12d4161b1508c60eadddadfeeb316e9a6955c9cf764` | rg load/scopes；122–138；load(true)才装三根 |
| B `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 116314 / `52a45545ebcf9ffd28802057b8e8c42fa09ea39081253a4442c35acc6a97d189` | manifest定位/hash；rg预算；898–916 |
| B `engine-runtime/src/main/java/cn/agentplatform/engine/domain/GraphRules.java` | 10716 / `be53806c692a450cf83b6d86460bb2964d7b9b2999b616a809cf9d8d5f40f392` | manifest定位/hash；rg size/limit/max；156–199 |

定位期间还对 N4b2 base 的旧 RunService 做过预算关键词rg；发现latest manifest不同后改读上表B版本，旧结果未用于本结论。另对 O1b formal brief 做了25/40/attempt有限rg以核支持范围，没有读取作者活动文件。该定位过程没有修改任何原件。

仅本文为新增交还物；完成后冻结。后续候选修订/正式brief由root决定，本notes不等于部署或实现授权。