# G6 Task5 N1：有限 FLOW 材料与原 status 组件（正式授权）

唯一工程/每次 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。不得读取、关联或修改相邻旧实现/配置/数据/进程。用户已授权按计划持续实现，无真实模型/本体/IAM/K8s时使用隔离端点；不再询问环境或是否继续。原v1.2、四契约、固定技术版本、七模块HTTP边界及生产IAM闭锁不变，不排工期、不部署/提交/发布。

## 已核前置与本段边界

D3b已独立Spec compliant / Task quality Approved，review SHA `d01c7076c4a6478c8a6bd6913df92fd1b97d92331b8d86278eac3401a16bc56d`；root全文读并处置全部跨段事项。root核357源/1710 target、七轮raw/XML/source，另一次核135302静态原件一致、两精确授权运行期变化；六索引及维护/来源八文档已登记在`.local/checkpoint-iam-d3b-docs-before/manifest.json`。T01仍唯一整体完成。D3b最终source manifest `docs/evidence/iam-stage-4-d3b/source-after.json` SHA `bdd8d926e3aed39bc4812d3a2ca9ee73651de7446b7bc03f35667fcbda1d4ced`。

读本稿后，读已接受的有限接缝提案`docs/evidence/iam-next-published-chain-seam-proposal.md`（SHA `5a7bef43ea57290367895cef38a029437f66733e0c6a3a636e34d1fc9aa9d308`）第1–3节及与N2的接口，第4–7节只作为依赖边界；本稿细化/裁决优先。原Task5完整材料/批准/恢复/流验收不删。本段只交付**实际engine材料写读与原RunService status组合组件**，不声称真实gateway、原受理或owned worker已接通。后续N2原调度/FlowDriver、N3真实受理/app client、N4可信gateway首链另验。

source/target唯一作者，前一作者和reviewer均已归还；root仅并发改精确`.local/current-work-state.json`，本段无其它已授权并发新增。开始捕获不可重写before；完成完整报告与机器交接后归还独占权，再独立双审。

## 允许文件和接口纪律

生产仅限engine既有`infra/MaterialScopeStore.java`、`infra/RunAuthorityStore.java`、`infra/TraceStore.java`、`application/RunService.java`的status/具体投影接缝，以及必要的`application/RunAuthority.java`最小`currentRetained`提取、一个实际下一连续迁移。新N1测试/极小专属测试fixture可加；原三E1b类及fixture可作确受影响的装配/schema兼容调整（例如把原“全表3行”断言明确为“三initial行”，同时增加新dynamic对照），保留所有原安全负例和原三initial精确语义，不能删测/放宽以凑绿，逐项报告。无新生产类/common/app/gateway/skill/model/monitor/POM/yml/路由JSON/driver修改；确需其它真实文件先给root实际依赖说明，不自行扩范围。

既有`createPublished`拥有唯一run INSERT，继续要求actual same-DS write Tx和main frame；不能给已存在run附权/upsert。现`RunAuthority.current`已实现resume→signed current→original binding/三初始scope→CAS→真实commit后期限终检，不重开协议。若N1真实writer需要完整proof，可现在最小提取`currentRetained(long,ServiceCall)`，原`current`委托并取`.current()`；保留网络事务外、原403/503/CAS行为与I1的UPDATE后同Tx、真实commit返回后两个终检，不加receipt/生产hook/调用者Clock输入。

普通构造兼容可最小保留；trusted Clock/DataSource经构造/DI，与E1b惯例一致，不能用System属性、静态全局或缺bean静默降为legacy。后台授权必须显式传RetainedAuthorization，不装请求frame/ThreadLocal权限，不把public VerifiedAuthority/AuthorizedMaterial record当不可伪造证明。旧Actor-only/raw入口对protected run默认拒绝；无authority的显式legacy功能保留，不自动回填旧run。

## N1.1 实际连续迁移和有限locator

开始先核源码实际Vmax和授权PG历史；V216已存在且不可改。下一候选217仅当两端预检连续无冲突才落地，不能高号占坑/outOfOrder；G2旧217候选随后让位，root台账按实际新Vmax修订。本段扩同一engine.material_scope，不建第二份payload/通用版本框架。

原V216三initial种类`run-snapshot/run-inputs/run-version-refs`与其publicId/SHA64 version、scope JSON和所有历史值完全保留；run_authority immutable及scope append-only trigger继续有效。扩以下固定kind/locator：

| kind | 真实内容/归属 |
|---|---|
| run-state、run-budget、run-output、run-error | run.public_id；state逐列固定，含status/event_seq/started/ended/stop_reason等实际控制列；不混dispatch心跳 |
| node-definition、node-state、node-output、node-error | canonical numeric node id，经run FK核owner；state含原nodeView/实际winner/committed/released控制字段 |
| attempt-state、attempt-request、attempt-checkpoint、attempt-error | canonical numeric attempt id，经node/run核owner；request_snapshot={}、checkpoint默认{}也是真实初始材料 |
| run-event | canonical numeric event id，经run/seq关联；固定seq/type/payload/created_at投影 |

在同表增加PG生成的单调material_revision；dynamic version为规范十进制串，initial version仍SHA64，DB CHECK分别限制。material_version可改足够容纳两种已定格式的文本。撤全局UNIQUE(run_id,kind)，换仅三initial kinds的部分唯一约束；保留tenant/producer/kind/id/version唯一、scope digest唯一、append-only。不覆盖scope旧行，回滚sequence空洞允许；latest由同locator最高revision确定。原payload被业务表覆盖而无历史值时旧ref拒绝，不拿新payload套旧scope；不承诺任意旧payload可读。

RunAuthorityStore.load/scopes查询精确限定三initial并仍恰好3，不改成>=3忽略缺失/重复。dynamic行不塞Binding.equals导致每次事件误变immutable绑定，也不要求其sourceDecision=admissionDecision。initial旧验证保持；dynamic owner/full-initial hash与真实root绑定，sourceDecision/authVersion取本次真实current，requirements保留initial floor、初始材料已固化labels与实际已消费父scope并集，当前新增义务只增不减。parent digest须同run真实已存metadata，不能candidateSHA/自造record/公有参数替代实际血缘。

## N1.2 真实写点、期限与事务

复用现private EXACT/normalize的PG JSONB精确数值表示，不退回double或泛称Json.canonical即可。SQL NULL用JSON null保留，与{}区分；最终status展示沿原TraceStore.json(null)→{}，不改旧output_digest含义。时间投影规定统一UTC表示并做真实往返/原响应兼容验证。

固定locator writer保持包内/具体用途，禁止任意SQL/table/column/URL回调或通用注册表。实际TraceStore claim/commit/fail/event/consume及具体初始节点/run scope接上显式proof，payload变化、scope、新revision、原CAS/事件同一DataSource事务；外层catch失败也不得提交半份。原节点CAS、lease/generation、SUPERSEDED_SUBMIT及失败事实语义保持。protected run旧无proof写入口拒绝，不能新helper安全而旧writer照改。protected run旧raw run/nodes/events读取入口亦不能先SELECT *再判权。

受保护写动作使用同TM专用RR write事务（保持run→node锁序），拒绝加入更低隔离ambient RC，不用REQUIRES_NEW、不全局改legacy atomic隔离、不在锁/Tx内联网或无界重试。先窄control lock/metadata、proof/binding/实际scope判权，再payload；写后与callback末尾、本地实际commit返回后终检。proof在commit后才过期时保留已提交历史事实且拒绝交付成功，不伪称回滚。admission用同main frame requireBound；worker用同Retained私Clock终检和original binding严格匹配，并核实际持久阻断为可推进、当次proof对应RunAuthority已成功CAS的current观察（如last_decision_id匹配），不能自行authorizeRetained后绕过持久403/503/旧检查。由currentRetained取得这个原有证明，不加receipt；未来N2再接实际dispatch关系，不能从Actor或guard_ctx重建授权。

createPublished在实际新run同Tx补run-state/budget/output/error初始scope，旧三initial写法不破坏。N1测试可在同原业务事务插真实fixture node并调用具体节点初始scope；真实RunService nodes INSERT由N3接入，报告区分组件fixture与原受理。attempt初始{}、nullable node/error/output和状态均有实际scope；freeze/response/routed_model/真实Agent checkpoint后续未迁移用途对protected run明确拒绝。

N1不改FlowDriver/RunScheduler直接SQL；它们实际接线是N2。不得把未跑原owned worker说成已运行；N1测试使用有限真实PG dispatch/CAS fixture说明其来源，不加第二套scheduler/driver。

## N1.3 原RunService status必须真实消费材料

在实际RunService.status组合逻辑接入受保护分支/具体statusPublished，不能只测试没人调用的MaterialScopeStore helper。先窄owner/root/authority metadata；在同实际main frame、同owner、root与purpose匹配后事务外刷新，再一个顶层只读RR中枚举scope/current refs、node/event metadata并逐份判权，之后同快照读真实payload/核摘要、完整物化原Map；返回前及真实读Tx结束返回后本地proof终检，无Tx内联网。一个status不能每份材料另开快照。已在合格RR/更强ambient的适用性明确；较低RC不能悄悄加入。

保留原字段与投影：runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused；nodeView原字段及nodeInstanceId/nodeDefId别名、events seq/type/payload/created_at、paused原allowedActions。读取哪些control/payload就核其真实scope；不要把select *藏在lockRun/nodeView后再筛。纯FLOW fileRefs为空须窄查确认无tool_call，未迁移tool结果/controlled run先拒绝，不能先读result或写死空列表。无scope的NULL不当作合法空值。

本段授权仅创建/重复POST内部status，call仍main gateway→engine run/app-version；独立GET status/debug/v1 alias目的未签收，不能冒用main/local票开放监控。普通新main认证读取历史材料可按当次owner/root和scope覆盖成功（包括旧grant过期但新授权有效），不强迫其等于历史grant；不覆盖原initial/grant/origin。后台继续严格原委托，两种用途不可混淆。

## 实际隔离数据库与有限验证

仅本工程私有PG127.0.0.1:25432/agent_v12，经既有受控runner获取密码，不输出/复制.env解析。新库固定`agent_platform_iam_flow_material_v12`；postgres管理库只可该固定exists/create。先真实target216预检，再执行本段实际连续迁移，前后history/约束/trigger核验，不预填通过。

因实际修改MaterialScopeStore/RunAuthorityStore/RunAuthority会影响原三E1b类，明确允许其既有隔离`agent_platform_iam_engine_v12`在最终受影响验证应用同一本段连续迁移并写原有限fixture；不得主agent_platform_v12/app metadata/policy/skill/model库。原RunAuthorityDatabasePreflightTest为当初target215一次预检，不在本轮最终集、不回跑它。若源/历史冲突或迁移旧负例fixture不兼容，向root给真实事实，不改原迁移或伪装跳过。

先可编译真实行为红，复用有限signed authority/E1b fixture，真实PG/TraceStore/RunService状态组合，H2/Map/store mock/假目标handler不能替代。测试建议FlowMaterialScopePostgresTest、FlowMaterialStatusPostgresTest，实际名称按实现记录。有限对照至少：

- 新run三initial不变，新增initial state/null材料和真实nodes/attempt/event写读；追加revision/旧ref拒绝、真实parent requirements与labels不丢、高精度数值/null/时间往返。
- metadata-first：错owner/root/proof/current收窄、缺scope/错digest/伪parent时实际payload读取0；读后完整性错误分清已读计数，正常确实读payload，保留全部原status字段。
- 每类实际写点payload后/scope中故障同Tx回滚，外层catch仍不能提交；CAS0不产生成功scope/事件，未知DB错误不伪装权限503。
- 单一status同连接RR、RC加入拒绝、无联网持锁；proof等锁/回调末/真实commit后过期正确拒绝，不延长票；finite wait按I1已核方式观测真正Lock后推进Clock，避免D3b单次观测窗口，不做压力测试。
- 实际TraceStore legacy无authority对照与原status展示保留；protected旧raw/Actor-only读写拒绝；fresh main历史read成功不重绑、严格local原grant失败/撤权/503保留真实阻断。若提取currentRetained，原I1两期限用例仍适用。

最终一次受影响N1集合+原InitialMaterialScopePostgresTest/RunAuthorityPersistencePostgresTest/RunAuthorityLocalCurrentPostgresTest（因共用实现已改，覆盖新迁移兼容）；不跑无关common全套、D2/D3b/七reactor或触主库的legacy测试。其它legacy测试允许testCompile，N1新隔离实际legacy对照需执行。不把历史37/新数量相加；以实际运行source/Running/XML为准，源码再改才按受影响复验。

## 保护采样、开源对照与交接

本段不重复扫描无关SDK/工具依赖树。source357及全target1710的before/physical副本先完成；保护contracts/AGENTS/根POM/docs既有证据、正式brief本身、八执行文档、.local根历史audit/脚本/静态artifacts/backups/checkpoints/各旧阶段冻结物，以及实际调用的.tools/run-iam-module-tests.mjs及相关runner脚本。其它工具SDK/Maven缓存仅按固定来源与既有manifest使用，不声称全工具树逐字未变。旧组件完整135304采样历史保持，不重写。明确列成功采样实际清单，不伪造未采部分。

本次before精确排除6运行目录.local/minio/、pgdata/、redis/、logs/、tmp/、wsl/及根级.local/postgres.log、精确root current-work-state.json；本任务自有.local/iam-n1-flow-material-status/、精确runner stage日志/新鲜secrets与自有evidence目录可排，不能宽排同stem正式brief或整个.local。root无其它并发新增；发现非授权静态漂移先报告、保留原失败，不倒改before/after。每轮只归档实际测试raw/XML/source，末尾一次保护after，不每轮全扫。

实际读固定Spring6.2.7事务/隔离/连接与PG锁行为所需源码，沿现精确JSON机制；注释解释不变量与真实边界，来源/文件SHA进本段upstream，root双审后更新共享来源索引。仅source下载/测试helper可在本任务目录，不改共享受保护索引或旧报告。

交付`docs/evidence/iam-stage-5-n1-flow-material-status-report.md`与`docs/evidence/iam-stage-5-n1-flow-material-status/`，包含真实命令/输出、行为红与setup/stub/断言噪声区分、每轮actual Running/XML/source与incidental、完整context patch真实git apply逐字一致、source/target/protected before-after/机器交接、自审/未验边界。无HEAD/remote，不能伪造空Git diff或Git提交；不改全局/root Git属性，不抑制JDK检查。全部完成后归还source/target，不自行登记全Task5或部署；最终只回状态、实际测试摘要、报告路径与关注点。必要问题先向root给源码证据，用户已有授权不重复问。
