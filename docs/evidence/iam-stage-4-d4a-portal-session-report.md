# D4a 有限门户会话后端交接报告

状态：实现已冻结，源码/target所有权交还root，待新的独立复审。不是生产IAM、浏览器或整个D/E/F/G验收。唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`；所有shell均显式使用该workdir。

## 结果与范围

实际提供精确 `GET /auth/session`，成功为既有 `ApiResponse<{tenantId,principalId,roles,expiresAt}>`。仅有限、显式导入的 `GatewayIamAssembly` 安装真实入口；没有开启全局IAM、permitAll或生产profile。新controller通过Optional client兼容正常非IAM启动，旧run-only assembly及原构造器保留。

依据完整读至EOF的D4a brief及随后root明确批准的 `iam-stage-4-d4a-verifier-scope-addendum.md`；实际SHA见本目录 `requirements.json`。AGENTS、implementation-brief、contracts README、root decisions及实施计划§2.7已读。使用TDD、Ponytail及implementer-prompt约束；没有提交、HEAD/remote/index变更、依赖/POM/SQL/前端/旧报告/runner/发布打包改动。

唯一11个源码位置：

1. `admin-common/.../CurrentAuthorityClient.java`：固定 `portalSession(String)`，默认503，无普通authorize/public VA/currentOnly转成功proof通道。
2. `admin-common/.../HttpCurrentAuthorityClient.java`：真实门户验签、固定资源和self-call，私有session proof与四字段DTO，最新authorize时钟/单调预算。
3. `admin-common/.../AuthorityResponseVerifier.java`：后批准的第11位置；仅会话可临时内部保留已签elapsed claims，原verify/pinned默认路径仍503，不更改其他action语义。
4. `admin-common/.../GatewayIngressBoundary.java`：固定SESSION purpose、同bean/declared method和一次性claimSession，保留RUN purpose。
5. `admin-common/.../RouteCoverageVerifier.java`：精确session覆盖计数/身份，所有mappings与原两条ANY继续核验。
6. `platform-gateway/.../GatewayIamAssembly.java`：可选真实session bean的固定安装，保留实际outer FilterChainProxy、servlet registration证明。
7. `admin-common/src/main/resources/agent-service-authority.json`：只增加精确GET、gateway→gateway、portal.session.read、delegated、integrationAllowed=false这一行。
8. `admin-common/.../LoopbackAuthority.java`（test）：只为新action添加窄资源/grant/source策略及有限时钟/JWKS/空封装fixture。
9. 新 `platform-gateway/.../api/PortalSessionController.java`。
10. 新common `PortalSessionAuthorizationTest.java`。
11. 新gateway `common/authority/PortalSessionHttpTest.java`。

完整准确路径、前后bytes/SHA及新增/修改分类以 `source-delta.json` 为准。新增3、修改8，无删除。第11文件批准前的物理原件为 `.local/iam-d4a-portal-session/verifier-approved-before.java`，真实补采时点/SHA见 `verifier-later-approval.json`；没有回填最初10文件捕获。

## 私验与实际释放边界

资源严格从TrustedJwtDecoder成功验证的门户来源派生，继续使用AuthorityProtocol.digest/canonical。subject域固定 `portal-session.subject.v1, verifiedIss, tenantId, principalId`；authentication域固定 `portal-session.authentication.v1, verifiedIss, verifiedClientId, verifiedJti, verifiedIatLongSeconds, verifiedExpLongSeconds`。不接受外部resource/tenant/subject/target/action/Clock。复用原portalExchange路径；其内部仍自行验签同一原始token，不依赖初次解码的未验内容。

真实exchange只交换gateway self-call ticket，随后同一ticket真实authorize。移交前以及ticket decoder之后、实际authorize query之前，均核exchange仍有效。对初始ticket和retained evidence核主体/resource/call/caller/operation/grant/真实grant expiry/initial ceiling/origin authentication ID；source及origin必须PORTAL，沿用nonce、请求摘要、ticket jti、authorizationVersion及within收窄规则。

只有上述HttpCurrentAuthorityClient私有路径能构造final PortalSessionAuthorization；构造器私有、没有capture/from(VA)、ThreadLocal、外部Clock或JWT字段。公开SessionView只是数据。角色为mapped issuer ceiling与当前允许角色交集，空集合合法；签名证据扩张仍拒绝。

UI expiresAt为真实 `min(portal exp, grant exp)`。本工程Json的JavaTimeModule及关闭WRITE_DATES_AS_TIMESTAMPS使其输出ISO-8601 UTC Instant字符串（例如隔离Clock的 `2026-10-20T00:00:40Z`），不是epoch毫秒或五秒current寿命。

输出proof使用owner验证Clock，nano预算锚定最新authorize query开始的Clock/nano观测；界为来源、真实grant/ticket/operation、最新current与maxDecisionSeconds。旧exchange只约束移交，不陪同整个响应。Clock回退不能延长预算，任何失效被永久记住，owner close立即失效，一次requireVerified消费后不能复用。

真实释放位置在同步void controller：必须claimSession→私验→完整Json.write(ApiResponse.ok(view))及UTF8编码→取得真实ServletOutputStream→proof.requireVerified→首次write(byte[])→flushBuffer。没有MVC返回值、deferred结果、异步worker或可绕过proof的成功writer；异常在成功写出前resetBuffer并写既有错误封装，成功和错误都no-store。IOException直接终止同步handler，boundary finally/afterCompletion清理入口capture。此边界不承诺撤回已发字节或任意慢客户端的物理强制断开。

来源签名/用途/日期无效及已验来源在途/首写前到期为401。可信当前拒绝或grant/ticket/operation/current截止为403。IAM不可达、unknown kid/坏签名/坏结构/未来日期、空unsigned封装和缺能力为503。会话专用verifier只暂时容许“已流逝exp”，仍复用同一个私有Nimbus签名校验实现；query先核完整nonce/request digest/client/call/requestedAt/active/current，再把真实elapsed判403，过期决定不返回成功proof。原普通verify/pinned的signed-expired503保持，语义绑定拒绝继续沿用403。

## 精确入口和隔离链

只接受GET及精确/auth/session；实际到达Tomcat的空query、普通query、body、Cookie、ApiKey、重复Authorization、身份/租户/资源/动作及其他凭据混入均拒绝。保留B2严格Cookie边界。未实现浏览器provider；后续浏览器必须显式省略请求Cookie，并真实验证同源ambient Cookie，不能以withCredentials=false推断同源Cookie被省略。

SESSION purpose固定真实PortalSessionController.session(HttpServletRequest,HttpServletResponse)，同bean/同declared method；替换handler或secondary mapping重复注册失败。session没有成为第三条ANY。run的/admin/**和/v1/**数量及实际filter/servlet注册证明保留，run-only实HTTP可消费旧capture并到达原PUBLISHED_RUN_UNAVAILABLE分支，正常legacy context无IAM client仍可启动。

LoopbackAuthority的新策略在exchange对真实verifySubject来源派生资源，在authorize对存储grant的原call、PORTAL source/origin及真实来源重核资源，不信任caller自报resource。新action operation不晚于source/grant较早值；普通action原source-exp行为不变。仅新action允许已授权角色收窄，其他action原角色条件不变。

隔离Gateway用真实Tomcat/handler/SecurityFilterChain/FilterChainProxy/实际servlet注册及真实签名HTTP issuer/token/decision。正例session自身exchange=1、authorize=1，所有动作/target均为portal.session.read/gateway。没有PublishedMetadataClient/PublishedRunProxy、engine/app/PG/模型/集群进程或业务发送；fixture business/policy calls为零。原PublishedRunProxy的5000ms及两个ANY源文件保护不变。

## 实际红绿与失败保留

24个唯一具名case最后适用结果全部pass：common16、gateway8。是多个源码阶段适用绿，绝不是一次最终同源全套24绿。精确20条命令/cwd/exit/source-before SHA、实际Running对应XML及每case历史见 `stage-ledger.json`。每stage目录保留原command.json、完整380/当时377～380 source哈希清单、当时批准候选文件物理overlay、launcher.raw、受控runner原日志maven.raw及Running XML。早期overlay包含尚未修改的批准候选，准确分类见 `stage-overlay-index.json`；没有把它们称为delta-only或完整source tree。

所有行为命令均为工程根目录的：`node .tools/run-iam-module-tests.mjs <stage> <module> <具名逗号/# selectors>`，由本任务证据wrapper实际执行。固定offline、本工程.tools/m2、Java21、V:/.local/tmp。没有+ selectors，未重跑旧48/25/179/B1/N3/962。受控不变runner按自身规则另写 `.local/<stage>.log`；20个本任务精确新日志原件也纳入own leaf manifest，stage的maven.raw是其字节副本，并未修改runner以改变输出位置。

关键真实红→绿：

| 阶段 | 实际失败与修复证据 |
| --- | --- |
| common-red01→common-green01 | 真实source/ticket路径尚为default503；2cases中正例error，随后2pass。不是compile-red，实际Running存在。 |
| http-red04→http-green01 | 真实GET403而预期200（1failure/0error）；安装controller/purpose后1pass。 |
| common-green02→common-expiry-green01 | 7case中6pass，unknown-kid上游403而预期503；仅失败case及新elapsed修复绿。 |
| common-expiry-red01/red02→common-expiry-green01 | 先保留exchange在途截止503，随后单独把authorize在途signed-expired置前，实际503vs403红；修复后两窗口403绿。原verify/pinned默认503 control另有实际pass。 |
| handoff-red01→handoff-green01 | ticket decoder的第三次真实JWKS推进6秒后原先误成功；修复后在authorize请求前403，authority实际只有exchange。最新authorize跨旧exchange窗口正控制仍pass。 |
| alias-red01→alias-green01 | X-Actor混入原先误200，补齐身份/凭据别名拒绝后仅该case绿。 |
| envelope-red01→envelope-green01 | HTTP200 {}原先403，交verifySession(null)后仅该case503绿，旧query分支未改。 |

覆盖内容还包括：四字段/no-store、issuer角色交集为空、portal/grant分别先到期；9类可信绑定拒绝；当前决定/来源/grant/ticket首写前永久失效；真实owner close/一次release；600ms有限真实nano预算加Clock回退；源码在exchange/JWKS/authorize返回时到期401；真实空query、body/Cookie/重复凭据/注入头；首写前getOutputStream窗口的normal正控制及401/403/503三类零成功字节；替换/secondary handler、错误及重复真实注册；normal legacy/run-only兼容；实际当前资源关闭。

明确保留的setup/oracle：HTTPred01是AuthorityProperties被ConfigurationProperties重绑定的fixture错误；red02/03是actuator自动handler未关闭触发严格coverage，均不计行为红。HTTPgreen02三个case pass，另有legacy排除安全自动配置后management链缺HttpSecurity的setup error；Proxy-Authorization在JDK直连HttpClient未到达服务器造成oracle failure（实际200），改有限raw Socket后该凭据和空query真实到达并拒绝。只重跑这两个失败case得到green03。没有修改生产逻辑迎合这两个fixture问题。

日志不是“无噪声”：预期非法安装的启动拒绝有ERROR栈，编译包含unchecked及既有PG测试deprecation提示，原样保存；编译这些测试不代表执行了PG测试。maven.raw是受控不变runner日志的原字节副本，其既有控制台编码乱码未伪装修复。其他只读路径/PowerShell字面量工具失误见tool-events.json，没有计作行为红。

适用性说明：default503和不可公开mint路径在初次绿后未改变；6个common风险绿的proof/check/角色及语义绑定没有被后续封装分类更改。私有authorize交接位置变更后，只补跑受影响交接、旧verifier control、session正例及latest-window正例。header别名修复只收紧原负例未用的头；mapping/registration/legacy绿的生产路径未改变。最后空封装修正只改变缺失/非文本assertion分支，不改此前已验证的正常/已签路径，因此没有为最终报告重复行为套件。

## 固定源码对照

`upstream-read.json`记录实际读取范围、原始本地归档路径、官方固定tag URL、bytes/SHA。仅复制了确实读到的3份现有归档，保留license header：

| 固定源码 | 实际读到范围 | 用途 |
| --- | --- | --- |
| Spring Framework 6.2.7 RequestMappingHandlerAdapter | 901–1011 | invokeHandlerMethod取得生命周期response、直接invokeAndHandle、区分concurrent handling。 |
| Spring Framework 6.2.7 StandardServletAsyncWebRequest | 251–330、351–470 | getOutputStream包装、write/flush/close生命周期异常边界。 |
| Tomcat 10.1.41 Http11OutputBuffer | 176–213 | doWrite的首次commit及flush物理边界，限定本任务保证。 |

既有HttpCurrentAuthorityClient的portal/query/ticket/retained等实际读到的原范围1–290及末尾close()复用；原件在before-overlay。另实际完整读AuthorityResponseVerifier、GatewayIngressBoundary和安装coverage。没有声称读过缺失的ServletInvocableHandlerMethod归档或整个Spring源码。复制件只承诺与原封存本地文件字节一致；不声称与远端重新下载文件CRLF/LF字节相同。本任务没有下载新版本/依赖。

## 冻结、重放与保护

正式before复用N4b2修复后source377/target1888及overlay-reference，不复制旧全树或fat业务JAR。开工实际完整source377哈希与正式before相等；target-before明确继承正式封存descriptor，不伪称重新物理扫描历史target before。

最终source-after：380条，78337B，SHA `7e1a1b53ae7b494361bf75b36d2878a1f4dbb1e5d0631c3311643a8e34822708`。

最终target-after：1931条，398161B，SHA `0a855288ee3f47bff030da2f562abdad3c6533c1ee224ed80e5e63d980f99516`。实际当前target完整哈希一次最终采集；75个新增/修改物理overlay逐条验，未变部分继承旧sealed descriptor。准确JAR归属见target-attribution；没有business JAR打包/复制。

中间source380/target1929冻结原件留在intermediate-freeze，不冒称最终；最后空envelope修正后重新冻结。每次真实测试前完整source manifest不可变，最终source/target清单与差异、source/target physical overlay、完整逻辑合成清单分别存于本目录。

`source-fullcontext.patch`是11文件、259511B全上下文补丁。工程根目录实际执行git apply --check和git apply，`-p1 --directory=docs/evidence/iam-stage-4-d4a-portal-session/replay-overlay`，cwd及完整参数/exit/stdout/stderr见replay-check。pristine只物理复制8个变动旧文件，其他复用immutable基线；重放后完整380个逻辑源码名称/bytes/SHA与最终source-after逐条相同。source-frozen-effective和source-replay-effective也与最终清单一致，target1931逻辑合成一致。没有只证明11个patch条目而遗漏未变源码。

保护沿用原B2 physical123837及后续完整谱系descriptor；本次没有全量重哈希123837个历史文件或再次遍历全部16347旧证据。实际核验13个关键历史/runner/root封存锚点、root最新8份索引afterSHA、v1.2实际迁移路径及9个当前contracts物理文件；见protection-anchors-before-freeze/protection-check/contract-key-anchors。保护锚点实际采集在测试之后、冻结之前，明确标时，没有伪称第一命令前捕获。后端完整source377→380差异仅11批准位置，原8 POM/44 SQL均未变。root新D4b brief/addendum和current-work-state属于明确外部授权路径，不收进作者own seal、不改原保护清单。

## 资源与自审

final-controls-green01的实际fixture gateway/issuer/resolver端口分别65087/65058/65059，try-close后逐端口真实connect-refused，context destroy关闭同一个owner后旧proof立即503。PID39240、start `2026-10-20T19:34:23.104Z`，后续CIM精确PID已不存在；任务启动后且命令含完整本工程root的当前Java进程为空。证据在resource-check和.local/resources原件。初次宽名称过滤发现的6个既有进程早于任务启动，原JSON保留，未把它们冒称本任务泄漏或0全局Java；没有停止/修改进程。所有受控Maven命令均结束，未留下本任务异步worker/业务子进程。

自审已逐项核：需求/批准范围、私有构造和不保存JWT、固定资源与真实grant/operation、双截止及状态来源、实际首写/no-store/一次claim、角色空集合、所有mappings/真实注册、legacy、固定上游默认兼容、当前资源、原始失败、source/target组合与全逻辑重放。自审发现的交接窗口、头别名和空封装缺口均有真实红→定向修复绿。没有尚未处理的已知功能缺陷；独立review仍由root另派，不自签审批。

交还产物：本报告、handoff.md/json、freeze/replay-check/source/target/delta/composition、stage-ledger、upstream/protection/resource证据及own leaf manifest/seal。own manifest明确排除其自身、handoff.json和seal.json以避免循环；最终seal绑定manifest和handoff的真实bytes/SHA，交还后作者停止写源码/target/自身证据。

限制：没有真实IAM、生产门户provider/浏览器、PG/模型/本体/K8s联调；Cookie浏览器边界、前端generation/请求隔离、其余D/E/F/G和17需求/34任务/30验收整体仍未完成。不能用24个有限case宣称这些外部或整体项目验收通过。
