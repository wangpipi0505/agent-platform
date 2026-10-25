# O1b engine 独立只读 status 实施交还

状态：**DONE，待 root 有限审计和 fresh 独立评审**。只交付真实 engine `GET /admin/runs/{id}` 的有限 IAM 分支；不签收 O1c、O2、RR1/RR2、完整 IAM 部署或整体任务完成。

唯一 cwd：`G:/207/项目/算法重组工具/代码/agent-platform`。已读 AGENTS.md、implementation-brief、contracts/README、19771B 正式 brief（`c2749821ad0ff71ccf08c72f35b0dce4308f5a325ed8a925ce8594c7609b260f`）及 4772B 补充（`88c895386a77977920f31af324865cd6eb6d159bbcbb79f4bcdbf7985e68b335`）。遵循已给定设计核对、SDD implementer、TDD、ponytail、Java 技能；未新派发子任务。

## 最终范围与实现

初始完整必要 source578 与 O1a manifest 逐项一致，初始 delta0，SHA 为 `719caa92b7dc1c33ee620e923247bb57bad45cee5c0b9c210401dc9fde463510`。实施前向 root 报精确接缝并获批准；新增 GraphRules 纯规则迁移、IAM status 2 MiB 有界输出裁定在首次生产编辑前记入 `.local/iam-o1b-readonly-status/scope-approved.json`。Json.mapper 已可复用，未修改 Json.java。

最终仅 **9 个既有文件修改、1 个测试文件新增**，完整清单及 bytes/SHA 在 `iam-stage-4-o1b-readonly-status/source-delta.json`：

| 文件 | 实际变化 |
|---|---|
| common RunObservationSelectors | 保留 Q canonical tuple/digest；固定 status action 与 run-snapshot/publicId/acceptedSHA 双向目的限制 |
| common RunObservationSelectorsTest | 原 Q 两方法保留，加 status purpose 错配方法 |
| common LoopbackAuthority（测试） | 可信受理后 registerObservation，按 run/owner/SHA 独立策略，exchange/authorize 同核原 call；按 run deny；不靠全局 allow 冒充 |
| engine api RunController | IAM admin GET 实际 handler/template/URI/dispatcher/query/body gate；同请求同步完整 envelope、有界序列化、实际 stream 后最终检查、IO 部分提交处理 |
| engine application RunService | private constructor ObservedStatus；observeStatus 与原 POST wrapper 共用 composeStatusBody；原纯规则入口委托 domain |
| engine domain GraphRules | 原 requirePublishedFlow 与必要纯 dependency 判断移入 domain；不引入网络/prepareSnapshot/执行依赖 |
| engine infra RunAuthorityStore | package-private 纯 PG loadObservationBinding；Q 顶层独立 wrapper、strict、原持久执行授权规则保持 |
| engine infra TraceStore | 封闭 StatusReadProof、ObservedView；顶层只读 RR、全部 scope 先验、snapshot/refs 内容关系、同 RR 固定读取与组装；原 PublishedView record 保持 |
| engine infra MaterialScopeStore | package 内固定 readObservedValue，复用原 EXACT/normalize/digest/固定 SQL；VA reader 和 writer 不放宽 |
| engine infra RunObservationStatusHttpPostgresTest（新增） | 真实 HTTP/security/handler/服务/PG、Q→fresh M、SQL/wire/各窗口/输出上限/部分 IO 共 17 方法 |

生产关键点：RunController.java:71；RunService.java:1590；TraceStore.java:351、386；MaterialScopeStore.java:520；RunAuthorityStore.java:105；GraphRules.java:156。无 POM/依赖、迁移、前端、gateway、catalog、ServiceAuthorization、O1a API/service/domain、生产 IAM 配置、部署或业务 commit 变化。

M 仍是 delegated PORTAL/PORTAL，固定 caller=gateway、target=engine、action=`proxy.engine.get.RunController.status`。Q 七字段 DTO 仅数据；fixture 真正请求 O1a HTTP 后，以同一 originalPortal 独立 exchange M，不把 Q forward/resume 为 M。此 fixture 扮演配置好的 gateway client，不是 O1c 实际 gateway 联合 GET。

StatusReadProof 包内可见但 constructor private，唯一 mint 在 TraceStore.statusObserved 中：真实 live retained/frame、PG owner/run/root/acceptedSHA、完整 scopes 集合及当前覆盖均检查后。ObservedView/ObservedStatus 也只有 private constructor；上层仅保留终检能力和状态数据，不暴露 raw M/grant。每份材料读前后、实际 RR commit 后、compose 后、serialize 后、实际 getOutputStream 返回后紧邻首次 write 均检同一 capture；刷新替换/关闭/跨线程不能复用。原 public PublishedView record 不改为观察 mint 入口，原 POST/local writer 用途独立。

入口先拒绝 ambient，再核 signed status resource，事务外刷新同 M。单 DataSource 顶层 readOnly REPEATABLE_READ 内先读窄 owner/TASK/FULL/unsupported/tools 控制、原 authority 与三根 manifest，再枚举全部 run/node/event 及最高 revision/父 scope/floor/实际 locator，requireComplete 和当前覆盖全部预检后才第一 payload。先用只读 proof 读 snapshot/refs，核真实摘要及 appCode/root、candidate、versionId/appVersionId/no/key、refs.appVer，再用同份 domain 纯 FLOW gate，随后同 RR 读其余材料。原 grant/initial/origin、blocked/check 不续期、不清除、不唤醒 worker；历史 provenance 不与新 M decision/version 强行相等。

原完整 status 字段、node 别名、event seq/type/payload/created_at、SQL NULL/JSON null、时间和 PG JSONB 大数小数均保留。旧 POST 与新观察只共用固定材料组装和 compose 算法；不读 attempt payload、不查当前发布指针、不解析运行依赖。

## 真实定向执行与最后适用覆盖

所有 Maven 命令均为唯一 ROOT cwd、未修改 `.tools/run-iam-module-tests.mjs`，固定 Java21、离线 `.tools/m2`，selector 只用合法逗号/`#`，无 `+`。每条运行前完整 source SHA 与 baseline 变化 overlay 已保存于自有 phase 目录；运行期间全部 source 冻结。完整命令、cwd、exit、实际 Running、结果原行、source manifest、ready/close 见 `commands.json`，不以构造/编译错误计行为红。

| phase | 实际结果及分类 |
|---|---|
| red01 | 新关键正例 1/F1/E0：真实 engine HTTP 403、期待200，缺能力行为红；真实受理/FLOW/节点/事件/scopes、Q HTTP 已建立 |
| green01 | 同关键正例 1/0/0，完整 status、单 RR、实际 payload16 |
| red02 | 两方法：cap 正例/越界已通过；stream 后失效真实 IOException（成功 Content-Length835，错误体只89）为产品 framing 失败；不是 setup。随后最小移除 IAM 成功 Content-Length，沿标准 chunked |
| green02 | 新类12方法，10通过、2 setup 错误：真实 revision/version CHECK、删除 run 的 FK 挡住破坏 fixture。日志保留，未当产品红 |
| final01 | common selector3/0；engine 原 GraphRules1/0、原 status2/0、O1a四具体方法4/0；新类16中13通过，另两个 fixture setup/一个拒绝层 oracle（真实401，原期待403） |
| final02 | 校正 fixture/client 固定 allowlist 和合法 DEBUG 行、401/403真实拒绝 oracle 后，新类16/0/0 |
| red03 | 新 IO 方法1/F1/E0。实际 peer 收到部分 HTTP200 JSON，但原 delegate close=true 指标失败；后来确认该指标不符合 MVC lifecycle，不能把它描述为更新日志断言的同一红 |
| final03 | 新类17中16通过、上述 delegate close 指标仍失败。生产已增加 best-effort close、primary/suppressed 日志及已提交时停止，MVC 的 ERROR 状态实际拒绝下达 delegate.close |
| final04 | **新类17/F0/E0**，更新 oracle 核 primary write cause、suppressed close 拒绝与 actual unknown 日志，同时真实 peer128业务字节/EOF/不完整 JSON，无重试/补写 |

最后适用覆盖是 **final04 新17**，加生产相关段未变的 **final01 common3、GraphRules1、原status2、O1a4**；不是一次合成的27全绿。final01 以后其他生产相关段未变，仅 IAM GET IOException 分支及新增测试修改。`last-applicable-coverage.json` 给实际 XML 定位和 SHA。

O1a 回归方法为 `notFoundAndOtherOwnerHaveTheSame404WhileOwnedLegacyHas403`、`damagedControlMetadataAndMissingOrInconsistentRootManifestsFailClosed`、`ambientTransactionIsRejectedBeforeOnlineRefreshAndLegacyAssemblyNeedsNoIamBean`、`currentRevocationOutageAndExpiryBlockBeforeSqlAndNoLegacyFallback`。原 status 两方法为 `explicitJsonNullOutputKeepsOriginalPresentationDistinctFromSqlNull`、`actualRunServiceStatusReadsOriginalMapInOneReadOnlyRrAndPreservesAliasesAndTimes`。没有重跑旧全套/旧主 live helper，未通过兄弟业务 JAR 或反射兄弟 target 装配。

各次原 runner log 均保留，初始红、red02、final01以后 XML 分阶段保留；green02 未单独复制其当时 XML，完整 setup 栈和原计数仍在原 runner log，未为补档重跑。Shell 两次 PowerShell 参数解析错误未执行测试，未当任何红绿结果，原工具输出在对话；没有改写已执行清单。

## 关键实际观测

专用 PG `agent_platform_iam_o1b_status_v12`，真实 PostgreSQL16.15/V200–217，共18业务迁移；fixture 从生产 createPublished/createNode 和实际材料 writer 建根、节点、事件/scopes。实际 engine Spring HTTP、security guard/coverage、RunService/TraceStore/MaterialScopeStore，真实签名 Loopback/JWKS。RuntimeClients app/model/skill 全指同进程自有失败端点并观测计数；scheduler 仅直接构造/绑定，未启动。普通正例 DB run/node/attempt/event/tool 计数与依赖端点调用均不变，未以“零 beans”推断调用为零。

新 M 可读 PG 旧执行 grant expiry 已过期、blocked 的 run；测试设置该旧 expiry 使用专用库 immutable 绕过，读前/读后原 grant/initial/origin/blocked/check 相同。新 portal 的初始角色/region/label 与原受理不同，fixture 显式重建并关闭 gateway client 以匹配固定可信 allowlist；不声称生产支持动态更新 allowlist。

正例实际 payload16、单 connection/isolation4/readOnly=true/autoCommit=false，全部实际 SQL 在精确完整字符串 allowlist 中；同次SQL的 authorityCalls 保持在入站+刷新两次之后，无事务内新增 HTTP。只允许原 authority `a.*` 控制投影，其他 SELECT 不用宽 `.*` 保护 payload 列。SQL 监听包含实际执行所有语句及连接属性；case 日志还包含之后的夹具事实核对/恢复写入，不把那些误称 status 只读 SQL。

缺 root/nullable/node/event scope、错误 owner/父链/digest/revision/floor、收窄当前覆盖、unsupported/controlled/tools、签名资源错配等，在对应层拒绝，预检负例 payload0。snapshot 实体损坏、合法 resource_digest 单点替换与 candidate 不等、refs 损坏在读后拒绝，实际 payload>0、业务 status wire0。正常 immutable 拒绝与专用库临时绕过/恢复均保留。合法 DEBUG 三元组、public_id 改名只用于隔离破坏夹具，不是普通业务迁移或生产 CHECK 放宽。未声称能发现管理员协调重写所有可信 PG 元数据再获新签名许可。

受控并发 writer 是普通零写断言的唯一明确例外：第一 snapshot payload 后，另一真实连接/只写事务合法更新 output/event_seq，新增 event 和相应 revisions/scopes 并 commit；读者仍返回 before output、水位1、旧1事件，库内水位已2，读连接唯一 RR，未拼新旧。实际 SQL 原件在 behavior-observations.json。

manifest、payload、真实 commit、compose/mapper开始、serialize后、真实 `getOutputStream` 先 super 获取再推进 clock/关闭/替换 frame 的窗口分别观测。manifest 失效 payload0；后续窗口 payload>0，全部首次 status 业务写出0且 HTTP 错误 envelope 完整。raw `?` 原样到服务端、重复/raw query/body、实际 forward/alias、缺 guard startup、Q/POST/local 与 status 用途分离、actual JWT caller/target/source/origin/workload/SHA 拒绝均覆盖。caller/target/workload 在 decoder 为401、其他多为guard403，wrongSHA在线一次后403，如实记录层次而非统一冒称403。

root 新批准的 **仅 IAM admin GET** 序列化 buffer 上限2,097,152 bytes：完整原 ApiResponse、UTF-8非ASCII及转义、真实PG/HTTP恰上限成功，多1 byte稳定 `RUN_STATUS_TOO_LARGE`403，buffer实测最大2,097,152。超限已读 payload>0，但 status 业务写出0；不清 blocked/check、不截断/自动重试。此为新增边界，不是原 GET 已有 cap；legacy/POST 不套用。toByteArray 有界副本可再占约2 MiB，原材料和对象图未因此有界，不能声称整个请求堆最多2 MiB。

部分 IO：实际 peer收到 HTTP200/chunked，raw456 bytes，其中业务128，JSON被截断，随后EOF；未追加错误/成功 JSON、未重试。Spring6.2.7 lifecycle stream 在 write异常后进入ERROR，best-effort close 本身被MVC拒绝，所以 **delegateClosed=false**。生产保留主异常及 suppressed close 异常，日志 committed/attemptedBytes/actualBytes=unknown；测试日志 appender核对应 throwable 链。此保证不等于收回已发字节、阻塞write内连续撤权或物理硬断。

## 读源、自审、资源和封存

`upstream-read.json` 仅列本作者实际窄读的六份本工程固定归档原件、bytes/SHA/官方固定tag URL/具体行段：Spring6.2.7 doBegin/prepareConnection/processCommit、MVC handler adapter、StandardServletAsyncWebRequest 251–474，Tomcat10.1.41 first doWrite。没有下载新版本、重读完整库或声称未读机制。源码注释对应事务/commit/stream与部分IO实际窗口。

自审核对：仅 admin IAM GET；原 PublishedView record 保留；private mint 与固定 reader 无 writer 泛化；Q/observer/历史 root 分离；所有 scope 在首 payload 前；读取后摘要/候选/app/version/refs 与纯 FLOW 再核；单RR；完整输出及新增cap；实际stream后检查；IO部分提交；source与对应 target 归因。未发现需扩大范围的产品缺口。legacy 仅有限直接构造/原POST reader回归，未宣称 legacy 整服务完整boot。

最后实际 ready：PID **50120**，engine port **65250**；`O1B_RESOURCES_CLOSED=true`，独立实际 probe PID=ESRCH、engine TCP=ECONNREFUSED，见 resources.json。HTTP client、decoder、authority/JWKS/resolver、依赖 HttpServer、RuntimeClients、scheduler/context 均同一自有 JVM 显式关闭，无子应用进程。单独 authority/resolver/dependency 端口未逐个打印/探测，不作该声称；JVM已退出。PG服务器及专用DB保留供审计，不影响既有项目PG生命周期。

完整 source **579**（后端387+前端192），相对 O1a delta **10**：`source-after.json` 109789B，SHA `a956230f2e97c552e026196748fb67519c77acc1bcfaeee747df744cac25f8de`。target 按 O1a1948 谱系继承，只读实际 compiler列表/具名report候选 **383**，实际 delta **52**（新增20），逻辑 **1968**：`target-after.json` 406646B，SHA `06e1fa0bd59c8e95e0f54a11cc59960d48f5f3d6670d958be4950decc49932f6`。没有扫旧全target/JAR/node_modules、复制旧全证据或打包/部署JAR；前端dist15沿D4b继承不碰。

source/target delta 与 frozen-overlay/target-overlay、compiler归因和 composition 已封存。重放实际ROOT cwd，私有 bare evidence repo `info/attributes=* -text`、core.autocrlf=false，只用index/write-tree，无commit；真实.git/config/.gitattributes不动。replay先置原9份BEFORE字节，再显式自有 `--directory` check/apply；**首次各一次exit0**，无重复apply/直接copy目标伪作重放。完整579逻辑names/bytes/SHA一致，物理只10份delta，见 replay.json（662B，SHA `9027b8cd528b5d1dfd9e01775f797b6ce1ab21604d1ad5d70781532a40b79bde`）。

所有 source、对应 target、自有 `.local/iam-o1b-readonly-status/`、evidence目录及本报告写权在最终 inventory/handoff 生成后全部冻结交还root。最终 inventory对自身与detached handoff说明明确排除，避免循环hash；精确bytes/SHA另以handoff给出。root八执行索引/review/state/未来brief始终未写。

外部真实 IAM/模型/本体/K8s、O1c gateway最后一跳、O2长流、RR1/RR2及整体D-E-F/17功能34任务30验收仍未验收。compiler unchecked、Flyway schema history提示与部分日志中文编码损失保留；实际HTTP UTF-8/数值断言由原测试执行，未改日志装作完美结果。
