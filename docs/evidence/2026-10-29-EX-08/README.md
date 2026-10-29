# EX-08 首批逐卡证据索引

**当前增量（2026-10-29）**：[EX-15a闭环与后续真实阶段](incremental-20261029-ex15a.md)已纳第11张整卡，代码提交1cc45338；39卡11完成/4进行中（EX-06、EX-15b、EX-27、EX-08）/24待启动。PLAT-09/T09.1完成后48能力为10完成/20进行中/12待验收/5待启动/1暂缓。EX-06仅Ontology与模型协议已实跑、EX-15b仅路径两例红绿；EX-08自身仍进行中。

**前次增量（历史）**：[EX-05双仓闭环与EX-15a后续范围](incremental-20261029-ex05.md)按当时10整卡、EX-15a尚待后续回归保存，不替代当前状态。

**前次增量（历史）**：[EX-04收口与352源上下文](incremental-20261029-ex04.md)按当时9完成/2进行中/28待启动保留，不替代当前主台账。

**前次增量（历史）**：[outbox、SDK与EX-03收口](incremental-20261029-sdk-outbox-ex03.md)新增三个独立提交及149源双层校核；累计8整卡＋EX-27三子步，EX-27和EX-08整卡仍进行中。下文是83726d50首批检查点的历史快照，原7整卡/SDK未关闭等措辞只描述当时，不覆盖本次增量和主台账。

2026-10-29，整理者 `/root/ex08_incremental_index`。本文是 EX-08 的首批汇总，纳入 **7 张已完成 EX 卡及 EX-27 已完成的 SSE 子步**；SSE 子步不是第 8 张完成卡。状态、执行顺序及最终关闭记录仍以[统一台账 §14](../../../../../文档/优化方案/优化台账-统一-20261028.md)为准，实施范围见[执行卡片](../../../../../文档/优化方案/执行卡片-统一补差-20261028.md)，真实联合路径见[验收手册](../../../../../文档/优化方案/联合验收与回归执行手册-20261028.md)。

本次读到三主入口版本为 v2.9 / v1.9 / v1.9；核对时 AP HEAD 为 `ae2d1860071c9e49d07e80ecace331e41f898c9d`，OR HEAD 为 `da3345a123752c3fda06c5a7a8a1dc9d614b8918`。仅只读文档、既有审阅回执和本仓 Git 提交对象，核实下列 10 个提交的父链、文件总数和非证据文件范围；没有重算全部 XML、重做已签审计、运行仓库验证脚本或产品测试，没有读取真实环境文件/凭据，没有操作 index、提交或修改主入口。未使用 Superpower；唯一新增文件是本 README。

以下七个验收维度分别保留。原 README/review 中的“候选、待提交”等措辞是当时阶段记录；当前提交身份已在 Git 对象和主台账交叉核对，不能把旧阶段措辞误读成当前尚未提交，也不能把后续提交倒填为当时已经存在。本文的作者核对不是 EX-08 整卡的独立终审。

## 首批七维映射

`P/F/E/S` 分别表示通过/失败/错误/跳过；数字是对应报告或命令口径，不互换成实际完成的方法体数。契约与浏览器、真实联合分列，局部通过不外推能力完成。

| EX / 当前有限范围 | 实现范围已齐 | 定向 / 原场景 | 模块回归 | 契约与浏览器 | 联合依赖 / 未覆盖 | 提交与回滚 | 独立评审 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| EX-00：基线采集完成 | 两仓仅证据/采集归因，无产品修改；[AP README](../2026-10-28-EX-00/README.md)、[OR README](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/README.md) | 纯采集，修复红→绿 N/A；22 项汇总器合成校验与采集/归因有原件，不制造产品绿 | 原主＋网关补采 AP 2579=2206P/66F/303E/4S，另有 1 坏类未报告；OR 496=495P/1F，6 无源模块 N/A。新 app/engine 1083 另轮保留，不与原轮相加；比较起点见[comparison-baseline](../2026-10-28-EX-00/comparison-baseline.json) | 无契约变更；AP 前端 295P/构建通过。OR 原 2 超时与 format 41 文件红保留，同 5000ms 单 worker 完整 269P 是独立观察，不算产品修复 | 只关闭采集与归因，不放行真实 J 组、IAM/模型/worker 或原基线红 | AP `20333f35bc9f97dc11df296c04c8028e27fd42c8`（102 证据文件）；OR `097165072eaa4123f40ecb8f4487490dec830771`（83 证据文件）。无产品回滚；保留证据/测试资源，不擅删库或清理未知数据 | `/root/ex00_collector_review`；[AP review](../2026-10-28-EX-00/review.md)、[OR review](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/review.md)接受基线有限范围，不核准产品全绿 |
| EX-28：61 项盘点及正式回填完成 | 61 行静态分类、旧三文档 v1.2 回填；[README](../../../../ontorepo/docs/evidence/2026-10-28-EX-28/README.md)、[文档指纹/补丁](../../../../ontorepo/docs/evidence/2026-10-28-EX-28/document-manifest.json) | 纯盘点红→绿 N/A；61 唯一 ID、162 个既有路径及原关闭标准保留的静态检查；产品/浏览器运行 0 | 无产品/测试变更，模块重跑 N/A；仅引用 EX-00 原范围，不能由静态分类消除原超时/格式红 | 三份仓外文档和准确补丁可追溯；61 项为 16 已实现待复验 / 42 进行中 / 3 待核实，不是 61 项验收通过 | UX-01～05 等原场景、真实 PG/权限/浏览器及原 61 ID 的关闭仍待；不重做已交 R/UIF，也不新增既有 space_id 列 | OR `70b278ff28abbe60dca9c3b78880e785d3cf33cd`（9 证据文件）；三份仓外文档不在该 Git 提交内，前后指纹/备份/零上下文补丁在证据中。回滚先按[document-diff.patch](../../../../ontorepo/docs/evidence/2026-10-28-EX-28/document-diff.patch)反向检查，仅恢复三文档，保留证据 | `/root/ex00_collector_review`；[review](../../../../ontorepo/docs/evidence/2026-10-28-EX-28/review.md)接受静态盘点与承接，未关闭 61 原 ID |
| EX-02：发布 pin 空间字段完成 | `ReleasePins` 请求补 spaceId，2 个既有测试类；[AP README](../2026-10-28-EX-02/README.md)、[OR README](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/README.md) | 19 项 17P/2F→19P；OR 2 类 11P。真实 ReleasePins 客户端直调 OR：原 400→PINNED true→同 ID false，跨空间 403；本轮 own 绑定 1、foreign 0 | 完整 app 23 XML /178=160P/18 原 E，原 177 身份在、新非通过 0；报告/命令链接在本卡 README | 只修已有请求协议缝；未改 OR 产品/API；无需新浏览器交付 | AppService 测试预置合格报告、OR 为桩；真实 OR 探针直接调 ReleasePins。这两层都不是完整真实发布/J9，并发撤权原子边界仍待 EX-07/J7 | AP `0cc967278007343bd5ab09faf9953d86cd573bf8`（32 文件，3 Java＋证据）；OR `da3345a123752c3fda06c5a7a8a1dc9d614b8918`（13 证据文件）。仅反向 AP 该 3 Java 差异；保留证据/本轮绑定，OR 无产品回滚 | `/root/ex02_independent_review`；[AP review](../2026-10-28-EX-02/review.md)、[OR review](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/review.md)接受冻结两仓候选 |
| EX-36：Spring 构造装配补修完成 | 既有 `OntologyContextPacks` 构造加明确注入标记及对应测试，仅 2 Java；[README](../2026-10-28-EX-36/README.md) | 真实 Spring 原 3P/1E（精确 NoSuchMethodException）→4P；保留原 3 个工厂方法，并到达属性/Vault/HTTP 断言 | [完整 engine 比较](../2026-10-28-EX-36/runs/after-module-20261028-230322-842/method-summary/comparison.json)：93 XML /907=792P/35F/80E；905 具名＋2 原类级事件，14 源 @Test 未执行仍保留；原 906/115 非通过身份与原因不变，新差异 0，完整执行/全绿仍 false | 无 API/迁移/取包授权及缓存规则改变；engine-only package exit0 是 0 测试的打包，不计定向绿；无新浏览器范围 | [正式启动补验](../2026-10-28-EX-36/formal-engine-startup.json)的 PID 健康/JMX，与从新 JAR 提取类路径启动的独立 Spring 探针读 OR 分列；不是 PID 内真实 run/J9。PLAT-13/T13.1 回待验收，EX-07 仍待 | AP `41b62e3f324ec8ef61630a9628d4664f2daae8c9`（48 文件，2 Java＋证据）。只反向该 2 Java 差异，保留新 JAR/证据及独立资源；不整仓 reset | `/root/ex00_collector_review`；[review](../2026-10-28-EX-36/review.md)含类级 FAIL/ERROR 与完整性分类的最终 delta 复核 |
| EX-01：服务配置隔离及开关完成 | 5 个产品/脚本文件，服务级配置隔离、PS5.1 兼容、真实启动/有效开关；[README](../2026-10-28-EX-01/README.md)、[冻结清单](../2026-10-28-EX-01/index-candidate.json) | PS 46P、Java 定向 74P；真实 legacy/服务配置隔离、PROCESS 空白名单 exit1；实际 app/skill/gateway 与 EX-36 后 engine 配置/开关证据分别保存 | skill 664、engine 原 906 的基线非通过保留；网关使用下述同一共享 288 记录门，新非通过 0。engine 两类级事件和 14 未执行不得写成方法全执行；旧 partial/wholeCardPassed=false 原件保留 | 无业务 API/浏览器变更；真实受限文件与凭据不入库，公开 .env.example 不是实际秘密。配置值/JMX与行为锚点分列 | engine PID50360/6276 的健康/配置，与独立新 JAR 类路径 Spring 客户端读取 pin/context-pack 分列；不拼作同轮全绿或 PID 内 J9，不关闭 PLAT-18/EX-07 | AP `0bd90eab1980bddeab14723447f8ac205e823728`（68 文件，5 产品/脚本＋证据）。仅反向本卡 5 文件差异，保留证据/独立资源；凭据撤销另记，不撤他人共享 key | `/root/ex02_independent_review`；[review](../2026-10-28-EX-01/review.md)实质及精确树终评均接受 |
| EX-35：代理锁反序/关闭死锁完成 | 仅 `PublishedRunProxy` 与 `GatewayPublishedRunProxyTest`；[README](../2026-10-28-EX-35/README.md) | 原实际锁环及 late-active 空 200、已领成功块状态竞态均有红→绿；完整 Proxy 33P（原 30＋新 3） | [共享 run](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/run.json) / [comparison](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/comparison.json)：27 XML /288=273P/7F/8E，真实 native1，新增差异 0，allTestsPassed=false；15 原非通过原因保留。原 frozen SSE 新 F/gate=false 另留 | 真实 Servlet/Tomcat/HTTP 竞争测试；无新增浏览器/API/依赖；预算、授权及错误规则不放宽 | 依赖 EX-27 SSE 子步先行提交；共享结果不是仅这 2 Java 的孤立效果。PLAT-18 其它残项、EX-27/Y-4 整卡不关闭 | AP `28aa99ea12cb96bbbf781859c017acebd30647a3`（110 文件，2 Java＋证据）；父为下行 SSE 提交。只反向本卡 2 Java，保留证据，不回滚父 SSE 或其它卡 | `/root/ex35_independent_review`；[review](../2026-10-28-EX-35/review.md)含精确树元数据最终接受 |
| EX-09：P-2 声明门禁接线完成 | 仅 2 个既有 mjs 接线/required 路径定位，无新校验器/依赖；[README](../2026-10-29-EX-09/README.md)、[冻结清单](../2026-10-29-EX-09/index-candidate.json) | [before](../2026-10-29-EX-09/before/commands.json)＋[after](../2026-10-29-EX-09/after/commands.json)共 18 命令/36 原输出：原独立 CLI 能拒三坏例，总门禁原漏过；修后双默认 0、两入口三坏例均 1 且字段精确，2 语法检查 0 | 本卡纯脚本回归即双入口默认/三负例/语法检查；不是 Java 方法数，未跑数据库/后端/前端/服务 | schema、freeze、设计快照、默认声明及 4 契约共 8 文件 before=after=current=HEAD；既有路由告警不改，无浏览器范围 | 只关闭 P-2；未交付运行期 READY、真实身份映射、能力包装载或 EX-10～14，PLAT-22/T22D 仍进行中 | AP `ae2d1860071c9e49d07e80ecace331e41f898c9d`（50 文件，2 mjs＋证据）。只反向两脚本差异，保留证据，不改冻结 schema/正文/默认声明 | `/root/ex00_collector_review`；[review](../2026-10-29-EX-09/review.md)接受冻结 48 项＋后附清单/review 的精确候选 |
| EX-27 SSE 子步：子步完成，整卡进行中 | 仅 `PortalRunEventsSseLifecycleTest`；修正实际回收正例前提并新增默认 off-lock 安全向量；[README](../2026-10-28-EX-27/README.md) / [实际阶段结果](../2026-10-28-EX-27/current-stage-results.md) | [447 受控交错红](../2026-10-28-EX-27/diagnostic-result.md)保留；准确方法 2P→默认完整 38P/49 child exit0。原 5 秒和安全断言保留；编译错误 0 XML 不算目标红 | 使用与 EX-01/35 同一共享 27 类 288 记录及[原因类型比较](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/nonpass-cause-comparison.json)，native1/差异门 true/全绿 false；不重复累计运行数 | 没改 SSE/HTTP/Budget 产品。新增恶劣向量没有原 SSE processor 复用，真实复用由同次完整类两个原向量另证；[安全事实](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/safety-facts-summary.json)分列，不拿普通 keepalive 冒 SSE 复用 | 仅关闭 SSE 子步；SDK GET_BODY 原 Full 新 F及其它 Y-4 未关闭。旧 SSE 原 F/单独 P、受控红、静态错/编译失败分别保留，不认定历史唯一同因 | AP `83cc21d0a490a50ae6e5ac3dbad57bd53e0381ef`（267 文件，1 Test＋子步证据）。只反向该 Test 差异，保留全部证据，不回滚 EX-35 产品 | `/root/ex35_independent_review`；[review-sse-substep](../2026-10-28-EX-27/review-sse-substep.md)包含 9 条证据属性精确锚定后的最终接受 |

共享网关运行只有 `safety-module-20261028-233458-012` 这一次实际记录；上表三处引用不能相加为三次全量。其 [stage-gate](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/stage-gate.json) 与原失败阶段分开。EX-36 的 2 个类级事件已包含在 35F/80E 内，不再额外加总；分别为 ModelInputMaterialHttpTest 的空 method FAIL（11 源测试未执行）及 RunEventReaderTimeoutBudgetPostgresTest 的空 method ERROR（3 源测试未执行）。

## 提交对象核对与回滚范围

上表 10 个完整 SHA 均由本次只读 `git rev-parse` / `git diff-tree --no-commit-id --name-only -r <SHA>` 核对，不仅转述台账。AP 父链为 `ebbfd62 → 20333f3 → 0cc9672 → 41b62e3 → 0bd90ea → 83cc21d → 28aa99e → ae2d186`；OR 为 `16d9698 → 0971650 → 70b278f → da3345a`。短 SHA 仅用于本段导航，精确身份以各行完整 SHA 为准。

非证据文件核对结果：EX-00/AP102、OR83、EX-28/OR9、EX-02/OR13 均无产品文件；EX-02/AP32 中恰为 ReleasePins / ReleasePinsTest / PublishPinTest 三 Java；EX-36/AP48 中恰为 OntologyContextPacks 及其 Test 两 Java；EX-01/AP68 中恰为 `.env.example`、两个 application.yml、start-backend.ps1、Test-ServiceEnvironment.ps1 五文件；EX-27 SSE/AP267 中恰为一个 LifecycleTest；EX-35/AP110 中恰为 Proxy 产品/测试两 Java；EX-09/AP50 中恰为两个门禁 mjs。证据目录内的精确 `.gitattributes` 仅保留原始证据字节，不是生产/Java 格式豁免。

回滚只针对上述各提交相对父树的准确差异，先核后续协作者编辑；保留证据/独立资源，不整仓 reset、不撤销其他卡。EX-28 的仓外文档回滚单独按其指纹/补丁处理，不能把 OR 的 9 文件证据提交当作已经包含三份文档。上述是回滚对象核对，**本轮未实际执行回滚**。

## 能力与原子项关联边界

只引用统一台账中已有明确关系，不据文件名猜测原子项：EX-02 对应 PLAT-18/T18.0 的 pin 请求缝；EX-01/35 及 SSE 子步只补 PLAT-18 的相应组件；EX-36 对应 PLAT-13/T13.1 的构造装配补修，能力/子项回到待验收仍需 EX-07；EX-09 对应 PLAT-22/T22D 的 P-2 门禁部分。EX-00 是横切基线，EX-28 是 61 原 ID 盘点，均不新增任何能力完成数。没有明确列出的原子项留待后续逐项映射，不自动归入本批。

本索引尚未形成 48 个能力项、45 个原子项及全部 39 张 EX 的完整七维覆盖；其余 **32 张未在本批作为完成整卡索引的 EX（含 EX-27 整卡、EX-03、EX-08 自身）** 仍须按主台账与原标准推进。不能据本批 7 张卡的独立审阅替代其余候选审查，不能由卡级/组件级通过升级主项状态。R14 仍需 EX-18，UIF-O3 等残项及真实联合/外部输入仍各自保留。

尤其 EX-03 的原完整 skill 新增 SDK GET_BODY FAIL仍未关闭；后续原类或模块诊断 PASS只按各自实际 RunId记录，不豁免原失败，不在本文宣布 EX-03/SDK子步通过。算法诊断状态可能继续推进，读者应查主台账 §14 的最新记录，本文不复制运行中的临时结论。

## EX-08 后续增量

按每张卡完成顺序，把其候选范围、准确方法/类级差异、契约/浏览器/联合适用性、独立评审及实际提交/回滚链接补入；随后补齐 48/45 的逐项实现与残项覆盖，复核历史已完成项。本文只有首批已完范围的索引及 Git 对象核对，没有新的产品验收，没有 EX-08 整卡独立终审或提交，**EX-08 不能标完成**。正式“进行中”与进度计数由主执行者在统一台账回填，本子任务不修改主入口。
