# 开发进展

基线：整合版详设v1.2，SHA256 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。仅在`代码/agent-platform`独立实现；独立Git、源码、数据、凭据、构建缓存、端口和进程，不读取或关联相邻工程。时间仅作证据追溯，不安排工期。

## 已有可验证的运行路径

- Java21 / Boot3.5.0 / SpringAI1.0.0七模块；Vue3.5 / TS / ElementPlus / X6六包与admin、WebApp、assistant三种构建产物。原文/快照hash和服务依赖边界检查通过，原契约未修改。
- PG权威run/node/attempt/tool_call/event、租约代次、原子提交、确认快照、调用身份与事实outbox；Agent/Flow驱动、受限表达式、分支汇合、调试挂起和幂等放行。HTTP写技能首次DRAFT通过受控DEBUG及真实确认后上架。
- 精确冻结模型binding及候选依赖；ADMIN管理、配置行锁/探测CAS；完整Schema及实际SDK wire计量、流式/FC、Redis应用/用户日额和供应商TPM/RPM、持久熔断拒绝。V305覆盖发送前配额预留与NOSEND/SENT恢复；缺usage保留未知，迟到结算不重复扣退。
- 上下文成对裁剪、独立SUMMARY调用及最终材料先存后发；CHAT固定版本和主体、停用拒绝新轮。历史权限收窄后的新轮及恢复加载现在重新核查历史scope，H1已修复并独立关闭；旧requestId保持幂等读取。
- 真实MinIO有界大结果、原始Schema验证、opaque受控下载与过期清理。FIXED/LAST_REAL仅认引擎权威，技能独立查源事实和当前有效参数；复现附件复制为当前run新归属。5MiB复现下载SHA一致且零业务外调增量。
- 候选真实执行/评测→六组预检→原子发布→正式运行。实际Chrome验证结构化配置、X6保存、FULL/STEP、刷新、断点、内联确认、精确版本输入、CHAT恢复及附件下载。
- OpenAPI JSON/YAML安全子集预览→结构化补配置→生成DRAFT→真实受控连接已落地。复杂/不支持操作显示原因，不生成假可执行契约；PATCH及JSON字符串实际HTTP序列化已修复。
- 短票据与直接ApiKey实际运行；管理403、应用/来源绑定、吊销401已有验证。SDK开发票据浏览器交互已验证；真实宿主出票至浏览器全链仍未完成。

## 最近完整回归与增量证据

**2026-10-20 06:55:24完整后端单轮BUILD SUCCESS：common5 + gateway15 + app35 + engine365 + skill167 + model66 + monitor22 = 675项，0失败/错误/跳过。** 见`backend-input-proof-reviewed-all.md`及同名日志；235份后端src/POM在该轮前后摘要完全一致。此前366/313/251均保留为历史阶段。675之后G5调度与MCP继续施工，不将后续变更冒称已经纳入该轮。

| 范围 | 已核证据 | 状态与限制 |
| --- | --- | --- |
| CHAT历史范围H1 | chat-history-scope.md、implementation-coverage.md追加 | ChatSessionsTest4项红→绿且独立关闭；已纳入完整366回归并更新本工程开发运行进程 |
| 复现与技能当前scope I1/I2 | engine-review-i1-i2.md、skill-mock-authority.md、review-execution-followup.md | 复现逐调用预检、当前guardedArgs、returnSpec与新附件owner已测并关闭具体发现 |
| 模型发送前账务I3 | model-quota-nosend-i3.md、review-execution-followup.md | 54模型测试，实际Redis7复验；NOSEND退款/恢复、SENT未知保留，独立复核关闭 |
| OpenAPI及HTTP wire O1/O2 | skill-openapi-report.md、frontend-openapi-report.md、review-execution-followup.md | 技能70项全绿含PATCH及JSON字符串；实际浏览器创建草稿并连接；独立复核关闭 |
| 前端 | frontend-test-inputs-live.md、frontend-test-inputs-mobile-fix.md、review-frontend-test-inputs.md、review-frontend-stream.md、review-frontend-evaluation-async.md | 已审固定阶段83项全套/三构建；命名输入真实9检查、原受控23通过，FLOW390样式已独立关闭；实际55秒EOF两端恢复通过。后续异步评测返修阶段102项/三构建，两P2已独立关闭，真实G5异步联通仍待部署后验证 |
| 基础设施 | open-source-references.md、backup-restore.md | PG16.15、Redis7.2.16固定源码hash与独立MinIO真实运行；最新PG备份→新库51表（含Flyway）恢复，证明SHA/输入行/迁移版本已核，不等于完整灾备 |
| 部署候选 | deployment-report.md | 官方schema43源/44渲染对象、Collector原生检查与发布拒绝反例；04:41再次静态检查通过，无镜像/集群运行 |

原始日志在忽略的`.local/`与`target/`，长期结论保存在本目录。HTTP fixture不等于真实模型或业务服务。构建跳过测试、单个XML存在及下载工具均不计为完整测试通过。并行开发时同模块Maven应顺序运行，避免共享target覆盖证据。

## 正在推进

- G1后端局部已独立关闭：严格JSON/Schema与RETRY/TERMINATE/PASS_WITH_FLAG、逐次持久化/预算/恢复/复现；两个Schema边界P2红绿修复，engine111阶段全绿。真实协议fixture验证三分支及无效ref零调用，PASS正式应用WebApp/SDK提示与历史恢复已联通。
- G3统计后端与V501、前端消费者已实现；NO_SEND发送量与前端应用过滤/分页P2都独立关闭。真实网关JWT/跨租户/3并发重建和PG源表4=4已过；未关闭完整T26/T25组合及容量验收。
- G4局部后端已独立关闭：复制、服务端筛选/显式归档检索、日志与版本对照；复制并发源快照app34全绿，真实发布v1/v2已Chrome显示；迟到diff前端P2也独立关闭。完整模板/字典治理待补。
- G5在线stream：双实际JVM的Redis片段和PG事实1–9、游标7补8/9已实测；MVC30秒与RUNNING空快照均修复并独立关闭。实际WebApp/SDK首EOF55018/55017ms，各2流/2状态、run/turn各1，旧文保留、后续片段终态前可见，最终答案完整、seq1–6不重、localStorage零写。已纳入675阶段回归。公平调度/分池现按run-scheduler-implementation-plan.md正式施工，V215尚未验收；其余内存闸/容量未完成。
- G2 HTTP SUBMIT_QUERY/CALLBACK、不可变NOT_APPLIED/NOT_SENT证明、旧APPLIED代次CAS与proof票据P2均已独立关闭，skill167纳入675整轮。skill PID18484更新后实际证明srp:1生成/读取/重复摘要相同，业务提交1；portal+SERVICE403、其他主体404，仍UNKNOWN且retryBusinessAllowed=false；异步三场景和签名回调四场景重新真实执行通过。见skill-reconciliation-proof-live.md。后续引擎单次许可须先完成expectedAttempt传播并依赖G5，计划engine-not-applied-retry-plan.md；取消三态/UNKNOWN人工闭环仍待。
- T20/T22命名输入局部链路已闭环：后端保存/归属/CAS/提升幂等、Schema sole P2独立关闭；前端23项受控Chrome、真实9项页面链路通过：保存rev2、显式使用、debug成功、独立expected/safety提升、不可变选集、原候选真实ACCEPTED，candidateSha未变。FLOW390撑宽仅用DebugPanel scoped CSS修复，canvas244px/两节点可见，83项/三构建及独立只读390/1440复核通过；历史8文件清单保留。完整多用例库治理另按原任务验收。
- 服务端运行入口及会话P1已独立关闭：最初真实五反例新增run5/fixture模型5；现在RunService/START完整声明校验、会话新预约前固定Schema校验已实现，engine365全模块及独立39项通过。更新engine PID59704后真实五反例均400/0run/0模型，required NULL/false/0/空容器FLOW成功，两实际会话拒绝后0turn、修正原ID/新ID正常一次模型且重放原run，见runtime-input-validation-live.md。原REQUEST_CHANGES及红证据保留。
- 评测状态兼容已独立关闭并部署：原WAITING_EXTERNAL误判REJECTED及PENDING遗漏，经9项红2fail→monitor全模块22、独立9通过。保持原报告/用例runId恢复，不重做外部业务，monitor PID44208已更新。见evaluation-pending-state.md、review-evaluation-pending-state.md。
- 统一设计→开源机制→代码→测试索引已新增：[design-traceability.md](../design-traceability.md)。覆盖审查的发现时状态保留，最新追加结论优先。
- MCP固定SDK0.10的真实SSE/JSON-RPC、列工具/能力协商与原始接收限额正在独立模块补齐；此前native调用代码及依赖存在不等于已经有协议验收，不将本项计入675已验阶段。

### 675之后的当前审查与返修

- G5原23文件/engine396/gateway16冻结历史保留。独立review-run-scheduler.md确认会话准入/停机P1、PG行锁阻塞其他dispatch/node/tool续租P2，以及大dispatch租约下心跳间隔超过固定节点90秒租约P2。返修新增9项先得到7个行为断言红，后续92定向及完整engine412/412（07:49:48）、gateway16/16分别通过，见run-scheduler-review-fixes.md。root已核25/25候选摘要与完整日志/补丁SHA；六文件补丁在隔离副本实际应用一致。原审查者07:53:15亲跑75项及独立真实PG反例，三项全部关闭，APPROVE仅限本增量；已追加原报告，当前尚不部署。root两实际JVM脚本已准备但未执行；没有将repository双owner测试当作真实双JVM通过。
- MCP本地协议补齐期间发现并真实复现远端Schema外取/字面量误拒、queryOnly误调用tools/call；已做封闭factory、Schema位置遍历及保留UNKNOWN不重发的定向修复，另补无对账配置时MCP重复查询的真实PG审计。早期skill239与后续242终轮保留为历史，242终轮07:32:34完整模块绿、12文件前后摘要一致，root再核12/12及日志。独立审查75项通过后，确认引用展开内存P1及正则计算预算P2，审查随后被自动安全审核中断；无最终APPROVE，尚未部署。见review-mcp-transport-interim.md，作者仅继续防护性预算修复和安全测试，不重放耗尽堆探针。root真实协议脚本已对旧运行版得到mcp-tools404行为红，最终纵切等待修复和审核后更新进程。
- 异步评测前端两P2已独立关闭：权限变化/重挂载保留创建ACK未知提示，详情读取与创建互斥。修复后27文件102项、三构建、新增8受控Chrome及原16+23通过；独立亲跑原两反例、19项定向测试及8状态重放，APPROVE仅限本增量。见frontend-evaluation-review-fixes.md及review-frontend-evaluation-async.md。原REQUEST_CHANGES/10文件SHA及修复5文件清单保留，真实G5异步页面链路尚未执行；未知提示仅为SPA内防误重发，不等于整页刷新后的服务端幂等保证。

### 资源补丁与MCP页面的后续冻结

- MCP资源修复作者08:04:44完成skill270/270、零失败/错误/跳过，12份源配置前后SHA一致；root已核12/12及日志/清单摘要。见mcp-resource-budget.md。纯图预检、固定RE2/J1.8编译/实例工作预算、deadline和实际退出后释放许可已实现；原慢/耗尽探针未重放。首轮268中18MiB旧异步产物因过小公共字符闸失败，已恢复既有64Mi接收边界后再完整绿；疑似500映射经源码及短测试核对未成立，不记录为已修复缺陷。该270阶段没有独立APPROVE或部署，随后共享迁移另立增量，不能继续要求旧位置清单与迁移后的文件一致。
- MCP注册与所选草稿只读工具检查前端127项/三构建、13新增及历史3+13+23 Chrome通过；root已核7/7 after。review-frontend-mcp-registration.md独立APPROVE：亲跑25项SFC及新Chrome组合探针，准确绑定/guard、authConfigured权限、所选definition/状态、无正文路由、迟到响应/重复请求、1440/390均通过。原16保护文件不变。发现结果不自动保存或应用，草稿CAS另需后续实现。
- 共享防护按shared-schema-budget-brief.md进行：三纯组件迁admin-common后接引擎输入/输出，保持G5生产文件与FlowDriver；8项有限行为红→205项定向绿，PG零收费及完整模块仍在验证。原OutputPolicy曾支持的递归本地Schema本次明确收窄为拒绝，已保存原全文及SHA，并保留非循环anchor的有效/无效断言；这是子集限制，不声称原递归例已证明耗尽，也不声称原所有断言不变。
- root已准备真实MCP协议+所选草稿浏览器检查与两实际engine JVM新库脚本，只做语法检查，仍待后端共享防护安全复核、统一回归、制品和进程更新后执行。没有将受控路由页面或repository双owner检查冒充这些真实纵切。

这些是675之后的独立增量，不能将模块数字与旧整轮拼接为新整轮。后续仍需统一回归、固定源码和制品、真实隔离端点及两进程/浏览器纵切，再同步三份执行文档。

## 仍需实现或验收

- G2外部取消三态、NOT_APPLIED授权重试与UNKNOWN人工对账；G5调度公平/容量及其余内存硬闸、完整跨副本故障验收；G6生产IAM及caller/target服务权限、完整操作审计；完整输入集治理、字典/模板/管理页面和历史检索仍待补齐。CALLBACK已完成的本地协议纵切不替代真实可信relay验收；上述进行中子项不能冒称全部缺口已关。
- 本工程自己的PG/MinIO/Redis均已就绪；Redis已对齐详设7.x，独立专用WSL未启动或复用既有Docker发行版。外部环境缺失不阻碍继续编写G1/G3/G4等内部代码。
- 用户已明确目前没有专用模型、本体、IAM、K8s环境，继续以新工程隔离端点验证；实际外部联合验收条件待后续提供，不再重复询问。MCP/K8s内部适配/协议测试仍继续实现。模块2本体平台25项范围外，只消费其服务并记录实际版本。
- MCP前端正在补注册工具名与所选草稿远端工具检查，见frontend-mcp-registration-brief.md；当前向导仅有MCP类型选项，不把后端局部协议通过视为完整用户路径。直接应用发现结果前另需草稿版本/摘要并发保护。算法适配器已完成只读algorithm-protocol-implementation-plan.md，列明登记/UID/Pod归属/有界I/O/原代CAS等静态缺口及有限协议红绿矩阵；没有跑算法测试或集群，不把SDK直接依赖当作K8s协议验收。
- 500运行、5000+SSE、200×5MiB、首token p95未执行；不报告容量通过。部署门禁保持IAM/scope未验收默认阻断，未推送镜像或启动生产集群。
- T01独立边界完成；其余T01–T34与A01–A30按原台账逐项验收。17项设计覆盖不等于实现联合通过，未代签评审或交付。Git尚无HEAD，源码/制品固定版本在交付前补齐。
