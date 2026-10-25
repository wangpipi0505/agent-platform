# G6 实施计划独立审查

审查日期：2026-10-20。对象：`iam-service-authority-implementation-plan.md`（459 行）及 `iam-service-authority-root-decisions.md`。本报告为只读计划审查；唯一写入为本文件，未修改计划、源码、契约或部署配置。

## 两项结论

1. **需求符合性：方向符合，但需补充后通过。** 接受外部 IAM 客户端 profile、不自建生产 IAM、不透传门户令牌、旧 Actor 不补权、原批准者按冻结 confirmRoles 核实、合法 EDITOR 正例和连续迁移顺序均符合约束。长连接与历史材料当前权限的完整路径、集成 resolver 的独立信任入口尚未形成可验收闭环。
2. **计划可实施性/质量：请求修改后复审。** A/B 的纯策略及验签工作可以继续；以下三项应在对应 C/D/E 阶段实施前明确，不能只依赖 F 的宽泛矩阵补齐。此结论不要求真实 IAM 环境，也不阻断有限隔离夹具开发；`BLOCKED_IAM_SCOPE_ACCEPTANCE` 必须保持。

## 实质发现

### R1 — P1：长 SSE / 模型 stream 的授权生命周期未定义

**方案位置：** 第 150 行（每次新发送重取证据）、188–200 行（票据 60 秒、决定 5 秒）、314–331 行（接收端/取票迁移）、338–340 行（修改文件）、400–406 行（过期/撤销矩阵与集成 SSE 例外）。

**触发条件：** 已建立的门户观察 SSE 或内部模型 stream 超过当前决定/票据期限，期间主体撤权、grant 到期或 authority 返回 503；另一种情况是 ApiKey 吊销发生在已建立集成 SSE 或在途模型响应中。

**当前代码事实：** `engine-runtime/.../api/RunController.java:112` 在建流时取 Actor，`:149` 后异步写响应，`:202` 循环继续读取/发送事件；`model-gateway/.../api/ModelController.java:119` 捕获 Actor 后启动虚拟线程，`:133` 发送 chunk，`:145` 发送 final；`ModelCalls.java:409` 聚合供应商 stream，`:434` 阻塞到完成或超时；`platform-gateway/.../api/GatewayController.java:81` 持续复制上游响应。入口 JWT 校验本身不重入这些循环。计划只明确“流式路径同样取票/禁止 redirect”，未给这些异步入口传递或更新授权证据的任务，Task 4/5 文件表也未列上述两个接收端流式 controller。

**实现影响：** 实施者无法从计划确定一个长响应的授权是受理时冻结、定期续核，还是到期终止；只替换 Authorization header 可以通过当前短请求矩阵，却完全不覆盖开流后的路径。另一方面，若简单把所有 5 秒后 chunk 当作“新发送”而统一停止，会改变 v1.2 snapshot:2887 明定的集成“已建立 SSE 与在途调用完成当前响应后关闭”语义。第 406 行保留了这一例外，但没有区分它与门户流、内部流、后续新模型/技能动作及重连。

**所需补充：** 在 D/E 明列三类流的受理边界、在途响应例外、票据/decision/grant 到期和撤销的处理；明确异步上下文传递、重新取证触发点或有界响应授权、失败后的断流/清理，以及断流不自动取消 run。保留集成已有响应完成的设计例外，但禁止由它授权后续新业务动作。补有限 Clock/闩锁测试：开流后过期/撤权/503、集成在途完成正例、终止后的 Last-Event-ID 重连重新认证与历史事件范围检查；观察剩余输出与新业务发送次数，不能只断言握手状态码。

### R2 — P1：历史材料所需范围只有原则，遗漏真实跨轮历史加载的旧权限比较

**方案位置：** 第 148–150 行（属性语义与历史 required scope）、333–378 行（Task 5 文件/存储/恢复）、398 行（历史 B 材料拒绝矩阵）。

**触发条件：** 旧轮或旧检查点含 region B，运行创建时 guard_ctx 为 A/B，排队或恢复前当前 IAM 收窄到 A；新一轮/恢复模型上下文加载旧轮输出、错误或输入。下载/回放读取相同历史材料也存在同一范围判定需求。

**当前代码事实：** `engine-runtime/.../application/ChatHistory.java:16` 创建新轮前以传入 Actor 检查旧 guard；但 `:59` 的 `load(store, run)` 没有当前授权参数，`:79` 起比较的是历史材料 guard_ctx 与本轮持久 run.guard_ctx。`AgentDriver.java:75` 直接调用此方法。`ContextAssembler.java:38` 将 sessionHistory 加入消息，requiredLabels 从配置取得，而不是从每份旧材料得到。`ReplayService.java:23` 读取 snapshot/input/output/events 等；这些都是需要计划落实的当前消费点，并非指称原代码已经实现 G6。

**实现影响：** Task 5 指定改 AgentDriver/ContextAssembler、保留旧 guard 审计，却未指定 ChatHistory 的旧对旧检查要如何改，也未定义材料 required scope 的可信来源、字段形状、摘要/版本绑定及传播方式。所列 run_authority 的 initial_ceiling 是委托上限，不自动等于各份历史材料所需范围。实现者若只把恢复 Actor 换成 current，历史加载仍可能用两份 A/B 快照通过；若一概否认缺 metadata 材料，则新生成材料是否可在下一轮正常使用也没有落地路径。dataLabels 是限制义务而 region 是许可集合，现有统一包含关系也不能直接充当方案的新语义。

**所需补充：** 给出最小材料范围模型及保守规则（允许用完整历史 ceiling 作为保守 required scope，但需明确其安全含义和属性语义），规定哪些写入点与业务事实同事务保存、摘要如何关联，以及跨 run/chat/checkpoint/artifact/replay 的传播和缺项默认拒绝。将 ChatHistory/ChatSessions 的创建、幂等恢复与 load 路径列入 E，传入可信当前证据，而非再比较两个旧 guard。补“创建时 A/B、实际加载前变 A”以及“同 scope 的合法多轮恢复成功”真实路径测试，要求模型/下载/回放输出在授权检查前为零；不能只测试孤立的集合交集函数。

### R3 — P1：集成 policy resolver 尚未落为可实现的独立机器接口

**方案位置：** 第 80–81 行（gateway-only 机器动作）、113 行（独立 resolver 前提）、144 行（workload）、281 行（六 client fixture）、303–331 行（Task 4）、394/406 行（集成完整链）。另见 root 裁决第 6 行的“authority 与 resolver 的机器动作和签发域隔离”。

**触发条件：** gateway 已验 ApiKey/集成短票据，authority 必须在签发委托前，或后续 grant 恢复/当前核权时，从 app PG 获取 credential ACTIVE、归属、claim_limits、策略摘要。

**当前代码事实：** `app-management/.../api/IntegrationController.java:42` 起现有 internal 接口是 origins/credentials；`IntegrationService.java:102` 的身份校验仅允许 `_gateway` + SERVICE + GATEWAY_AUTH。`authenticate` 的返回还含 `secretRef`。计划明确替换该 gateway 特例并继续把 credential/origin 机器能力限定 gateway，但没有新增 authority/resolver caller、独立 policy handler、所用认证/签发域和最小返回 DTO 的任务。六 client 测试表也没有 resolver 调用身份。

**实现影响：** 依计划现有路由表严格执行默认拒绝时，authority 的独立 policy 查询无允许边，集成交换/恢复无法通过。直接复用 gateway 身份或让测试 authority 静态返回“当前策略”又不能证明真实 app PG 的吊销/上限变化闭环；把 resolver 接入通用 `requireWorkload → authorizeWorkload` 流程而不明确独立认证路径，还可能让 authority 查询策略时再次依赖 authority 决策。root 已接受 profile 原则，不能代替该端点和信任环路的具体技术定义。

**所需补充：** 在 C/D 增加或明确映射独立只读 policy resolver：固定 method/path/caller/action、独立认证验证路径与信任配置、tenant/credential/app/biz/subject/策略版本绑定、最小必要返回字段（不暴露 secretRef），并说明该读取不依赖尚未签发的用户票据，也不形成 decisions 的递归调用。若采用外部等价 resolver，明列实际 adapter DTO 与隔离服务实现责任，不能只留一句“将来 IAM 提供”。新增有限真实 HTTP 链 `gateway → authority → app policy resolver → authority → downstream`，以真实 app 策略修改/吊销后原 grant 的 exchange/resume/authorize 被拒作为验收；错误 resolver caller、跨 credential/subject、不可用同样拒绝。

## 已核对且不列为问题

- caller/target/action、精确 handler 登记、未知 handler/default target 拒绝与 tenant/owner 二次核验已明确；最终清单安排在 Task 1 生成，不能因尚未生成就判定所有边遗漏。R3 是其新增基础设施依赖尚无任务闭环的问题。
- 机器启动凭据采用无用户参数的 WorkloadCredentialResolver，避免为 IAM client 凭据伪造用户 Actor；生产不使用共享 HS256/SERVICE 授权。原有业务 SecretVault 仍属于后续实现核查边界。
- 旧运行无 grant、撤权后事实保留不外送、恢复重新授权以及未知外部写入不自动重放均明确；本次没有要求撤权后开放纯机器事实豁免。
- 原批准者身份和本 call 的 roles/confirmRoles、合法 EDITOR 正例已写入 Task 5 和矩阵；未发现需要统一改为 APPROVER 的依据。
- 候选 engine V216/skill V312 后接 G2 V217/V313，实施前按实际最大迁移号复核、不建空占位、不用 outOfOrder，符合 root 裁决。本次未访问数据库来确认实际 Flyway history。

## 阅读范围与未验证项

读取：审查 brief、规划 brief、完整计划、root 裁决、工程 AGENTS、implementation-brief、contracts/README；按问题核对 snapshot 中 SSE/集成吊销原文及当前 SecurityConfiguration、GatewayController、IntegrationAccess、IntegrationService/Controller、RunController、RunService 定位、ContextAssembler、ReplayService、ChatHistory、ChatSessions、AgentDriver、ExecutionRecovery、SkillOutbox、ModelController/ModelCalls 相关源码。使用 code-review 技能作审查流程；所有当前代码事实与计划建议分开描述。

没有运行 Maven、测试、服务或数据库动作，没有读取 `.env`、vault/masterkey、真实凭据或相邻工程；没有改算法/Schema、实现者 target 或其他文件，没有重放资源审查。未独立打开计划所列 Spring/RFC 外部资料，因此不将其宣称为本次已验证来源；发现依据是本工程设计、计划与实际源码，无需以外部协议假设代替证据。

没有真实 issuer/JWKS/token/decision/resolver 环境签收，未验证生产吊销时延、供应商流取消、K8s/网络策略或密码学实现。该报告不是已实现/已通过测试/可生产发布的证明。

---

## 返修复审（2026-10-20）

本节追加复审结果，保留上文首次 **REQUEST_CHANGES** 历史。以下计划行号均针对本次冻结的返修版本；不对应旧基线行号。

### 材料与差异核验

已读返修记录、完整基线、真实 diff 和当前计划，并与原审查及 root 裁决核对。独立计算的 SHA256 均与返修记录一致：

| 材料 | SHA256 |
| --- | --- |
| revision-baseline.md | `be3d88ea5afd3752082297010b50daa84d7bb0cb83f1b7388b8ce9dfd5be223d` |
| 当前 implementation-plan.md | `792b502c25973f325366d456b8de4be2f46362f1242d2ff6245a85aebb3ebfaf` |
| revision.diff | `844a1778d6e3c48010b8ce4771dd5eefd91cd5f24b1a2427f9370ec7c8d935e7` |

执行 `git apply --reverse --check` 只读检查成功；`git apply --numstat` 为 144 行新增、7 行删除。重新生成 `git diff --no-index --no-ext-diff --no-color`，仅同返修记录一样归一旧文件名及换行后，与保存 diff 完全一致。未应用或反向应用 patch。

独立计数为 7 个 Task、48 个未执行实施 checkbox、9 个文档自检 checkbox；它们不是 57 项功能测试，也不是 48 项实现已通过。PowerShell Get-Content 为 596 个内容行，返修记录的 597 可由末尾换行分割口径产生，不列实质问题。

### 两项复审结论

1. **需求符合性：主体符合，仍需修改。** R2 的历史材料闭环与 R3 的 resolver 无环信任已补到可实施的计划层面，新增管理端宿主 JWT/session 配套在认证边界上可接受。R1 的长流方案已有实质补强，但集成在途例外与材料当前授权接口仍冲突；另需明确持久恢复与原门户令牌过期的关系。
2. **计划可实施性/质量：仍为 REQUEST_CHANGES。** 不要求真实 IAM 环境、不要求本次实现代码；A/B 和无冲突部分可继续准备。以下 RR1 应在 C/D/E 交接前闭合，RR2 应在 C/E 恢复接口冻结前明确。原三个问题并非仍全部原样存在；本次未闭项已收敛为具体接口交界。

### 原审查项逐项处理

| 原项 | 返修落实与独立判断 |
| --- | --- |
| R1 长响应 | 第 157–175 行定义 response-only open/check、来源签名、原期限、watchdog、异步参数和集成 drain；第 382、404、418、443、530–537 行分别落 common、真实 controller/connector/复制循环、有限 Clock/闩锁及 wire 计数。已解决“只改取票 header”的原缺口；但尚不能整体关闭，见 RR1、RR2。 |
| R2 历史材料 | 第 179–212 行明确可信完整 ceiling、payload/version/父链摘要、许可要求与 dataLabels 不同语义、同事务 side table 与受信 manifest；第 454、462、485–487 行落 ChatHistory/ChatSessions、SUMMARY/MAIN/RETRY、checkpoint、artifact/replay 的真实路径和正反例。旧对旧 guard 比较有明确删除任务，原 R2 的独立缺口可在计划层面关闭。与 drain 共用读取接口的冲突并入 RR1，不能当已实现材料授权。 |
| R3 resolver | 第 85、216–241 行定义精确机器路由、独立固定公钥域、请求 assertion/nonce/body 绑定、限定主体的 PG 查询和最小 DTO；第 386、405、416、442、538 行落 fixture/真实 app endpoint、revision 事务、PG 修改后 exchange/resume/authorize 拒绝及 resolver→authority=0。普通业务签发链不再参与 resolver 自身认证，原 R3 可在计划层面关闭。生产私钥与等价 profile 仍须日后独立签收。 |

### RR1 — P1，未闭：集成 drain 的材料读取仍要求已经失效的普通当前授权

**计划位置：** 第 167–173 行允许已受理 INTEGRATION 响应在票据/grant 到期、撤销或 authority 503 后完成原响应，且继续按每份 manifest 限制范围；但第 195 行要求验证当前证据到期、第 201 行跨服务 manifest 需要当前 delegated ticket，第 210 行将 SSE 历史事件纳入材料核验，第 462 行只定义 `MaterialAuthorization.require(scope, VerifiedAuthority)` 与 `MaterialScopeStore.read(ref, VerifiedAuthority)`，均要求当前有效性。第 443、531、537 行的集成完成正例没有落实这两种能力的衔接。

**真实触发路径：** 当前 `engine-runtime/.../api/RunController.java:227` 每轮从 PG 查询下一条 run_event，`:259–264` 读取其 seq/payload 并发送，并非握手时把整个响应材料预读完毕。按 E 的新规则，这个位置必须先读该事件 manifest 再通过材料读取接口。若开流后 grant 到期或 authority 503、当前决定随即过期，下一条属于原 run/原 ceiling 的合法事件仍没有有效 VerifiedAuthority 可传；模型 final 或跨服务接收完整结果并核 manifest 时也要处理同类边界。

**影响：** 严格执行现有材料 API 会令合法 drain 正例断流，不能兑现 v1.2 snapshot:2887 的当前响应完成语义；为了通过测试而跳过所有材料检查、复用过期 openingAuthority 冒充普通 current，又会破坏 R2 和默认拒绝。文字上说“原 ceiling 内可发”尚未提供可实现的读取能力，不能只在 StreamAuthorityGuard.beforeWrite 放行。

**需补充：** 明确一个仅完成既有响应的材料读取/发送判定与调用位置，与授权新模型/技能/下载/回放操作的普通 VerifiedAuthority 分开；名称和实现方式由 planner 选择。该能力仍须验证签名开流来源、owner、原 responseId/资源/期限、材料版本/摘要与开流范围；不能转换为通用 current、建新 response 或用于下一业务动作。说明 manifest 在本域/跨域何时取得；若需要在 drain 时跨服务读 manifest，必须明确限定能力，不能临时使用已失效通用票据。

**验收补充：** 用真实 RunController + PG event/material_scope，第一事件后以闩锁暂停，在第二事件尚未读 payload 时分别推进 grant/decision 到期、撤销及 authority 503，再继续。原 run/原 scope 的下一事件应按选定 drain 规则完成；加入同 run 超 ceiling、错 manifest、错 version、另一 run、hardDeadline 到期的事件必须拒绝，且后续模型/技能/新 SSE 请求为零。相同输入走 PORTAL 观察流则不可继承 drain。测试必须实际经过材料读取接口，不能只测预先缓存的两个 chunk。

### RR2 — P2，未闭：持久 grant 恢复与原 portal JWT 硬到期的关系未确定

**计划位置：** 第 116、138、402、460、484、488–497 行允许后台仅凭持久 DelegationRef 和当前 authority 恢复；第 165–166 行却把原 portal JWT 到期设为所有 PORTAL 来源内部模型流的硬终点，第 173、386 行禁止各 hop/check 重置 sourceExpiresAt。计划没有声明持久 grant 必须短于原 portal JWT，也未说明后台新恢复操作的 sourceExpiresAt 来源和区别。

**触发与实际路径：** 原门户 JWT 已过期，但 authority 签发的持久 grant 仍有效、当前 subject/材料范围仍通过，后台 `RunService.driveRecovered`（当前源码 `:1539`）恢复执行，走 `ModelExchange.java:319 → RuntimeClients.chatStream`。这时没有新的门户 HTTP 请求或用户 JWT；正常恢复是否能建立新模型流，按目前两组规则得不到一致答案。

**影响：** 按原 portal expiry 硬拒绝，E 的“有效 grant/current 可恢复”正例可能无法进入真实模型调用；由调用者自行把 sourceExpiresAt 换为当前票据到期，又与“不可换 hop 重置”冲突。此项不是允许过期用户令牌重入，也不是要求绕过当前核权，而是要求把独立持久委托的寿命与交互响应的寿命讲清楚。

**需补充与验收：** 明确 persistent grant 是否可跨原用户 JWT 寿命，及 authority 如何给后台恢复的新动作签发受限来源/有效期；如果首版决定一律受原 token 寿命限制，也应明确恢复限制与重新授权步骤，避免同时承诺相反行为。添加“原 portal token 已过期、grant/current 有效、无需保存/重放用户 token”的真实恢复用例及被撤 grant 对照用例，覆盖 ModelExchange→model stream；同时保留旧门户观察 SSE 到期关闭，不能借后台委托替旧浏览器续流。该决定属于软件 profile，可用隔离 fixture验证，无需请求真实 IAM。

### 新增管理端配套的独立判断

对第 247–253、414、424、444、534、539 行的技术判断为**计划层面可接受，未发现需另列的阻断问题**，并非依据 root 同意直接通过：

- `/auth/session` 只接受已验门户 JWT，并与当前 authority 的 tenant/principal 一致绑定；四字段白名单、roles 交集、拒绝 integration/workload、非 permitAll、401/403/503 的边界明确。expiresAt 不含短 decision 的寿命可接受，因为这里只供 UI 会话显示，计划明确不得当作后端权限缓存，各业务动作仍在线核权。
- 同源宿主 provider 与 SDK integration ticket 用途分离；只在内存持 token，无 query/hash/localStorage、无 refresh token 存储；生产无 provider/会话不可用均无 dev-token 回退，原开发模式显式隔离。
- 新身份先 generation 递增/清理/abort，旧 generation 的成功或失败都不回写，旧401不能清新会话；到期、退出/卸载清理和不自动重放 POST 已定义。实施测试须按该明确语义覆盖全部迟到结果，不能仅沿用现有 createApi 的 token 字符串比较。
- 当前 `web/shared/api.ts` 只有 dev login 和内存 Pinia；`web/admin/src/main.ts` 直接 mount，确无现成管理端 provider。当前 observer 对普通 stream_reset 继续取 snapshot；计划已把共享 observer/events 列入修改，授权 reset 停止观察且不 cancel run。浏览器验收明确真实签名链而非 page.route.fulfill，作为待实施任务成立。

本次继续采用 code-review 流程，读管理端 Vue 入口时查阅 vue-skilld，只按项目 Vue3.5 约束核查，没有引入技能中 beta API。再次核了真实 SSE 事件读取、ModelExchange 的实际模型 stream 调用和恢复入口；未读取当前算法实现、未操作实现者 target。所有工具执行仅为文件读取、摘要/差异/文本计数与本报告追加。未运行 Maven/npm/浏览器/数据库/服务或实际 IAM 请求；R2/R3 的计划关闭和门户计划可接受均不表示功能已经实现或生产已获签收。发布门禁保持 `BLOCKED_IAM_SCOPE_ACCEPTANCE`。

---

## 第二轮返修复审（2026-10-20）

本节只评审第二轮计划返修，保留前两次 REQUEST_CHANGES 及其事实依据。以下行号针对本轮计划 SHA256 `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14`。

### 版本与只读检查

已核第二轮返修记录、v2 baseline、真实 v2 diff、当前计划及 root 裁决新增第 8 项。v2 baseline 的 SHA256 精确等于上一轮已完整审阅版本，未以返修摘要代替差异审阅。

| 材料 | 独立核得 SHA256 |
| --- | --- |
| revision-v2-baseline.md | `792b502c25973f325366d456b8de4be2f46362f1242d2ff6245a85aebb3ebfaf` |
| 当前 implementation-plan.md | `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14` |
| revision-v2.diff | `a8c6847bb420b8b9eb17b45eb27c00355ecba6d5bc10841c9cdfce74c77c831c` |

`git apply --reverse --check` 成功，未实际 apply；numstat 为 59 行新增、16 行删除。重新生成 no-index diff，仅归一旧路径和换行后与保存 diff 完全一致。实际计数：639 个内容行、7 个 Task、52 个未执行实施 checkbox、11 个文档自检 checkbox。没有运行任何功能测试，这些数字不能当作测试或已实现数量。

### 两项结论

1. **需求符合性：通过（计划层面）。** RR1 与 RR2 的具体矛盾已经消除；原响应完成例外保留 v1.2 的集成在途语义，后台新操作与原门户观察响应的寿命分离。原四契约、合法 EDITOR 确认、原批准者核验及生产默认拒绝边界未因本轮返修被削弱。
2. **计划可实施性/质量：通过（APPROVE_PLAN）。** 本轮未发现仍需修改后才能实施的实质阻断；可以进入受既有隔离及分阶段审查约束的软件实现。此结论仅对冻结计划负责，不是功能 APPROVE、测试通过、真实 IAM 能力签收或生产开放许可。

### RR1 关闭依据：原响应完成与普通 current 已形成不同能力

- **签名能力明确。** 第 182–186 行把 `ResponseMaterialPermit/Access/ResponseMaterial` 与普通 `VerifiedAuthority/AuthorizedMaterial` 分开；permit 在有效 INTEGRATION open 时签出，绑定 issuer/用途/主体、operation/response、producer/consumer、action/resource/request 摘要、原 ceiling、材料选择器和独立完成期限。读取和发送仍检查 permit 自身签名/exp/holder 状态，不把过期服务票据或旧 Actor 当 current。PORTAL/DELEGATED、缺 permit、关闭/重启丢失 holder 均不能 drain；模型尚未实际首发也不能借 permit 首发。
- **真实下一事件有落点。** 第 188 行明确将 RunController 原本一次查出 payload 的路径改为 metadata-only 的 nextEventRef，再走 readForResponse；同版本快照内取得 payload，发送前核实际摘要与停止位。第 480 行把第一事件后、第二事件 payload 尚未读取时的 grant/decision 过期、撤销及 503 列为真实 PG 联测；另一 run、超 ceiling、错 manifest/version/deadline 和 PORTAL 对照均有读取/输出计数。它解决了上轮只允许 buffer 写出、实际材料接口仍拒绝的问题。
- **跨服务 final 不再补发无权 manifest 查询。** 第 190–192 行规定 model 同事务保存事实/scope，在原连接先发私有 material_manifest 再发 final；RuntimeClients 校验已认证连接及对应 permit/响应关联，经过 acceptForResponse 后交 ModelExchange 记账。私有帧/header 不传门户或 SDK；缺失/乱序/错绑定/跨连接复用拒绝，drain 新 manifest HTTP 为零。当前 RuntimeClients.chatStream 的实际分支在 `:117–118` 直接处理 chunk/final，本计划已明确替换该接收点，不能只修改 model 发送端。
- **能力没有延伸为后续动作。** 第 194、230、496 行禁止 permit 换票、resume/open、新模型/技能、下载/回放或新观察响应；返回的 ResponseMaterial 不能作为新动作 assembler 的 AuthorizedMaterial。正常 PORTAL/DELEGATED final 仍使用 fresh current。第 438、480、569、581 行分别安排能力级、真实接收/材料接口和最终链路正反例。

该关闭是对计划闭环的认可。特别是第 480 行联测依赖 E 的表/接口，必须在那些依赖可用后实际执行；D 的流基础测试不能代替 E 材料路径，也不能提前把该关联项计绿。第 581 行明确无完整行为证据时相关实施项仍未完成。

### RR2 关闭依据：新后台 operation 与原响应 forward 不再混用

- 第 198–200 行选择可跨原门户 JWT 寿命的持久 grant，但必须由 authority 当前检查后签发新 DELEGATED operation；origin PORTAL 只作审计。action lifetime policy、grant 到期、签名 operation expiry共同限制新动作，缺 policy/字段拒绝，不由客户端补 source 或 now+TTL。
- 第 202、415、427–433 行把 `forward` 用于同一操作的跨 hop 精确子票，保留 operationId 与截止；只有明确的新动作检查点可请求 `resume`，仍重核当前主体、材料和业务守卫。原 PORTAL/INTEGRATION 转发不能借 resume 换成新后台来源。初次 exchange 的 OperationBinding 和持久原认证审计也已在第 519 行说明。
- 第 204 行保持真实 driveRecovered→ModelExchange→RuntimeClients→model stream 路径；第 537 行给出原门户 exp=t+10、grant=t+300、t+11 后恢复的可观察正例，以及撤 grant/收窄/伪来源/改期限、DELEGATED 流中撤权/503/operation到期反例。正常首次 provider 外发恰一次，已记录 response 不因新授权重复发送。
- 原门户 observer 仍在原 token 到期关闭，不能以后台新 holder/期限续旧浏览器流；DELEGATED 不享受 INTEGRATION drain。第 570、581 行将该差异纳入最终真实链路验收。root 第 8 项提供了软件选择，本次通过依据是上述签名/调用/时限/验收边界与该选择一致，而非把裁决本身当作技术证明。

### 本轮其余检查与边界

Task 1/2 第 363、388 行已明确：类不存在只算编译接缝失败，最小可编译骨架后必须观察真实错误授权/配置/验签路径的行为断言失败再修复，不能把编译失败算关键红。R2、R3 和管理端配套在本轮无削弱其既有结论的修改；其所有真实 PG、HTTP、浏览器及错误路径验证仍待实施。

本次核对了 v1.2 snapshot:2887 的在途响应规则及原审阅的真实事件/模型读取路径，补读 RuntimeClients 的现有 final 接收分支。未扩大到当前算法实现、未读真实凭据、未改源码/测试/target/计划/裁决，唯一写入是本节追加；未运行 Maven/npm/浏览器/数据库/服务/实际 IAM 请求。response-completion、DELEGATED operation 和 resolver 均仍是拟实现的客户端适配约定与有限测试 fixture；生产须另行签收等价能力，`BLOCKED_IAM_SCOPE_ACCEPTANCE` 继续保持。

