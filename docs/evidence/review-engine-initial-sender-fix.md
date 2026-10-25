# Engine initial sender 集中修复独立复审

## Spec Compliance / Task Quality

**Spec Compliance：✅ 本次限定修复符合要求。Task Quality：Approved（仅代码复审）。Critical 0 / Important 0 / Minor 0。** 原 I1–I4、M1 及新增终态提交窗口的功能级问题均闭环。此结论不等于组件完全验收、执行合规或项目签收。

**三档组件建议：PROPOSED NOT_ACCEPTED，当前不提出 CODE_ACCEPTED(条件) 的批准申请。** 原因是仍有不属于 §3 穷尽白名单的实际保密执行违约，另有需 root 关闭的证据继承/物理资格事项。已披露不等于获得白名单资格；也不能把已披露事项直接称为 §4.5 的“未披露”。独立代码批准与组件建议分别成立，项目负责人不能仅通过 §6 批准手续扩张 §3。

本报告唯一写入目标为 `docs/evidence/review-engine-initial-sender-fix.md`。所有下述生产源码行号均对应 `.local/engine-initial-sender-fix/final4-source/source/`，不是活动 canonical 源码。简写 `main/` = `engine-runtime/src/main/java/cn/agentplatform/engine/`，`test/` = `engine-runtime/src/test/java/cn/agentplatform/engine/`，`common/` = `admin-common/src/main/java/cn/agentplatform/common/`。无项目 commit，不虚构 BASE/HEAD。

## 已闭环的需求及优点

| 项目 | 独立结论及代码证据 | 行为证据与限度 |
|---|---|---|
| I1，声明先于付费 | `main/application/ModelInputMaterials.java:108` 复用完整 OutputPolicy，再按 initial profile 拒绝 RETRY/显式非零 retries；省略 maxRetries 不误判既有默认 2。`domain/OutputPolicy.java:55` 使用 integral + canConvertToInt + 非负检查，巨大整数不能截断为 0。`ModelInputMaterials.java:103`、`:207` 和 `TraceStore.java:1098` 在准备、原字节读取、dispatch 重新检查；实际 consume modelCalls 在 `TraceStore.java:1157`，声明在前。 | `test/infra/InitialSenderFactsPostgresTest.java:26` 同时断言 invocation 0、modelCalls 0、无独立 ingress；final4 根/嵌套 bogus 及实际元比较预算拒绝通过。格式类型、策略名、巨大整数、RETRY 在 final3 有成功记录，保留其旧 cohort 标签；既有类型/范围构造及拒绝逻辑未因 final4 放宽。 |
| I2，无效输出稳定终态 | `ModelExchange.java:47` 返回真实 durable response，不再把语义不合格一律抛入 defer。`TraceStore.java:1257` 验证 invocation/attempt checkpoint 原响应相同，派生 outputValidation/structured；PASS_WITH_FLAG 保留原文、移除无效 structured；TERMINATE 沿既有 fail 写 OUTPUT_INVALID。checkpoint、node、attempt、run、events 同一保护事务完成。 | final4 invalid-pass 与 schema-terminate 实际 scheduler recovery 后稳定 SUCCEEDED/FAILED、没有再次 ingress。`InitialSenderFactsPostgresTest.java:48` 的共用断言保留 raw 无 outputValidation，恢复只产生派生结果，第二次 recover 状态不变。final3 invalid-terminate/schema-pass 是旧源补充，不冒称 final4。 |
| I3，预算失败保留事实 | `TraceStore.java:1209` 在 DISPATCHED/response-null CAS 成功后，保存真实 response、response_ref、checkpoint；仅已知 usage 更新真实 token 总量，使用 BigInteger 累加。`initialBudgetError:1245` 只捕获 BUDGET_TOKENS/BUDGET_DURATION，其他 PlatformException/DB/authority 故障仍向外传播。未知 usage 不设 usage_accounted=true。预算错误存入 checkpoint，并且没有第二次 consume/HTTP。新调用仍检查 deadline，已有 response 可在预算耗尽后恢复终态。 | `InitialSenderFactsPostgresTest.java:77` 的 token/time 两条 final4 方法都证明 paid response 在、66 tokens 只记一次、恢复 FAILED、无逻辑成功、二次恢复预算不变、ingress 不增。duration 用注入时钟跨越预算，是真 PG 的预算结果恢复证据，不能扩大为真实墙钟慢响应/容量证明。 |
| I4，真实 JSON/schema 正控 | `test/infra/InitialSenderIntegrationTest.java:11` 经实际 drive→model→SDK/provider，校验 schema 在 canonical 原字节内，native response_format.type=json_schema 且 schema 相同，model durable wireUtf8 等于实际 wire；raw 只留文本、恢复后 derived structured.ok=true/outputValidation.valid=true，一个 invocation/send/commit。 | final4-schema-002 成功。受控 provider 按测试返回 JSON；该证据证明 pinned SDK 的实际 wire 映射及 engine 输出验证，不证明外部厂商实施 schema 强制。final4-text 独立保留 TEXT/未知 usage 正控。 |
| M1，scope 投影上限 | `MaterialScopeStore.java:256`、`:290` 把 model-input 的 262144 字节 CASE 放到实际动态读取查询；`:360` 在解析前拒绝 NULL 投影。ordinary-only 查询死 CASE 删除；其他 kind 仍直接 scope::text。无 containsAll、丢 scope 或未知 kind 豁免。 | final3-scope-bound 是实际持久化超限 scope 的拒绝证据；最终此修复代码未再改变。新库 FLOW/material/current 回归补充普通路径，不能把该 final3 方法重标 final4。 |
| 新终态 lease 窗口 | `TraceStore.java:1313`、`:1317`、`:1322` 在即时、beforeCommit 和事务后执行 `initialTerminalFence:1326`，要求 proof、dispatch、实际 run/node/attempt 关联、相同结果态、owner、live node lease；成功须 committed_attempt_id 匹配，失败须未逻辑提交。弥补既有 private fail 在 node CAS 未命中时仍写 attempt 的行为；initial 检查失败会回滚整个派生终态事务。 | `InitialSenderIntegrationFixture.java:45` 的真实 prepareForCommit hook 在 initialFailure 已写后令 node lease 失效；`InitialSenderFactsPostgresTest.java:97`、`:109` 断言 run/node/attempt 仍 RUNNING、terminalEvents=0、raw response_ref 在、initialFailure 不在。final4-lease-window 通过，检查不是只断言抛异常。final4 current/lease response 后失效也通过。 |

`main/application/RunService.java:1891` 未修改但已定向核对：成功返回 commitInitial 后无额外成功覆盖；只有真正异常才 defer。新稳定 OUTPUT_INVALID/BUDGET_* 终态不抛入旧泛化 defer 路径。已有 response 的恢复复用 `TraceStore.java:1111` 保存的 canonical、manifest、route、attempt-request 和 invocation response；没有重建 invocation。新终态仍走 `writable:535` 的 run→authority→node 锁、材料精确集合、floor/parent/current 检查。

Ponytail 判断：7 个文件内使用既有 OutputPolicy、Plan、保护事务和 node/run primitives，没有增加新生产类、预算框架、DTO、依赖或全局配置；死 SQL 已删除。本次没有应删的确定性冗余，net: 0 lines recommended for deletion。重复当前/材料校验是明确安全边界，不能为了行数删掉。

## 元 Schema 预算的独立判断

检查目标是新增 `ModelInputMaterials.java:117`，并非重新审计 common 的所有 Schema 功能。

1. 精确资源为 pinned `json-schema-validator-1.5.6.jar` 内 `draft/2020-12/schema` 以及 `meta/core`、`meta/applicator`、`meta/unevaluated`、`meta/validation`、`meta/meta-data`、`meta/format-annotation`、`meta/content`。经 root 额外明确授权，只用本进程 ZIP API 静态读取这 8 项，没有执行 JAR。8 项 bytes/SHA 与 `meta-admission.json` 全部一致；JAR 实际 576194 B / SHA256 `5531436b97dfa90d34bb2acbbe65e054a0a24ded1576032b684ee179728f86d4`，与 runtime3 dependency 元数据一致。
2. loader 仅接受这 8 项及同字节 classpath 别名；未命中直接 throw，不返回 null 去尝试网络/文件 fallback。format assertions 显式 false；实际元图中的 format:regex 不触发 JDK regex 断言，真正 pattern 使用既有 BoundedRegexFactory 的两条固定 RE2 表达式，报告 programUnits=15 与准入公式对应。
3. 独立查看引用图：根 allOf 只进入 7 个 vocabulary；$dynamicRef 返回 meta 的位置全部位于声明的子 schema 字段、map value 或 array item，严格下降实例树。固定非下降 $ref 只到小定义（schemaArray、stringArray、simpleTypes、anchor/URI string、nonNegativeInteger 及 Default0），无对同一实例位置循环返回根的图。旧 dependencies 的 anyOf 至多一支递归 schema，另一支 stringArray；type 的 anyOf 是固定 simpleTypes 和数组分支。共享小定义的重复在不同声明字段/元素位置发生，而不是指数叠加递归。
4. 因而对这一个固定图，以全部 324 JSON 节点作为每个实例位置的保守系数是可接受的；不是“资源数天然等于展开量”的一般规则。定向核对 `common/schema/SchemaWorkBudget.java` 的 Plan.admitInstance：全四标志确保 324*N²<=2000000 的 uniqueItems 上界，15*324*(C+N)<=4000000 的 regex 上界，及字符/比较/数值上界均在 meta.validate 前执行。单看前两式，N 至多 78，C+N 至多 823；因此用户声明规模在进入此固定 meta 图前已显著收紧。numeric 位数和前置 inspect 的深度/引用/字符上限继续生效，用户自己的低成本 flags 没有被拿来低估元校验。
5. `SchemaDeadline.until` 位于读取资源之前，包含资源加载、遍历、RE2 初始化、getSchema、initializeValidators 和 validate，末尾有 check。`SchemaDeadline.java:17` 是同线程协作检查，不是抢占式杀线程；固定 classpath IO/库内部步骤之间不能声称精确在 2.000 秒被中断。这里可接受的防线是固定小资源/固定引用图、64 预加载层数、严格实例准入及同一 deadline，不能拿末尾 elapsed check 单独当工作量证明。既有 OutputPolicy 构造先有自己的两秒域；新增 meta 阶段两秒不等于整个 initialOutputPolicy 总共只两秒。
6. 较大但合法声明可能被 initial profile 拒绝，这是已授权披露的保守兼容限制。final4 meta-budget 使用 100 个 required 项验证实际预调用拒绝；根 bogus、嵌套 bogus 和小 schema 真链正控分别覆盖有效性与正例。没有把“更保守”本身作为上界正确的证据，也没有放宽循环 schema 全局限制。

此范围内没有元校验功能级 Important。固定依赖/资源版本一旦变化，以上图论据必须重审，不能只重新求节点和并继承本结论。

## 测试证据与 cohort

读取 test-summary 的安全方法/容器元数据，并直接解析其对应 events 的安全字段；选定 final4/新库 regression/两个串行补验的 events SHA 均匹配。普通 Surefire 只投影 testcase 的 classname/name/skipped/failure/error，未打印 XML Properties、凭据、日志或 argfile。

| cohort | 独立读取到的实际结果 | 采用方式 |
|---|---|---|
| final4 DIRECT | 12 方法 SUCCESSFUL，0 failed test/container，0 skip | final4-budget/current-outcome/duration/invalid-pass/invalid-schema/lease-outcome/lease-window/meta-budget/nested-schema/schema-002/schema-terminate/text |
| 第二新库 regression | 6 方法 SUCCESSFUL，0 failed test/container，0 skip | default-iam、flow-commit、flow-deny、http-current-002、material、material-current；原 http-current 容器失败仍保留，不计方法执行 |
| ordinary Surefire | 4 OutputPolicy PASS + 1 ModelExchangeTest SKIP 投影 | strictJsonRejectsDuplicateKeysAndEmptyDocument、noneDoesNotApplySchemaToOrdinaryText、schemaUsesCompositionAndLocalReferencesWithoutNetwork、explicitNullPolicyIsInvalidRatherThanDefaulted 实际通过；finalRequestPrecedesSdkAndResponseIsReusedAcrossReplicas 未执行 |
| final3-schema-serial / final3-budget-serial | 各 1 SUCCESSFUL，无失败容器/skip | 替代有共享 DB 初始化干扰风险的两条 final3 重叠记录；仍是 final3，不重标 final4 |
| 其他 final1–3、green/red、candidate684 原 53 | 各自标签保留 | 不合计成“最终全量通过”；原 RED、setup/discovery/断言/编译失败不抹除 |

新增 final4 核心路径具备真实 PG/跨服务 HTTP/受控 SDK 端点结果。独立审阅没有启动任何测试或服务；“真实依赖环境”的具体源码→编译→运行时→artifact 物理关系和关闭资格仍由 root 独立核验。

⚠️ legacy main 与 summary：本波 XML 的类级 ENGINE_PG_TEST 未启用，SKIP 不是 GREEN。最终 diff 仅改变 exchangeInitial，旧 exchange/summary 及共享 private fail/commit 没有改写；common/OutputPolicy 未变。已有 candidate684 两个真实 PG PASS（root grant 指定 regression-legacy-main-003 和 regression-legacy-summary）可以作为旧源证据候选。root 应明确确认被继承方法、其生产调用路径/依赖与 final4 的关系后决定覆盖是否充分；不能只因“测试方法正文没改”就完成继承。此项当前列为跨任务证据待决，而非凭空提出新生产代码缺陷。若 root 证明失败或存在新受影响路径，才转为明确功能验证缺口；不得用将来的联测义务替代当下 §4.3 门槛。本 reviewer 不请求重开已关闭库/增加第三库/全量重跑。

## Critical / Important / Minor

- Critical：0。
- Important：0；原 I1/I2/I3/I4 功能问题和提交窗口问题已关闭。
- Minor：0；原 M1 已关闭。

上述计数是代码与本次需求复审计数；下面的执行资格事项不是用 Minor 掩盖的生产缺陷，也不因 C0/I0 自动获得组件条件验收。

## v1.0 三档适用、缺口登记建议

独立依据为 `.local/component-conditional-acceptance-rule-v1.md` 全文，尤其 §2 条件档四项同时成立、§3 穷尽白名单、§4 排除、§6 独立提出/负责人批准、§8.2 不扩张解释。作者 candidates 只是输入，没有照抄其自评作为裁决。

| 编号 | 实际事项及不可回补原因 | §3 分类 / §4、§8 判断 | 本次建议 |
|---|---|---|---|
| P1 | final3-schema/final3-budget 历史重叠，峰值 5 server JVM + 2 Redis；历史共享初始化干扰和超限事实不能被后来串行通过改写 | 可归测试执行过程类；必须排除受影响原 cohort，保留精确重叠披露。串行补验解决该功能证据疑问，不追认原执行合规 | 可作为将来条件清单的逐项候选，尚非条件档批准 |
| P2 | root 的闭源方法比较直接 Python，缺该次 stable PID/start/preseal 记录；后来 guard 无法补造当时身份 | 历史记录类/执行过程证据候选；已知仅静态比较、exit0，不据此证明运行时通过 | root 保留缺口并以独立物理资格结果判断可采用的结论，不回补历史 |
| P3 | root 的只读定位命令未保留首次 exit/session，精确完成/身份不可从现存记录还原 | 记录缺失可提出历史记录类；但“是否仍有活动工作”是现实状态问题，不能以缺记录自动宣称已关闭 | ⚠️ root 分别处理历史不可补证与实际 noBackground 资格；不以另一 session9813 或 writer 关闭记录替代 |
| P4 | 原 writer/fixer 的 synthetic fixture/helper 凭据字面量输出，违反明确不输出要求；已披露，内容无法从会话历史撤回成未输出 | **不属于 §3 三类过程证据缺失。** 这是实际执行保密违约，不是封存/PID/归档口径缺口。synthetic、后来安全投影或已关闭资源都不自动把它变成白名单项。已披露事实本身不构成“未披露”的 §4.5 指控；若另查出隐瞒/伪造，则另适用 §4.5 | **当前阻止提出 CODE_ACCEPTED(条件)**，不扩张白名单，不自批豁免；保留 NONCOMPLIANT。代码 C0/I0 与该执行事实分别报告 |
| P5 | 曾口头“5通过”，后来纠正并保留为 4PASS+1SKIP | 现存透明更正不是未披露的虚报证据，不能假称最新仍 5PASS；也不是需要用 §3 消化的测试成功 | 保留旧说法/纠正；按实际 XML 计数，若发现故意造假另依 §4.5 |
| P6 | 本波 legacy PG 类级跳过，父 cohort 通过与静态关系待 root 资格判断 | 功能覆盖问题不在 §3；如果当下必需覆盖仍未满足，适用 §4.3，不允许塞入证据组织类 | ⚠️ 按上节具名调用关系决定，不能拿 T31–T33 将来结果提前充当本轮 GREEN |
| P7 | 更早 674/677/680 的执行限制和资格可能传递 | 各项必须独立登记；不能概括成“继承历史均白名单” | ⚠️ 由另一个被授权 reviewer/root 的已完成结论合并，本 reviewer 未读其 WIP、未重跑旧审计 |
| P8 | 本 reviewer 一次误填不存在 cwd，在 CreateProcess 前被工具拒绝；一次猜错技能模板路径；一次 PowerShell 投影管道语法错误 | 前者没有命令启动或目录读取；后两者为只读准备失败，无测试/业务副作用，不是生产缺陷或需捏造的历史验收缺口 | 如实保留，本报告不声称整个 review 执行零准备错误；其后命令全部显式 P |

三档逐一判断：

- **ACCEPTED：不建议。** 执行证据/行为资格不完整，物理资格尚待 root，不能称完全验收。
- **CODE_ACCEPTED(条件)：当前不建议。** 代码门槛通过并不满足全部遗留缺口在 §3 的必要条件；P4 明确在白名单外。P3/P6/P7 也不能先假定已解决。§6 负责人批准是必要手续，不是扩大白名单的权限。
- **NOT_ACCEPTED：当前提出。** 保留原记录并单独记录本次代码复审 Approved。对已披露但白名单外的执行违约，v1.0 没有给出可自动转换的渠道；在规则没有允许前维持未验收，而非错误声称这是未解决的生产功能缺陷或已证实 §4.5。

§4.1/4.2/4.4：本次没有发现未关闭的生产功能/安全缺陷、实质契约偏离或 Critical/功能级 Important；元准入的保守边界是披露的有限 profile 限制，不伪装完整 schema 兼容。§4.3：本次核心新增真实链路全绿；P6 与 root 物理绑定尚须完成。§4.5：没有从授权输入中发现未披露/伪造的证据，故不作该指控；已更正、已披露也不能因此扩张 §3。§8.2：生产行为/权限/契约问题永远不能改记过程项。

## 风险对冲计划建议（不提前使条件档生效）

以下是若后续满足规则、由独立复审提出且项目负责人依 §6 批准时必须绑定的计划；即使维持 NOT_ACCEPTED，亦可作为后续联合验证清单。无排期、无当前容量承诺、无默认激活。

| 对冲项 | 实际核心行为 | 预期联合证据 |
|---|---|---|
| H1 — T31，17 项联合功能验收中的真实初始链路 | 实际身份入口→冻结 TASK/FULL/CHAT→current/retained/forward→材料→tokenizer/配额→SDK/provider；TEXT 与小 JSON/schema 两正控；canonical UTF8/SHA/material MAIN/send_seq1；raw 与 derived 分离 | 将此组件映射到 T31 的实际 17 项清单编号，逐项提供实际环境、版本/源码、输入/产物摘要、调用关联、一次调用/发送/提交与结果，不把本片段声称完成全部17项；production IAM/D3 启用后实测真实身份授权链 |
| H2 — T31/T32，声明和无效结果 | bogus 根/嵌套、错误类型/策略、巨大 retry、预算拒绝均在付费前；PASS_WITH_FLAG/TERMINATE 对 JSON 解析失败与 schema mismatch 均稳定 | 真依赖 ingress、invocation、call budget 为零的拒绝证据；有效/无效四组合实际终态、原文、诊断、无校正请求；有限 profile 准入边界随接口说明披露 |
| H3 — T32，故障/并发/隔离 | token/time 超限，收到最终响应后 crash/restart/scheduler recovery，ACK-loss/UNKNOWN；并发 owner、current/dispatch/node lease 在验证与提交窗口失效；tenant/root/version/floor/parents/exact scope 隔离 | PG 事务前后 run/node/attempt/material/event 对比，原 paid response 与已知 usage 一次留存、未知 usage 未伪核销、旧 owner 无终态/发布、无重复 send/commit；真正墙钟 timeout 与真实生产授权失效另有联测 |
| H4 — T33，容量/背压/结果 | 有限 profile 下并发 streaming、慢消费者、队列/租约压力、声明校验成本、背压后的恢复与结果一致性 | 批准负载条件下的实际容量/延迟/资源/背压观测、拒绝与错误率、canonical/response/usage/终态一致性；记录保护阈值及达到阈值的结果，本报告不预言吞吐 |
| H5 — T31/T32/T33 共存及生产 IAM/D3 联调 | initial 与普通 FLOW、legacy MAIN/SUMMARY 共存；默认 IAM 仍关闭，正式可信装配启用后才测试生产 current/forward 与真实联合依赖 | 实际部署/依赖/授权记录、具名方法/业务场景结果；关闭 P6 时不重标旧源证据，后续联合试验也不能反向抹去本波 SKIP；不得顺带打开 public CHAT 或扩展 history/tools/retry profile |

**T34 硬闸：**逐组件核对负责人批准编号、白名单条件逐条清单、T31/T32/T33 和生产 IAM/D3 的实际覆盖。未实际覆盖的任何拟条件组件不得生产签收。联合验证发现功能缺陷，立即撤销条件档并回 NOT_ACCEPTED；条件清单、对冲计划、覆盖结果三者随交付物向客户披露。

## 检查范围、精确身份与 root 待办

已读完整 final grant、fix brief、原需求/计划、原独立 review、规则及作者追加修复报告。最终 contextual native diff 按相邻四段读一次，没有主 Git、没有重新生成 diff、没有虚构 commit。仅 metadata/hash 核对下列入口，不重复全树/运行时/包资格审计：

| 输入 | Bytes | SHA256 |
|---|---:|---|
| review brief | 10920 | bdeaf137ef4968f8a6fc1b0caa02a8136ea450bb9f479d12ba3665135fa7a50e |
| rule v1.0 | 6866 | 0817c6021decb015a6daef3ebdb4f3d1fe301babdc91ed0d38748cd4d9404b05 |
| contextual package | 67153 | d7908dd84199c86d031e23cf88162aedc189c2d040cb1041429c2ef59a28b852 |
| original review | 17700 | 06057d5e2acb7921a84c74145cdb12192e2c96994649f82689a1979c34653da1 |
| appended author report | 45040 | 1868f3df27faa040b6e4811cdb0f7a0b40bc73e0164aef6211ab5e7d8a44d8eb |
| candidate684 source manifest | 135941 | 75cec022e9048a1bd3da3271a44ea0bf55615cb283d3d76db9587d3d5b6ff960 |
| fixed684 final4 source manifest | 135942 | 1d11e82ab89aa589aa8415fee8a14e36b38fab40dd18e4567c9b085ea9153901 |
| fix test-summary | 63794 | 73c678a552014c4c5bff6b3fd216ccb2b414d1b93f9423f416798d5746dc9e56 |
| final4 Surefire summary | 2446 | b04af8d65e38b52c9b6af5a283d8b76fe2b33503c68ce07e8a4c7fe5455c7ea3 |
| author conditional candidates | 6146 | 0301ba21013e391719c4d4bc688966525bf9a5bc8535932f9e304a3b5bb5a38a |

具名 outside-diff 检查仅有：

1. 完整声明/整数/无效结果策略是否真由既有 API 保证：`OutputPolicy.java:42` 构造、`:128` validate、`:163` diagnostic。
2. 新 324 系数是否低估递归/重复元验证：`common/schema/SchemaWorkBudget.java` 的 inspect/Plan/statistics、`SchemaDeadline.java:17`、`BoundedRegexFactory.java:16`/`:84`；加上 root 特别授权的精确8资源静态 ZIP 核对。没有扫描其他依赖版本或运行类。
3. 新失败结果是否被旧调用方误当成功/无限 defer：仅 `RunService.java:1880`/`:1891` 这一具体 caller。
4. 新 terminal fence 是否真的能回滚旧 fail 的失效 CAS、预算异常是否误吞：`TraceStore.java:535` writable、`:588` protectedAtomic、`:858` commit、`:948` fail、`:1017` budgetAfter、`:1172`/`:1180` initial fences、`:1367` requireDispatch。
5. 差异 hunk 截断 dispatchInitial 的恢复事实核对和真实 consume/INSERT 收尾，因此仅补读 `TraceStore.java:1110` 与 `:1154` 对应中断片段，确认同一 canonical/manifest/route/attempt/response 和一次调用标记。未整文件重读改变的生产文件。

⚠️ root 仍需独立完成 final4 源码/compile/runtime2/runtime3/fresh JAR/embedded common/native replay/overlay/inventory/control/completion 的物理绑定与资源关闭；本报告的功能结论不能代替这些资格。7MOD/677same、accepted680 组合7MOD4ADD673same 是输入包关系，未再全树扫描。

⚠️ root 须定向解决 P3/P6/P7，并保留 P4 原执行违约；不能从 writer ALL 或工具读取成功推断全体资源/会话均关闭。另一个旧组件规则 reviewer 的结论由 root 合并，本 reviewer 未读取其工作产物。

⚠️ 默认 IAM/D3/public CHAT、完整 UNKNOWN resolution、其他调用 profile、真实外部 provider 兼容/容量、项目全局状态均未获得本报告接受；Java21/Boot3.5.0/SpringAI1.0.0 与冻结 v1.2 不因本复审改变。受控端点结果不冒充完整生产联测。

**ALL / noBackground / NOTAccepted。** 本 reviewer 所有已执行命令均已返回；无子代理、构建、测试重跑、服务、HTTP/DB/端口/进程探针、主 Git、后台工作或额外文件写入。唯一落盘为本报告。代码接受与保护台账更新由 root 处理，条件档批准由项目负责人依规则处理。
