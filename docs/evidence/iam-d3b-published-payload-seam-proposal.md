# D3b：真实已发布应用 payload 接缝提案

状态：只读准备，待 root 正式 brief；没有生产修改或测试执行。唯一工作根为 `G:/207/项目/算法重组工具/代码/agent-platform`。本文件是唯一新增产物；原 `iam-d3e-first-published-path-proposal.md`（972e08）与 `iam-stage-5-e1b-persistence-seam-proposal.md`（6e222）保持只读。

## 1. 结论与边界

既有方案足以继续，不需要新授权框架。紧接 E1b 的最小 app 切片是：在现 `AppController.published` 的真实 `GET /internal/apps/{code}/published` 内接入一个 `AppService` 安全读取方法，以真实 app 元数据重建 main root，事务外完成 current 刷新，短事务内锁 application、重核 pointer/status/版本元数据、局部 proof 终检后才 SELECT snapshot；提交后返回前再局部终检。继续使用现 JSON 返回投影。

这只是 app payload 组件。E1b 三种初始材料不覆盖 nodes/events/output/error/checkpoint/status/streams，不证明真实 RunService/FLOW 首链成功。生产 `iam` 全局启动闭锁不得由此解除；未迁移其它 payload 路径和未签收 authority source 仍是后续依赖。

本次只读了明确允许的 app 文件、E1a 正式 brief 与 `docs/evidence/iam-stage-5-e1a/frozen/admin-common`；没有读正在修改的 common/engine 新文件、target，没有 PG/网络/进程/测试操作。以下 API 来自 E1a 冻结，不假设 E1b 未交付接口。

## 2. 已核真实入口与共享风险

| 真实入口/方法 | 现行为与边界 |
|---|---|
| `AppController.published` → `AppService.published` | `/internal/apps/{code}/published`；catalog caller=engine、target=app、action=`app.published.read`、delegated、integrationAllowed=false。此入口是本次安全接缝。 |
| `AppController.snapshot(code,draft)` | `/internal/apps/{code}/snapshot`，action=`app.snapshot.read`。draft=false 调 published；draft=true 先 get 再 frozenCandidate。它同时承担发布别名和调试草稿，不可改成统一 published action。 |
| `AppController.version` | `/internal/apps/{code}/versions/{version}`，action=`app.version.read`；先 get 检查停用/归档/current pointer，再读请求的历史版本。它不等价于“仅当前已发布版本”。 |
| `AppController.candidate` | `/internal/apps/{code}/candidate?draftRev&candidateSha256`；`app.candidate.read`，engine/monitor 各有登记，用于调试/评测冻结候选。 |
| get/flow/adminVersion/versionDiff | general app/draft/admin 历史版本用途，共享 get/version。不能强迫它们取得 engine 的 published 权限。 |
| `AppController.inputSchema` → `publishedInputs` | `/v1/apps/{code}/input-schema`；可显式 versionNo，并提取 definition/config 的 schema。仍是 payload，不是免费元数据。 |

`AppService.get(code,false)`（约124行）实际 JOIN `app.app_draft` 并 SELECT `a.*`、draft config/definition/layout；row mapper 还解析这些字段并计算 candidate SHA。`get(code,true)`只 `FOR UPDATE OF d` 锁 draft，不锁 application 发布指针。两者均不能放在授权前当元数据检查。

`AppService.version`（约485行）立即 SELECT `id,snapshot::text`，解析后把原 snapshot.appVersionId 字符串保存在 appVersionKey，将 appVersionId/versionId 都替换为数字行 ID。`published`（约560行）先 get，再检查 DISABLED/ARCHIVED/currentVersionNo，再调用 version；这就是当前预读漏洞，不能只在 version 返回后补授权。

`PublishedMetadataService.published` 已有正确的无 payload 单 SQL投影：application LEFT JOIN 当前 app_version，连接同时包含 tenant_id/app_code/app_id/current_version_no，返回 code/versionId/versionNo/candidate_sha256。它调用的是 `AuthorityContext.requireFor(...MetadataSelectors.published(...))`，**不能直接从 main payload 方法调用它**，否则把 main root 与 query-selector 能力混用。可以在现 AppService 内复用其查询形状；无需新增公共 selector/grant/metadata JWT。

## 3. root、数字 ID 与 SHA 的准确绑定

收到的实际服务票必须由现 TrustedJwtDecoder、ServiceAuthorization 和实际 MVC HandlerMethod 校验。随后使用 `AuthorityContext.current()` 的已验 call/actor/operation：caller=engine、target=app、action=app.published.read、resource.kind=app-version、originKind=PORTAL；不能从 body/query/header补 caller/tenant/root。普通 delegated forward 不意味着 sourceKind 必须是 DELEGATED；PORTAL 来源 main operation 转发后可仍是 PORTAL，真实 resume 才是新 DELEGATED。两者不得因名称被误拒或放宽 origin。

真实绑定是：

- tenant 来自已验 current actor；URL code 只是查询定位，必须与该租户真实 metadata及 signed root一致。
- metadata `a.id` ↔ `v.app_id`，并核同 tenant/code、`a.current_version_no=v.version_no`。
- 重建 `ResourceBinding("app-version", code+"@"+versionNo, storedCandidateSha256)`；与收到的 main call.resource 全等，再调用 E1a requireInitialFor。
- 数字 versionId 是 app PG 的 `app_version.id`，不是 signed root 的 id 字符串。现 root 没有独立数字 ID 分量；不能声称 body中的 versionId 被签名。用实际 PG行、app_id/no/SHA关联确定数字 ID，并在锁内复核数字 ID未变，返回既有数字投影。后续 engine 必须与其已取得的真实 metadata四元组比较，不能只看 code 或 request versionNo。
- `candidate_sha256` 是发布时保存的候选摘要。commit 先做 candidate，再附加 versionNo/draftRev/candidateSha256、`appVersionId=code@no`、interpreterVersion 和冻结 model refs后写 snapshot。不能把返回JSON整体 hash 当这个 stored SHA；E1b snapshot bundle digest 是另一层含义。

现业务判定是 DISABLED/ARCHIVED拒绝或没有当前版本拒绝；不能擅自收紧为仅 status=PUBLISHED。DRAFT但仍有真实pointer的状态沿原判定处理。现代码没有“只能created_by本人读取”的契约，不能凭空新增 owner条件代替 authority。

## 4. 最小读取时序与事务边界

建议仅在 AppService 新增 `publishedAuthorized(String code)`（建议方法名，尚不存在），方法内部窄 metadata record/private helper 即可；不另造发布服务/driver/授权 receipt。

1. **入口与无锁阶段。** 检查当前实际 call形状、origin、code格式；无 frame直接失败。用该 actor.tenantId读上述窄 metadata；不能调用 get/version/PublishedMetadataService。无应用404；停用/归档/无pointer或悬空版本409。若 signed root指向同code旧no，而当前pointer已变，返回409，不重试另一版本、不重新exchange。错误kind/code/同no错误SHA为权限不匹配403；不返回payload。
2. **事务外 current。** 重建真实 root后 `retained=AuthorityContext.requireInitialFor(actor,root)`。它在已绑定实际请求frame上刷新一次，复核同call/grant/operation/version/full initial/origin；403/503/过期直接退出。立即使用 refreshed actor，不恢复初始Actor做授权。这里不持PG行锁，不开包围网络请求的事务。
3. **短本地事务。** 为这一读取使用独立、有限超时的 TransactionTemplate，不修改 AppService 现共享写事务模板。入口拒绝已经存在的实际业务事务，保证上一步不在调用者锁/事务内；不要仅依赖默认 REQUIRED或用自调用@Transactional假定生效。使用 READ_COMMITTED（显式）以及实际事务管理器；不设置 PG readOnly=true 后再执行 FOR SHARE。
4. **先锁 application。** 窄 SQL `select a.id,a.code,a.status,a.current_version_no from app.application a where a.tenant_id=? and a.code=? for share of a`，不读 draft/snapshot。获取锁后 `AuthorityContext.requireBound(retained)`，检测等待期间过期/请求失效。设置有限事务/语句/锁等待边界，失败不重试/不续票。
5. **锁后重新读 metadata。** 在同事务下一条 SQL重新 JOIN当前 app_version的窄列，复核状态、a.id、pointer、v.id、no、stored SHA与步骤1观察值完全一致。不要把“含 LEFT JOIN 的单条 FOR SHARE语句等待后返回的 join列”假定为全表同一最新快照；先锁再单独窄查询使等待后的 READ_COMMITTED语句明确重读。所有合法发布/rollback/disable/archive都更新该 application行，需与此锁串行；app_version按现生产契约不可变。缺行/指针变化409，错误root拒绝。若发布期间status改变，保守返回409让调用者重新发起，不隐式选择别版。
6. **payload前终检。** 再 `requireBound(retained)`；仅随后 SELECT snapshot，谓词包含 tenant、app_id、app_code、数字id、version_no、candidate_sha256，使用步骤5的真实值。不用旧version(code,no)这个宽helper。payload SQL返回0行或结构身份不一致则拒绝，不回落草稿/旧版本。读取后可以验证snapshot的appCode/versionNo/candidateSha256/原appVersionId与metadata一致，并按原version()投影返回；这些是payload完整性检查，不能冒称它们发生在payload SELECT前。
7. **事务返回。** callback结束前再 requireBound；正常提交后释放行锁。`tx.execute`真正返回之后、服务方法返回Map之前再次 requireBound。异常路径不把缓存在局部变量中的snapshot转成成功响应。AppController随后用原ApiResponse.ok封装，保持 schema与数字字段语义。

所有online authorize均在步骤2及HTTP preHandle发生；步骤3–7只用E1a本地requireBound，其内部私有Clock和对象身份检查不能被业务传Clock或重新bind绕开。callback不能用 requireInitialFor/requireFor续期；提交后也不能换新proof冒充同一读取。旧对象在refresh替换、异常失效、frame关闭/跨线程后均不可用。

锁保护的线性化点是payload读取时的同应用pointer/status。提交后另一事务合法发布新版本，不意味着已读旧版本要自动切换；不能声称直到客户端收完JSON都锁住pointer。提交后本地终检覆盖到“服务返回前”；Servlet实际序列化/网络写出的更晚时刻不是本段可证明的原子撤权点。本方法返回完全物化Map，不返回lazy流/ResultSet/异步回调，不持锁跨网络。后续若需要发送期guard必须单独明确边界，本提案不借机引入stream框架。

snapshot内部config.guard还不是独立元数据列；这里依靠真实 main root current授权完成payload前校验，不声称通过提前读取config来验证config.guard。原RunService的guard与后续材料覆盖检查仍需保留。若正式IAM要求把该内嵌guard也变成SQL读取前的独立政策前提，需要已有可信策略投影接入；不能偷偷把payload预读当元数据解决。

## 5. 实施文件、装配与别名闭锁

最小生产修改为两文件：

- `app-management/src/main/java/cn/agentplatform/app/application/AppService.java`：新增上述受保护方法、窄metadata/helper和专用短事务模板；现get/version/published/publishedInputs/frozenCandidate语义保持。明确禁止受保护方法在已存在业务事务中执行，避免锁内联网。
- `app-management/src/main/java/cn/agentplatform/app/api/AppController.java`：仅真实published入口选择受保护方法。通过构造注入既有`AuthorityProperties`的已验证部署mode；iam必须走新方法，显式dev-hmac/test走现legacy published以保留原可运行行为。不得catch缺frame后降级；不得请求参数开关；未知mode失败。原只有两参构造的测试装配应显式补properties，不保留会隐式关闭保护的便捷构造器。

此mode分支不是新功能开关。冻结SecurityConfiguration已由authorityModeGuard绑定/校验并注册authorityProperties；production iam仍抛IAM_TRUST_CHAIN_UNAVAILABLE。isolated actualHTTP组件测试可按既有PublishedMetadataHttpTest模式显式装配iam属性与真实security/guard，不能仅开test profile导致请求误走legacy分支。若正式作者采用更窄的现有装配方式，也须等价证明“真实已验service请求必走安全方法、缺frame不降级、legacy仅显式旧mode”。

不需要修改catalog的158 actualHTTP集合、Common E1a API、POM、迁移、application.yml、六索引或PolicyResolverSecurity。现app机器policy入口为精确POST独立链，不可借它permit `/internal/**`，也不可让新链抢占它。

**有限组件通过不等于可以打开整个AppController的IAM服务。** snapshot/version/candidate/admin get/input-schema各自正确签名的能力仍需各自迁移，普通ServiceAuthorization只证明route/call，不能补这些方法缺失的资源前校验。因此本次保留全局生产闭锁；隔离组件receiver只对正式测试入口作有限调用，不将其作为部署模板。发布票访问其它alias必须由真实guard拒绝，但这只能证明action隔离，不能冒称其它alias自己的票已经安全。后续总装配必须在guard后对尚未迁移handler明确关闭，或逐条补齐其实际语义，再解除整体启动门禁；不能把所有ANY proxy统一豁免。已有D3a0元数据组件继续单独验证，不把metadata grant传给payload。

真实joint后续还需修改gateway与engine以走已登记published目的、主Grant保持独立、对数字ID/no/SHA做同一root比较，并完成材料/status/scheduler。当前E1b作者source归还前不得读取其新实现推断通过。

## 6. actualHTTP + PG 的有限验收设计（尚未执行）

新增建议测试 `app-management/src/test/java/cn/agentplatform/app/application/PublishedPayloadHttpTest.java`，复用现PublishedMetadataHttpTest的isolated Spring receiver、LoopbackAuthority、TrustedJwtDecoder、SecurityConfiguration.serviceSecurity、ServiceAuthorization.mappedInterceptor、RouteCoverageVerifier与真实AppController/AppService、DataSourceTransactionManager、真实app迁移。不使用假target handler、不引业务JAR、不用H2/Map替代PG。已有测试明文env读取是将来授权测试执行的部署输入，本次没有读取凭据或运行该测试。

fixture链用保存的同一原PORTAL source，先gateway exchange metadata call取真实metadata，再gateway exchange真实 `engine/proxy.engine.post.RunController.run/app-version` main call，engine authorizeRetained/forward生成app.published.read目标票；发实际HTTP到AppController.published。main/query Grant不同而origin一致，app子call沿main Grant/root。此fixture步骤不运行RunController，不计作engine受理成功；不另造metadata JWT或RunController替身。

至少保留下列有限对照，使用可调可信Clock、少量CountDownLatch、每个await明确超时并finally释放/关闭，不sleep轮询或扩大压力：

| 场景 | 必须观测 |
|---|---|
| 正常已发布真实行 | HTTP200，原ApiResponse和完整snapshot，数字appVersionId/versionId等于PG真实id，appVersionKey=code@no；存在合法current对照。 |
| 无票/portal直传/integration/workload/错caller-target-action-method、伪签名 | 401/403；实际业务metadata/payload SQL均为0（若在入口guard拒绝）。 |
| metadata-selector或同root的metadata action访问published | 403且payload SQL=0；query/main Grant不互换。 |
| 错tenant/code/kind/SHA，缺proof/手工bind VA | 拒绝；错误call形状在业务SQL前拒绝；允许为核root读取窄metadata，但draft/snapshot计数始终0。 |
| current第二次检查403/503/到期 | payload SQL=0；事务写0；不能吞异常返回旧Map，不能重新exchange。current收窄但仍获真实授权的对照不强制失败。 |
| 正常请求计数 | preHandle一次current查询+显式requireInitialFor一次，锁内/提交后网络查询0；三个payload前metadata查询可解释（无锁join、锁application、锁后join），不夹杂get/a.*/draft。 |
| 两阶段之间pointer变更或disable/archive | latch停在online刷新完成/开启锁前，用真实PG并发事务改pointer或status；旧票409、payload=0，绝不返回新版本；新请求新metadata/main可合法返回新版本。 |
| metadata查询之后、online刷新尚未返回 | 暂停fixture current响应，并发PG更新application可以完成，证明网络等待时没有持application行锁。该请求恢复后409。 |
| 读取已持FOR SHARE | latch暂停在锁后窄metadata/读取前；另一PG连接更新pointer/status在有限lock_timeout内失败或等待，reader恢复返回同一旧版本；释放后writer才成功。不能以单线程模拟锁。 |
| 等锁期间proof到期 | 用第二连接先持application写锁，等reader尝试获取锁后推进可信Clock越过proof截止，释放写锁；reader锁后requireBound失败，payload=0，original source/operation截止不延长。 |
| payload之后/commit前、commit后/服务返回前到期 | 分别用真实JdbcTemplate/DataSource或事务管理器测试装饰器闩锁暂停，不替换业务实现；推进Clock后本地终检拒绝；payload可能已读但HTTP无成功data，不能谎报这两例payload计数0。 |
| 不可用、missing、DRAFT有pointer、显式旧mode | 保留既有404/409及合法旧行为；不以只允许PUBLISHED掩盖回归。draft/eval/general方法在legacy回归内保持用途。 |
| 发布票访问snapshot?draft=true、candidate、version、admin get/input schema | 实际guard按各自action拒绝、payload=0；不改catalog扩大published能力；不把这组结果当其它能力的资源保护已实现。 |

SQL观测应在真实JdbcTemplate/DataSource装饰器记录实际执行语句分类及连接事务状态：metadata只列窄字段；payload计数包含snapshot、draft config/definition/layout、a.*、version helper。不能沿旧测试的“不是某种left join就统算payload”粗分类，否则新增锁元数据会误报。计数器/闩锁只在测试，生产不加可调用故障hook。

用真实PG检查读事务完成/失败后锁释放；本段无业务写，不虚构新run rollback测试。受理run/authority/initial scope同事务rollback属于E1b及joint测试。有限test事务管理器在super.commit已完成后暂停再推进Clock，可确切测postcommit requireBound；不能只在callback内测试就宣称覆盖提交后窗口。

## 7. 交接与未完成项

无须新增接口决策即可拟正式D3b brief；核心方案已充分明确。author应以本文件的两生产文件范围和真实入口测试先实现，保留production iam启动闭锁。若希望同一切片打开完整app IAM，则必须另行纳入其它handler资源保护与实际装配范围，不能把本提案改名当已完成。

本稿没有应用测试结果、PG状态、迁移号或端到端成功承诺；未改原计划/v1.2/四契约、旧两份proposal及任何source/target。final SHA在交接消息中报告后本文件不再编辑。
