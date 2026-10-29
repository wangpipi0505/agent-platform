# EX-08 第四批增量：EX-05双仓闭环与EX-15a后续范围

2026-10-29，整理者`docs_reader_check`。本次将EX-05加入已完成卡索引，累计10整卡及EX-27的SSE/SDK/outbox三个已闭环子步。39卡为**10完成/3进行中（EX-15a、EX-27、EX-08）/26待启动**；48能力仍9完成/21进行中/12待验收/5待启动/1暂缓，45原子项不因契约同步批量升级。EX-08自身的48/45完整七维覆盖、历史完成项复核及整卡独审仍未齐，不能关闭。

本次只读两仓Git对象、已存实质/索引审阅与实际回归JSON，更新获准三主入口及EX-08文档/精确快照。没有运行产品、Java/Maven、Node、PG/HTTP、前端或服务启停，没有读取凭据、stage/commit，也未使用Superpower。EX-15a运行由root独占窗口完成，本记录只读取回执，不把它记为文档作者执行。

## EX-05七维闭环

| 范围 | 实现 | 定向/原场景 | 回归 | 契约/浏览器 | 联合/未覆盖 | 提交与回滚 | 独立评审 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AP本体消费映射与原总门禁 | 仅`contracts/skill-invocation.md`追加§16及`scripts/verify-contracts.mjs`精确接线；原63,602字节/freeze及其它契约不变 | 旧契约缺增补实红，修后总门禁通过；20项（1有效/19拒绝）含正文/边界/七键/语义及EX-09原三负例 | 本卡是文档/CLI契约验证，没有Java/PG/前端产品改动；原G-9告警保留，不冒称后端全绿 | 正式HTTP/SYNC/API_KEY、来源/分页/风险及SecretVault引用与源码一致；无新增浏览器页面，N/A | 不证明真实OR导入/J4、模型或EX-06/07；OFFSET不表示自动翻页/强制总行限额 | AP `824294a451f2a7a8a5986183897358343a56525d`，parent `67d1d41dc599a99ecad4654a4736446e076f84f7`，tree `b7df5b61cd2016decc48fdbbc45cb9e9b43c9d49`，14文件恰2实现＋12证据；只反向这两实现的准确差异，保留证据，不回滚EX-15a | [实质审阅](../2026-10-29-EX-05/final-review.md) `algorithm_interrupt_readonly`；[精确索引审](../2026-10-29-EX-05/index-review.md) `artifact_boundary_review`，加入其报告后精确14文件 |
| OR七路径十操作、生成及漂移门禁 | 仅`contracts/openapi.json`、`web/packages/api-client/src/generated.ts`、`deploy/Verify-ApiContract.mjs`；原109路径/126模型/142操作不变 | 旧契约十操作missing实红；同一BF829F消费TS在旧8AF1生成物上两条TS2741实红，仅修两个新字段default表示后同夹具绿；不手改generated/全局设置 | 最终154门禁、双生成稳定、消费TS/typecheck/build native0；完整33文件269P无skip；首轮268P1原5000ms超时和定向诊断原件保留；format同41原文件native1/新增0，不能称所有门全绿 | 最终B0C549/6D899F/157542候选；仅新增7路径10操作15模型/2认证方案；删新增后整份契约结构等基点；既有页面无改，前端全组/类型/构建已有证据 | 本体服务key、人Bearer、消费ontopa用途区分；本卡不替代真实授权/发布/J1～J9或comments未来状态门 | OR `f19299681a2a54d60cace3672a56cf4bf8fef7e6`，parent `da3345a123752c3fda06c5a7a8a1dc9d614b8918`，tree `f09e1e8b75421657a3243dfb3b5af6a4af3205c0`，62文件恰3实现＋59证据；契约/生成物/gate同批反向，原证据保留 | [实质审阅](../../../../ontorepo/docs/evidence/2026-10-29-EX-05/review.md) `algorithm_interrupt_readonly`；[精确索引审](../../../../ontorepo/docs/evidence/2026-10-29-EX-05/index-review.md) `docs_reader_check`。原61路径/OID及raw/index字节匹配candidate，含属性文件；root只加报告成62并核原61不变后提交 |

上表提交SHA/parent/tree和14/62文件数由本次Git只读核对；未把README里当时“候选/待提交”倒填为历史已经提交。OR最终三实现真实字节摘要在实质/索引审中相同：契约`b0c549cba1567c3dc0d957d975aa0696081261bdcf02caef9f48a69f22e463fe`，生成`6d899fac3e4b39d011ad35fbc391b96e43aa6db490700d23d1d813521fe87995`，gate`1575422eac1e98be531237ea3f9a764c435bdac1dae51fa9ca1d8d7da96bf7c0`。本次不重做已独审产品结论，也不把读取回执当自己重跑。

EX-05关闭解除EX-06及EX-24相应硬依赖。EX-06即使已存在静态准备文件也仍待启动，本检查点不登记真实资源已创建、脚本已验或改变其状态；下一阶段由root另行冻结。EX-07/主项余量照各自关闭标准推进。

## EX-15a实际进度与后续准确边界

- 已独审URL/资源11项、上传长度16项与真实PG/MinIO 64MiB上传/下载/完整同步HTTP出站保留；fresh-store为同JVM组件重建，不是服务进程重启。旧PLAT-09/T09.1“V318未做/64MB全内存”已在主入口标为历史，当前V306有持久链，本轮无新迁移。
- 只读实际[Full回执](../2026-10-29-EX-15a/collector/runs/full-skill-20261029-ex15a-01/summary.json)：71XML/698=582P6F106E4旧S，原689＋9齐；missing/unexpected/statusChanges/sourceDrift=0，方法/原116原因门通过，SDK5P/4outcome/0诊断。native0使用ignore采集，collector0、allGreen=false；不写698P。
- 随后串行[Adjacent回执](../2026-10-29-EX-15a/collector/runs/adjacent-20261029-ex15a-01/summary.json)：5XML/32P、native0/noignore/collector0/0skip、source/context stable。两轮结果不相加冒充全模块全绿，仍需网关/正式重启及最终整卡独审提交。
- 已先在卡片登记生产`platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java`仅proxy请求限额选择/严格MediaType解析，及新`platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java`。精确POST `/admin/artifacts`＋合法精确multipart/form-data（含合法boundary）为65MiB总wire，其它2MiB，actual copyBounded含chunked；下游64MiB和原auth/error不变。定向红绿可直接真实Controller＋流式Servlet输入＋实际loopback上游，显式已认证上下文不冒称入口验签/完整FilterChain；真实入站层另验。
- 网关完整回归以EX-27 `safety-module-20261028-233458-012`原27类288=273P7F8E、`reuseForks=false`及15原非通过原因为门，新增测试另计，新失败/错误/skip/缺失0。定向/模块结果尚未由本次登记产生。
- 已登记collector三文件及网关`gateway/test-candidate.md`/`gateway/run-selected.ps1`；live静态候选准入`live/run-live.ps1`、`live/ArtifactLiveProbe.java`、`live/README.md`，不把脚本存在写成执行。正式只启gateway＋skill两JVM，AP25432先核前置不存在并新建本轮唯一空DB、登记name/owner，沿EX-01隔离以免启动scheduler清共享库；经18080真验签上传/下载64MiB核len/SHA，保持gateway并真正停止/重启skill核新PID/JAR后，同artifact再核len/SHA。只对本次artifactId＋tenant＋principal＋run＋完整key到期清理，不cleanTenant。

## 三主入口精确快照与检查点边界

三主入口升v2.15/v1.15/v1.15。[精确快照清单](main-entry-snapshot-20261029-ex05/manifest.json)保存原绝对路径、版本、字节数/SHA及原样副本；这是仓外文档的逐字节证据，不是新的执行入口，内部相对链接归原位置。AP/OR实现提交不包含仓外主文件，不能把本检查点误写成产品代码提交。

本检查点只纳入EX-05整卡关闭及EX-15a已到达结果/后续范围；保留原主入口EX-15a两个子步、EX-04/SDK/outbox等历史过程及旧快照。EX-08增量文档的独立复核/精确提交由root另行安排，作者自检不是本检查点独审。回滚仅反向本批文档准确差异，不撤销上述产品提交、原失败证据或本轮独立资源；本轮没有push/tag/外部部署。
