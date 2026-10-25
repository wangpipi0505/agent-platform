# Task5 N1：有限 FLOW 材料与原 status 组件

唯一工程：G:/207/项目/算法重组工具/代码/agent-platform。日期：2026-10-20。作者自检交付，等待 root 独立 Spec/Quality 双审；不是完整 Task5、真实受理、owned worker 或生产 IAM 签收。

## 实际结果与边界

本段实现了原 engine 组件的有限材料写读与实际 RunService.status 受保护组合分支。生产仅改 MaterialScopeStore、RunAuthorityStore、TraceStore、RunService.status 和 RunAuthority.currentRetained 最小提取，以及真实连续 V217；无新增生产类。原三 initial 的 publicId/SHA64/JSON/owner/Grant/initial/origin/source 审计不变。原 RunAuthority.current 委托 retained 版本后取 current，原 I1 UPDATE 后同事务与实际 commit 返回后两个期限用例在本轮最终集合实际通过。

最终一次受影响命令：

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-n1-final01 engine-runtime FlowMaterialScopePostgresTest,FlowMaterialStatusPostgresTest,FlowMaterialTransactionsPostgresTest,InitialMaterialScopePostgresTest,RunAuthorityPersistencePostgresTest,RunAuthorityLocalCurrentPostgresTest
```

exit 0；actual Running 六类、六份对应 XML，69 tests / 0 failures / 0 errors / 0 skipped。本轮分别为 Scope11、Status11、Transactions10、原 Initial12、原 LocalCurrent12、原 Persistence13；没有累加历史测试数。57 份其它 XML 只是 incidental，未作为本轮执行证据。固定 Java21/offline runner、原 POM、框架及业务 JAR 不变；没有全 reactor/common/D3b 重跑。

完整 raw、actual Running/XML 和当轮 source 在自有证据目录各 stage；机器审计为 test-audit.json。每轮只是该轮实际结果，不以随后源码冒称早先全量同码。

## 材料与真实事务

V216 原件 SHA 仍为 e8ed2edd783ba6e14a04653f2327b3d4c1ff114bd1e4533a0bfecf6bc5de0999。先执行真实 target216 preflight，源 Vmax 与两个授权隔离库历史无 217 冲突，才添加 V217。新库 agent_platform_iam_flow_material_v12 用于 N1；原 agent_platform_iam_engine_v12 只在最终受影响集合迁 216→217、运行原有限 fixture。两个库最终 history/约束/trigger 证据均在 final01 raw；postgres 管理连接只查询/创建固定新库。未访问 main/app/skill/model/monitor 数据库。

同一 material_scope 增加 PG identity material_revision。dynamic version 严格等于该 revision 的规范十进制；initial 仍严格 SHA64。只撤全局(run_id,kind)唯一，换三 initial 部分唯一，保留 locator/version、scope digest 唯一及原不可变 trigger。新 run 七份材料中，load/Binding 永远只纳入精确三 initial；dynamic 不进入 immutable binding equals。旧 ref 在 locator 最新 revision 已变时于 payload 前拒绝；没有伪造旧 payload 历史副本。

固定 run/node/attempt/event locator 均核实际 FK/owner；node/attempt/event id 必须 canonical 正 long。state 固定列包含实际 status、seq、时间、stop、winner、committed/released 等事实字段。run 排除 dispatch 心跳；node-state 排除 lease_owner/lease_until，后者在 claim 返回和 CAS 中窄读真实控制列。已读原 renewLeases 实际 SQL；纯续约对照不会破坏用户事实摘要。request_snapshot={}、checkpoint={}、nullable output/error 都有真实初始 scope。

dynamic 保留 initial floor、initial 固化 labels 与实际已消费父 scope 并集，再加入本次 current 的 obligations。父 digest 必须同 run 真实已存 metadata，且 revision 先于子；不是候选 SHA 或公有 record 自报血缘。一次组合动作后续事件也保留该事务刚产生并实际物化的 scope 为父。专有 derivedOnly 在 initial 三份中不存在，第一次派生带入，第二次 current 已不带时仍经真实直接父保留；不是 initial 已有同标签造成的退化用例。

createPublished 拥有唯一实际 run INSERT，随后私有初始化四份 run scope；TraceStore.createNode 拥有真实新 node INSERT 与四份 scope，不提供给历史 node 补权的方法。两者加入原 admission 写 Tx，同 frame、原 Grant/full initial/origin；失败标同 holder rollback-only。原受理外层 commit 后检查由未来 N3 具体拥有者负责，本段不冒称初始化方法已经完成调用方的外层 commit。

TraceStore 实际 claim/commit/fail/event/consume 新显式 proof 重载在同 TM/DS 顶层 RR 中完成 payload、scope、原 CAS/事件。保留 generation/lease、CAS0 的 SUPERSEDED_SUBMIT、原失败事实语义；fail 的 node CAS0 仍写真实 attempt failure，且没有逻辑 NODE_FAILED 事件。旧 protected Actor-only/raw run/nodes/events/lock/claim/commit/fail/consume/freeze/response 先窄判权并默认拒绝。无 authority 的 legacy 实际 claim/freeze/response/commit、CAS0、预算及原 status 对照实际运行，不回填 authority。

锁序是 run→authority observation row→node。recordCheck 原 CAS 只锁 authority，不反向取得 run/node 锁；网络 resume/current 都在这些事务之外。持有 observation row 防止一个已持 run 锁的 RR writer 在持久 denial 已提交后仍用旧快照提交材料。red08 实际记录 CAS_DONE=true/BLOCKED=AUTHORITY_DENIED；最终 actual Lock 观测通过，raw 为 CAS_DONE=false/BLOCKED=null，writer commit 后阻断 CAS 再提交。遇并发 RR serialization conflict 关闭本动作，不无界重试、不伪装 503。

worker 还要求不可伪造 Retained 私 Clock、原委托严格绑定、无持久 block，且 last_decision_id 等于 RunAuthority 成功 current CAS 的本次 decision；直接自行 authorizeRetained、旧 current 观察、403/503 block 都不能绕过。写后、callback 末及顶层真实 commit 返回后终检；commit 后才过期时保留已提交事实并拒绝交付成功。实际锁等待、payload 后过期、真实读/写 commit 后过期均有限观测通过。

## 原 status 与 root 裁决

实际 RunService.status 首先窄分 protected/legacy，protected 调 TraceStore.statusPublished；原 Map 组合代码保留 runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused，nodeView 字段与别名、events 四字段、paused 原三 actions 都有实际对照。pure FLOW 的空 fileRefs 来自窄查 tool_call 不存在；真实 tool fixture 在任何 result/payload 读取之前拒绝。controlled/未迁移工具用途也拒绝。

owner/root/purpose 窄核后，事务外在同 main frame 刷新 proof；一个顶层只读 RR 先枚举并核齐全部实际 scope/node/event metadata、逐份授权，再同快照读取真实 payload 和核 SHA，完整物化原 Map。14 份 payload 的物理连接 identity/isolation=4/readOnly=true/autoCommit=false 完全相同。所有 metadata 错权/缺 nullable scope/错 digest/伪 parent/丢父 labels/full-initial hash 错误均为实际零 payload；实际内容摘要损坏则明确是已经读 payload 后的完整性拒绝。

复用私有 EXACT/normalize 的 PG JSONB 精确数值，不经 double，旧 output_digest 仍按原含义计算。UTC 微秒字符串统一投影并恢复原 Timestamp 展示；SQL NULL 的材料为 JSON null，与{}区分。另在同次实际 payload SELECT 保留 raw sqlNull flag，维持原 SQL NULL→{}、显式 JSON-null→null，red06 已真实捕获误展示并在最终集合复验。

root 批准的具体边界逐字保存在 root-decisions.md：worker 公共写只接受顶层 RR；ambient RC 报 REPEATABLE_READ_REQUIRED，已有 RR/Serializable 报 PROTECTED_WRITE_REQUIRES_TOP_LEVEL_TRANSACTION。status 刷新前拒绝任何 ambient，内部固定一个顶层只读 RR。没有假装 joined execute 已观察到外层 commit，没有 ThreadLocal/receipt/新调度器。N2 原 prepare/aggregate/DEFAULT_VALUE 的外层组合事务必须在具体拥有者重新接保留同 proof 的 commit 终检，不能简单嵌套顶层 writer。

另按 root 裁决，保留旧 read(MaterialRef,VerifiedAuthority) 编译签名，但受保护 initial/dynamic 都立即拒绝；真正读取使用不可伪造的 read(ref,RetainedAuthorization)。原 Initial、Persistence、LocalCurrent 读用例最小改传真实 p，不把原负例全部变成入口拒绝；原 status-purpose 负例也传真实 retained 才达到 purpose 校验。原“全表三行”断言明确三 initial 行并另核四 dynamic 行；原安全负例未删。普通 fresh-main 同 owner/root/scope coverage 可读已过期并永久后台 blocked 的历史旧 Grant 材料，且 immutable initial/grant/origin 不改；fresh main 无法借读取补写旧授权。

本段只接受原 main gateway→engine POST run/app-version 的内部 status，独立 GET/debug/v1 alias 没有被开放。未把组件 fixture 称为真实原受理或 owned worker。

## 每轮结果与真实失败分类

下表列 T/F/E/S，Running/XML 是实际执行类数。

| stage | Running/XML | T/F/E/S | 分类 |
| --- | --- | --- | --- |
| final01 | 6 / 6 | 69/0/0/0 | single final affected N1 plus three E1b classes passed |
| green01 | 1 / 1 | 2/0/0/0 | two behaviors passed |
| green02 | 3 / 3 | 22/0/0/0 | 22 N1 behaviors passed |
| green03 | 3 / 3 | 24/0/0/0 | 24 N1 behaviors passed |
| preflight01 | 1 / 1 | 1/0/0/0 | setup/database preflight passed |
| red01 | 1 / 1 | 2/2/0/0 | two actual PG behavior reds |
| red02 | 2 / 2 | 13/1/0/0 | test assertion noise: ancestor was incorrectly required as direct parent |
| red03 | 1 / 1 | 9/1/1/0 | two actual behavior reds (lease digest and after-read-commit proof) |
| red04 | 1 / 1 | 1/1/0/0 | actual metadata-first behavior red: mismatched relation read 11 payloads |
| red05 | 1 / 1 | 1/1/0/0 | actual forged public VA behavior red; final counter assertion not reached |
| red06 | 1 / 1 | 1/1/0/0 | actual JSON-null presentation behavior red |
| red07 | 1 / 1 | 1/0/1/0 | observation noise: unpooled backend closed before pg_stat_activity query |
| red08 | 1 / 1 | 1/1/0/0 | actual durable-block race red after finite observation correction |

首次 red03 launcher 请求使用了受控 grammar 不允许的“+”，在 Maven 启动前拒绝，无 XML/行为结论；保留 launcher-rejection.json。随后用允许的整个 Scope 类参数执行 red03，未改受保护 runner。

red02 把祖先 READY scope 误要求为 commit 直接父；真实直接父是 claim 后的 RUNNING scope。保留失败并改断言对真正直接父，同时单独验证传递义务。red07 的 unpooled PG backend 已结束使单行 pg_stat_activity 查询抛 EmptyResult；保留为观测噪声，以 exists 查询与有限完成状态核验纠正后才获得 red08 行为红。

red05 在入口改动之前实际运行 forged public VA 的 initial 读取，assertThrownBy 因读取成功而失败；随后冻结 raw/XML/source 才改生产。计数 reset 有执行，但此失败提前终止，末尾 payloadReads 零断言未执行，原 raw 没有实际计数值；不补造计数、不回改代码制造红。最终同例零 payload 拒绝已通过。

preflight 的归档是测试结束后的源集合，其中包含发起 preflight 后加入的 Scope 测试/fixture 装配改动；不把完整359项冒称 preflight 的编译输入。实际执行的 preflight 文件保留当轮原字节并由 raw/XML 指认；该轮所有生产源码仍为 D3b 基线。最终69例的完整362项与冻结/live/replay逐项同字节，无此适用性歧义。

## 字节、保护与上游证据

- 不可覆盖 before：source357、全部 target1710 均有物理副本于 .local/iam-n1-flow-material-status/before/；完整 before.json 已复制入证据目录，前置 manifest 是 D3b 最终 source-after（SHA bdd8d926e3aed39bc4812d3a2ca9ee73651de7446b7bc03f35667fcbda1d4ced）。
- final source362，允许清单内改动14文件；baseline/frozen/replay 逐字核验。source-after.json SHA bbc0491672b771781405173869706a2d741673dd96b76d9b919666560a2aa51c。
- final target1740，51项变化仅 common/engine target；其它五模块目标和所有业务/生产 JAR 变化0。target-after.json SHA 6f2f43db986d1ec09a942342b469e9d9d998814889a86a2ed92a1cc7a354c706。全部 target-final 为真实现存目标副本，不声称重新 package/deploy。
- full-context change.patch 355021 bytes，SHA 86d68bcc5f484b41cd96dae522054488f15aed97865d89e8da98eed1006aac58；真实 git diff --no-index exit1、git apply --check exit0、实际 apply exit0，362项 SHA/大小全部 byte exact。仅证据根局部 .gitattributes=* -text 保留混合原字节，未改 root/global Git 属性，未使用 whitespace fix。actual git-boundary.json 记录没有 HEAD/remote，没有造空 diff、index/commit/reset/发布。
- 首次保护成功清单：65,381 个静态文件、28 个 runner 脚本、8 份执行/维护/来源文档。具体路径/大小/SHA 在 before.json；只排 brief 规定六运行目录、精确 postgres.log/current-work-state、精确本任务 stage log/fresh-secrets、自有目录/报告。正式 N1 brief 不因同 stem 被排除。SDK/Maven 缓存仅采用既有固定来源；本段未重复135k内容采样。曾用 filename-only 检索定位 formatter，结果含少量无关 SDK 路径，未读取其内容，不声称全工具树未变。
- 最后一次保护采样实际通过：65,381 静态文件、28 runner、8 执行文档变化0，未授权新增0；362 live source 与1,740 live target 仍逐字匹配冻结。正式 brief SHA 仍6a93200f533091376a55a4cf164b2275bf7b81e0085564c7b2e26f4cdacb0fa6。详见 protection-summary.json / protected-changes.json。
- 实际阅读 Spring6.2.7、Jackson2.19.0、PostgreSQL16.15 固定源码的方法/段落及文件 SHA、原 URL、许可证见 upstream/manifest.json 与 READING.md；未改共享来源索引或任何旧报告/正式 brief。

## 自审与后续依赖

本轮 69 例只是 N1 当前组件和确受影响原三 E1b 的实际有限验证；没有原 gateway/controller 创建路径、app HTTP、owned scheduler/FlowDriver 或真实模型/IAM/集群。N1 节点使用具体 createNode INSERT，RUNNING/WAITING_STEP 过渡是明确标注的有限 PG dispatch/status fixture；不是第二套 driver，也没有跑原 owned worker。真实原 createInternal/重复 POST、可信 gateway、调度/聚合及其它直接 SQL 接线仍由 N2/N3/N4 验收。

后续 Agent checkpoint/response/routed_model、工具结果、downloads/replay/approver/CHAT、外部 source/history refs 未迁移；本段原 raw API 对 protected run 拒绝。新 scope 历史不承诺任意被业务表覆盖的旧 payload 可读。既有生产 IAM 启动闭锁和 BLOCKED_IAM_SCOPE_ACCEPTANCE 保持；只有 T01 仍是整体完成项。

无 POM/业务JAR/driver/框架/globalstate/receipt 修改、没有主库数据、没有 .env parser 复制/凭据输出、没有生产启用、部署、提交或索引更新。作者完成冻结/保护与机器交接后归还 source/target；之后仅 root 双审决定下一段，不自行推进整体 Task5。

作者交接状态：DONE_WITH_CONCERNS。69例最终实测与362源/1740目标冻结完成，所有 source/target 独占写权现归还 root，作者不再修改 source/target 或运行 Maven。保留的关注点是明确的 N2/N3/N4 接线边界、VA-only/ambient 拒绝的有意兼容变化，以及上述早期证据局限；不是未披露失败。机器索引：iam-stage-5-n1-flow-material-status/handoff.json。
