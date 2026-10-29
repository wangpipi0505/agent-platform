# EX-06 AP 阶段方案独立静态审查

2026-10-29，`artifact_boundary_review`。审查 `ap-stage-plan.md`、其源索引与 JAR 盘点，并读取 AP/OR 实际相关源码。本轮仅新增本文，未修改计划、脚本、Java、主入口或 index，未运行 Java/Node/PG/HTTP/服务，未读取凭据内容或进程环境。

## 判定

**可以进入“模型协议夹具＋协议 driver”的最小实现阶段；当前不能据此开放 AP 写业务或将 All 标为完成。** 既有管理 API 足以支撑导入、真实技能测试/发布和三应用评估/发布，无需为该阶段新增产品 API。业务窗前除计划已有的 PG/JAR/V222 准入外，须补下列 Redis 与 TestFixture 身份约束。主线程随后提出的“三应用先全部发布，J9 再发布 pinApp 新候选并重放”与实际代码一致，应替代计划 §5 留 pinApp 未发布的建议；不需要放宽 All 完成门或最终 manifest 形状。

## 必须处理的具体项

### 1. 独立 PG 库不足以隔离现有 Redis（AP 业务运行阻断）

计划 §4 指定独立 AP DB，但仍默认复用 26379，尚未定义 Redis 状态归属。`ModelQuota.java:27,216–228` 的键包含 tenant hash、provider 数字 ID、app hash、principal hash，**不含数据库/session 身份**。空 PG 的 provider ID 会重用；即使避开该 ID，固定 `local-v12/developer-v12` 的 user-daily/user 键仍相同。进入真实模型调用会操作这些共享配额/租约，不是仅健康读。

`ChunkBus.java:52–60` 直接构造 `RedisChunks(host,port,password)`，没有传 Spring Redis database；`RedisChunks.java:17,102,154` 使用固定频道 `agent-platform:v1.2:engine:chunks`，载荷仅 `run`（数字内部 ID）与 text。新 PG 的 run 数字 ID 同样会重用。**只给 Spring 配逻辑数据库不能隔离此频道，也不能宣称该自建连接消费了 database 配置。**

主线程随后选择 **本会话独立 Redis endpoint**，不只切逻辑 DB：复用本仓 `AgentPlatformV12` WSL 中已安装 Redis 7.2.16 二进制，使用唯一 loopback 端口、随机密码、独立配置/目录/PID，只关闭本次 owned 实例。这可同时隔离 model 的键与 engine 固定 Pub/Sub 频道，产品已有 host/port/password 配置足够，不需改 namespace。不要 FLUSH/接管26379，也不要先用真实调用“看看是否冲突”。本项不阻断纯 loopback 模型协议测试；独立 endpoint 的实际身份验证仍在业务窗前执行。

现有支持与需要记录的准入事实：

- `scripts/start-cache.ps1` 已明确本仓 `.local/wsl/ext4.vhdx`、发行版 `AgentPlatformV12`、`/opt/agent-platform/redis-7.2.16/src/redis-server`、redis 用户、loopback/protected-mode/随机密码/有界 maxmemory/noeviction 的使用方式。这里只读脚本，**没有访问/启动 WSL 或核当前二进制运行身份**。
- root 启动前核发行版注册位置确属本仓，二进制实际路径/版本/SHA、配置路径和本会话唯一目录归属；核拟用端口无人监听，不改26379/共享 `.env`/共享 redis-process.json。私有配置含密码，仅保留ACL、SHA/键名，日志不得回显 requirepass。
- 启动后以本次密码认证，核 INFO 的 version/process_id/run_id/port 与登记PID；核 `/proc/<PID>/exe`、进程用户、实际启动配置、CONFIG GET dir/pidfile 与本次独立目录一致；核Windows可达地址仍为loopback。以这些身份事实证明启动了本次实例，不以PONG单独证明归属。
- engine/model 的 `AGENT_REDIS_HOST/PORT/PASSWORD` 都必须指该 endpoint；engine RedisChunks 也消费相同 host/port/password，所以不遗留26379的旁路。其它服务如声明 Redis 地址也不得继承错误的共享值。
- 退出仅在重新核本次 endpoint+PID+run_id/目录后关闭本次实例并有界确认；不杀同名进程、不终止整个发行版、不删除共享数据。固定频道与配额键位于独立 endpoint，无需新增产品namespace。
- 原 start-cache 写固定26379/全局.env，verify-cache 的目录许可也只含既有共享布局；**不能原样调用这两个脚本来创建/关闭会话实例，也不能放宽其检查**。root可按既有受控运行方式执行一次性精确命令并留存原件；若新增持久采集器，先登记该准确文件，当前阶段A不包含它。

### 2. TestFixture 编码和 DEMO 标记必须冻结（真实模型调用前阻断）

`model/application/FixturePolicy` 规定 provider code 含 `fixture` 或以 `smoke_model_` 开头即为夹具，新夹具必须遵守；`FixtureEgressGuard` 在默认模式拒绝该供应商的新建、模型调用及冻结/校验。`RunService:182–204,670` 则从 AGENT_DEMO_MODE 写入运行 DEMO/REAL 标记。

计划的 TestFixture provider code 尚未明确，环境表也没有列 DEMO 约束。主线程已选定 provider 遵守 fixture 命名，model+engine 明确 DEMO；证据同时标 TestFixture。不能选择普通 provider code 绕过守卫，再让固定输出的 run 标为 REAL。Real 模式只消费显式已有真实 entry，并保持其独立能力/信任标签检查。

其余服务的实际影响已查：app 的 DataHealthService 只用该值标记健康结果 DEMO/REAL，建议同一 TestFixture 环境设 true 以免健康面与 run 相反；不改变发布门/ReleasePins。skill 的 FixtureSkills 使用它限制 fixture 命名的新技能创建及报告标识，不替换 SkillExecution/SkillAdapters 的真实执行；本次 OR 正式导入的 joint_* 契约仍按原 endpoint、API_KEY、guard、DEBUG fact 和发布门执行。gateway/monitor/admin-common 当前业务源没有把该值用于模型/OR调用的假响应分支。可为本次环境保持一致标记，但**DEMO 不等于跳过真实OR执行/取消评估安全门**：必须继续观测实际consumer调用、真实非mock执行事实、实际pin与PG报告；不要启用任何独立 debugMocks/执行mock来让门通过。

### 3. 三应用完成门采用主线程的 J9 新版本方案（已静态验证可行）

准备期三个应用均应真实评估、预检、发布；pinApp 不得以“待 J9 首次 commit”进入 All 成功。J9 时只对 pinApp 将 config.ontology.releaseId 从旧版改 replacement，用新 revision/SHA、独立新 dataset/真实评估、新 precheck、新 idempotencyRequestNo 发布；另外两个应用继续用于 J5，不能互相替代。

源码支持此流程：

- `AppService.save:351–375` 的 config 语义变化递增 revision 并变为 DRAFT；ontology.releaseId 是非展示键，会改变 candidate/semantic 身份，不能使用旧评估。
- `AppService.commit:1445–1463` 的同 requestNo 分支要求原 draftRev/SHA 一致，读取原发布 version 的 releasedCandidate；`1608` 后仍调用 pin/recordPin。因此同请求号重放复用原 app version，却真实重新核远端 pin。
- OR `ReleaseBindingService.bind:45–103` 按 space 行锁串行；同 `(space,tenant,app,environment)` 的 ACTIVE release 相同，返回原 bindingId/changed=false；切换 release 先将旧行 UNBOUND，再建新 ACTIVE，返回 changed=true。

J9 应记录准备期旧 binding、首次新发布的 `pin.state=PINNED`、`changed=true`、新 bindingId 与 replacement release，再读取 OR 权威绑定和 app.publish_pin；同原请求重放应同 app version、新 bindingId 不再变、changed=false，业务实体不重复。请求若结果未知，先保留 pending，按原请求号进行明确的幂等核实；无法取得首次 changed=true 的原响应时，不伪造它，允许用本次明确登记的前后业务事实另行复核。审计/消费事实增加不等于业务实体重复。

`ReleasePins.pinned` 对任何 2xx 都标 PINNED，即使正文解析失败也可仅带 reason；所以 **PINNED 本身不够**，必须有真实字段与 OR 读回。默认 pin 失败不回滚已经发布的 app；app.publish_pin 的观测写失败也不回滚，脚本不能只看 HTTP200/版本号继续。该限制不需要本卡改产品来掩盖。

### 4. 连接报告 ID 与未知写恢复不能按不存在的 API 实现

`SkillGovernance.connection:40–117` 返回 ok、latencyMs、candidateSha256、semanticDigest，以及首次请求可得到的 runId/status/controlledCallId；插入 connection_test 后**不返回 reportId**。acceptance:193–225 才返回 `id=sa_...`。计划 §3“连接/选型 reportId”应区别记录：连接留完整安全结果/已知 runId/callId/版本摘要；选型留其真实 id。不能伪造连接 ID，也不用因此新增产品 API。

连接测试首次创建 DEBUG 后可能未完成，必须等待已登记 run，并以同 controlledCallId 明确重检；重检仍会新增连接报告，不能称无写副作用。POST evaluations 没有供脚本任意重试的创建幂等键，acceptance/import/provider/app create 也不能视为 GET。未知写结果要保留 pending 并停止，不能重建资源或重签 key。只有已取得 evalId 的 resume、已取得受控 callId 的重检、已冻结候选+requestNo 的 publish 重放等已知协议，可作为**明确的新恢复动作**执行，仍登记它们的事实和边界。

## 管理 API 与发布门核对

计划 §2 的主要路径和真实门有源码支撑：model provider 新建 DISABLED、test 才启用；entryHealth 实测目录/chat/tools；OntologyManifestImports 只接受正式 HTTP/SYNC/POST，并实际解析本 tenant API_KEY 引用；导入仅生成 DRAFT。其 tool_code 直接成为技能 code，绝不能在 AP 改 manifest 来绕冲突。一次只导入本会话 A 的一条，避免整批 API 非原子创建造成无法归属的部分成功。

SkillGovernance.connection 要求同 tenant/principal/skillVersionId/sha 的非 mock SUCCEEDED DEBUG fact；acceptance 真调 model 并校验工具名/输入，命中率至少 80%；SkillCatalog.publish 核精确版本/sha/digest 对应的连接与选型。模型目录或单次 HTTP200 无法替代这些门。

应用使用 `config.skillRefs[{code,version}]`，不是预先伪造 config.tools。AppService 原编辑 revision、候选摘要、模型冻结、引用和 guard 门均须通过。EvaluationService 要求至少一条 safety=true，等待 ACCEPTED；安全用例只允许 EXACT/SCHEMA。AgentDriver 终态 output 包含 text/usage/actualModel，不能把 `{text}` 与完整输出用 EXACT 硬称相等。计划的安全 SCHEMA 必须把 text.const 和必要输出形状写实；普通 CONTAINS 只能证明输出含文本，不能单独证明本体调用、pin 来源或身份链，另核调用事实/冻结快照。

发布门继续要求真实 BLOCKING/PASS、本候选对应 evalReportId，无 WAIVED/ADVISORY/OFF；新候选变化后重做评估，不复用 pinApp 的旧报告冒充新版本。precheck 15 分钟窗口和 requestNo 不变；J9 现场新取 precheck。

## opCode、旧 partial 与 Prepare/All/Resume

当前 `Prepare-Joint.ps1` 的 operation 创建、目录过滤、endpoint 过滤、真实消费路径、resources 和 isolationFixtures 都写死 joint_list；修改不能只替换创建点。

新 session 采用 `joint_`+SHA256(UTF8 SessionId)前24位小写 hex，30 字符，满足 OR operation ≤80 和 AP skill ≤64、ASCII 字母开头的实际正则。首次业务写前落 journal，不在 Resume 重新计算。碰撞仍按本 tenant 精确 code 查归属，已有无本会话成功 action 的同 code 即冲突停止。B 空间可用同 opCode，但不把 B manifest 再导入同 tenant 技能目录。

旧 partial 兼容须补准确读取规则：当前 A_operation/B_operation action 仅保存 id/draftRev，**没有保存 code**。完整 ONTOLOGY_READY 的 resources.opCode 可复用；中途旧 partial 没有 resources 时，必须用已登记 operationId 在本会话 space 的目录读回核旧 code，不能假称 journal 已有 code，更不能把已发布 joint_list 改成新 hash。旧 partial 尚未创建 operation 时也须明确沿 legacy joint_list 的兼容规则；只为真正新 session 启用新派生。任一 pending 未核清先停，不先改字段再重试。

当前 Stage=All 在业务操作之前抛 JOINT_ALL_STAGE_NOT_DELIVERED；Resume 要求上下文逐项相同、pending=null；最终 shape 固定19主字段+revocationFixtures/isolationFixtures。应在原基础上完成 AP 阶段，不能删除 All 拒绝后只复用 Ontology 成功。主线程 J9 方案不需要新增“pinApp未发布也就绪”的最终 manifest 字段。

新增 AP journal 在写入前持久保存准确 action、目标、安全请求摘要、候选/版本、已有 requestNo 与 source manifest SHA；成功才清 pending。数据库名/安全环境与 JAR 选择也须绑定本会话运行上下文，不能只保持相同 BaseUrl 就把另一个 PG/模型/环境当可 Resume。unknown create/import/model test 结果不得自动重试或收养同名对象；人工/明确协议核实后才推进。

## 六服务、数据库与 JAR 准入

独立会话 DB 的方向正确，但 schema 清单须包含 **gateway、app、engine、skill、model、monitor 六个**。实际 gateway application.yml 也配置 datasource/Flyway；六服务都要明确本次 DB URL/用户/各自 currentSchema，不能只写三份 OR 凭据 env 就认为其他服务已隔离。现 env 文件内容不能证明运行进程已载入；需由 root 在专窗检查所登记 PID/JAR/启动时环境安全指纹和服务健康。

本轮独立复核源索引33项 SHA 全部与当前源码一致、六 JAR 均与 inventory SHA 相同；这不把 source hash 转换成 JAR 编译来源证明。V222 再次通过 ZIP 原件确认：源码缺 `V222__run_context_pack.sql`，target/classes 与 engine JAR 均含同字节，SHA=`419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`。它是 **target-only 遗留迁移**，不能称 source-only/clean build。计划对 EX-27 债务的保留正确；root 决定使用明确登记的现有 JAR或另行准入构建，不能由 EX-06复制SQL、repair、改history或clean来消除差异。

`start-backend.ps1:140` 即使 SkipBuild 仍先执行 start-database；还会装载全局 .env。per-service 文件优先校验与私钥隔离不等于跳过默认 DB 初始化。因此它不是只读 preflight，不能未核默认数据库边界就调用。其已有服务env支持可复用；若不能安全调用，使用此前已授权的安全 wrapper，由 root 操作，不再造启动框架。engine scheduler 和 skill artifact/service/outbox 生命周期有真实恢复/清理副作用，先完成 DB/对象/Redis归属再启动。

## 下一最小阶段与准确白名单建议

**阶段 A：现在可准备的纯协议薄步**（不接产品/PG/OR写，不改任何产品源码）：

| 文件 | 允许的最小变化 |
| --- | --- |
| `scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs` | 显式 Model 角色/会话已登记 model和tool；OpenAI-compatible models/chat/completions、health_probe、真实 tool_calls 参数、tool结果后二轮终答；loopback、输入/响应/请求数/总寿命/连接有界，原 protocol 角色保持 |
| `docs/evidence/2026-10-29-EX-06/Test-JointProtocol.ps1` | 模型目录/探测/选择/二轮、坏体/越界/未知model/tool/停止的真实 HTTP 向量；保留原25断言 |
| 本卡独立协议 RunId 证据 | 源指纹、PS5 Parser、native退出、实际断言/本次PID关闭事实；红绿真实记录，不为“先红”故意写坏fixture |

模型返回是原生 OpenAI JSON，不能套平台 `{code,data}` 信封。现 `Joint.Common.Invoke-JointHttp` 强制该信封，协议 driver 的模型请求应使用测试内受限原生 JSON 读取或明确的模型专用窄分支；**不得全局放宽原平台/OR envelope 验证**。无需为这个阶段改 Joint.Common/Prepare-Joint。纯协议首绿只能记协议首绿，不能记模型业务验收通过。

**阶段 B：root 补齐规格和运行准入后才进入单技能/三应用**：

- `scripts/acceptance/Prepare-Joint.ps1`：新/旧 opCode 解析统一使用、AP 安全 action/有限等待/幂等恢复、真实三应用完成判据；保留 Stage=Ontology 与旧 partial。
- `scripts/acceptance/Joint.Common.ps1`：仅确需的会话上下文/安全持久化公共逻辑；保持 A008 原子替换修复与严格 envelope，不能为了模型角色破坏现有错误门。
- `docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1`：原32断言不放宽，新增旧 ONTOLOGY_READY/中途partial/派生碰撞/pending停止/All未齐拒绝/环境上下文变更等。
- 六份会话受限 env、partial/安全证据只在已约定本仓忽略目录；内容由 root 管理，秘密不入 Git/普通日志。

此阶段不修改 AppService、SkillCatalog、ModelCalls、EvaluationService、ReleasePins、迁移、启动器或 Redis 传输产品实现。确有产品反例另开准确补差，不能在验收脚本内做假的发布/评估成功接口。

`Invoke-JointAcceptance.ps1`、`Invoke-S1Acceptance.ps1` 已在主卡整体白名单，但不是阶段 A 的写范围；待阶段 B 有真实三应用和最终 manifest，再在各自定义范围实现/执行，不能将本计划或本复核计作 J1–J9/S1 通过。

## 当前指纹

| 文件 | SHA-256 |
| --- | --- |
| ap-stage-plan.md | `44a25235e70e71c1404c6313bf356a3e4e3e6054bf59068ed00467ab0504b671` |
| Prepare-Joint.ps1 | `fce9ce3a4e06d3b01ac9b34f80283de86740c0393a14788cbe8ef29d842fab98` |
| Joint.Common.ps1 | `a008f692d89d18a416b657c9cb3b5f08f804b2bb44205e616253b90db48505cd` |
| Joint-ProtocolFixture.mjs | `31cb04c62461097d9a02b28337184990a2601642ef6e59c0ea336317657a1d4e` |

静态审查接受阶段 A 的实现准备及主线程修订后的 J9 语义；业务运行准入仍取决于上述环境与恢复规则实证。本轮没有替 root 开任何实际运行窗。
