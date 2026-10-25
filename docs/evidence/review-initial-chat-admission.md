# 首次无会话 CHAT：独立 Spec / Quality / v1.0 适用性复审

日期：2026-10-23。范围：actual684 → final-source2（688 项，7 MOD / 4 ADD / 0 DEL）。

**Spec Compliance：Issues found（I1）。Task Quality：Needs fixes。代码发现 C0 / I1 / M0。PROPOSED：NOT_ACCEPTED。**

本报告是全新独立任务复审，不是实现者自评，也不批准任何条件档。ALL / noBackground / NOTAccepted 表示审查工作交付及不自批验收，不表示组件已验收。旧四组件 NOT_ACCEPTED 没有被用作本组件的自动结论。

## 1. 输入、审查方法与实际边界

第一文件完整读取 `.local/initial-chat-admission-review-brief.md`：8488 bytes / SHA256 `8391fe061dd4e609ace3d96ca7ccbadd8c09731df43f2950c421106c32bab88a`。本次以该实际完整授权为准，另读 draft 只取风险清单。

完整读取原 grant、actual684 rebind、execution-context、plan、作者报告及最终原生上下文包；阅读 AGENTS.md、implementation-brief、contracts/README 和冻结设计针对 F1/F2/F3、trace 的有限相关段落。初次合并输出出现截断后已窄范围补读。使用 SDD task-reviewer 与 code-review-expert 检查口径；固定授权覆盖技能中的通用 Git、提交、补测试或派生 agent 工作流。本次没有子 agent，没有运行 Maven/Java/Python/Node/Git、测试、业务资源、DB、端口或进程检查。所有 exec 的 cwd 均显式为 P；技能文件为开发者提供的固定只读说明，业务文件只在 P。唯一写路径为本报告。

P = `G:/207/项目/算法重组工具/代码/agent-platform`。下文 E = `engine-runtime/src/main/java/cn/agentplatform/engine/`，T = `engine-runtime/src/test/java/cn/agentplatform/engine/`；引用行均为本轮 canonical 行。

| 核心输入 | bytes | SHA256 |
|---|---:|---|
| `.local/initial-chat-admission-review-package.md` | 103838 | e0cfe5bb532995625a0df94af04bd0c1e698a7bafbb8f780fa6772aac2e6a40b |
| `docs/evidence/initial-chat-admission-report.md` | 30388 | 875afc6214b2edf8b1276b635c3dec76ab5903e302becc98c0230e39456ec2ee |
| `.local/component-conditional-acceptance-rule-v1.md` | 6866 | 0817c6021decb015a6daef3ebdb4f3d1fe301babdc91ed0d38748cd4d9404b05 |
| `.local/initial-chat-admission/final-source2/source.json` | 136836 | 2d9378c13c487bfe4fe40d191bb961dd1e76f2f4058c83a0486015c4e7190f20 |
| `.local/initial-chat-admission/final-evidence/test-summary.json` | 52176 | 24b4ab8642d7b3507dfb6124d416bec2d415ea7a974cea4f659101ff758d8460 |
| `.local/initial-chat-admission/final-evidence/cohort-bindings.json` | 4671 | 866f1c808e4de22e61ed9afe59f20544f212d3568dbb700d49ad79a39e869e95 |

本报告独立核对上述字节/摘要，并完整读取、解析 final source / tests / cohorts / binding-audit / closure / control / runtime files / dependencies / package equality 的实际 JSON，仅输出安全元数据。生产 diff 全文阅读；包中新增 fixture/test 源先省略全部字符串和注释值再输出审查，未 raw 打印 helper，不使用 keyword-only 过滤。未读取私有 JWT、key、Properties、argfiles、原始 provider/user payload 或 raw XML。测试段去字面量投影适合验证控制流和断言结构，不能据此声称独立逐字核实了每条私有 SQL 或每个断言字面量；行为细节结合作者报告、实际结果摘要和 root 有限物理复核。

仅为判断明确风险，补读 diff 截断的 RunService 请求/回读事务、TraceStore status/initialNode、ModelInputMaterialStore 存储验证、MaterialScopeStore reader/floor 及 RunEventReader Work 方法；另读 RunController 的实际 POST 转交和 PortalRunEventsService 的出站 liveness/chunk 声明。没有重审父组件旧树或重新执行绿色测试。

## 2. Spec Compliance

### I1：新 CHAT 分支未拒绝 POST 顶层显式会话／历史标记

**等级：Important（功能与有限准入规格），未关闭。**

位置：`E/application/RunService.java:470`–471（新增分支），相关公共请求 gate `:140`–179、首次回读 `:376`–389、容量回读 `:399`–440、事务内回读 `:543`–578、统一 `verifyReplay:898`–921；规则 `E/domain/InitialChatRules.java:15`–23。实际入口 `E/api/RunController.java:39`–58 原样传递整个 JsonNode，没有 DTO 或独立层拒绝这些顶层字段。

新分支只执行 `requireFirstChat(source, req.path("inputs"))`。`InitialChatRules.requireSources` 检查 snapshot 和 inputs，不能看到 POST 顶层；`requirePublishedRequest` 的顶层拒绝表已有 sessionId/historyRefs/materialRefs，却遗漏 chatSession/chatTurn/sessionHistory/history。因而在其余鉴权、发布版本和 inputs 均有效的请求中，把显式历史或会话标记放在顶层，会绕过本轮有限资格检查并走真实 CHAT admission；这些标记没有保存或参与模型组装。对已存在 requestId，早期回读先于新 CHAT 验证，统一 verifyReplay 只比 inputs 等原身份字段，加入顶层历史标记仍可能回原运行。

最小静态反例：在已证明有效的无会话 POST 对象上添加顶层 `history: []` 或 `chatSession: {}`，其余字段不变。沿现有条件不会触发任何相关拒绝；初建可建立无会话 CHAT，重放则可读取原结果。这是代码路径结论，本 reviewer 没有执行新 HTTP 测试。非空值有同样问题。它不证明读到了数据库中的真实旧会话，也不代表所有未知业务字段都必须拒绝；发现只针对本组件已明确命名并规定拒绝的 session/history 保留名。

规格依据：原 grant 第19行禁止丢弃 session fields / old history，第52行要求 session/first reserved turn/second turn/history 显式拒绝且无意外 rows/calls；实际 rebind 第14行和独立 review brief 要求 session/turn/history 不漏检。冻结 v1.2 的 F1/F2/F3没有定义这些 JSON 名字必须位于顶层，本发现不伪造该契约细节；直接依据本次更窄的有限准入承诺。已有 inputs/snapshot 的正确拒绝不能补足此遗漏。

**最小修复：**在三处 requestId 回读之前的共享受保护请求 gate，对本轮明确禁止的 chatSession/chatTurn/sessionHistory/history 等保留名使用字段存在性拒绝（包含 null/空容器），与已有 sessionId/historyRefs/materialRefs 一致；或增加等效、不会被回读短路的明确 CHAT 请求校验。不要删除这些字段后继续，也不要为此扩大 session 支持或修改四契约。

**最小验证：**真实既有 admin 接口分别测有效来源的新 requestId 与已接受 requestId，输入顶层上述字段（至少空和非空；null 按存在性规则）；均应拒绝。初建断言无新增 run/authority/node/model invocation/provider ingress；回读断言原结果及 attempt 不变、没有第二发送。保留正常无会话回读、inputs/snapshot 拒绝和旧 TASK/FLOW 正控。本发现可在本次授权的 RunService 与新测试文件内修复，不要求新增依赖、migration 或扩大文件范围。

### 已确认符合的主要边界

| 边界 | 证据与判断 |
|---|---|
| 实际发布与 CHAT 身份 | RunService:467–494 从受保护 published HTTP 选择 CHAT，保留原快照，不走 legacy enrich 后删字段；requireFirstChat:190–194 做输出声明/FrozenModels 预检。RunAuthorityStore:22–77 单独入口严格校验后直接 INSERT CHAT，不是 TASK relabel。 |
| 原子 AGENT/scopes | RunService:537–755 在原 admission 事务内持有 requestId 锁并创建真实 protected AGENT；:742–744 使用 null node_def、step1、精确 `{type:AGENT}`。RunAuthorityStore:40–77 不另开事务并标记失败回滚。 |
| 既有幂等身份 | replayPublished:219–238 核对 tenant/principal/app/mode/原触发；statusPublished:329–348 重新校验当前 purpose/root；verifyReplay:898–922 比原 inputs/version。I1 是其缺失的顶层资格检查，不是已有身份核对全部失效。 |
| 独立准备入口 | ModelInputMaterials:40–87 的 prepareInitial/prepareFirstChat 经私有实现复用；ModelInputMaterialStore:69–176、:179–205 在实际 run/authority 锁下核对持久 trigger，insert 重新读取/比较 supplied source，不靠调用方数据 mint authority。 |
| 首轮关联与旧 TASK | InitialChatRules:15–23 拒绝 snapshot/inputs 的 session/history；ModelInputMaterialStore:93–105 拒绝已链接 chat_turn/invocation/tool；TraceStore:1068–1097 重查完整 durable run、唯一节点与 MAIN/round0/outputAttempt0 invocation。未链接 session 的 snapshot.chatSession 也被拒绝。旧 TASK 仍要求 LLM/initial 的精确表示；不能误写为所有位置已覆盖，I1 仍在。 |
| 真正持久触发选择 | RunService:1921–1933 与 TraceStore:1108–1115 从 protected durable run 选择 CHAT 准备入口；RUN_STARTED 在 TraceStore:1175 使用经验证的持久 trigger。 |
| observer 与 writer 分离 | MaterialScopeStore:564–610 新 readObservedValue 使用 StatusReadProof；没有传 observer proof 给 writer。readCurrentValue:545–554 的共同 immutable reader解决 admission 与 local execution purpose 差异；原 current/reader 检查仍在，后续 mutation 仍走原 writer。known/append 写集合未扩为 model-input 通用动态版本。 |
| 精确 immutable 身份 | MaterialScopeStore:573–610 核对单行、schema/profile/producer、owner/root/run/snapshot、manifest摘要、m_ locator、raw request SHA/version、semantic等价、binding、scope摘要和三个完整原始 parents/floor。scope exact equality 保留在 TraceStore:510–537；并非 containsAll 替代完整集。 |
| bounded SSE | RunEventReader:138–207 将唯一 model-input 纳入 plan，核对物理行数量与 scope 集、locator、根/parent/floor。MaterialScopeStore:671–688 先probe尺寸并计metadata/root/DOM/raw/canonical预算，再读payload；Work:339–387 保留 live/query剩余时间和出站证明。新增哈希不把 normalized JSON 当 raw SHA。 |
| status 与出站 fence | TraceStore:387–425 在独立当前观察授权、RR 和全scope授权后读业务值，返回后再检当前；:462–468 校验 CHAT 单AGENT。PortalRunEventsService:203–218/245–247 仍在写/flush前后检当前。:280 明确 chunkAvailable=false/chunkReplay=false。 |
| 有限 scope | native package 的7MOD/4ADD与grant相符；无 POM/migration/模型/common/IAM/catalog/前端生产变动。initial-chat-main-v1仍是既有两消息/零工具/三parents，默认IAM/D3、session、v1/integration、public token/chunk/cancel未由本diff开放。 |

## 3. Task Quality 与测试证据

**Task Quality：Needs fixes，C0 / I1 / M0。**没有为了凑数把假设、历史记录缺口或旧组件状态列成生产缺陷。新增纯规则类和命名准备入口保持有限职责，事务内不引入跨服务网络调用，观察权限没有混为执行许可。唯一必须修复的具体代码发现为 I1。没有建议无关架构重构、删除现有能力或扩大依赖。

本轮真实依赖证据是隔离 PG、Redis、实际 app/model HTTP、受控 IAM/provider/tokenizer。它不是生产 IAM/D3 或真实模型环境的签收。test-summary 的24个 groups包括失败/诊断/早期部分通过；仅下列9个选定 group各记录1方法、1 test、0 failure、0 skip、0 containerErrors，不合并声称一次最终完整 suite。本 reviewer另逐一读取这9个 result ref并核对实际SHA与摘要相等，返回均exit0；八业务result无timeout/live root，pure rule的guard也记录exit0/无timeout/rootAliveAtReturn=false。

| 群组 | 实际意义和保留限制 |
|---|---|
| observer-green6 / sse-fixture2 | 真CHAT→AGENT→一次模型发送→SUCCEEDED→fresh status/事实SSE；早期observer-green1不是此终态/SSE测试。 |
| request-boundary1 / negative-fixture1 | 七个真实HTTP非法形状拒绝、无CHAT行；合法重放同run、inputs冲突409。未覆盖I1顶层保留名。 |
| observer-current1 / negative-fixture1 | Last-Event-ID 3后续事实、ahead999 reset/terminal snapshot；当前降权403/outage503，持久结果不被改写。 |
| immutable-negative1 / negative-fixture1 | 最小engine角色对专用新行的UPDATE被原immutable trigger拒绝、changed0、之后observer200。不是reader接收污染行后拒绝。 |
| response-recovery1 / recovery-fixture1 | durable response后故障，旧worker/receiver关闭、注入lease过期、新scheduler claimSpecific→poll→driveRecovered；同attempt/provider1、SUCCEEDED。 |
| dispatch-unknown1 / unknown-test1 | durable DISPATCHED尚无HTTP，注入到期后保持UNKNOWN，provider0、同attempt、没有伪造成功或重发；run仍RUNNING。 |
| concurrent-final / final-tests | 两HTTP200同run、唯一row/attempt/provider1、SUCCEEDED、两个业务permit归还；只证明该真实并发结果，不证明两事务均在首次commit前入DB。重构receiver发生在同JVM，不是进程重启。 |
| private-task-final2 / final-tests2 | 旧私有TASK实际provider1并SUCCEEDED；之前private-task-final失败保留。 |
| finite-source-policy / final-tests2 | 纯规则正例与八配置/八输入形状拒绝，未启动server；不计真实依赖业务链。 |

REDA red-a11 的实际app发布CHAT/numeric版本/FrozenModels/FLOW正控后，在旧GraphRules FLOW gate业务拒绝；REDB observer-red1在真正新CHAT上被旧TASK-only metadata gate拒绝。setup/compiler/readiness失败不算RED。observer-green5的probeObserved model-input拒绝后有green6实际SSE通过。未把fixture跨秒iat/exp风险追认为原失败JWT的已证根因。

尚未本轮新跑的JSON/schema正例、完整材料字节/预算污染矩阵、lock-wait权威变化及全tenant/principal/root/version/origin/purpose/currentOnly/stale排列，由作者明确披露；未把父组件标签冒充本轮PASS。root有限收敛后不要求重跑整个历史。I1是本次新增入口的具体遗漏，有最小新负例，不属于无依据扩大测试范围。

## 4. 新增过程事实逐项登记及v1.0分类

以下分类依据规则§3/4/8，编号独立于作者的“三编号”。“可归§3”仅表示该项性质可提出为条件清单，不表示本组件已满足条件档或获批准。

| 编号 | 实际事实与不可补证边界 | 分类及所需处理 |
|---|---|---|
| P1 | prepare.ps1 / build.ps1两helper缺少各自PREabsence；owner注册不能倒填原时点。 | 历史记录/测试执行过程类，可归§3；保留精确两个路径和无法追溯原absence的事实。没有证据据此推定覆盖相邻工程。 |
| P2 | observer-green2 fork→exe-check→trap竞态，原Linux PID/start/identity/pid/log缺失；Windows wrapper结束不足以证明原Redis结束。 | 历史记录类，可归§3；原始身份不可补证。不得把后续scan当原PRE封存。 |
| P3 | 第一次授权full-config扫描自匹配sh，355ms失败；纠正exact-exe/full-argv零命中也不足以证明关闭。 | 已披露的测试恢复过程与历史记录；自身匹配失败未kill，不能计成功关闭。单凭失败不构成生产安全缺陷。 |
| P4 | 精确只读3份Redis源码后，exact exe AND（完整registered config argv OR exact wave cwd）扫描408ms、matched0/readFailures0。 | 只证明scan时可识别该波不存在，不证明全局进程无遗留或整波合规。与P2共同保留§3历史缺口，不扩为全局审计。 |
| P5 | 早期outer helper从mutable authoring路径运行，child source/args/dependencies/guard已封存；observer-green3之后outer也封存并执行副本。 | 测试执行过程类helper封存缺口可归§3；后期正确做法不能追认早期outer实际字节全程不可变。 |
| P6 | source/mechanism输出并非统一在输出前删除全部字符串/注释。包括580673旧P内InitialSenderFixture非credential drive/recover/create rawSQL片段；f424b4新owner model stdout stack仅关键词替换；f1bf1c省略字符串但保留注释；早期newbuild前12行raw、迁移名字片段、某redis-server.json参数数组的精确调用ID/路径不齐。 | 已披露的输出控制偏离与记录缺口须拆开。可见有限片段据root澄清未见凭据值；本review没有完整原输出，不能独立证明全部输出合规，也不虚构已知泄露。缺失调用定位属于历史记录，但实际输出是否越过私密值安全边界仍UNKNOWN；不能把全部P6直接塞入§3。若有限原记录能确定仅非敏感源码/名字片段，才可按剩余历史记录另议；若有实际秘密/隔离边界影响，§8禁止白名单化。 |
| P7 | P内.tools filename discovery范围比必要宽。 | 已披露有限发现行为；不是父/兄弟目录内容读取或外部隔离突破的已知证据。保留范围事实，不自行授予“所有目录发现”白名单。未发现生产功能缺陷；能否仅留历史记录须以实际有限发现范围为准。 |
| P8 | red-a3 stopmarker错误，Windows wrapper被强制结束，随后按已登记PID/start/exe精确关闭exit0/absent=true。 | 已闭合的测试过程故障加延迟关闭记录；不可说正常即时退出，也无证据推定关闭了未知进程。不要求伪造无故障历史。 |
| P9 | native replay-base多余git add触及30秒cap被guard终止，注册identity结束；关闭后仅移除自有index.lock；后续worktree apply --check/apply及688字节回放闭合。 | 已闭合setup/证据组织故障；timeout保留，不是业务RED或生产缺陷。没有扩大cap、main Git或另造patch形式的已知证据。 |
| P10 | cohort-bindings多个file='.'，涵盖source/overlays/private helpers/additional source/eight preseals；bytes/SHA存在但'.'不是可解析文件引用。 | 新的证据组织问题，不能默默改原元数据。root转达作者解释为PowerShell大小写不敏感导致Ref参数$p遮蔽root$P、GetRelativePath(file,file)返'.'；解释不代替物理证明。root已出具22项限定解析证明，全部现存文件bytes/SHA匹配；本review完整读取并核对证明本身身份。可补的定位链已闭合，原file=点号历史保留，不倒填原时点，也不将该笔误定生产缺陷。 |
| P11 | new work私有live clock秒精度、单一iat基准TTL修正；TLS/random secrets、ready atomic replace、专用2slots；与production source分开。 | 明确fixture overlays及证据组织差异，不是生产放宽。早先TTL失败tuple未捕获，不可倒推根因；已绿群组不重跑制造单一fixture历史。条件档如将来适用应保留各cohort。 |
| P12 | 受限migration setup临时DB CREATE，三个独立原迁移串行；外层revoke与9项检查在业务server前完成；仅一个NEW DB。 | 已授权setup过程，有实际收口主张；不能冒称从未临时授权，也不能按业务扩权推断。root物理闭合核对是明确后续接口。 |
| P13 | lease expiry为精确专用run的注入；tamper被storage挡住；并发未证明双事务同时到首次commit前；receiver只同JVM重构。 | 已披露功能覆盖的有限事实，不是过程白名单补偿的“完整覆盖”。不得改名自然过期、reader污染拒绝或进程重启；后续真实联测需覆盖相应场景。 |
| P14 | launcher/classpath/JUnit launcher、keytool参数、fixture JSON key、WSL shell、V300权限、RouteCoverageVerifier、UDS、frozen refs误读、FLOW未终态容量、compile缺兄弟源/模块参数、ready文件半写、argfile/编码/#选择器/Modules类型及PowerShell变量碰撞等setup失败全部保留。 | 修正后的setup失败不是测试通过/RED，也不能仅因曾失败直接定生产缺陷；报告分组及原失败记录应继续保留。 |

P6中特定command ID和无凭据值的有限澄清来自root在本复审中的协作消息，不是本review自行检索了全量历史输出；未为对话输出虚造hash。P10原文件的'.'已由本review独立读取确认。

## 5. 三档PROPOSED判断

- **ACCEPTED：不适用。** I1未关闭，且P1/P2/P5等原时点过程事实不可补证，不能声称执行过程证据完整。
- **CODE_ACCEPTED(条件)：当前不适用。** 规则§2要求独立复审无生产功能/安全C/I；I1是具体新CHAT准入规格遗漏，落入§4.1/4.2/4.4，§3不能豁免。P6输出处理未知边界不能整体白名单化，P10虽已有现存定位解析证明，不能反向倒填原引用历史。
- **NOT_ACCEPTED：本次独立PROPOSED。** 决定性原因是I1。该结论不是继承旧四组件NOT，不是因PID缺失自动推定安全缺陷，也不是把9选定通过群组否认为实际通过。

先修I1并按其最小真实HTTP验证补闭环，再独立复审实际新delta。若最终没有生产C/I且P6安全边界可有限厘清、P10解析证明纳入正式证据链，才可以重新判断所有剩余缺口是否确实全部为§3；届时必须逐条提出条件清单并由项目负责人批准，不能以本报告自动升档。

未来若提出条件档，至少同时登记下列可执行对冲，不是本报告授予条件档：T31/T32/T33联合验收计划覆盖真实发布CHAT→固定版本→AGENT→一次模型调用→独立status/factSSE；顶层/inputs/snapshot的session/history拒绝与全部requestId回读；生产IAM/D3启用后的真实receive/forward/resume/current收窄/outage、lock-wait/commit/出站fence；真实进程重启、自然lease到期、response重用/UNKNOWN不重发、并发容量permit与游标补发；材料manifest/rawSHA/parents/floor/预算和JSON/schema正反例。每项登记执行环境、精确source/runtime/cohort与可核验结果。T34逐项查覆盖，任何未覆盖组件不得生产签收；联测发现功能缺陷立即撤销条件档回NOT_ACCEPTED。条件清单、对冲计划、实际覆盖结果随交付向客户披露，不用概括“全部通过”替代。

## 6. 仅交root的具体跨任务核对

⚠️ 不从代码diff推断物理执行：binding-audit声明688 canonical match、720runtime files/685Java21 classes/158dependencies/282production classes，fresh equality声明317production entries（common108/engine143/model40/app26）及3服务embedded common相同；本review读取实际manifest计数分别688/720/158并核对主refs哈希，但不另扫完整blob/解包JAR。root正在做限定本轮新delta物理核对，须报告八业务cohort各143engine生产runtime一致、独立classpath、fresh JAR/runtime、native688回放及具体不匹配项；不得替换为全父历史审计。

P10限定定位链已闭合：`.local/initial-chat-admission-root-cohort-ref-resolution.json`，8866 bytes，SHA256 `57906f2ce210610dba014793018ba870530167fd60b38db5240826e27e110847`。本review完整读取：22条覆盖source、overlay、11helper、additional source、8preseals，均physicalMatch=true。Credentials两实际副本的字节/摘要一致，分别列明。此证明来源为root的实际物理核对，解决现存locator，不重写原cohort/control/report，也不把原file点号当有效路径。

⚠️ closure实际JSON声明315guard records、700stable identity observations、27Linux closures、sessions0、3roles NOLOGIN/nonpriv/member0/schema privileges revoked；observer-green2例外独列。control在最后实际guard返回之后写入，链接audit/cohort/inventory的真实结束，最后记录exit0；本review未做新进程/DB探测，noBackground仅指本review没有创建任何后台资源，作者资源关闭以该证据和root物理核对为依据。不得发一个新seal命令伪造历史末次执行。

⚠️ P6仅核对已明确指出的有限输出记录及其确切定位/可见值性质；既不授权全日志扫描，也不要求重复旧资源。仍不能厘清的安全边界按UNKNOWN披露。

本次工作已完成；唯一交付为本报告。组件保持PROPOSED NOT_ACCEPTED，未签署条件批准，未改台账或扩大T01/T09/T11/T12/T33及全17需求/34任务/30验收状态。

