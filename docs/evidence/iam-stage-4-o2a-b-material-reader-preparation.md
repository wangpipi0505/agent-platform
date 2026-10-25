# O2a B：有限材料 reader 实施前准备

候选、未实现、未测试。唯一工程及每条 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。O1c 已由 root 宣告 Accepted；本次用 source585 冻结清单和继承原件，未读活动 A 源码、测试或任何 target。Capture 仅概念依赖，A Accepted 后核最终 API。

继承 O2a finalization notes（38637 B，`4dca2b2f020997b20a46081eb9823f8a1dc541e7e6cd9746aef8d53e7672f55c`）及 cursor-scope notes。receiver 仍 STATUS-only；B 不预埋 C handler/writer/slot/HTTP cursor parser 或 O2b 协议。

## 1. 最小真实 writer 正例

冻结 O1c fixture 106–135 已有真实签名 admission→decoder→ServiceAuthorization→frame，同事务 createPublished/createNode，事务外 currentRetained(run.advance) 后调用 event 的种子路径；另以真实 RunService gate/sentinel 验证 DAG，未启动 worker。

新建 B PG fixture，不改 O1c fixture。15 个不同 id：`start:START → c01:CODE → … → c13:CODE → end:END`；14 条 `from/to` 边，CODE 顶层 `expression:"1"`，无外部依赖。FlowDriver 154–155 确认字段，但本轮未验表达式执行。snapshot 保留 FLOW/config/version/candidate 结构，以受控 authority 签发新 app-version admission；candidate 不等于 accepted snapshot 摘要，不改历史材料。

步骤：

1. 运行 `GraphRules.requirePublishedFlow` 和 fixture 的真实 `RunService.createReservedSessionTurn` DAG gate；storage 前 sentinel 到达，source/target 错误边失败。不称真实 HTTP POST。
2. 同一 PG 写事务调用 `createPublished(proof,snapshot,inputs,refs,budget,"FULL",requestId,labels)`，依序15次 `createNode(run,id,stepSeq,type,definition,proof)`。无裸插 scope/删父/补历史/schema 改动。
3. 提交后真实取得 writer current，调用 `TraceStore.event(run,"B_PARENT_FIXTURE",小型精确JSON,writer)`。B reader 另取 A 真签名 response Capture，不使用 execution proof。

三根+四份 run+每 node 四份，目标前 `C=7+4×15=67`；首次独立 event 无额外 revision，预计 `D=67`。这是“一次目标 event、无 attempt”的最少节点方案。恰好65可用14 nodes/三个独立 event，第三个预计 `D=7+56+2=65`，无需作为首选。

真实 PG 必须测 N/A/E/U、locator/revision、D/去重数量、H=所有所需 scope 的 parent 数之和、metadata UTF-8/最大记录/root 字节及解析峰值/backing capacity。预计目标+父68份，三根不重复计；H 含完整数组。目标后的新 run-state revision 不是其父。推导不替代实测，67父不证明全部4096情形。

## 2. B 的最小候选文件和边界

四个生产文件候选，正式 brief 可决定方法名：

| 文件（均 engine-runtime/src/main/java/cn/agentplatform/engine/ 下） | 必要改动 |
|---|---|
| `infra/RunAuthorityStore.java` | 新包内 `loadObservationBindingBounded(db,subject,publicRunId,budget)`；有界 control/initial projection；复用或提取原纯关系校验。不得先调用旧 loadObservationBinding/load 再统计大小。 |
| `infra/MaterialScopeStore.java` | 新包内 metadata probe、限定文本读取、完整 scope 验证及 root/event 限定 payload 读取；拆出不发 SQL 的校验以复用 digest/floor/locator 规则。旧 writer/read/status 入口不改用途。 |
| `infra/TraceStore.java` | 仅把 `requireSnapshotRelations(Binding,JsonNode,JsonNode)` 的纯校验改为包内可复用，或等价移到已有同包类并改原调用；不改 status 输出，不走旧事件裸 SQL。 |
| 新 `infra/RunEventReader.java` | 明确构造 DataSource/Clock 的有限 reader 服务，无 stereotype/route 注册。入口候选 `readNext(Capture,long afterSeq)`；内部顶层只读 RR、两个工作区 permit、私有 emission mint。嵌套私有构造的 `EventEmission`，不另建通用 proof 框架。 |

B 暂不建 application 空转发类。新测试候选 `RunEventReaderPostgresTest`、`RunEventReaderBudgetTest`；必要 signed authority helper 另列文件，禁止手造 Capture/VerifiedAuthority。A API 核后冻结总文件数。

Capture 用已批准 `subject/resource/responseId` 和本地 `requireLive/requireMaterial`；resource 须绑定本 run accepted snapshot。公开 DTO/Actor/执行 retained/status proof 不可代替它。open/renew 全在事务外，reader 无网络。

撤回历史 origin==新 observer 的建议：O1b loader 按 owner 查 run，status 核 fresh resource/逐 scope current cover，未将历史 `origin_authentication_id` 与新 Portal 相等。历史 provenance 只做内部一致性；当前 response source 由 A 私有 proof 保证。本人新登录仍可观察历史 run，不为此给 A 加方法。

初始 effectiveCursor 是请求绑定，afterSeq 是进度。B 单次入口要求非负/可表示、选中 seq>afterSeq，将二者及 response/resource 绑 emission；无事件返回 empty。C 初始化一次后按已发送 seq 前进，B 不声称跨轮防回退。A Accepted 后若 B 确需且未提供初始 binding，则列最小 Capture getter/本地断言；不现在改 A，不强制 progress==initial。

## 3. 同 RR 的精确查询顺序

先拒全部 ambient（含同库 RR），requireLive 后申请2个工作区之一，无 permit 即拒。顶层同库 readOnly RR/有限 timeout；旧 snapshot 可加入 ambient，不能直接套用。取消后 JDBC/解析真正退出才归还 permit。

**a. control/initial。** 沿旧 owner/publicId、TASK/FULL、unsupported/has_tools 检查，未知/他人404，其余沿原错误。先标量 id/version 和全部变长 control/initial 的长度/类型，不得 a.*/r.* 或先 getString 再量长。核 root/app/version/初始摘要/accepted/candidate/provenance 原关系；历史 execution grant 不用于 strict、recordCheck、恢复运行或写锁。

**b. 三根/下一 event。** 查三根身份及长度/类型/count；`run_id=? AND seq>? ORDER BY seq LIMIT 1` 仅取 event id/seq/type长度，不取payload。按 run-event/material_id/实际revision定位scope。空轮仍校验必要run绑定与根，不产emission。

**c. selected scope→父 probe。** 先身份标量、`octet_length(scope::text)`、`jsonb_typeof(scope->'parentScopeSha256')`，array 才取 array_length；变长 locator 同样有限。CASE 受限返回 selected scope，受限 parser 取得≤4096个64hex父hash；以run_id/hash及 `material_revision<selectedRevision` 查全部父标量，limit+1、精确集合、缺失/重复拒绝。三根去重，不能把父换成latest。

**d. 全 metadata/scope。** 总长度/records/各parent数先过预算和溢出检查，再逐份 CASE 限定全文，核长度/身份；失败NULL用独立状态。完整scopeDigest核schema/producer/kind/locator/owner/initial/sourceDecision/sourceAuthorizationVersion/revision/父唯一性。三根严格admission关系；动态scope保留历史版本。全部直接父核row/locator/hash/先后，完整父数组计摘要/预算；event floor用直接父集合，不为逐父floor递归加载祖先。

复用root validate/dynamic纯row检查/floor/locator语义，不直接调用无界dynamic/currentScopes。digest/floor不发SQL，locator仅标量。根及全部父均用同capture.requireMaterial；dataLabels仍是义务。批次/解析后及payload前requireLive。

**e. 根payload→event完整行。** 全scope通过后probe snapshot/refs长度，root合计≤8MiB，CASE限定JDBC返回。精确数字+原normalize/digest核payloadSha、accepted sha，复用snapshotRelations/GraphRules核candidate/app/version/refs/pureFLOW。inputs不消费则不读payload。最后取固定 `id,run_id,seq,type,payload,created_at` 投影，沿原UTC微秒/精确数字算完整行摘要，不只hash payload。投影先测量再限定；原event256KiB口径沿既有handler，不能用完整摘要投影偷换公开预算。

**f. commit后mint。** 事务内末尾和tx.execute真正返回后核同capture；generation/stop/到期/迟到取消均丢结果。私有构造mint绑定run/resource/responseId/afterSeq/seq/type/完整行hash的单事件emission，无全run缓存；bounded copy/access再requireLive并计副本。B不含stream；C负责编码后、取得actual stream后、每帧write前检查，不能提前声称已验出口。

## 4. 容量与可复核样本

候选：scope及initial单JSON≤1MiB、metadata≤8MiB、records及每份parents≤4096；root另≤8MiB；其余control≤256KiB且计metadata。逐JSON parser：metadata深度32/token65536/string65536字符；root深度64/token262144/string1MiB；token含field-name，number长度候选1000字符。event完整摘要投影另限1MiB工作记录，不改公开event限额。这些不是合法run最大值。

records含目标，4096父至少需4097记录，必触总量，不能声称端到端成功。H重复数组实计，压缩floor不等价完整摘要；所有限额同时适用。

| 测试层 | 精确样本与证据 |
|---|---|
| 真实 PG 正例 | 上述15节点、真实67父；单轮读取完整 event，记录 D/H/字节/row revision。另追加正常事件再读，证实新 revision、seq 与当前请求进度可前进；新登录同 owner、不同历史 origin 可读，另 owner/tenant 不可读。 |
| 真实 PG 顺序/一致性 | 记录 SQL 与 ResultSet 实际 getter：scalar probe 后才 scope text；全部 scope通过前 root/event payload getter 为0。同 RR 的并发新增事件不得把两个快照混读；当前读完成后下一轮见新事件。metadata失败时payload为0，实际payload摘要失败时payload>0但无emission。 |
| SQL 机械容量 | 用真实 PG 的参数化 VALUES/临时测试数据集执行相同 probe/CASE 投影形状，构造 `octet_length` 恰好1MiB和+1、总和8MiB和+1、root8MiB和+1；+1不应取得大text。此为 SQL 边界测试，不是裸插业务 material_scope 的授权正例，不冒称真实writer可生成每一字节大小。 |
| parser/账本机械 | 完整合法结构分别达到深度/token/string/number限制与+1；父4096/+1、records4096/+1、重复hash、错JSON类型、trailing tokens、long累计溢出。用受限 parser 的实际读取计数，不能先 JsonNode 再检查。精确字节由生成输入的 UTF-8 实测校准，不能用字符数代替。 |
| 真实 PG +容量拒绝 | 用真实 writer 允许的定义/label/事件规模取得至少一个生产 reader 容量失败；记录最先触发的维度，不要求低成本67父种子同时命中1MiB或8MiB。无法生成精确合法边界时如实留下机械覆盖与真实样本间的限制。 |
| 私有权限与取消 | 真签名 E/current 得 Capture；wrong owner/resource、无权限父、停用/新generation/过期、SQL等待及commit后失效均无emission。刷新在事务外；新current不能把旧emission复活。 |
| 并发/内存机械 | 两个工作区占用时第三个立即容量拒绝；阻塞 query 逻辑取消后仍持permit，实际退出才归还；异常不泄漏。统计 raw text、UTF-16、parser tokens、DOM、规范化/排序副本、parent arrays、encoded bytes及扩容 backingcapacity，不能以 count≤cap 冒充容量≤cap。 |

workspace按同时存活表示求和再乘2，8MiB文本不是8MiB堆；逐份释放，digest序列化副本仍计费。有限装配须唯一reader owner持2个permit，不能每请求新建额度。转移出的emission另计单帧生命周期；C再接ChunkBus预算，B不建队列。

容量错误沿 root 已决定的类别准备：opening 对应429/STREAM_CAPACITY，committed BUFFER_LIMIT 与 payload EVENT_LIMIT 留 C 映射；B 仅产生可区分的有限容量失败，不能将材料篡改/撤权伪装为容量失败，也不能自己输出 stream_reset。真实 PG 测试不得用 H2；common 真签名测试绑定可信 frame 是 receiver seam，不宣称 EVENTS HTTP route 已开放。

后续按既有Java21/Maven/PG前置指定运行新两类及status/writer有限回归，正式brief冻结命令，不猜环境参数。保留A closed receiver；B不改common路由/签名、controller/装配、migration/POM。

## 5. 实际读取清单与交还门槛

以下前缀均相对唯一工程根：C=`docs/evidence/iam-stage-4-o1c-gateway-status/frozen-source/`；B=`docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/`；N=`docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/`。engine 路径缩写 E=`engine-runtime/src/main/java/cn/agentplatform/engine/`，common路径 K=`admin-common/src/main/java/cn/agentplatform/common/authority/`。

| 冻结原件 | bytes / SHA256 | 本次必要范围 |
|---|---|---|
| C `engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusEngineFixtureMain.java` | 18886 / `280c772b385c3f0f0adcd67334ecb7d1c2e6b46f67ba7ed14cf7f4460e3b986e` | seed具名rg；104–137 |
| B E`infra/RunAuthorityStore.java` | 20003 / `b49d3408c70f3ca553a1a12d4161b1508c60eadddadfeeb316e9a6955c9cf764` | 23–79、105–198 |
| B E`infra/MaterialScopeStore.java` | 33599 / `7674bd68fb1dcb17a150d4a93dba68ada1f43c56b4546bac3fc59f7a89836e29` | 18–47、136–275、288–414、416–480、484–644 |
| B E`infra/TraceStore.java` | 76375 / `2187eea017f65173ce01e23bf268647445d845a3533ca7a43234967734f58516` | 390–433、489–517、541–588、615–713 |
| B E`application/RunService.java` | 116314 / `52a45545ebcf9ffd28802057b8e8c42fa09ea39081253a4442c35acc6a97d189` | 466–505、645–704、具名rg |
| B E`domain/GraphRules.java` | 10716 / `be53806c692a450cf83b6d86460bb2964d7b9b2999b616a809cf9d8d5f40f392` | 151–201及具名rg |
| N E`application/FlowDriver.java` | 12462 / `f522722f3b5f88bd0553142d60e12751bb64ffc64e390e47e972d1eca4979ed3` | CODE具名rg、129–156 |
| N K`MaterialAuthorization.java` | 3752 / `0834dc440e06341322cb0e4aeadb17227d3d245735821ca106b48fbfdb54b72b` | 1–50 |

SHA均物理核且与source585相符；清单SHA `047196ce0cfc82590e6fb68b7bba71a0ad99d9a17e493284711b6dcc529edd7e`。读composition确认overlay优先继承；最终窄修仅transport/test来自root，未读其修复清单。另读AGENTS/SDS、具名定位旧notes；一次误猜NodeExecutor的rg仅返回不存在，随后由manifest定位FlowDriver，无扫库。

A Accepted后核Capture可见性/generation/stop/owner/source/跨线程/anchor、requireMaterial和初始request binding。正式brief再冻结文件数/测试/容量/错误；不扩receiver。本次无测试或源码改动，交还后冻结。

EOF
