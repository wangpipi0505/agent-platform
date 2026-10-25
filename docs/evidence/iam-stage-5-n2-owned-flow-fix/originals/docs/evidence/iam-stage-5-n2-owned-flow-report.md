# Task5 N2：原 owned scheduler 与纯 FLOW 实际推进

2026-10-20；唯一工程 G:/207/项目/算法重组工具/代码/agent-platform。作者自审交付，等待 root 独立 Spec/Quality 双审。范围是隔离装配下的真实 engine 原 owned worker，生产 IAM 闭锁、v1.2、四契约与固定版本保持。

## 实际结果

最终实际命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-n2-final03 engine-runtime OwnedFlowSchedulerPostgresTest,OwnedFlowMaterialsPostgresTest,FlowMaterialScopePostgresTest,FlowMaterialStatusPostgresTest,FlowMaterialTransactionsPostgresTest,RunAuthorityLocalCurrentPostgresTest,GraphRulesTest
```

exit 0；actual Running 7 类、对应 XML 7 份，67 tests / 0 failures / 0 errors / 0 skipped。各类：GraphRulesTest 1；FlowMaterialScopePostgresTest 11；FlowMaterialStatusPostgresTest 11；FlowMaterialTransactionsPostgresTest 10；OwnedFlowMaterialsPostgresTest 15；OwnedFlowSchedulerPostgresTest 6；RunAuthorityLocalCurrentPostgresTest 13。58 份其它 XML 仅 incidental，未计为执行。每轮 raw/Running/XML/source 各自冻结；没有把历史绿色拼成最终数量。

原 RunScheduler reserve/Admission.persist/created 与 poll、真实 owned dispatch、原 RunService.driveRecovered/drive、原 FlowDriver、真实 PG 产生了节点输出、attempt、events、材料 scopes 和 terminal aggregate。创建 fixture 使用 N1 RunAuthorityStore.createPublished/TraceStore.createNode，在实际 PG 受理组件事务中保存原身份/授权；没有经过原 RunController/RunService.createInternal 或 gateway，不称门户整链通过。

生产只改七个既有 engine 文件：application/RunAuthority、RunService、FlowDriver、RunScheduler；infra/RunScheduleRepository、TraceStore；domain/GraphRules。源码变化 11 文件（包括两份新测试和两份既有测试/fixture）；无新生产源文件、第二 driver、通用事务框架、权限 ThreadLocal、授权 receipt、POM/yml/route JSON、迁移、common 或其它业务模块源码变化。固定内部 work/decision records 只是普通值，仍须显式 proof 与实际 dispatch。

## 事务、图与材料

TraceStore 的 startOwnedFlow、prepareAndClaimFlow、commitFlow/commitDefaultFlow、failFlow、stopFlowForBudget、aggregateFlow 拥有各自顶层 RR。原 N1 proof-only 公共 protected writer 仍拒绝 ambient；新组合从 private 写原语复用，不反向依赖 application，不接 public Supplier/任意 SQL 回调。RunService 保留编排，FlowDriver 保留原纯执行与输出映射。每个固定事务 callback 末检查同 proof，真实 protectedTx.execute 返回后再次检查；提交后过期保留已提交事实，拒绝继续交付或业务分流。

锁序为 run→authority observation→按 id 的 node。先核真实 immutable binding、original grant/full initial/origin、持久成功 current CAS/无 block、owner/generation/live dispatch，节点锁等待后再次核实时 dispatch，再授权全部 current scopes/物化 payload。新写入的 scope/state 在本事务立即重读更新到 view，后续 prepare/claim/aggregate 使用新值。prepare、claim、commit/default/fail、budget/aggregate 均有实际 dispatch 约束；失效 generation 不写当前 generation 成功/失败事实。有效 dispatch、仅 node CAS0 的 fail 保留真实 attempt 失败，且无 node NODE_FAILED/success scope；失效 dispatch 则退出。

GraphRules 最小保存原 edge 顺序、CONDITION/ERROR_EDGE 转换、ALL/ANY/skipCountsAs、实际 SUCCEEDED 首个 ANY winner、write-once winner、仅独占 READY/RUNNING 直接前驱 ABANDONED 的规则。legacy FlowDriver.prepare 与 protected prepare 共用纯准备决策；legacy 聚合原实现保留，protected 聚合使用最小提取的同语义纯状态/输出选择。每个实际 changed row 写 scope；原 FAILED/SKIPPED 事件保留，winner/ABANDONED 无额外虚构事件；CAS0 不造成功事实。

每个新节点动作重新 current，prepare/claim/execute/commit 同 proof；聚合是事务外再取得 current 的新动作。execute 只消费 claim 物化的 inputs、snapshot、全部 node output/error 形成的 scope，实际测试禁止其任何 PG 查询，并验证 protected worker 不出现 raw select *。claim 的真实 attempt 初始 request/checkpoint/error/state scopes 保留全部执行父要求；commit 重新授权当前材料并继承本真实 attempt 的传递要求，另一个节点输出更新后旧执行输入祖先仍在。候选 SHA/public scope record 不是证明，旧 payload ref 被覆盖后的 N1 不可读边界不变。

预算预检和实际预算写共用 private budgetAfter。运行级 maxParallel/node lease/attempt_no、首次 steps、默认 attempt 材料与 NODE_STARTED 保留。预算耗尽在任何 claim/node RUNNING/attempt/预算写之前返回有限结果，提交已经完成的 prepare 副作用；随后同有效 proof 的具体 budget stop 重新核实际锁内预算及错误码。未耗尽的预算不能仅凭调用者代码被终止，SQL/材料/权限错误也不能当预算结果。DEFAULT_VALUE 的 node.error 与 output/committed CAS、attempt、scopes、LOGIC_RESULT_COMMITTED/NODE_FINISHED 同一个事务；CAS0 无默认 error 或成功 scope。

aggregate 在同 RR 授权物化、按原顺序准备并刷新、选择原聚合状态与首个 SQL 非 NULL END（否则最后节点 output），写 run state/output 与 terminal 事件/scopes。SQL NULL 与显式 JSON null 的 flag、digest 与展示语义保留。terminal 早返回不重复 RUN_FINISHED；父范围覆盖全部实际消费材料及该事务 prepare 新 scopes。

## 调度、期限和失败分类

protected claim 只写 dispatch owner/until/generation，不提前 PENDING→RUNNING、RUN_STARTED 或读取业务 payload。claimNext/claimSpecific 都检查 authority 存在、原 grant、DENIED/EXPIRED、503 retry_not_before 和 dispatch_next_at；显式 protected-only repository 不把缺 authority 历史行当 legacy。legacy 部署和 specific claim 原有直接入口行为保留。固定 deferOwned 以原 release CAS 保存有界 3 秒退避；finally 第二次 release 为 CAS0，slot 恰归还一次。

authority grant/retry 与 proof/material 校验使用构造时固定注入的可信 Clock；不接受每次调用 Clock、System 属性或静态可变配置。dispatch/node lease 在真实 PG 锁之后用 clock_timestamp 实时检查，不依赖事务开始时 now()。测试私有签名 authority 的 Clock 与真实 PG 墙钟是两种明确时间来源，green01 的实现失败是最初混用了它们，原失败未改写成 setup。

RunAuthority 将网络/已验未到期 current 的原 initial coverage/strict 拒绝，与成功 CAS/本地 commit/基础设施错误分开。真实拒绝/503/原 grant 到期仍沿原 Binding/check_version CAS 持久记录；原范围收窄永久 DENIED 和严格绑定负例全部保留。仅短 ticket/operation/proof 到期且原 grant 仍有效，不永久封死 grant，有限退避后可新 current 恢复。没有新 load 一个 Binding 盲写 block，原 I1 UPDATE 后/实际 commit 返回后检查仍通过。

RunService 将 execute 业务故障 catch 与所有提交/current 分开。权限、期限、材料完整性、dispatch、serialization/DB 错误先退出，不被转成 RUNTIME_ERROR、DEFAULT_VALUE、NODE_FAILED、budget 或 503。原每 poll resumeExternalFacts 的 metadata 候选 SQL 在进事务前排除 protected 未迁移工具事实；renewLeases 只续真实 live 控制 lease，lease 字段不进入 node-state digest。

## 有限真实验证与失败记录

| 阶段 | Running/XML | tests/F/E/skip | 实际分类 |
| --- | --- | --- | --- |
| final01 | 7/7 | 66/1/0/0 | actual affected set implementation regression: 66/1F/0E, shared claim omitted original lease_until return |
| final02 | 7/7 | 66/0/0/0 | 66/0F/0E after restoring lease return; separately frozen before final failFlow self-review |
| final03 | 7/7 | 67/0/0/0 | final source-change justified seven-class affected verification after failFlow callback-end dispatch fence; actual Running/XML/source determine counts |
| green01 | 1/1 | 1/1/0/0 | implementation failure: trusted authority fixture Clock differed from PG wall time, so grant eligibility excluded the run |
| green02 | 1/1 | 4/0/0/0 | four actual owned scheduler/worker behaviors passed |
| green03 | 1/1 | 8/0/0/0 | eight actual FLOW material behaviors passed |
| green04 | 4/4 | 31/0/0/0 | 31 affected graph/N2/LocalCurrent behaviors passed including actual Lock and RR serialization |
| red01 | 1/1 | 1/0/1/0 | compiled actual PG protected scheduler behavior error: old claim invoked legacy RUN_STARTED writer |
| red02 | 1/1 | 1/1/0/0 | actual PG behavior assertion red: protected claim rejected by old RUN_STARTED writer |
| red03 | 1/1 | 1/1/0/0 | actual PG node Lock behavior red: expired dispatch read 15 payloads before end-of-action rejection |
| red04 | 1/1 | 1/1/0/0 | actual budget-stop behavior red: caller budget code terminated a run with available budget |
| red05 | 1/1 | 1/1/0/0 | actual PG behavior red: failure scopes allowed a same-Tx control dispatch expiry to commit failure facts |

red01 是实际编译后到达旧 protected claim→旧 RUN_STARTED writer 的错误；red02 用断言明确同一行为红，未先改生产造红。red03 真实 node 行锁观测后等 1100ms，使实际 PG dispatch lease 到期，旧实现在末尾拒绝前读了 15 份 payload；锁后读取前 fence 修复并验证。red04 保留了未耗尽预算可被错误终止的真实行为红。

final01 的 66/1F/0E 是提取共享 claim 时漏返回原 lease_until 的真实回归，原 N1 断言未改。恢复原 lease_owner/lease_until 窄实时控制查询后 final02 实际 66/0/0 并先冻结。最后逐方法自审发现 failFlow 的 attempt/scopes/events 后缺末 dispatch fence：red05 使用现连接 fixture 在真实 scope INSERT 前，同 Tx 控制 UPDATE dispatch_until=clock_timestamp()-1 second，证明旧方法仍提交失败；这属于真实 PG 控制到期注入，**不是**自然时间/锁等待证据。增加 callback 末 fence 后才进行 final03。这些源码变化/真实失败解释了复验；不是同码绿色重复。

有限测试还覆盖真实 403/503 零 payload/attempt/RUN_STARTED、双 claim 退避/slot、过期 PORTAL 有效 grant 的 DELEGATED 推进、未知/CONFIRM/AGENT/LLM/SKILL/RETRIEVE 拒绝、DEFAULT_VALUE 与 budget/aggregate 多 scope 写点故障回滚、ANY 独占/共享分支、CONDITION/ALL/ERROR_EDGE、node CAS0 与旧 dispatch、全 terminal aggregate-only 新 current 不重跑节点、SQL NULL/JSON null 与 RUN_FINISHED 恰一次、真实 PG RR serialization 原类型退出、legacy 原 scheduler/FlowDriver 对照、短 proof CAS 到期可重核恢复。原 N1 三类与原 LocalCurrent 安全断言纳入最后实际集合。无 H2/Map/mockstore/假 target handler，未运行旧耗尽 probe。

## 数据库、保护与交接证据

固定新库 agent_platform_iam_owned_flow_v12，127.0.0.1:25432/agent_v12；凭据仅由原受控 runner 引入 child env，无输出或 .env parser 复制。red01 先核实际源 Vmax=217，确认固定新库 absent 后 CREATE，Flyway 正常应用连续迁移；没有旧目标 215/216 一次性 preflight。后续最终 raw 的 N2_TARGET_HISTORY 实际列出 schema marker 与连续 200..217 的 version/description/checksum/success，并有断言。N2 无新迁移、不占 218、不改 V216/V217；原 N1 flow_material_v12 与 engine_v12 仅在授权受影响 fixture 中使用。主库 agent_platform_v12、app/policy/skill/model 等业务库未运行测试。

不可覆盖 before 捕获 source 362、全 target 1740 与物理副本；分别核 N1 source/target final。root 八索引 afterSha256 在当前基线再次逐项匹配。最终 source 364、target 1761；变化源 11、目标 46，业务 JAR 变化 0。完整 context patch 357273 bytes，真实 git apply --check 与 apply 均成功，364 份重放源码逐字 SHA/bytes 一致；before 的 362 源/1740 目标物理原件再次核验。没有 HEAD/remote/commit 虚构，见 git-boundary.json；未改全局/root Git 属性。

最终保护采样实际 75091 静态文件、28 原 runner 脚本、8 执行/维护/来源文档，changes 0、runnerChanges 0、externalDocChanges 0、未授权新增 0。只排六个各自 runtime 目录、精确 postgres.log、精确 root current-work-state、自有 local/evidence/report 与每轮预先登记的精确 runner log/fresh-secrets 路径；正式 brief 在目录外仍受保护。未采 SDK/Maven 依赖整树，固定来源不等于全工具逐字采样。

打包工具错误也保留：archive 的 stem 替换曾把 12 轮证据写入新误名 iam-stage-5-n2-flow-material-status，导致首次 finalize ENOENT，尚未产生最终冻结。核 before 无此前缀受保护文件后，用同一原生 rename 逐 stage 迁到授权目录，4421 文件迁前/迁后 SHA/bytes 全等；原 manifest/raw 未重写。before 没有目录对象采样，因此不声称它证明事前空目录不存在。仅移除已核空误名父目录，没有递归删除或旧 replay 清理。relocation.json 保存逐文件原/新绝对路径及 SHA；local archive-path-correction-before/after.json 保存实际失败与恢复。这是打包工具错误，未改变测试/生产源码或重跑 Maven。

关键机器文件：before.json、source-before/after.json、target-before/after.json、changed-files.json、target-changes.json、change.patch、apply-check/apply.json、patch-check.json、test-audit.json、protection-summary/protected-changes.json、handoff.json；物理 baseline/frozen/replay/target-final 在同目录。来源与实际阅读段落见 upstream/manifest.json、READING.md，复用 Spring6.2.7/PG16.15 原件先核 SHA；只声明已列的方法/段落，未声称全项目阅读。正式 brief SHA 635978cb1475efd19a5d16d51ff778e96bc86f60c197c3461326e4701ee36dd3。

## 尚未验收的边界

生产全局 IAM 仍关闭。显式隔离构造的 RunAuthority/RunService/RunScheduleRepository 组合不是正式可信 Spring 装配；N3 仍须把原 RunController/RunService.createInternal/RuntimeClients 受理与 after-commit 检查接上，N4 才是实际 gateway 链。完整后台恢复域的 ExecutionRecovery/Approval/debug 周期入口、CHAT/工具/模型/审批/流、Observer、RR1/RR2、真实 IAM/本体/K8s/容量与全 Task5 均未签收。测试证明列出的有限真实组件/边界，不是全故障排列、生产吞吐或外部系统验收。未 commit/deploy/publish、未改共享索引；待 root 独立双审后登记。

作者交接状态与 source/target 写权归还在 handoff.json；完成后作者停止 Maven/source/target 写入。

作者最终交接：DONE_WITH_CONCERNS。source/target 独占写权现归还 root；作者停止 Maven 及 source/target 修改。保留关注点是报告列出的正式装配、N3/N4/未迁移恢复域和真实外部验收边界，以及已完整记录并恢复的归档目录工具错误；没有用这些边界掩盖未披露测试失败。
