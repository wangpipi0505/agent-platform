# 首次无会话 CHAT I1 修补：独立复审

2026-10-23。P = G:/207/项目/算法重组工具/代码/agent-platform。

**Spec：Compliant（本次三文件修补范围）；Quality：Approved for production-code delta，附一项证据组织 Minor。原生产 I1 已闭合。生产代码 C0 / I0 / M0；本次总发现 C0 / I0 / M1（M1 仅证据计数）。正式组件建议：PROPOSED NOT_ACCEPTED。**

这是独立复审意见，不是实现者自评、条件批准或生产放行。建议 NOT_ACCEPTED 的当前原因是 P6 剩余早期输出安全边界 UNKNOWN，尚不能证成规则 §2③“全部为 §3 白名单过程缺口”；并非发现新的生产功能缺陷，亦非认定发生凭据泄露。本报告没有用旧四组件 NOT 自动决定新组件档位，也没有用本轮 GREEN 消去旧过程事实。

## 1. 实际授权、方法与审查边界

FIRST 完整读取 .local/review-initial-chat-admission-fix-brief.md（8923B，SHA256 21eea91875e8f27c97044602b436ff9e10e25ed53c5d741eee9e040ff4bb32e1），以其中实际授权为准。完整读 AGENTS.md、docs/implementation-brief.md、contracts/README.md、原 brief/plan/rebind/execution-context、修补 grant、14329B 修补报告、原独立报告的相关完整章节、规则和后续真实联合验收义务。组合输出截断后分段补读。

使用 code-review-expert 的安全、边界和质量检查口径；通用 Git、执行测试、再次委派的流程被本次只读授权取代。using-superpowers 明确 subagent 跳过；requesting-code-review 是发起复审的通用说明，本 reviewer 不再委派。未运行 rg、Git、Java、Maven、Python、Node、测试、服务、数据库或进程扫描，没有外部程序/helper执行，没有新子 agent。每个 exec_command 显式 workdir=P，仅 PowerShell 原生文件/JSON/hash操作。唯一写路径是本报告和 .local/review-initial-chat-admission-fix/。

原生 change.patch 已实际阅读；读取必要原/终 Java 源时先删除全部字符串和注释内容再投影，仅少量明确字段语义由私有计算输出 boolean。没有输出凭据、配置、token、argfile、provider/user payload、原始 XML 或原始日志。读取 DIRECT JSONL 后只投影事件/结果/计数；控制 JSON 只投影状态、布尔、计数及结构键。本文不声称逐字人工复核全部私有 SQL/所有业务字面量，也不把已复制 classpath 认定为每个类实际加载。

独立实际核对：

- intake 的 15 主引用逐个物理 bytes/SHA 匹配。
- old source2 688、最终冻结源 688、canonical 688，共 2064 份 bytes/SHA 全匹配各自清单；两份清单文件名集合相同，无 ADD/DEL；仅授权三文件不同，其余 685 same。
- RunService 删除精确四个新增名单行后，与原文件逐字相等。非名单生产变更为零。
- 5 个实际业务 DIRECT JSONL 与 runner result 引用均物理匹配；四 GREEN 每组 1 successful test / 0 failed / 0 skipped / 0 failed container；RED 每组 1 failed test、容器正常。
- 24 个新建/重放控制响应逐个读取，实际均403，bytes/SHA匹配 tests.json 引用。
- 16 份 GREEN preseal 引用物理匹配；RED source-inputs 的 RunService SHA 与原source2相同。
- closure 81 个引用物理匹配，但其中只有79个实际guard记录，详见 M1。六份 Linux closure 均物理匹配且 absent=true；closure.identities的869条中，176条为有效PID/start身份观察且aliveNow均false，693条为空身份元数据投影。DB记录sessions0/memberships0、3角色NOLOGIN且高权限全false、9项schema权限全false。
- 未另解包全部 JAR/重做全部 runtime blob 比较或新 native apply；相关317条制品/721运行文件/158依赖/native688回放依据已物理匹配的封存证据及 root 同步限定核对。这里明确区分本 reviewer 的直接检查和实现者证明。

审查原生检查清单：.local/review-initial-chat-admission-fix/checks.json。没有主Git状态、工作区全局扫描或新测试结果。

## 2. Spec 与 I1 闭合

### 2.1 最终源码

位置均为当前 canonical：

| 位置 | 判断 |
|---|---|
| engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:140 | requirePublishedRequest 保留既有严格入口参数限制。新增四名位于170–173行，175行仍使用 request.has(key) 立即抛 denied；null/空/非空均由存在性拒绝，不删除后继续。 |
| RunService.java:353 | protectedAdmission由iamEnabled确定，356–364行同一gate在所有后续requestId查询前运行。普通首次查询380–396、容量拒绝后事务回读402–445、受理事务内查询560–582均在其后。 |
| engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java:39 | 既有受保护admin路径限制保留，58行仍完整转交请求到create；没有DTO吞字段或另一条新绕行。该文件未改。 |
| RunService.java:194 | requireFirstChat继续检查原snapshot/inputs、输出策略及FrozenModels；本修补没有以顶层gate替换这些检查。 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionFixture.java:238 | 新action仅为有限测试控制：四名单、三值形状、真实request调用、tenant/app相关计数、从实际published来源派生的纯规则snapshot负例；没有生产接口/业务UPDATE。 |
| engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java:12 | 新真实业务RED、两矩阵与FLOW轻量正控；旧TASK测试方法保留且本轮实际执行。 |

静态精确检查确认唯一生产差异是 chatSession、chatTurn、sessionHistory、history 四个名单元素。共享gate会对受保护TASK/FLOW请求同样拒绝这些明确保留名，符合原gate的有限无会话请求边界；合法不含保留字段的FLOW/TASK有正控。此修补没有引入任意未知字段一律拒绝，也没有扩展会话/二轮能力。

所有三处回读都受同一前置gate约束是静态路径结论；本轮HTTP实际覆盖普通新建及已接受requestId，不声称强制触发容量回读/首提交前竞争两种时序。四元素修补不影响原事务、current authority、requestId一致性、scope、材料、单次发送或恢复机制，已有其他生产文件均逐字未改。

### 2.2 真实RED/GREEN与具体边界

| 波次 | 直接核对及结论 |
|---|---|
| red-i1 | TLS alias setup失败，没有业务测试，不计RED；原model强制关闭记录保留。 |
| red-i1b | red-source-inputs明确绑定旧RunService SHA 6d0cbbba9809dd5d4beb94b40c8984fe627b49195e5d9fdde5021143238bcc48。control-published显示type=CHAT、正数字versionId、hasFrozenModel=true；control-facts实际httpStatus200、非空runId、nodes1。DIRECT目标测试失败，runner exit1/无timeout/rootAlivefalse。结合实际测试期待403及fixture请求路径，是真I1业务RED，不是setup/发布/签名失败。 |
| green-new | 四字段×null/空数组/非空对象，12个不同新requestId响应全部403。测试在循环前后比较run/run_authority/node_instance/model_invocation/providerRequests整组计数，再比较inputs四负例与snapshot四纯规则负例后计数；实际DIRECT成功。结束计数为runs2、authority2、nodes0、invocations0、provider0；前两项是fixture既有基线，结论是“无增量”，不是声称数据库绝对零行。 |
| green-replay | 合法首次CHAT与原requestId回读HTTP200，实际SUCCEEDED/provider1/admitted1/node1/attempt1/invocation1；12个带保留字段回读全部403。测试比较run/nodes/attempts/invocations/providerRequests，并在末尾合法回读比较原结果。控制文件最终合法runId与持久事实一致。 |
| green-flow | 实际FLOW创建200，轮询SUCCEEDED，独立status200，provider0；DIRECT成功。 |
| green-task | 旧私有TASK必要单正控，实际SUCCEEDED/send1/commit1；DIRECT成功。 |

四 GREEN runner 均 exit0/timedOutfalse/rootAlivefalse。保留各自方法/fixture/cohort，没有把5个群组相加称一次全套suite。控制文件同名会覆盖前一次，故“前后不变”的过程证明来自已冻结测试断言和真实DIRECT成功，而不是声称磁盘另存每个前后快照。snapshot负例只为真实published来源派生后调用纯规则，未伪称完整错误快照HTTP发布链。

本轮没有重跑原九重点群组、SSE/恢复/完整预算材料矩阵。原source2审查及root限定核对仅为未变生产路径的继承依据；其局限继续适用：注入lease非自然到期、同JVM重建非进程重启、双HTTP同run非两事务首提交前进入、trigger拒写非observer读取污染行拒绝。实际生产IAM/D3和真实模型、部署、容量仍未验收，T33未启动。

**原I1关闭意见：关闭。** 原遗漏位置、最小修补和真实HTTP新建/回读拒绝证据形成闭环。没有未关闭生产Critical/Important，不要求为求单一最终suite重复旧绿色测试。

## 3. Quality 与发现

生产改动直接复用既有gate，复杂度和依赖未增加；没有无关重构、网络/事务迁移或新权限入口。测试通过有限枚举构造边界，并检查无写入/无外发及合法正控，符合本次风险。未发现需修改生产代码的质量/安全问题。

### M1：closure将两份元数据数组计为guard记录

定位：.local/initial-chat-admission-fix/final-evidence/closure.json 的 guardRecords=81 和 records；docs/evidence/initial-chat-admission-review-fix-report.md“关闭审计覆盖81个此前guard records”。

records中以下两项不是guard结果：

- .local/initial-chat-admission-fix/control/fixture-overlays.json：5项overlay元数据数组。
- .local/initial-chat-admission-fix/control/work-input.json：688项源码元数据数组。

其rootPid/capSeconds/exit/rootAliveAtReturn等被投影成null数组，不能据此证明启动/关闭，亦不能把非空数组的布尔真值误读为“进程存活”。其余79条rootPid为标量，rootAliveAtReturn全部false。869条identity混合枚举中仅176条有有效正PID/startTicks；另693条空身份项恰为5+688个metadata元素。独立读取79个实际guard的identities合计亦为176，176条aliveNow均false。六Linux absent及DB收口独立成立；没有因此发现未知存活资源。

等级Minor（证据组织/统计口径），非生产代码缺陷。建议由root保留原81项证据文件原文，在独立证书/正式登记中追加“79实际guard / 176有效身份观察 + 2 metadata / 693空身份项”及对应定位，不修改历史原报告/清单来抹去误称。可补的是当前分类解释，不可倒填原采集算法语义。应同时保留closure audit之后control单列的三个实际guard结果，不能把本报告79数替代整个执行历史总数。已即时向root报送。

此项属于§3证据组织类；本次已可通过有限物理分类厘清，不构成§4.5伪造结论。没有证据表明作者故意把metadata当启动记录，本复审也不作此推定。

## 4. 原P1–P14原文保留

以下为原独立报告第4节原文，作为历史记录逐字保留，不将其中原I1状态或原档位自动延续；本次重新判定见其后的附表。

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


## 5. 原过程事实的本次独立复判与具体对冲

下表基于原文及本次实际授权补充；“可列候选”只判断缺口性质，不表示目前已提出或批准条件档。

| 编号 | 本次判定、已知/未知与不可补证性 | 精确后续处理/对冲 |
|---|---|---|
| P1 | 两helper原时点PREabsence缺失，属于§3历史记录；后来文件存在/摘要不能证明原时点不存在。未见越界覆盖证据。 | 保留两个精确路径/原记录缺口；T31真实链和T32拒绝矩阵绑定新source/runtime，T34查原缺口仍披露。 |
| P2 | 原Redis PID/start缺失为§3历史身份记录缺口；当前无匹配不能恢复原身份。 | 与P3/P4一并登记，仅引用当时有限收口；未来T32恢复/清理须记录真实启动和退出身份，T34不得称全程追溯完成。 |
| P3 | 首次扫描自匹配并失败，未kill；是已披露测试恢复失败，无生产缺陷证据。原失败不可改为成功。 | 保留355ms失败及纠正记录；未来资源清理使用准确身份，不用后测替换旧失败。 |
| P4 | exact-exe且registered config/cwd的零命中仅证明有限扫描时点；与P2属§3历史缺口。 | 明确保留匹配谓词/时间边界；未来T32关闭链及T34核验不得写全局无遗留。 |
| P5 | 早期mutable outer未封存，属于§3helper执行过程类；child已封存不补outer原字节。 | 精确列早期波与后续封存起点；T31/T32/T33真实联测将outer/child/input一起事前封存并关联结果。 |
| P6 | 拆分判断：可见有限drive/recover/create SQL、过滤栈、残留注释本身未见凭据；部分callID/路径与完整早期输出仍UNKNOWN。无法证成全部仅为§3记录缺口；§8.2禁止把可能涉及安全边界的未厘清部分概括白名单化。亦不能据UNKNOWN认定已泄露或§4.5伪造。 | 仅由已有有限原记录澄清各条原输出性质与定位；禁止普扫日志、打印原凭据或伪造transcript哈希。若原内容确已不可得，明确留下无法排除的边界，不能靠新测试或IAM联测替换历史判断；当前阻止条件档提案。 |
| P7 | 范围较宽但仍在P内的filename发现，不等于读取相邻工程内容；目前可作有限历史记录，不能扩成任意发现白名单。 | T34保留原目录范围和未读内容界限；后续只给精确有限输入清单。 |
| P8 | stopmarker错误及后续精确已登记身份强制关闭，测试过程已收口；不能追称正常退出。 | 保留失败与精确关闭证明；T32在真实故障下验证资源退出，T34查看原延迟事实。 |
| P9 | native阶段超时/自有index.lock处理与后续字节回放，属已披露setup/证据组织过程；未见主Git或cap扩大。 | 保留timeout，不另跑历史来美化；T34核native最终证明和原失败引用。 |
| P10 | file='.'不能定位，22条root实际解析已补现存locator，属§3证据组织；不能回填原时点。 | 正式材料必须同时引用原元数据与22项解析证明；T34核对具体bytes/SHA，不仅引用解释。 |
| P11 | live-clock/TLS/ready/slots是有限test overlay、与生产分开；旧JWT因果未知不可追认。剩余是cohort证据组织类。 | T31生产IAM/D3真实链、T32撤销/缩窄/outage/过期须新真实证据；保留原各cohort，不能合并为同一次最终suite。 |
| P12 | 已明确授权的迁移CREATE窗口且收口，非未授权生产扩权证据，不是须豁免的生产行为。 | T34核迁移前后真实权限；T31生产IAM/D3与最小权限独立验，不称从未临时授权。 |
| P13 | 四个覆盖边界是功能验证范围，不可放入“过程白名单”后视作全覆盖；也不是本修补生产缺陷。 | T32真实进程重启、自然lease、response恢复/UNKNOWN不重发及确切并发窗口；污染reader若要声称必须对应受控有效证据。T33容量不由本单组件代替。 |
| P14 | 已披露setup失败与后续各群组分别保留，测试执行/证据组织类；未绿setup不是业务RED。 | 联测证据将setup与业务结果分列，T34检查原失败仍可定位，禁止重新标绿历史失败。 |

P6具体最新已知范围采用实际brief披露：580673旧InitialSenderIntegrationFixture drive/recover/create SQL及路径可见非凭据；f424b4 concurrent-replay2/model-server.stdout部分过滤栈可见非凭据；f1bf1c新work LoopbackAuthority已删strings而comments未全删，可见非凭据。early newbuild首12原始行缺callID，旧P-migration名字/片段及一次redis-server.json argv缺精确路径/callID，其他可能输出值UNKNOWN。本review未检索全量原会话；对这些对话事实不虚造文件哈希。此澄清缩小了已知范围，却没有证明缺失内容全为非敏感；不能把“未见泄露”写成“已证无泄露”。

## 6. I1修补执行新增事实逐项判定

| 编号 | 实际事实/定位 | §3/§4/§8判断、不可补证与处理 |
|---|---|---|
| F1 | 064d23只读inspect从未封存authoring outer执行，exit0、报告称无child资源；后续sealed。 | helper封存缺口属§3测试过程候选，原时点字节不能由后seal回填。对话事件的完整原输出本review没有独立重放；不借后来绿抹去。对冲T31/T32真实链完整preseal，T34保留原事件。 |
| F2 | 78f362 sealed路径斜线判断在child启动前失败，修正派生新封存副本。 | 已披露setup失败，不是业务RED或已知生产缺陷。保留失败及新副本分界；不需要重跑业务补历史。 |
| F3 | 早期work/copy manifest、build/common-build1/services-build1/classpaths1、compile/helper-engine1/helper-model1/helper-app1/producer-alias1、database-commands/database-create1/migration-open1/migration-close1、setup/schema-migrations1、waves/red-i1/red-i1b部分内部输出仅目录/owner PREabsence；red-i1部分预列为占位路径。后续机制内部日志/argfile/ready/class也不可概称逐文件PRE。 | 精确历史路径/归档前置记录缺口属于§3测试执行与历史记录候选；原absence不可补。没有据此推定旧资源复用，但不把目录不存在等价每个文件独立PRE记录。对冲T31/T32/T33按实际输出边界预列并绑定；T34原缺口逐路径披露。 |
| F4 | red-i1随机TLS alias与测试producer固定key-alias不匹配；只改NEW work属性，模型guard exit-1、后续真实身份关闭与Linux absent。 | setup缺陷非生产验证器放宽；不计业务RED，强制关闭不改称正常退出。有限overlay差异归§3证据组织候选；T31真实TLS/secret注入，T34检查属性仅测试且原失败保留。 |
| F5 | 首次source patch上下文不匹配与rg Windows wildcard错误均静态阶段，报告披露。 | 不是功能测试或资源执行成功；无需豁免生产缺陷。历史失败保留，不能当新安全事件。 |
| F6 | source-freeze因Dir alias解析失败，实际guard661ms/exit1；source-freeze2为6370ms/exit0。green-build1编旧work未作GREEN，green-build2用最终源。 | 原生guard状态独立核对。是setup/证据组织类，失败保持；最终源与canonical688身份已证。T34按source-freeze2与green-build2绑定，不把第一次冻结计成功。 |
| F7 | native-apply完成apply/688比较后同名replay.json写入冲突，6759ms/exit1；native-verify827ms/exit0，新replay-result。 | guard返回独立核对、final replay-result声明688/equal；属于证据组织类，不是业务缺陷。未重跑apply。本review未运行Git，root核物理回放；T34同时引用失败和现有结果。 |
| F8 | 默认test-compile未产JAR，package-equality614ms/exit1；fresh-package后package-equality2为856ms/exit0，final-package空目录保留。 | guard返回独立核对；已披露构建/归档setup，不以失败影响真实GREEN或以新JAR代替旧runtime。T34查capture先于fresh package及317条逐字比对。 |
| F9 | RED仅保留source-input/preseal，未另存完整RED runtime blob树；GREEN另存721files/686Java21classes/158deps。 | 证据颗粒度限制，已披露，不能把GREEN class当RED旧class。属于§3证据组织候选；业务RED有旧源绑定及真实HTTP失败，本次不要求补跑历史。T34写明缺失原blob不可重建同次实际运行。 |
| F10 / M1 | closure混入2份metadata造成81/869口径膨胀，实际79guard/176有效身份观察、693空身份项。 | 当前物理分类已可补，原误称不可抹；§3证据组织，不是§4.5已知虚报/伪造。root已收到并独立确认，将在新限定证书和台账追加准确口径，原报告/closure保持原文。T34核79/176/2与原81/869的对应。 |

F1/F2/F4/F5中对话事件与因果的部分依据是实现报告明确披露，本review没有为了核对历史输出去普扫原日志。F6/F7/F8返回和F10结构为本review实际读取原文件核实。F3不借当前有限ledger的存在倒填原absence；本报告没有把它们声明为已获批准的白名单。

## 7. 三档独立建议及硬闸

**ACCEPTED：不建议。** 原P1/P2/P5及新F1/F3保留不可追溯过程缺口；P6仍有未知安全边界。因此执行过程证据完整性不成立。本次真实业务通过及M1当前可解释，不改变这一事实。

**CODE_ACCEPTED(条件)：当前不提出适用。** §2①就本次明确修补范围，真实PG/Redis/受控IAM/provider/实际app/model路径的受影响功能测试GREEN成立；本轮不声称真实生产IAM或原完整矩阵新跑。§2②生产C/I已闭环，I1确实关闭。§2③要求遗留缺口全部属§3且逐条登记，而P6未定位原输出仍无法确认仅为过程记录，§8.2不允许扩张解释。P13等未覆盖真实场景也不能拿过程白名单替代后续功能覆盖。新M1本身可归证据组织，不能把它当决定性的代码阻点。

**NOT_ACCEPTED：本次正式组件PROPOSED建议。** 当前无法证明两种接纳档的全部前提，只能继续保持未验收，等待P6有限边界明确及root新限定证书。本结论不把UNKNOWN自动认定为§4.1安全缺陷或§4.5未披露行为；已知代码I1对应的原§4阻点已经解除。§4每项检查结果为：未发现新的未解决生产功能/安全缺陷；未发现本diff契约实质偏离；受影响真实依赖测试已绿但不冒称全部平台测试；无未关闭生产C/I；没有已知未披露行为/伪证的证据。不能由“未发现§4”反向推导“已证全部缺口属§3”。

这是规则在证据不充分时的保守适用意见，不是给P6新增生产缺陷等级。若后续有限原材料足以确认P6仅非敏感源码/名字片段，可以就剩余精确过程缺口重新提出条件档；若原安全边界永不可知，不能以再跑GREEN或责任人一般批准替代§8边界。

当前没有条件档白名单提案交付审批，也没有已生效条件；上节逐项候选及对冲仅为将来重新判定输入。不得将“代码Approved”缩写成“CODE_ACCEPTED(条件)已批准”。任何未来条件档都须fresh独立适用意见及项目负责人§6批准，原NOT记录保留追加。

本组件未来真实联合验收必须执行已冻结 .local/initial-chat-admission-joint-acceptance-obligations.md，不因档位未定而删减：

- T31/A27：真实网关+生产IAM当前授权、实际published CHAT数字版本/FrozenModel、原INSERT CHAT/FULL、step1且node_def_id空的单AGENT和scopes、真实tokenizer/预算、一次attempt/DISPATCHED/HTTP/SSE、durable response→一次logical commit、独立status/有序事实SSE；chunkAvailable=false与usage不可用如实表达。
- T32：顶层四字段null/空/非空的新建与回读拒绝且无增量；合法幂等/不同输入版本冲突；真实IAM/D3的receive/forward/resume/current撤销/缩窄/outage及lock-wait/commit/出站fence；真实进程重启/自然lease/精确并发窗口；response重用、UNKNOWN不重发；材料rawSHA/parents/floor/预算和JSON/schema正反例。注入与自然故障分别记，未覆盖不伪称。
- T33/A29：按批准真实部署场景量化500并发run、每副本5000+SSE、首token p95、200并发×5MB及慢客户端/背压/取消/清理；公共token/取消需其前置组件批准后执行。当前未启动，不能用本轮三serverJVM或事实事件延迟顶替。
- T34/A30：逐组件、逐编号核实条件清单/责任人批准/真实覆盖/失败未知及部署secret/IAM/D3。未覆盖或未批准不得生产签收。若以后获条件档而联测暴露功能或安全缺陷，立即撤回NOT_ACCEPTED，修复→重测→独立复审→必要批准。白名单清单、对冲计划、实际结果随交付向客户披露。

## 8. 证据索引、实际命令与结束状态

本review实际命令仅为PowerShell原生Get-Content/ConvertFrom-Json/Get-FileHash/Get-Item、有限Get-ChildItem、源字符串/注释脱敏、私有boolean断言、清单循环与写自己报告/owner。读取/核对调用均已返回exit0，没有超时/后台会话、helper child或业务资源。数次组合输出截断后按需要窄范围补读；一次closure“非空null数组”初步筛选产生过宽候选，随后按正标量PID/startTicks独立纠正为79/176，未将其宣称资源存活。没有重跑旧GREEN或修补测试，没有造transcript哈希。

以下主引用逐个物理核对bytes/SHA，路径均相对P：

| 引用 | bytes | SHA256 |
|---|---:|---|
| docs/evidence/initial-chat-admission-review-fix-report.md | 14329 | 1b9c415cbf84b8ca1c2976a01e1d6c630a1078d27b79dec4f7cc5139f1428c38 |
| .local/initial-chat-admission-fix/final-source/source.json | 136835 | 2625ce4d7f40fcf40c0d529f8b0d59284ceda1ff40253eca629f9bc75ff18ca7 |
| .local/initial-chat-admission-fix/final-source/scope.json | 1288 | 5e1597fc866c644f30dc6ca91ecb3b40b991487a1ac55bbf662e7e04b888366f |
| .local/initial-chat-admission-fix/final-source/overlays.json | 2420 | d37ff03333e49b5485d57d867bc9e908969fa29c985318461b6a4b8426b3def8 |
| .local/initial-chat-admission-fix/final-runtime/files.json | 454270 | a09c4416b6532a474c0d949fba46a63d9a045d2c5777843802537dbd9c4315ee |
| .local/initial-chat-admission-fix/final-runtime/dependencies.json | 54391 | 78b5bd5e72b69b23f30f129998dc9bcfeb8b85cec5df8ca3379e6ad2a8d6e6ab |
| .local/initial-chat-admission-fix/final-runtime/binding.json | 4236 | ede8d1430b59f58a8d992f767b1a78a0e9142362f2e204bc9d254a5079315dee |
| .local/initial-chat-admission-fix/final-package2/equality.json | 1697 | 61d5ec3957c0a709fbc5767a3c5eda199e1c683062a09e1b4a57ea72b84424a1 |
| .local/initial-chat-admission-fix/final-native/change.patch | 12798 | 39ae8ba9cbc5241911df48ce06af097c467a3201b04a231ff3c02f7c6c1fdb2b |
| .local/initial-chat-admission-fix/final-native/replay-result.json | 697 | 37fab493ad610364017c853939d965c68b33dbf78a7c2dd6780f17bbbfee1641 |
| .local/initial-chat-admission-fix/final-evidence/tests.json | 31174 | 1d53b2d60f979dde228ceb6635dda3695a2c66c8c648f58562776a7d6c5d6461 |
| .local/initial-chat-admission-fix/final-evidence/closure.json | 387323 | cfd8adafbbb2dacf84f396a57b485a2d9478a9fa90a10397cbee8aed6d636853 |
| .local/initial-chat-admission-fix/final-evidence/provenance.json | 9751 | 71f4b62c7a46d0658700804276c644f540012fd031120bbdb46b988084b8bdc6 |
| .local/initial-chat-admission-fix/final-evidence/inventory.json | 7076 | cf42c741c486912d26a37cd9ff20636a17e9f29f4837d1edd26d2e33b54a4a8a |
| .local/initial-chat-admission-fix/final-evidence/control.json | 3908 | 0a0dc85705fd0a3b635847f2fe76a366de7e62ff36bb82739cec6376a9e354ca |

附加实际输入身份：

| 引用 | bytes | SHA256 |
|---|---:|---|
| .local/review-initial-chat-admission-fix-brief.md | 8923 | 21eea91875e8f27c97044602b436ff9e10e25ed53c5d741eee9e040ff4bb32e1 |
| .local/initial-chat-admission-fix-brief.md | 13100 | 65d98775531370e05ea56493157faaf56dce0944a2d69d221edabf5156a8e307 |
| .local/component-conditional-acceptance-rule-v1.md | 6866 | 0817c6021decb015a6daef3ebdb4f3d1fe301babdc91ed0d38748cd4d9404b05 |
| docs/evidence/review-initial-chat-admission.md | 23998 | 5394a6bc6f2b0793b10689420b833d4220bf47360e8bfb0dbb59ce6915b59326 |
| .local/initial-chat-admission-joint-acceptance-obligations.md | 3630 | 477e2036a25585da31d752a97477fd669806a4feef3fa996c04e755da2e2fff8 |
| .local/initial-chat-admission-root-qualification-v1.json | 4677 | 57f1dfca4a48bf96df32a413373791d9713c1b9c277ae9ab71ba3f85d5e75038 |
| .local/initial-chat-admission-root-cohort-ref-resolution.json | 8866 | 57906f2ce210610dba014793018ba870530167fd60b38db5240826e27e110847 |

本review安全投影与物理检查：
.local/review-initial-chat-admission-fix/checks.json
；
60630
 bytes；SHA256 
0d1f1d6aa2e58eb4a22655662815a62e3233391c5bf03c396fa85a5e782b91bb
。

本次复审 ALL / noBackground / NOTAccepted（未自批准）。只完成本授权独立复审，不改原交付、台账、8保护文档、设计或T01/T09/T11/T12/T33状态。报告外无后台工作或资源。
