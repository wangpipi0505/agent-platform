# G6 IAM 实施计划独立审查返修记录

2026-10-20。本记录只说明计划文本返修，供原审查者复核；没有实现认证、材料授权、长流、resolver 或门户功能，也没有运行功能测试。

## 交付和冻结材料

| 材料 | 路径 | SHA256 |
| --- | --- | --- |
| 修改前完整基线 | `docs/evidence/iam-service-authority-plan-revision-baseline.md` | `be3d88ea5afd3752082297010b50daa84d7bb0cb83f1b7388b8ce9dfd5be223d` |
| 当前返修计划 | `docs/evidence/iam-service-authority-implementation-plan.md` | `792b502c25973f325366d456b8de4be2f46362f1242d2ff6245a85aebb3ebfaf` |
| 基线到当前的真实文本 diff | `docs/evidence/iam-service-authority-plan-revision.diff` | `844a1778d6e3c48010b8ce4771dd5eefd91cd5f24b1a2427f9370ec7c8d935e7` |

基线在任何返修编辑前通过文件复制保存，随后未修改。diff 来自 `git diff --no-index --no-ext-diff --no-color` 的真实比较，只把旧文件 header 的基线名称归一到计划的标准相对路径，便于在持有基线计划的审阅目录应用；没有人工编造 hunk。`git apply --reverse --check -- docs/evidence/iam-service-authority-plan-revision.diff` 对当前计划通过，仅检查，未反向修改文件。`git apply --numstat` 返回该计划 **144 行新增、7 行删除**。

当前仍为 **7 个 Task**，有 **48 个未执行实施 checkbox**（基线37，新增11）和 **9 个已完成文档自检 checkbox**，合计57。已勾自检仅指计划覆盖核对，不是实现或功能测试通过。当前597行，26个代码围栏起止标记成对。

## 对独立审查三个 P1 的响应

| 审查项 | 已补入计划 | 实施与验收落点 | 仍待验证 |
| --- | --- | --- | --- |
| R1：长SSE/模型stream授权生命周期 | §2.4分PORTAL观察SSE、普通内部模型流、INTEGRATION在途当前响应；response-only open/check、固定deadline、source expiry、decision续核、watchdog、异步显式lease、逐buffer/chunk/final检查；集成仅drain原响应；断流不取消run、不撤销外部效果 | Task3 ResponseAuthorityClient/Guard及组件红绿；Task4明确RunController、ModelController、ModelCalls、FrozenRequestConnector、RuntimeClients、GatewayController真实循环；Task6有限Clock+两chunk/final闩锁、idle关闭、Last-Event-ID重核、后续provider/skill发送0 | 真实response continuation profile、各接收循环、资源清理与浏览器停止观察均未实现/未测；生产缺能力默认关闭对应流 |
| R2：材料scope可信持久与真实ChatHistory消费 | §2.5明确MaterialScopeV1/Ref/AuthorizedMaterial、初始可信full ceiling、source/version/payload摘要、parent要求并集、dataLabels义务并集；每域事实/READY同事务；cross-service受信endpoint/manifest；不从旧guard补录 | Task5新增ChatHistory/ChatSessions/ModelExchange/ContextProjection/TraceStore/ControlledReplay/ModelStore真实路径，去掉load旧对旧授权，new/known/reserved turn、SUMMARY/MAIN/RETRY/checkpoint/artifact/replay/status/events均核当次current；缺metadata拒绝，同scope合法多轮恢复正例 | 新metadata和各域迁移、事务一致性、跨域manifest来源、真实历史加载/出域/下载/回放均未实现/未测 |
| R3：独立policy resolver信任/接口/HTTP链 | §2.6固定POST路径、iam-policy-resolver caller/action、独立RS256 policy assertion与部署公钥验证域、最小DTO、无secretRef、PG policy_revision、nonce/body/subject/credential绑定；resolver不调用authority | Task3 authority fixture只消费app HTTP；Task4 app独立chain/controller/service/DTO/候选V104与真实PG策略变更；Task6 gateway→authority→app resolver/PG→authority→downstream链，旧grant exchange/resume/authorize拒绝、resolver→authority计数0 | policy信任配置、真实PG revision变更和无环HTTP链未实现/未测；不将fixture政策静态表当成PG验收 |

三项不是只补Task6总矩阵：C/D/E各任务均有新增具体文件、接口、红绿步骤、命令和可观察断言。R2采用root允许的完整可信历史ceiling保守要求；它可能多拒绝，不能缩小父材料所需许可或删除旧dataLabels。R1保留v1.2 snapshot:2887的集成当前响应例外，仅来源由authority签名确认时适用，不能为新业务动作续权。

## root追加的管理端门户配套

实际核 `web/shared/api.ts`、`web/admin/src/main.ts`、`web/admin/src/App.vue`：管理端当前只有dev-token入口；`web/assistant.js/src/main.ts` 的ticket回调是集成用途，不是管理端门户tokenProvider。

计划§2.7及Task4/6补同源宿主PortalTokenProvider和只存内存的生命周期、宿主更新generation隔离、到期/卸载清理、401/403/503、旧401不覆盖新会话、无开发token回退、无自动POST重放。root已允许将新增 `GET /auth/session` 作为配套依赖纳入独立复核：仅门户JWT认证，当前authority绑定tenant/principal，响应严格白名单tenantId/principalId/roles/expiresAt，不返回attributes全量/票据/grant/凭据。有限真实浏览器必须经过签名issuer、gateway/session/authority和业务HTTP，不能用page.route.fulfill替代。SDK ticket() 对外协议保持。以上全部是计划，不表示管理端现已具备生产登录。

## 本次实际核对与保护

本次读了独立审查报告和root裁决，并按 Receiving Code Review / Writing Plans 技能核对当前源码事实；管理端读取时亦查Vue技能，仅采用项目Vue3.5约束，未引入beta方案。实际再次读取/定位了ChatHistory、ChatSessions、AgentDriver、ContextAssembler、ModelExchange、ContextProjection、TraceStore、ReplayService、RunService、ArtifactStore、IntegrationService/Controller、RunController、ModelController/ModelCalls、GatewayController以及管理端/SDK认证入口和现有浏览器测试入口。snapshot集成吊销/当前响应原文已核对。没有新增外部来源阅读声明。

此次写入仅四个文件：当前计划、完整基线、diff、本返修记录。没有改业务源码、测试、target、POM、部署、root裁决、原四契约、执行台账或旧G2历史计划；没有读取 `.env`、vault数据/masterkey/真实凭据或相邻工程；没有运行Maven、npm、浏览器、服务、数据库操作或派子agent。

实际完成的验证仅为：基线/当前/diff SHA256、真实diff反向可应用检查、numstat、任务/checkbox/围栏数量及文本覆盖自检。计划中的Java/PG/HTTP/浏览器命令没有运行，不能列为通过。原独立审查结论仍待原审查者复核，不自行改为APPROVE；生产门禁继续 `BLOCKED_IAM_SCOPE_ACCEPTANCE`。
