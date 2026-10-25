# N2 独立评审修复 I1：正向限定 FLOW

唯一工程/每次 shell workdir：G:/207/项目/算法重组工具/代码/agent-platform。用户授权按计划持续实现与隔离端点验证。不读取、关联、复用或影响相邻旧代码/配置/数据/进程；原 v1.2/四契约/固定版本/七模块分层与业务 HTTP 边界、生产 IAM 闭锁不变，无 commit/package/deploy/publish/push。

## 评审与修复定义

先读本 brief，再读 docs/evidence/review-iam-stage-5-n2.md。该独立评审 Spec Compliance / Task quality 均 Needs fixes，只有一个 Important I1，无 Critical。原 N2 brief 仍是约束，不回改。I1 要求：TraceStore.pureFlow 当前仅排除 CHAT，必须正向要求 snapshot.type=FLOW；缺失/JSON null/未知/其它非FLOW不得经原 owned worker 开始或执行。

仅修改既有生产文件 engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java 中这个具体 gate，保持其余事务/锁/父范围/期限/异常语义。必要测试限既有 OwnedFlowSchedulerPostgresTest.java 与 RunAuthorityFixture.java，若确需已有 OwnedFlowMaterialsPostgresTest 的最小测试辅助改动须有具体理由；不新增生产文件/迁移/框架/common/POM/其它服务/API，不扩大此修复为 N3。

M1 是最终 raw 中11条预期负例 scheduler WARN；root 已全文读并将登记维护项。保持生产 WARN 行为，不能为了绿屏整体禁日志。本次只解决 I1；新负例若局部采集预期日志可以使用现有依赖，但不为日志美化扩展旧套件或改生产日志。最终如仍有噪声，精确说明原有/新增来源与数量。

## 实际基线、证据和独占

当前原 N2 source364，manifest docs/evidence/iam-stage-5-n2-owned-flow/source-after.json SHA e0f4cb1573c63c67d8360347a1f367f85b25ddd6976ddf4b21d1be919d0861a1；target1761，manifest SHA8de40cd5a3f9a9788ecaa587b42b5e9866875cd27625acbe5de41e4dd3a2451d。root 已核 before362/1740物理原件，364 live/frozen/replay/final源码、1761live/frozen目标、完整清单、12轮raw/Running/XML/source、4421迁移文件SHA；旧最终67/0F/0E/0skip，7实际类、58incidental XML不计。

独立 root 保护核75091原静态+28脚本+8索引，共5332308930B，0未验证/漂移，原设计/正式brief/八索引保持。见 .local/iam-n2-root-fast-check.json、iam-n2-root-protected-check.json、iam-n2-root-audit-addendum.json。目标原统计46是22新增+24修改，另1旧StaleCheck.class删除，合计47路径；具体删除机制/时点没有逐class日志，不能称Maven清除已证。

原作者已归还 source/target；本 brief dispatch 后仅你重新获得本次修复 source/target 独占。reviewer readonly等待，root只并发精确写.local/current-work-state.json。其它docs/source/target不得自行变化，保护中包括 root 新 N3 草案 .local/iam-n3-prep/root-outline.md。原 N2各历史轮、before/frozen/replay/target、handoff/report最初版本与初审原件均要保留。

## 回归与一次最终受影响集

先构造正常签名/原binding/materials均有效的真实 N1 createPublished/createNode受理fixture，在受理之前传入缺失/null/未知值（至少UNKNOWN）和另一非FLOW（CHAT）snapshot.type；图仍正常 START→END、metadata/root/数字版本/节点定义真实匹配。不能通过受理后直接改snapshot导致scope摘要不符来冒充类型gate回归。经原reserve/persist/created/owned RunService/FlowDriver；断言 RUN_STARTED/attempt/execute/output 增量0，run仍未开始；授权后读快照进行 gate 所需payload不要冒称0。保留正常FLOW完成正例。

在修生产之前以现受控runner运行新有限负例，保留可编译真实行为红；不得先改再回退造红。四个有限输入可参数化或单测试穷尽，按实际Running/XML计，不预填数量。明确每种都在绿色中检查，不因首个红断言提前结束就声称红跑完全部。

修复后一轮最终集合为 OwnedFlowSchedulerPostgresTest 与 OwnedFlowMaterialsPostgresTest（pureFlow直接使用的实际N2组件），同时覆盖正常 FLOW、四类拒绝及既有事务材料边界。RunAuthority/GraphRules/N1三个公共writer/LocalCurrent代码未改时不重复旧整集67/common/七reactor/D3b/耗尽probe；若真实调用关系证明还需其它覆盖，给root精确代码原因后调整。只用原.tools/run-iam-module-tests.mjs、Java21/offline Maven，stage iam-stage-5-n2-fix-*，tests逗号/class#method不用+。每轮raw/actualRunning/XML/当次source原样冻结，失败/观测/工具问题区分。同码绿无重跑。

固定仅复用已授权隔离 agent_platform_iam_owned_flow_v12；不新建库、不重跑一次性history预检，不动主库/app/policy/skill/model等。无运行六主服务/外部实集群动作。资源有界关闭。

## 修复 before/保护和机器交接

精确自有目录 .local/iam-n2-owned-flow-fix/、docs/evidence/iam-stage-5-n2-owned-flow-fix/；每轮原 runner log/fresh-secrets在新的root-decisions文件首次执行前逐项预登记。不要复制错stem，先核resolved授权目录与stage不存在。原N2误目录不再动，旧已被拒绝的replay清理也不动。

先不可覆盖捕获当前364源/1761target manifest与物理副本，核等于原N2 final；原完整report和初审也在before保存实际字节/SHA。已有75091+28+8原保护清单可复用已获root实际核验的基线，不必重新读全部再做before；明确引用原before及root复核时点，补采原N2新增静态证据/本轮root审计/初审/N3草案等尚未入旧清单的真实文件，不能宽泛排除历史N2目录。原运行目录/精确postgres.log/精确rootstate继续原排除，不排整个.local；不采SDK/Maven整树。最终保护after一次逐旧原件读核，新增补采项也核；源/target按允许本轮变化另列。未授权漂移先保存失败向root报告，不能改before遮差异。

按skill修复报告追加到同一 docs/evidence/iam-stage-5-n2-owned-flow-report.md（这是唯一旧文档授权追加例外），必须保持原15189byte前缀/SHA1bed43ce19c658e8a0919dbcb986441ef67cc3a2cddea1a1caaec1110cdf38d0逐字不动，原全文副本存本轮before供旧handoff历史SHA引用；追加区明确旧67是旧代码，本次实际最终数/命令/输出/现source才是当前代码证据，不拼总数。旧handoff/初审/root审计原件不改；fix另有自己的handoff及完整before-after、物理freeze、真实patch/apply/replay与保护报告。目标计数含增加/修改/删除三类，JAR变化0。

交接必须给实际覆盖测试名、命令、输出（含负例红/最终绿/噪声），不是一句测试通过。原report追加、source/target/protection/raw/XML/source、patch逐字重放与fix handoff全部完成后，停止Maven并明确归还source/target；root核后交原reviewer复审（原review只可由该reviewer追加保持前缀）。仅回状态/真实测试摘要/报告路径/关注点。不能自行更新共享执行/维护/来源索引或宣布N2/Task5已验收。
