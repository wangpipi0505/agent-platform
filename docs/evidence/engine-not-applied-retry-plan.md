# G2 引擎未生效重试许可后置实施计划

状态：**仅计划，未实现**。2026-10-20，只读核对后新增本文；没有改源码、测试、迁移或原契约，没有运行 Maven、启停服务或创建子智能体。根任务明确先完成 G5 调度，再移交本计划实施；本文不能作为已具备 UNKNOWN 自动重试能力的依据。

依据为冻结详设册20 F6/执行事实分工、册31 F7/调用登记与W1–W3，以及 `reconciliation-cancellation-plan.md`。冻结详设 SHA 为 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。本计划只细化原“未生效→受控重试、旧事实不丢、逻辑结果一次”语义，不修改原四契约或稳定幂等键。

## 依赖与实际代码约束

1. 先取得 G5 调度的最终冻结实现、评审和迁移清单。G5 预留 V215，本阶段引擎默认使用后续 **V216**，不得抢占 V215、与 G5 并写 engine 或复用其 Maven target。实施前重新检查实际编号和新调度入口；本文不假定当前 V215 已实现。
2. 技能侧 V310 的不可变 NOT_APPLIED/NOT_SENT 证明已有局部实现，167项技能测试通过，但本轮票据 P2 仍以原独立评审最终关闭为准。消费者只使用该关闭后的最终 SHA 清单。证明 GET 要求已验签 internal audience、service purpose、SERVICE角色及原tenant/principal。
3. 当前 `tool_call.attempt_id` 是 `engine.attempt.id`，即节点执行尝试，不是另一个工具私有自增计数；`TraceStore.claim` 取得节点租约时创建 attempt。不能在重试 API 内绕过 G5 凭空创建 RUNNING attempt，也不能把迁移编号或 permitId 冒充 attemptId。
4. `ToolCalls.register/claim/submitted/fact` 已负责登记、租约和事实裁决，锁顺序为 run→node→call。`ExecutionRecovery.adopt` 只采纳确定执行事实，不能被改成看到 UNKNOWN 就重新 execute；普通恢复和重试许可须分开。
5. 现有 `SkillExecution.execute` 只对 readonly + 非 sideEffect + RETRYABLE_FAILURE + 引擎REGISTERED新代放行重试；UNKNOWN重入只交付既有事实。该合法只读路径、readonly_retry_count、其原预算和回归必须保留，不能因新增写技能证明门禁而全部禁止或默认为写重试。
6. 当前 `SkillAdapters.invoke` 没有 expectedAttempt 参数，这是启用新代前的阻断项。仅重置 binding 为 REGISTERED，会使持有旧 submitted ACK 的旧线程读到新 binding 后误用新代发送权；本阶段必须先补显式原代/许可传播，才允许任何 UNKNOWN 换代。
7. `ToolCalls.approval` 的 approved 布尔还依赖 RUNNING 节点/当前租约。等待外部时不能把它的 false 当作“原批准已过期”，也不能绕过它直接复用一个历史 true。需分别核对持久批准快照的有效性，再在新代 claim/submitted 时验证当前租约和批准。

## 交付范围和不变量

本阶段实现一次**显式发起的**证明重试请求、持久许可、受调度器控制的新尝试、技能原代归档与受许可发送，及其恢复/拒绝证据。UNKNOWN清单、完整人工产品页面、三个取消事实与各适配器真实停止接口另属后续阶段，不在本文冒称完成。

- 逻辑 callId、call_seq、idempotency_key、原 skillVersionId/合同、原参数摘要保持不变。参数或技能版本改变不能沿旧 call 重试，应走原设计的新调用登记与新确认，不能通过编辑 body 更新旧身份。
- proofRef/proofSha256 只指向待核实证据。调用方不能通过 body 自报 NOT_APPLIED、jobId、args、当前权限、批准状态、目标attempt或“允许重试=true”取得权限。
- 一次证明请求至多产生一个可生效的新发送许可；重复请求、超时重投、换副本、重复 outbox 均不能增加实际业务发送。后续一次再次 UNKNOWN 需要该次原代的新证明和新的显式决策，不能循环消费旧证明。
- 引擎是许可、逻辑结果/节点状态/事件的唯一提交者；技能中心只验证许可并持久执行事实/发送栅栏。外部业务仍只由适配器执行，证明、查询、准备、恢复和消费许可的入口都不自行重发业务。
- 同一 call 统计仍计一次；真实各代执行耗时与预算累加。对账等待、许可排队、重复交付不计成新的实际执行。

## 最小内部接口草案

以下是后置实现草案，实施时按 G5 冻结代码固定字段和错误码，不写回原契约。每个新增入口都显式校验已验签 Jwt 的 `token_use=service`、internal audience 和 SERVICE 角色；ADMIN或带SERVICE字样的portal令牌不能代替服务票据。tenant/principal来自验签主体，跨主体/租户返回404。

| 入口 | 最小用途和约束 |
| --- | --- |
| 现有技能 `GET /internal/skill-facts/{callId}/reconciliation-proofs/{oldAttemptId}` | 引擎主动获取原代证明，重算正文摘要；忽略调用方自带证明正文。去掉 GET 附加的 proofSha256/retryBusinessAllowed 后按相同 Json.canonical 算法重算。 |
| 拟新增技能 `POST /internal/skill-facts/{callId}/retry-check` | 仅核实原证明、当前原代 UNKNOWN、原binding、当前守卫覆盖原guardedArguments；不发业务、不更新新代、不授引擎许可。body只给 oldAttemptId、proofRef/proofSha256、请求关联号。返回本次核实摘要、原绑定摘要、当前守卫摘要及有限有效期，供引擎记录来源。 |
| 拟新增引擎 `POST /internal/tool-calls/{callId}/retry-permits` | 明确发起本次重试。body仅为 requestId、expectedAttemptId、proofRef、proofSha256。先完成HTTP取证/当前授权核实，再在引擎短事务登记许可意向；同请求同意图返回同一受理标识，变更意图409。不得同步调用技能业务execute。 |
| 扩展现有引擎 `GET /internal/tool-calls/{callId}` | 仅向原主体内部调用返回本call的当前许可绑定：permitId、原代/目标attempt、证明摘要、合同/args/key绑定及是否已经提交。技能中心从此权威读取，不能信 body 的 permitId 就放行。 |
| 现有 `claim` / `submitted` / 技能 `execute` | 增补 expectedAttemptId、permitId 关联；引擎继续按原租约CAS。正常首次执行、合法readonly重试与证明写重试显式区分，普通UNKNOWN execute仍不获得重发权。 |

若 W1 恢复需要单独的技能“准备/栅栏”内部RPC，只允许创建/核实已授权代的 PREPARING/REGISTERED持久图像、可靠NOT_SENT，不得发业务；禁止用一个未验权的 prepareOnly body 开关绕过正式 execute。该RPC的必要性必须由下述崩溃矩阵先证实，再固定最小接口，不能用内存状态填补跨服务窗口。

## 引擎持久化和 G5 调度衔接

**V216候选**：新增许可/幂等请求记录及工具原代快照归档。具体表名可按实现调整，必需字段包括：tenant/principal、run/node/call、原attempt、proofRef/proofSha、固定合同/args/key、请求意图摘要、批准快照摘要、当前守卫核实摘要/有效期、受理时间、目标attempt（未领取前可空）、领取/提交裁决、拒绝或终结原因。归档保留原 tool_call 的状态、结果引用、批准上下文、执行耗时与租约/原代，不改写成新成功。

requestId唯一键必须带tenant/principal并绑定call及完整意图摘要；除同requestId去重外，同call/原代的并发不同requestId也必须用唯一约束/原call锁裁决，只能有一个活动且可生效的许可。证明正文保留不可变，许可进度和原代归档分别记录，不能覆盖证明使其看起来“已经允许重试”。

受理与发放的两个事务不能合并成跨HTTP大事务：

1. **事务外取证。** 读取本call的候选身份，调用技能证明 GET/retry-check，核对完整证据和当前权限。尚未取得原代明确未生效证据，不触碰tool_call UNKNOWN，也不把run/node强行设READY。
2. **受理短事务。** 按run→node→call加锁，重新核对原UNKNOWN、原attempt、原版本/合同/参数/key，run/node未取消未终态、节点未逻辑提交，批准和授权核实材料仍有效；然后登记唯一许可意向及调度就绪标记。原调用在真正领取之前仍是UNKNOWN，排队信息取PG；不先创建无执行槽的RUNNING attempt。
3. **G5实际领取。** 由G5已建立的容量/队列/租约入口领取，依旧遵守交互/批量池。必要的HTTP授权重新核实先完成，领取短事务再按同锁序复核；与新node attempt创建同事务绑定目标call/许可，归档旧引擎call状态并将该call置REGISTERED。旧claim/lease清空，固定call身份不变。不能先改call再异步“补写permit”。
4. **新代检查点。** 从已存模型响应和tool_call登记恢复目标call，不再调用模型生成新call_seq。节点内其他已完成/待外部的兄弟call各自保留身份和结果；授权一个工具不能把整个Agent节点中的写工具重跑一遍。未取得各自证明的兄弟UNKNOWN保持等待。
5. **一次提交裁决。** 新worker在claim/submitted上回传原expectedAttemptId及permitId。引擎在原子提交发送权时再次验证许可归属、当前run/node/lease/批准；该线性化点之后的取消属于已授权在途，不能宣称本地取消已撤销外部效果。此前取消胜出则许可不能生效、无新业务外发。

待领取意向若授权/批准过期，不分配新attempt、不发业务，记录明确阻断原因。同requestId返回同一受理记录及其状态，不悄悄再生成一代；新的显式请求仅在旧意向从未形成可提交的新代且原证明/UNKNOWN仍匹配时可重新核实。已绑定或已提交的新代必须走本代W1/W2恢复，不能借重投旧requestId再次花费原证明。

## 当前审批与范围核实

- 应用/技能版本和完整输入均取运行/调用冻结事实。对照proof的tenant/principal/run/node/call/attempt、skillVersionId、skillContractSha256、argsDigest、guardedArgsDigest、idempotencyKey及原发送状态。证明的PG身份和正文摘要均核实；普通404/5xx/超时/UNKNOWN/错误job/key没有许可。
- 参数未变不等于当前权限未变。retry-check以本次已验签原主体的当前角色/属性执行原技能守卫，要求当前范围覆盖原guardedArguments；不能用run.guard_ctx旧角色快照推断“现在仍可写”，也不能在body中补原已撤销属性。
- 原批准复用只在 APPROVED、未过期、原call/技能版本/参数摘要一致、批准角色仍合法且批准证据可验证时成立。ADMIN/EDITOR不自动成为确认人。若批准者为另一主体，不能以发起人的角色证明批准者现在合法；缺可信角色证据则保持等待并请求原确认流程的当前合法确认人重新确认。
- 生产IAM尚未提供的角色新鲜度/撤销证据不得假装由本地旧快照补齐。开发验证可以使用明确受控、当前验签的角色证明夹具；生产缺失时failclosed，发布门禁保持原状。
- 新代领取/提交前再次检查有效期。队列中等待导致批准或核实材料过期，不能凭受理时的true继续发送；也不能为了通过批准检查而先伪造RUNNING旧租约。
- 新代工作使用原权限上限与本次可信当前范围的交集，原run冻结快照保留用于审计。恢复器不得从旧guard_ctx重新拼出已撤销写权限；不能证明目标调用及需复用的受保护检查点仍获准时阻断，不能通过重放旧大范围结果绕过新守卫。

## 技能原代归档与 expectedAttempt 传播

**技能迁移候选V311**（后置实施时确认未占用）：增加外部绑定原代归档和许可激活/消费的持久关联。复用已存在的 execution_fact_history，原事实、原job/key/contract/args及send_started/回调状态完整保留。原proof仍指原attempt，不跟随当前行变成新代证明。

正常新代路径在当前参数/守卫/确认检查及引擎claim成功后，技能中心再次主动读取原主体的引擎许可；然后短事务锁原fact→原binding/激活记录，核对仍为同代UNKNOWN、证明未冲突/未被确定终态取代，原绑定归属吻合且该许可没有被另一代激活。归档旧fact和binding、更新当前行的新attempt/PREPARING、新REGISTERED/send_started=false绑定必须一个事务完成，不先删除事实再重建（现proof外键仍引用稳定call行）。新代actual duration起点从0开始，逻辑累计真实耗时保留。

显式传播链：

`AgentDriver/目标call恢复材料 → SkillExecution.execute 的请求原attempt → 引擎权威get/claim/submitted核实 → SkillAdapters.invoke(expectedAttemptId, permitId/绑定证据) → HttpAsyncAdapter.bind/invoke/提交前CAS`。

expectedAttemptId从原请求进入后保持不变，禁止在旧线程里读取当前binding.attempt_id后替换它。普通首次执行也传原expectedAttemptId；普通HTTP/MCP/Algorithm的适配入口不得保留一个会绕开代次校验的旧业务发送重载。实际连接执行沿SkillGovernance现有受控引擎运行取得真实调用/attempt；没有引擎调用身份的纯协议探测保留为独立探测入口，不以默认0“兼容”业务发送。readonly路径可以没有写重试permit，但仍核对其合法新attempt和引擎REGISTERED授权。

HTTP发送CAS至少绑定tenant/principal/run/call、固定skillVersion/contract/args/key、原expectedAttempt、当前许可/激活身份、REGISTERED/send_started=false。同permit的重复execute只有一个CAS成功；发送前已写引擎SUBMITTED，ACK丢失不自行再发。旧submitted ACK线程即使在新binding已REGISTERED后恢复，其expectedAttempt仍为旧值，必须在适配器入口/发送CAS被拒绝，不能借当前新行冒充新worker。

ACK/job lookup/DONE、失败/UNKNOWN写回、Outbox ACK、CALLBACK都继续按原代和请求快照裁决。callback必须能查原代binding归档来验签和归属，记录旧代LATE/冲突证据；不能只用当前binding拒绝后丢掉合法旧成功，也不能让旧event覆盖当前事实。逻辑提交仍只有引擎，旧事实参与核实不等于旧线程取得新执行权。

## W1–W3 与跨服务不一致窗口

| 窗口 | 规定行为 |
| --- | --- |
| 意向已PG受理，尚无G5资源/新attempt | 重启后从PG恢复同意向；不创建额外许可，不自动重做原UNKNOWN业务。 |
| 新attempt/许可已绑定，技能尚未激活 | 只能按原许可核实并幂等准备该代。不能把“找不到该代fact”当作可直接外发，也不能重新消费旧proof创建另一许可。 |
| 激活前原callback或对账已写确定终态 | 技能激活短事务拒绝归档/重置；引擎终结未发送许可，保存确定事实。必须有同锁序、同permit/原代CAS的恢复分支回到可采纳该原代事实的状态，再走W3；不能因为call已指向新attempt而永久丢弃旧成功或强行把旧结果伪装为新代。 |
| 新代PREPARING/REGISTERED已落库、尚未send_started | W1恢复必须先用同代持久发送栅栏和引擎当前状态证明未发送，再由调度器重新绑定合法执行租约。不得仅凭REGISTERED字符串重发写操作；旧owner迟到与栅栏CAS须竞争，只有一方胜出。 |
| 新代认领租约丢失/需要新的node attempt | node attempt和业务发送许可不能混淆。先栅栏旧代；若旧send已胜出，保留该call原发送attempt走UNKNOWN查询。若可靠NOT_SENT胜出，按显式许可链/新代证据办理受控交接，原许可、各次lease/attempt和栅栏证明完整关联。不得由G5普通claim无条件搬移已提交call的attempt_id。 |
| 旧worker已拿到submitted ACK但尚未本地发送，新代已准备 | 原expectedAttempt仍旧，CAS必须失败；这条失败用例是启用新代的硬前置。 |
| 已send_started/引擎SUBMITTED后丢响应 | W2：只查原key/job，保持UNKNOWN；新请求号/节点重启/旧permit重投均不重新业务发送。 |
| 事实已持久但逻辑交付/许可ACK丢失 | W3：重复交付同事实、恢复同许可，不重新执行；一次逻辑结果与事件提交。 |
| 取证后、引擎短事务前取消/终态/换代 | 引擎复核拒绝许可；任何本地证据不使取消运行复活。 |
| 引擎已受理、技能激活/发送前取消 | 当前授权/claim/submitted拒绝或激活条件不成立；保留许可拒绝原因及历史，不能用本地取消伪称外部停止/撤销。 |

这些窗口是实施必测内容，不能在报告中以“通常不会发生”略去。跨服务不使用PG长锁/两阶段数据库提交；依靠不可变证据、唯一意向、显式代次、可幂等准备和本地CAS。若领取后原事实终态的回收/W1身份交接尚未实现，不得先开放证明重试入口。

## 必须先失败的验证矩阵

每个实现增量先真实PG关键失败测试，再最小修复；HTTP均用测试目录受控端点并独立计数。Spring/Nimbus票据过滤、PG唯一约束/行锁/事务和至少一次真实engine+skill跨进程纵切都要有证据；不能仅用内存Mock验证副作用次数。

| 组 | 先写的失败用例 | 验收判定 |
| --- | --- | --- |
| READY | 明确NOT_APPLIED、当前审批/范围有效、G5有资源 | 同call/key仅一项许可、新node attempt；只目标工具增加一次受许可提交，最终一次逻辑提交。 |
| READY | NOT_SENT可靠栅栏路径 | 原代submit=0，新代最多1；普通send_started=false其他状态不能代替证明。 |
| READY | 无G5执行槽/批量排队/交互拒绝 | 不先分配伪RUNNING attempt、不占错池、不越过容量硬闸外发；PG受理与实际执行状态可区分。 |
| READY | 同Agent节点多个已完成/UNKNOWN工具 | 已完成call与模型检查点不重做，未获许可的兄弟UNKNOWN不发送，call_seq/key不变。 |
| 证明 | proofRef缺失/错hash/错tenant或owner/run/node/call/attempt/version/contract/args/key | 全部拒绝、无新attempt/permit消费/外调；原事实保留。 |
| 证明 | 错job/key、404/5xx/超时、未配置标记、仍UNKNOWN、Mock事实 | 不形成可提交许可；不借普通网络失败解释NOT_APPLIED。 |
| 证明 | 回调终态或不同来源证明在取证/排队/激活之间胜出 | 激活拒绝、不归档覆盖终态、不发新业务；原确定事实仍可按原身份W3采纳。 |
| 请求 | 同requestId同body并发/ACK丢失重投/换副本 | 同一受理标识与意图，不生成多许可/多代；实际提交仍一次。 |
| 请求 | 同requestId改proof/call/expectedAttempt，或多个不同requestId同时消费同原代 | 409或读同一既有活动许可；唯一约束与call锁阻止双发。 |
| 当前权限 | 原AB→当前A、撤销角色、属性证据缺失、只有旧run.guard_ctx | 查询核实/许可/发送前拒绝，无宽结果复用或新业务。 |
| 当前权限 | 相同集合重排、原A被当前AB覆盖 | 合法覆盖可继续，交集不扩大原调用参数。 |
| 批准 | 已过期/拒绝/未知、错误skillVersion/args、过期发生在队列中或submitted前 | 不发送；有效期再次验证，角色合法的新确认仍走原流程。 |
| 批准 | 当前操作者ADMIN/EDITOR但无声明confirmRole；批准者不同且无法核实当前角色 | 不伪造确认，不因发起人角色代替批准者合法性。 |
| 参数 | 请求企图修改args/技能版本，或取证后PG原args/contract改变 | 原call重试拒绝；变化必须走新登记/确认，不能重写旧proof。 |
| 票据 | portal+SERVICE、混合aud且purpose=user、错误purpose/aud、坏签名、跨tenant/owner | 新入口保持严格内部票据边界，401/403/404与资源泄漏边界明确。 |
| LATE | 旧submitted ACK线程挂起→新代REGISTERED→释放旧线程 | 旧expectedAttempt发送CAS失败，不能借新binding发送；新worker仅发送一次。 |
| LATE | 旧query/ACK/DONE/UNKNOWN或outbox ACK在新代激活后返回 | 旧代只留证据，不改新代binding/fact/callback状态；不将旧body当新代结果。 |
| LATE | 旧合法CALLBACK成功/失败、同event重复/冲突/重投 | 原代归档可核实，迟到不丢事实、不覆盖新代，唯一逻辑提交。 |
| CANCEL | 取消早于取证完成、排队领取、激活、submitted CAS | 取消胜出则不发送，run/node不复活，许可无效；同锁序无死锁。 |
| CANCEL | 发送权已提交后取消、外部响应迟到 | 标为已授权在途，结果留痕；不宣称已停止或撤销外部效果。 |
| W1 | 受理/绑定/激活/发送CAS每个边界崩溃，另一副本恢复 | PG中恢复同意向与合法代次；能证明未发送才受控交接；旧线程晚回不能双发。 |
| W2 | 新代业务可能发送但ACK丢失/超时 | 只查询原key/job，旧permit/requestId不得再授权发送。 |
| W3 | 事实落库、逻辑交付/收件ACK丢失/多次outbox重投 | 外部计数不增，duration不重复累加，逻辑事件仅一次。 |
| 账务 | 原执行耗时+新代耗时+重复交付/对账等待 | 只累计真实各代执行段；模型预算/usage不重置，call统计仍1，重试次数可审计。 |
| 兼容 | 首次执行、原readonly RETRYABLE_FAILURE、G5公平队列/两池、CALLBACK、输出策略/发布评测 | 原合法路径保持；不能以新许可要求破坏readonly或绕过G5。 |

## 实施顺序与退出标准

1. G5和proof票据评审先关闭，核对最新源码/迁移编号和恢复接口。记录此次明确范围及待测窗口，不边改G5边实现重试。
2. 先补技能适配器 expectedAttempt显式传播与“旧submitted ACK借新REGISTERED”红测，保持现有UNKNOWN不重发。此步还不开放许可入口。
3. 实现引擎取证/当前授权核实、requestId/唯一意向及G5领取绑定；V216及必要技能V311只在本新工程，原契约不动。
4. 完成技能激活/归档、单次发送栅栏和旧代事实分类；完整覆盖跨服务激活拒绝与W1接管，然后才接通证明重试入口。
5. 先定向红绿，再冻结整个增量、独立规格/安全评审、一次最终全模块与前端受影响检查。至少一次真实两个engine副本+skill进程纵切：同请求并发、旧ACK迟到、取消、外部提交计数、PG历史/permit/事实及唯一逻辑事件相互印证。
6. 根任务统一部署本工程服务后再写实际纵切报告，分别记录协议夹具与真实供应商验收。任一旧代双发/终态污染/审批或scope绕过未关闭，不宣称本阶段完成，不启用自动UNKNOWN重发，不关闭整个G2或生产发布门禁。

尚未实现的UNKNOWN人工列表/操作体验、取消三事实、供应商永久未生效保证/幂等协议、生产IAM即时撤销、真实MCP/K8s及容量验收继续留在各自后续台账，不用本计划替代实现证据。
