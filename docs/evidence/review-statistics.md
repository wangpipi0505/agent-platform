# T26 / G3 统计后端独立评审

评审日期：2026-10-20。范围：新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 中 T26 统计后端，不覆盖 T25、前端、生产 IAM 或整轮联合验收。

## 结论

- **Spec review：限定 T26 绑定语义通过，NO_SEND P2 已定向复核关闭**。发送前 CAS 失败留下的明确 NO_SEND 日志现已从两个模型统计入口一致排除，真实 SENT 缺 usage 仍保留未知。初次发现与修复复核过程分别保留在末尾。
- **Quality review：限定统计后端可交消费者联调，无未关闭的确定 P1/P2**。已独立阅读测试断言、最终日志和必要事实写入上下文；没有重跑成功套件，没有把历史日志称为本评审新执行结果。原 24 项分批证据之外，已补核读 NO_SEND 新增 9 项、model 全量 66 项及主任务网关开发烟测。
- 冻结详设第三部分 §1.4/§3.6 和管理 F8 写有日报预聚合/异步批量刷入路径；当前实现是有界权威事实直接聚合及持久报告重建。该差异已在 `statistics-t26.md` 如实披露。本结论仅覆盖指定绑定语义，不自动认定日报预聚合、后台任务或大规模容量验收已经完成。

## 输入与范围固定

已读 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`、冻结 `contracts/snapshot/detailed-design-v1.2.md` 第三部分 §1.4/§3.6/§5.6、管理 §1.8 F8，以及 `docs/evidence/statistics-api.md`、`statistics-t26.md`。

采用 `.local/reviews/g3-statistics.json` 的 `current-source-snapshot-no-HEAD` 当前源快照，捕获时刻 `2026-10-19T20:51:55.635Z`。评审时以 `Get-FileHash -Algorithm SHA256` 核对其中 12 个文件，全部与清单一致。没有使用 Git、读取相邻旧工程、读取凭据、启动/停止服务、修改业务或测试代码。额外上下文限本新工程 Actor/安全链/内部票据、模型事实写入、技能事实与回调、引擎工具事实和确认事件写入、四组统计测试及本次已有日志。

## 核对结果与定位

| 要求 | 源码依据与判断 |
| --- | --- |
| 可信身份，F8 严格 ADMIN | `admin-common/.../Actor.java:23` 只从 SecurityContext 的 Jwt 取 tenant/principal/roles；`SecurityConfiguration.java:35` 验签并验证 issuer/受众/用途，`:141` 为 internal 路径施加 ROLE_SERVICE。monitor `StatisticsService.java:45/79/142/203/218/233` 均要求 ADMIN；engine `RunStatistics.java:27/181` 与 model `ModelStatistics.java:21` 同样要求 ADMIN。`requireRole("ADMIN")` 不会被 EDITOR/SERVICE 替代。 |
| 外部请求边界 | monitor 只投影白名单筛选，身份参数不进入下游请求；重建 `StatisticsService.java:143` 明确拒绝 total/tokens/tenant 等未知字段；requestId 正则、明确 from/to、page/pageSize 在业务读取前校验。SQL 使用绑定参数，groupBy 固定为 day。内部访问同时受公共安全链约束，不能用单独 `requireRole("SERVICE")` 的 ADMIN 特例来推断 HTTP 绕过。 |
| 技能 owner 与 ADMIN 独立 | `SkillStatistics.java` 的事实过滤始终绑定 actor.tenantId；非 ADMIN 另加 principal_id，ADMIN 仅提升到本租户。版本关联同时约束 tenant，版本号为正数；普通 owner 与 ADMIN 错误样本分别走 `/admin/runs` 和 `/admin/statistics/runs`。`SkillController.java:128` 的 internal 入口要求 ADMIN 且必须提供 code，旧无界 dump 未承接 HTTP。 |
| 逻辑调用/用途/Mock | skill.execution_fact 的 `(tenant_id,call_id)` 主键为一行一逻辑调用；`SkillStatistics` 直接聚合当前事实而非 history/attempt。byMode 三用途分列；日成功率只取真实 FORMAL，Mock 独立计数并从真实状态、成功率、耗时排除。engine 按唯一 tool_call 行聚合，运行 CHAT/TASK 映射 FORMAL，DEBUG/EVALUATION 独立。 |
| UNKNOWN 与实际业务段 | `SkillExecution.java:293` 重试替换原行且只累加本次实际执行段；`:619` 对账仅更新结果/状态，不加等待耗时。`engine/infra/ToolCalls.java:296` 已接受事实摘要去重，`:315` 读取同 attempt 已接受时长，`:334` 只累加非负差额；重复投递和同 attempt 对账结果更新不会再次累计旧业务段。统计查询读取现有状态，因此 UNKNOWN 转终态不增加逻辑调用。运行耗时明确是运行墙钟时长，与技能耗时分别展示。 |
| 运行/确认先聚合 | `RunStatistics.java:79` 选运行 cohort；`:92` 运行聚合中按每个 run 独立统计 APPROVAL_DONE；`:115` 技能分组为独立查询，没有把运行或确认量与工具多行连接后重复求和。确认写入上下文对运行/节点加锁并在已完成状态重试时返回 deduped，事件随业务状态同事务写入。 |
| 模型先聚合，null 不伪造为 0 | `ModelStatistics` 去重 runIds，按 run_id 聚合持久 call_log，保留 knownTokens 部分和、usageMissingCalls 与 totalTokens=null。monitor `StatisticsService.java:92` 只使用 engine runRefs 查询模型，拒绝重复/未关联的返回 runId；`:109` 合并部分和，`:313` 任一缺 usage 时总量仍为 null。**NO_SEND 修复后，两模型统计入口共用 tenant+receipt.id 的持久 NO_SEND 排除条件；真实 SENT、旧无 receipt、孤立 receipt 与未确认状态保守保留。见末尾定向复核。** |
| 跨源与租户 | monitor 仅对 engine/model 做内部短票据 HTTP，未跨 schema。model 查询再次绑定当前 Actor 租户，不能因为 runId 相同而纳入其他租户 token。engine business 只来自冻结 guard_ctx.attributes.bizSystemCode，字符串或唯一值数组之外保持 null，不从 inputs/body 造维度。 |
| 时间/硬闸 | 技能和运行查询均为 UTC `[from,to)`、最长 366 天、day 分组。engine 用 10001/2001 哨兵读取并在超过 10000 运行、2000 运行或技能维度时明确 422；monitor 不把超限正常截断，转发来源 422，HTTP 来源最多 8MiB，15 秒读超时。技能失败语义超过 100 种也明确 422，每种最多 5 个样本。内部 model 输入超过 10000 条按已交接接口返回 400；平台最大运行规模仍由 engine 的 422 闸保证。 |
| 快照一致性 | skill/engine/model 均使用只读 REPEATABLE_READ 事务包住该来源的多查询并返回 transaction_timestamp。monitor 返回 engine/model 分别的 snapshotAt，标记 PER_SOURCE_SNAPSHOT；未宣称全局原子快照。 |
| 分页 | `StatisticsService.java:242` 先取完整有界报告，再分别切 groups/skillGroups；groupsTotal/skillGroupsTotal 是切页前分组行数。快照持久化完整报告，读取再分页，重建响应同样只显示第一页。当前日志没有覆盖多页断言，结论来自代码。 |
| 重建与幂等并发 | `StatisticsService.java:149` 先查本 tenant 的 requestId；筛选 hash 不同 409。来源读取先完成，`:164` 开本地事务；插快照 `ON CONFLICT(tenant_id,request_id) DO NOTHING`，冲突后重新读取已提交赢家并核对 query_hash。READ_COMMITTED 的冲突后新语句能看到赢家；同筛选返回同一持久快照，异筛选 409。插报告与 active 切换在同一事务，来源失败发生在事务前。没有内存缓存充当重建权威。 |
| 快照 tenant 外键 | `V501__statistics_snapshots.sql:8` 主键/请求唯一键都含 tenant；`:14` active 的外键为 `(tenant_id,snapshot_id)`。所有列表/详情读取也绑定 tenant，外 tenant 返回 404。 |
| ADMIN 最小下钻 | `RunStatistics.java:192` 只选本租户 run 的故障元数据；`:206` 只选前 100 条失败/UNKNOWN 技能调用标识、状态、错误语义及实际耗时，另给全量条数，不展开 run.inputs/outputs、技能 args、模型请求。该入口的 ADMIN 权限不改变普通 owner 执行记录入口。 |

## 独立检查的既有测试证据

以下是本评审核读的已有测试日志，**没有重跑**。

| 既有最终日志 | 已核对的结果 |
| --- | --- |
| `.local/statistics-engine-final.log:92/105/108` | RunStatisticsPostgresTest：5 tests，0 failures/errors/skipped，BUILD SUCCESS，`2026-10-20T04:46:35+08:00`。 |
| `.local/statistics-final.log:99/143/159/216/231/234` | skill 当时 6；ModelStatisticsPostgresTest 3；既有 ModelQuotaPostgresTest 5；StatisticsPersistenceTest 4；均 0 failures/errors/skipped，BUILD SUCCESS，`2026-10-20T04:47:56+08:00`。 |
| `.local/statistics-skill-final.log:100/113/116` | 技能最终增至 7 tests，0 failures/errors/skipped，BUILD SUCCESS，`2026-10-20T04:49:31+08:00`。 |

按各组最终证据合并是新增 skill7+engine5+model3+monitor4=19 项，连既有 quota5 为 24 项，不能把中间 skill6 与最终 skill7 重复累计。

已经阅读四组新增测试的实际断言：技能正式/调试/评测和 Mock、半开窗口终点排除、版本与 owner、UNKNOWN 原行更新、admin/owner 链接及 internal 必需 code；引擎 app/biz/mode、确认不放大、10001 运行 422、Mock/UNKNOWN、下钻 tenant 与无 inputs；模型重复 runIds、缺 usage、跨 tenant 和权限；monitor 可信 runRefs、忽略伪造身份参数、分页边界、来源失败保留 active、持久读取、重复 requestId 与异筛选 409。

## 验证边界与待消费者

1. 统计测试使用真实专用 PG fixture；monitor 使用真实本地 HTTP 测试对端，但该对端只检查 Authorization 以 Bearer 开头，并没有真实验签。四组主要入口是 standalone MockMvc，预先放入 JwtAuthenticationToken，绕过完整安全过滤链。本次可信身份结论有公共安全配置和票据源码支持，不能把这 19 项描述为完整 JWT 验签联调已过。
2. 重建并发正确性由唯一键、冲突赢家重读和本地事务推导；现有 monitor 用例是顺序重复请求。并行同号/异筛选、2001 维度、跨页总数、跨源写入期间快照与响应体/超时上限缺专门实测断言。本评审不把缺覆盖直接当作已复现缺陷，也不将它们记成已经验证。
3. 来源 HTTP 失败保留 active 已有断言；真实进程 kill、提交窗口断连、数据库中断/恢复未做故障注入，不能声称崩溃恢复已实测。
4. 待统计页/技能详情消费者按 `statistics-api.md` 对接对象响应，保留 null 的未知含义，展示正式/调试/评测及 Mock 的独立口径，使用 groupsTotal/skillGroupsTotal 作分页总数，展示 UTC/cohort/PER_SOURCE_SNAPSHOT，按 ADMIN 与 owner 样本链接分流。实际 UI 页面、按钮权限、空态/错误态/分页与下钻联调未验。
5. 主任务仍需跟踪 T25、全量联合验收、生产身份接入、规模容量以及日报预聚合路径的设计差异；本报告不关闭整个 G3。也没有把同时间的其他 engine 修复包变化归入统计快照。

本评审唯一写入文件为本报告。最终结论以末尾 NO_SEND 定向复核为准：已发现的 P2 已关闭，无未关闭确定 P1/P2；所有既有自动化证据均注明来源，不是本评审重新执行的测试成功声明。

## 定向补查历史：[P2] 排除发送 CAS 前明确 NO_SEND 的模型日志（现已关闭）

**发现定位：**`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStatistics.java:52-67`，重点是 `count(*)` 与 `count(*) filter(where total_tokens is null)`。同口径的旧 `ModelStore.statistics(Actor)`（`:616-623`）也未排除 NO_SEND。

**触发路径与事实证据：**

1. 同步生产路径 `ModelCalls.java:340-350` 先 `store.sending(...)` 持久插入 call_log，再 `store.quotaSending(...)`，随后才 `sendStarted=true` 和 `execution.execute`。流式 `:364-374` 同序；`FrozenRequestConnector.writeWith` 只有 beforeSend 回调成功后才向底层提交请求体。
2. `ModelStore.java:417-425` 的 quotaSending 仅允许未过期 RESERVING 行 CAS 为 SENT；租约过期或恢复任务已先改为 NO_SEND 时更新 0 行，明确抛 503，网络发送尚未发生。
3. 此时 logId 已赋值而 sendStarted 仍 false。`ModelCalls.java:470-471/489-491` 调 `store.failed`；`ModelStore.java:604-610` 只把既有 call_log 改为 FAILED/error/duration，不删除、不标记未发送。`:494-501` 的 finally 仅把 quota_reservation 改为 NO_SEND 并退款；`pendingNoSend` 恢复路径也仅操作 quota_reservation，没有修改 call_log。
4. `V305__durable_unsent_quota_reservations.sql` 把 RESERVING/SENT/NO_SEND 明确区分在独立持久表；当前 ModelStatistics 未关联该表或读取其他已发送标识，所以留下的 FAILED/null-usage 日志仍贡献 calls=1、failed=1、usageMissingCalls=1。monitor 继续合并该行；如果相同运行组另有一个真实成功调用且 usage=21，平台报告会由正确的 calls=1/totalTokens=21 变成 calls=2/totalTokens=null。

这是源码可确定的指标错误，不是推测 kill 窗口，也不是把测试覆盖不足本身当作 bug。它违反交接中 `grain=MODEL_SEND`、`groups[].model.calls` 为实际发送数以及无发送才可为合法零的口径。本次没有执行新 fixture，因此不声称已经运行复现。

**既有 I3 证据为何未覆盖：**`ModelCallsPostgresTest.failedSendingLogStillHasDurableZeroSendRefund` 在 `sending` 内直接抛异常，日志未持久落库；wire rejection 测试在 checkActualWire 阶段先拒绝，也未进入 sending。`ModelQuotaPostgresTest.expiredUnsentLeaseFencesLateSenderAndRefundsOnlyOnce` 证实过期租约能挡住 quotaSending 并退款，但没有预先产生 call_log，也没对统计断言。这些成功结果不能排除本问题。

**最小定向回归方案（仅建议，未写测试/未运行）：**

- 在现有隔离 PG/Redis/本地 HTTP 的 ModelCallsPostgresTest fixture 中，用唯一 runId/invocationRef，覆写 `ModelStore.quotaSending`：在进入该方法时将本 receipt 对应的 reservation.lease_until 改为过去，再调用真实 `super.quotaSending`。这样日志插入仍走真实实现，同时稳定触发真实 SENT CAS 失败，不依赖等待或时序竞态。
- 分别执行同步与流式 call，断言调用失败、对端 HTTP 请求计数为零、call_log 已存在、reservation 为 NO_SEND、退款/对账可重复且不重复退款；再以本租户 ADMIN/SERVICE Actor 调用按 runIds 的统计，要求此运行没有 MODEL_SEND 行或实际发送数为零，不能贡献 usageMissingCalls。
- 在同一 run 再放一个真实发送成功且 usage 完整的已验证调用，断言统计只计这次真实发送，calls=1、usageMissingCalls=0、totalTokens 为实际已知值；并保留“真实已经发送但 usage 缺失”仍计发送、totalTokens 仍为 null 的对照，防止把所有 FAILED/null-usage 粗暴过滤掉。
- 恢复兼容性至少覆盖：预留在 CAS 前已被恢复任务改为 NO_SEND、catch 的 NO_SEND 更新暂时失败而后由恢复任务完成，以及旧 call_log.quota_receipt='{}' 的历史行；不能为排除本问题误删/排除真实发送或把未确定状态伪造为零。关联应使用 tenant 与持久 receipt/reservation 身份，不能仅按可能重试的 invocationRef 粗略过滤。

**当时的修复方向：**让发送事实与明确未发送状态在持久层可区分，并让两处统计使用一致口径；基于可信 NO_SEND 事实排除即可，不能把真正发送失败或缺 usage 的发送一起排除。当时结论为 request changes；其后修复已按下面定向复核关闭，不能继续引用本历史段为未关闭状态。

## NO_SEND 修复独立复核与补充证据

本轮仅复核上述 P2：读取 `docs/evidence/statistics-nosend-fix.md`、两个统计生产文件、两个新增/变更测试、定向红绿/全 model 日志，以及主任务开发烟测脚本和结果。未扩大审查、未改源码、未重跑任何套件。

实际 `Get-FileHash -Algorithm SHA256` 与实现交接表逐项比较，以下四文件全部匹配。这是原 12 文件快照之后的增量依据，不声称旧 ModelStatistics 哈希仍匹配：

| 文件 | 已核对 SHA256 |
| --- | --- |
| `model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStatistics.java` | `78b9511de3fa5dd04fb126058d7c8aa4d8df69d6a0da9d993ef033d6a11668c4` |
| `model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java` | `c6a2ef98b9b214f07d9e7ed63e3214177579f6dba5e2b25c3d0002003f8003d7` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelStatisticsPostgresTest.java` | `f9805694a3622767ac9b6ccdb0bfdc9521067b79717393e2656bd6c98d40045a` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelNoSendStatisticsPostgresTest.java` | `965385ee835a7f4b4c809ed27583fac928c62808a1d4aa38534d279e7c06058a` |

修复核对：`ModelStatistics.SEND_FACT_FILTER` 使用 NOT EXISTS，只按 `q.tenant_id=l.tenant_id`、`q.reservation_id=l.quota_receipt->>'id'`、`q.status='NO_SEND'` 排除，有复合主键支持，不会连接放大；ModelStore 老入口复用同一个条件。没有按 invocationRef 粗略关联，也没有直接排除 FAILED/null-usage。无发送的 byRun 是空集，monitor 原有 emptyModel 会自然得到零；老汇总仅在没有缺 usage 时把空集总和归零，存在真实未知 usage 时总 token 仍为 null。

已逐项读断言：新增生产者 fixture 先真实连通探测再清业务 HTTP 计数；同步/stream 都走真实 ModelCalls、sending 日志提交、真实 PG 租约过期和真实 `super.quotaSending` CAS 失败。断言日志已存在、零业务 HTTP、NO_SEND/RECONCILED、日额和 TPM/RPM 退款、重复恢复不重复退款；同 run 后续真实成功调用得到 calls=1、failed=0、missing=0、known/total=12。另有 NO_SEND 确认方法故障后真实恢复重分类、恢复先赢和真实 SENT 缺 usage 不退款/保留 null 对照。统计 PG fixture 覆盖相同 reservation ID 外租户、同 invocation 不同 receipt、重复 runIds、历史空 receipt 和孤立 receipt 的兼容性。

独立核读的既有日志：

| 日志 | 结果 |
| --- | --- |
| `.local/statistics-nosend-red.log:42-67` | 最小 PG 复现 1 test/1 failure/0 errors/skips；calls expected 1、actual 2；05:01:11。 |
| `.local/statistics-nosend-producer-red.log:1610-1810` | 6 tests/5 failures/0 errors/skips；五个未发送路径仍产发送统计，真实 SENT 未知对照通过；05:02:52。 |
| `.local/statistics-nosend-targeted-green.log:1613-1654` | 生产者6+统计6+既有quota PG5=17 tests，0 failures/errors/skips，BUILD SUCCESS；05:03:48。 |
| `.local/statistics-nosend-full-green.log:609/2187/2193/2195/2220/2222/2226-2232` | model 全量66 tests，0 failures/errors/skips，BUILD SUCCESS；`2026-10-20T05:05:32+08:00`。原57+新增9=66，未重复累计定向17项。 |

**关闭结论：**源码与真实生产者回归断言覆盖了原 P2 的日志成功后发送 CAS 失败路径，两统计入口口径一致；持久 NO_SEND 可排除、恢复后会重分类，真实发送未知未被伪造为零。本 P2 已关闭，无新的确定 P1/P2。这里确认的是所读实现和所读运行证据，不是本评审重新执行测试。

主任务另已执行 `tests/smoke-statistics.mjs`，本评审只读核查脚本断言及 `.local/statistics-live-smoke.json`：开发网关完整 JWT 过滤链查询成功，运行报表计数4与独立 PG count4一致；3个并发同 requestId 重建返回同 snapshotId，持久快照读回一致，同号换筛选409；READER查询/重建403、外 tenant 快照404、篡改 JWT 401。结果中 `realGatewayJwtChain=true`、`sameSnapshot=true`、三个拒绝字段均为 true、`consistency=PER_SOURCE_SNAPSHOT`。

这补足了前文“统计模块 standalone MockMvc 不证明完整验签”和“没有并发同号重建实测”的部分边界：现在有主任务执行的开发进程烟测证据。身份由本地测试签名/开发登录取得，不能外推到生产 IAM；该烟测运行时服务尚未重启纳入本轮 NO_SEND 修复，因此不能称 NO_SEND 修复已通过部署后跨服务实测。真实 kill/数据库或 Redis 停机、2001维度/大容量、实际统计 UI、日报预聚合差异仍保留，T25/整个 G3 仍由主任务跟踪。
