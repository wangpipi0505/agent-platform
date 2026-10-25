# D3a0 published 元数据查询组件实施报告

结论：DONE_WITH_CONCERNS，交独立 Spec/Quality review；不代表完整 D3、真实 gateway 代理、R3、E 或真实 IAM 已验收。实现按 root 正式选择的方案 2：普通 C 的 typed metadata 查询 Grant + 同一原认证再次 exchange 的独立真实版本主 Grant。未实现 metadata JWT/receipt、任意 child 换根、真实代理接线或运行受理。

唯一工作根为 `G:/207/项目/算法重组工具/代码/agent-platform`，所有 shell 显式 workdir。无子 agent、commit、发布、主服务启停、七模块 reactor/package；未访问相邻旧工程。读取了本根 AGENTS.md、implementation-brief、contracts/README、D3a0 brief 和 root handoffs 正式裁决。D2 最终 332 项 source-after 在基线时逐项核验一致；新增根 POM 纳入本轮基线，因此本轮 source-before=333。

## 实现与数据边界

- `MetadataSelectors.published(tenant, code)` 构造 kind=`metadata-selector-v1`。id 为 canonical JSON tuple `[schema,tenant,code,PUBLISHED,projection]`，schema=`app-published-selector.v1`、projection=`app-published-metadata.v1`；digest 为五字段 descriptor 的 canonical SHA256。tenant 来自已验 Actor；code 沿既有 `[A-Za-z][A-Za-z0-9_-]{0,63}`。它表示查询语义版本，绝不充当 application 的业务 revision。
- `ServiceAuthorization.route` 在普通 route/current 校验前执行双向用途约束：任何该 kind 或该 action 都必须同时是 `app.published.metadata.read`、target app、caller gateway、delegated mode、source/origin PORTAL。其他 payload action 即使由 fixture 故意签上该 kind，也不能进入普通 handler。DELEGATED resume、integration、workload 均不能成为首入口。未放松 D1/D2 或 C 的 exact-root 约束。
- 目录只新增 `gateway -> app GET /internal/apps/{code}/published-metadata`，`integrationAllowed=false`。完整实际 controller 集合与目录比较保留：159 条普通 handler + 1 条独立 policy ingress = 160，另保留 5 条 springdoc；11 条既有 integrationAllowed 不变。
- 新 `PublishedMetadataController` 拒绝任何 query 参数。`PublishedMetadataService` 使用实际 path code 与已验 tenant 重建 selector，先 `AuthorityContext.requireFor(actor, actualSelector)` 在线复核，再执行一条 SQL。SQL 仅选择 application id/code/status/current_version_no 与 app_version id/version_no/candidate_sha256；join 同时限定 tenant、code、app_id、当前 version_no，where 再限定已验 tenant+code。单条 PG 语句保证指针/版本来自同一 statement snapshot。无个人 app owner 列，因此未虚构 owner 条件。
- 无 draft/snapshot/config/definition/layout/prompt 查询，无调用 `AppService.get/published/publishedInputs` 或 `DraftMetadataService.get`。沿既有 published 规则，DISABLED/ARCHIVED/无已发布指针 409 `APP_UNAVAILABLE`，未知或跨 tenant 未命中 404。输出遵循 `ApiResponse`，data 只有 appCode/string、versionId/long、versionNo/int、versionSha256/string 四字段。
- `PublishedMetadataClient` 尚未接到真实 GatewayController。调用者给已验 SubjectKey；客户端向 C 正常 exchange 原 source，核 returned operation subject/resource/source/origin 后，仅向固定 app origin 发送目标 service ticket。无原 token 或 authority Basic 凭据下传 app；无任意 URL、redirect、重试、跨主体结果缓存。
- 生产构造只接受 HTTPS origin（禁止 userinfo/path/query/fragment，保留 JVM 默认证书链与 hostname 验证），测试构造显式允许 127.0.0.1 HTTP。connect=1s、完整 body=2s、最大4096 bytes；这些是有限安全读取界限，不是生产 SLA。subscriber 在超限/错误/timeout/finally 取消实际 subscription/future，client.close 调用 HttpClient.shutdownNow。实际 stalled HTTP 测试验证完整 body 期限；subscriber 测试直接观察 cancel。
- 客户端严格校验 ApiResponse envelope 与恰好四个 data 字段、无重复 key/尾随 JSON、严格数值类型与正整数范围、code 相等、64位小写 SHA；不接受额外 snapshot 或 user-uploaded DTO。返回真实 `app-version(code@versionNo,storedSHA)`，供调用方明确再次 `C.exchange(originalSource, mainCall)`；查询 Grant 不可 forward/resume 换根。

生产变更仅 common 小 helper/守卫/单条目录、app 窄 controller/service/DTO、gateway 小客户端与 gateway test-scope common test-jar 依赖。六服务 yml、GatewayController、IntegrationAccess、前端、其他业务模块、migration、原四契约均未改。测试 fixture 最窄公开既有 `portal(long)`、`advanceClock(Duration)`，另经 root 裁决提供只准 app/gateway 的 `testClientSecret(service)`；默认 source 身份、有限预算、D2 修复全部保留。

## 测试与真实红绿

统一实际命令格式：

```text
node .tools/run-iam-module-tests.mjs <stage> <modules> <tests>
node docs/evidence/iam-stage-4-d3a0/capture.mjs stage <stage>
```

runner 只向子进程注入本新工程 PG 测试凭据与随机隔离 JWT/master/vault；实施者未读取/打印 `.env` 或 vault/key。每轮结束立即存该轮 raw.log、source/source-sha.json、原 XML。`reports/<module>` 与 `executed-reports.json` 仅依据本轮 rawlog 的 Running 项选取，禁止将 target 旧 XML 算入当轮。早期完整 target/surefire 快照也保留，其中未选中的旧 XML 不是本轮结果。

下表 stage 均带完整前缀 `iam-stage-4-d3a0-`；F/E/S 分别为 failure/error/skip，所有轮次 skip=0。

| stage 后缀 | modules | tests 参数 | 实际结果 |
|---|---|---|---|
| red-app | app-management | PublishedMetadataHttpTest | app 4，F0/E4；explicit actor 缺 obligation labels，fixture 准备失败，不计行为红 |
| red-behavior | app-management | PublishedMetadataHttpTest | app 4，F3/E0；合法查询200，三个安全断言期望403实际200，是真行为红 |
| red-client | platform-gateway | PublishedMetadataClientTest | gateway 7，F1/E0；错误 appCode/DTO未被拒绝，是真行为红 |
| green-focused | app-management,platform-gateway | MetadataSelectorsTest,PublishedMetadataHttpTest,PublishedMetadataClientTest | common4/0、gateway7/0，app9/F0/E1；名字含green但整轮失败，integration fixture缺region/labels；未计为绿 |
| focused-final | app-management,platform-gateway | 同上 | common5/0，gateway10/F1/E0，app未运行；非canonical Map.of字段顺序使一个“缺字段”样本替换未命中，已改canonical fixture |
| focused-verified | app-management,platform-gateway | PublishedMetadataHttpTest,PublishedMetadataClientTest | gateway10/0；app9/F0/E2，分别为有限integration fixture仍缺confidential label、重复固定另一租户seed唯一键冲突；已只修测试输入 |
| app-verified | app-management | PublishedMetadataHttpTest | app9/F0/E0/S0 |
| smoke | platform-gateway | PublishedMetadataSmokeLauncherTest | launcher1/F1/E0；子JVM未传既有unixdomain临时目录，HttpClient selector初始化失败；原进程日志保留 |
| smoke-verified | platform-gateway | PublishedMetadataSmokeLauncherTest | launcher1/0；跨JVM smoke成功，独立结果保留 |
| common-final | admin-common | * | 15份实际XML，155/F0/E0/S0，完整common |
| smoke-owned-exit | platform-gateway | PublishedMetadataSmokeLauncherTest | launcher1/0；按root补充PID/退出遥测后唯一重跑smoke，未重跑已绿common/app/client |

具名行为红：`forgedSelectorTenantCodeOrDigestCannotQueryPg`、`deliberatelySignedMetadataKindCannotEnterActualPayloadController`、`payloadKindCannotEnterMetadataController` 均在真实 Spring app、实际 controller/PG/globalMappedInterceptor/runtimeCoverage 下，原实现返回200；不是缺类/编译/仅404路由不存在。修复后 app9测试覆盖：正常窄查询1/payload0；伪造tenant/code/digest、实际payload action误签kind、反向payload kind、缺上下文/错caller/target/action/aud/tenant/method、portal/integration直连、普通workload、实际C.resume后DELEGATED HTTP拒绝；未知参数/非法code、跨tenant404、disabled/archive/draft无发布/空pointer409；在线收窄投影依然只授metadata。

gateway10测试覆盖：固定目标 service ticket 的 target/aud/jti、原token不等于app Authorization、主/查询Grant分离与query根不可换绑、原source到期拒绝、严格四字段/类型/范围/SHA/extra/duplicate/trailing JSON、固定URL/code限制、redirect0次、超大body拒绝、真实stalled response期限、subscription实际cancel、client.close终止、subject不符零app send。common新增5项包括 canonical tuple/descriptor、输入边界、双向purpose、唯一目录、查询后current权限收窄导致第二exchange及主current拒绝。

最终源对应关系见 `final-test-source-comparison.json`：common-final的全部73项common源/POM与冻结完全一致，app-verified的36项app完全一致；gateway10测试对应的生产/client测试源码与冻结一致，仅随后新增了两个smoke测试文件的临时目录和PID输出；最终smoke-owned-exit的344项与冻结完整相同。没有把多个轮次拼称一次全套绿。

## 跨 JVM 实际 smoke

classpath 从各自最新 Surefire XML 的具名 `java.class.path` 单项提取，未输出其他属性。校验每个依赖只在本工作根 `.tools/m2`、本域 target 或 common target，gateway无app业务类，app无gateway业务类。提取初次因Surefire结尾空classpath项被安全校验拒绝，之后过滤空项；没有放宽目录检查。

受控 Maven launcher 仅一个执行壳用例，启动 gateway smoke main；gateway拥有有限 LoopbackAuthority，私有stdin给 app fixture 本域trust/独立app凭据/固定fixture clock。两个子进程各自用本域classpath；app运行真实 controller、AppService（仅供误用途负例）、JdbcTemplate/PG、ServiceSecurity、globalMappedInterceptor、RouteCoverageVerifier。同一mapping下正常metadata和实际 `/admin/apps/Smoke` payload误用途请求均实际发出HTTP。

最终机器结果：`smoke-result-ad3affad-58f9-45e2-973a-a5f3cbeb762c.json`；原输出：`smoke-process-ad3affad-58f9-45e2-973a-a5f3cbeb762c.log`。gateway PID=51332、app PID=61548；appExit=0、launcher观察gatewayExit=0、ownedAppExited=true、ownedGatewayExited=true。正常STOP/EOF和try-with-resources关闭本次端点与子进程；finally仅触及本次持有的Process，不操作其他进程。

实际检查：PG metadataReads=1、payloadReads=0、globalGuard/runtimeCoverage=true；返回 `app-version:Smoke@1` 及PG存储SHA；queryGrant换根resume/forward拒绝；同原source正常第二exchange产生不同mainGrant且截止不延长；签metadata kind的实际payload action HTTP403；当前权限收窄拒绝主current和第二exchange；推进原source到期后第二exchange拒绝。这里只验证真实metadata组件链及授权分离，未运行主payload成功闭环/发布指针重核/run受理。

## PG、警告和开源对照

仅新建专用 `agent_platform_iam_metadata_v12`。首轮记录 D3A0_DATABASE_EXISTED=false，此后true；只应用既有app100–104。未迁移/改动 D2专库或main，不增加生产migration。测试使用UUID租户，不清历史测试数据。

完整common原log包含38次预期启动拒绝、39行ERROR（另1行为LoggingFailureAnalysisReporter），XML全部155/0；原因分布保存在 `expected-common-startup-errors.json`，包括IAM_TRUST_CHAIN_UNAVAILABLE及配置闭锁。没有把预期日志删掉。common最终WARN/WARNING=0；app/gateway最终WARN=0。首轮Flyway有1条“schema app already exists, skipping”警告。编译未发生错误；历史编译轮保留既有AppService/ServiceAuthorizationTest unchecked说明（共6组、每组2个INFO说明行，含6条-Xlint:unchecked），不是测试失败。原Maven日志中文编码显示异常亦原样保留。Git apply成功但保留8处行尾whitespace警告（显示5处、squelched3），不隐去或改写警告。

实际读取源码记录在 `upstream/read-source-sha256.json`：Spring6.2.7 JdbcTemplate的绑定prepared statement/单次executeQuery/finally关闭ResultSet；MappedInterceptor.matches无include/exclude覆盖所有mapping。固定OpenJDK `jdk-21+35` 的 HttpClientImpl.shutdownNow实际abort selector，以及ResponseSubscribers关闭时cancel订阅。JDK对照说明机制，未声称下载源码tag恰等于本机21.0.12补丁版本。下载的两份源码与原Spring只读副本SHA均登记；没有修改旧证据或外部开源文档。

## 冻结、回放和保护

`source-before.json`/baseline：333；`source-after.json`/frozen：344；changed-files.json：16项，完整列出5项原文件修改+11项新增。`target-before.json`/target-after.json及frozen-target保留七模块target的1632项最终摘要/字节；实际只编译common/app/gateway。四契约、其他模块源码、六服务配置均仍在完整source集合中逐项保留。

完整patch SHA256=`dc290dffefa85ddf9d077b2f0a0f5b093ea6600efed1e6899d683dbca5d16ea6`。不依赖HEAD/remote，不伪造空gitdiff。第一次Git ceiling误设当前目录，rev-parse仅发现本新工程根即断言中止，未生成patch；原 `git-boundary-initial-failed.log` 保留。修正为父目录ceiling并禁system/global attributes后，独立边界检查exit128；真实 `git diff --no-index --binary` 与 `git apply --binary`、apply.log、replay-check.json记录全部344项逐字节一致。

原protected-before的8954项全部SHA匹配；protected-after/changes记录不覆盖旧文件。不整目录豁免root-owned证据。已知具名遗漏：最初采集 `!startsWith(out)` 误排除了同前缀 `docs/evidence/iam-stage-4-d3a0-brief.md`，其原SHA未采集且root无可确认前稿，因此不追认为基线保护。root确认本轮追加跨JVM/有限hook/launcher裁决已授权；只登记当前SHA `e7617d7cd2bbc4250ccda0eb981b45c4408d00d3c536484a7f02ee8c68dd48a4`。原manifest未重写；capture.mjs仅修正后续过滤为目录加斜杠，见protection-notes.json。

root本轮新增只读准备文档（非实施者写入，原SHA不存在）：`iam-stage-5-e1-initial-seam-proposal.md` 当前 `62f50f02059938cdff2bdbf440aa59c80ef89006e46ab8edc51dac470bee52d3`；`iam-stage-5-e1a-brief.md` 当前 `259014d371807a1f9e6f930c1cfafa16f51efcf3273c5366fce857538fac57ab`。它们与具名brief遗漏单列 `protected-additions-and-omission.json`，不把后续准备文档放入业务patch，也不伪造old/new相等。

## 自审关注点与未跑项

1. brief原保护SHA遗漏是证据完整性关注点，不能用当前SHA替代；8954项其余保护核验有效。首次Git边界失败、fixture准备失败、smoke启动失败及8处patch whitespace警告均需独立review知悉。
2. gateway客户端非200统一失败为503 APP_METADATA_UNAVAILABLE；app真实endpoint保留404/409。后续真实入口如需向用户传播业务409，应明确接线语义；本组件未偷偷接入旧代理。
3. app服务新增SQL只验证selector与tenant/current授权，真实主资源payload守卫、published pointer竞态409/run受理、集成app绑定投影仍待后续D3/E1。没有receipt或lookup先消费条件；主域必须独立验证真实PG版本与权限。
4. 原业务mode未迁移测试（包括旧DraftMetadataHttpTest、完整app/gateway旧业务套件）未跑，不宣称绿色；D2完整HTTP19矩阵未重跑，只保留已批准起点；完整common155涵盖直接common回归。未跑七reactor/package、容量、真实模型/本体/IAM/K8s、原17/34/30矩阵。IAM_TRUST_CHAIN_UNAVAILABLE和BLOCKED_IAM_SCOPE_ACCEPTANCE保持，T01仍唯一完成。

source/target冻结归还后，由root组织独立Spec与Quality review；本报告不是自签Approved。


## D3a0 deadline 测试判别力补强（2026-10-20，fix-only）

本次结论：DONE_WITH_CONCERNS，source/target 归还 root，待同一独立 reviewer 针对 fix-only full-context patch 复审。原独立审查为 Spec compliant / Task quality Approved；本节只修 Minor 1 的测试判别力，不改其原结论。原报告前 15850 字节及 SHA256 2d1c7d3cd6ab7bb0822be0eeee1da91640e92357a3ab7709079b709185325c00 原样保留；original-report-prefix.bin 保存原字节。原 review SHA cc9cb265a98327d2e39d95acdaf640d93979c80ddc0a90e7da7b189e7ac1a4bb 已核对且保持不变。

仅修改 platform-gateway/src/test/java/cn/agentplatform/gateway/application/PublishedMetadataClientTest.java。原夹具发“{”后 sleep 3 秒再 EOF，且测试容许 4 秒内任意 PlatformException，可能由 JSON 不完整造成假阳性。现夹具声明完整 Content-Length，先发送 headers 与既有 valid() 合法 DTO 除最后一字节外的全部内容并 flush，用 partialBodySent 通知测试。完整尾部必须等待 releaseBody；8 秒兜底若届满也只会补发完整合法 DTO，不能自行关闭畸形响应。测试先有界等待前缀发送，再要求真实 lookup future 在 4 秒外界内以 PlatformException 为直接 cause 失败，并断言 releaseBody 仍为 1、handlerFinished 仍为 1；因此客户端实际结束时响应尚未释放、handler 尚未关闭，不能依赖 EOF/JSON 错误通过。没有仅收紧墙钟上限。

同一具名用例继续断言 hits=1、实际 Authorization 为独立 Bearer service ticket 且不含原 source，再通过实际 app.authorize 校验 app/metadata action/真实 selector 并核对 subject。原 overflowAndExplicitCancelCloseTheActualBodySubscription 直接 subscription cancel 断言逐字保持，不将其说成本轮重新执行。正常或任一断言失败均在 finally 释放响应、cancel 本次 lookup；查询 executor shutdownNow 后 3 秒 awaitTermination。HTTP handler 等待尾部最多 8 秒；正常路径另断言 handler 在释放后 3 秒退出，AfterEach 再释放、停止本次 HttpServer、shutdownNow 并 3 秒 awaitTermination 断言其 executor 退出。无新增通用 harness、生产 timeout 或配置。

只运行一次指定方法，实际命令：

    node .tools/run-iam-module-tests.mjs iam-stage-4-d3a0-deadline-fix platform-gateway PublishedMetadataClientTest#fullBodyDeadlineCancelsARealStalledHttpResponse

受控 runner 返回 exitCode=0、timedOut=false；原始 Maven 输出为 Tests run: 1, Failures: 0, Errors: 0, Skipped: 0，Time elapsed: 2.822 s，BUILD SUCCESS。立即归档在本节新证据目录 run-01 的 raw.log、实际 XML/TXT、344 项 source/source-sha.json；XML 仅有 fullBodyDeadlineCancelsARealStalledHttpResponse，不把 target 中其他旧 XML 当作本轮结果。raw SHA256=6be03af64b3c234d71ad72395c90c8d1151fc32ac1349745afc216106a86b6bd，XML SHA256=c3c70043084cfe09ce21c4b4573be2fc7f6d2953384d543454a6b6e131259798。本次是已正确生产行为的测试补强，没有制造生产 bug 红轮；Maven 无失败轮，不重跑同码 common155/app9/client 其余9项/smoke、七 reactor/package、主服务或 DB。

新证据均在 docs/evidence/iam-stage-4-d3a0-deadline-fix/。source-before 的 344 项与原 D3a0 source-after SHA 和 frozen 实际字节完全一致；source-after/frozen 仍 344 项，仅本测试一项变化。完整 fix-only full-context change.patch SHA256=69d30d583ddde0b6530c1c2b0c4b3c468398a9c20fd83f4b15319e2e51e41208；正确 parent Git ceiling 下 rev-parse exit128、真实 git diff --no-index --binary exit1、git apply --binary exit0，replay-check.json 的全部344项逐字节相同。未访问或伪造 HEAD/remote/commit。本轮 apply.log 无 whitespace 警告；原 patch 的8处 whitespace 和原历史失败/警告记录均未修改。

target-before=1632 项，target-after=1633 项；本轮仅 gateway target 6 项发生内容/新增变化：3 个该测试 class、其 XML/TXT 和1个新 Surefire dumpstream。gateway 当前75项 target 全部存 frozen-target；其他模块 target 摘要逐项不变。虽然 -am 顺序经过 common 生命周期，其源码/classes/test-jar 摘要未变，未执行 common 测试。target-changes.json、manifest 与 final-verification.json 登记各自 SHA。

保护共21303项，原 D3a0 证据、原 review、受控 runner、契约及其他保护路径均保持。首次 freeze 的严格“无保护差异”断言实际 exit1，原因唯一为另一个 root-owned 文档 docs/evidence/iam-d3e-first-published-path-proposal.md 并发追加，从 b27b63051d7e63c83fef7503f9cc6af8779503c1bbbc0b27890cf42ece92921b 变为 972e08d6b7c5e4b06488d914989163d170bcb7f850fb0764544019cf58461739。root 确认这是其显式授权的 architecture 原 proposal 第10节和10.8追加且已结束写入；该差异单列 root-protection-disposition.json，不纳入源码 patch。原 protected-before 和真实差异未重写，初次失败记录保留 freeze-attempt-01-failed.json；续核明确匹配这一项例外，其余21302项 SHA 相等，不豁免目录、不重跑测试。

新增诊断关注：本轮 raw.log WARN/ERROR=0，但 Surefire 新建 2026-10-20T16-40-22_085.dumpstream，原文保留于 run-01 和 frozen-target，内容为 Boot Manifest-JAR 绝对 classpath 路径、'other' has different root 及 argLine 提示。没有改配置掩盖该诊断，也不因它重复绿轮；XML 1/0 与实际退出码分别如实保留。本轮首次 freeze 的保护断言失败与此 Surefire 诊断均属证据关注，不能表述为生产缺陷或测试失败。原报告既有 brief 历史保护 SHA 缺口、D3/E/R3/真实 IAM 未验收、BLOCKED_IAM_SCOPE_ACCEPTANCE 等边界保持；未扩大本次范围。

报告追加 helper 的首次 node --check 因模板中 Markdown backtick 过度转义报 SyntaxError exit1，发生于执行/追加前，未改报告或源码/target；改用普通摘要文本和缩进代码块后同一语法检查 exit0。两次实际结果单列 helper-checks.json，不混作 Maven 失败轮或生产红。

本轮 dumpstream 的精确归档路径为 docs/evidence/iam-stage-4-d3a0-deadline-fix/run-01/2026-10-20T16-40-22_085.dumpstream，SHA256=5cc647062826bb97f98d79ac00f141a7ef352339a8e8030de37413d3884b8c3c；同字节副本在 docs/evidence/iam-stage-4-d3a0-deadline-fix/frozen-target/platform-gateway/target/surefire-reports/2026-10-20T16-40-22_085.dumpstream。原始诊断逐行如下（保留在归档原文件中的 CRLF，以下仅作显示）：

    # Created at 2026-10-20T16:40:23.752
    Boot Manifest-JAR contains absolute paths in classpath 'G:\207\项目\算法重组工具\代码\agent-platform\.tools\m2\org\apache\maven\surefire\surefire-booter\3.5.3\surefire-booter-3.5.3.jar'
    Hint: <argLine>-Djdk.net.URLClassPath.disableClassPathURLCheck=true</argLine>
    'other' has different root

该提示没有伴随实际 fork 失败或测试漏执行：本轮 XML 明确列出 fullBodyDeadlineCancelsARealStalledHttpResponse，tests=1、failures/errors/skipped=0，Maven 实际 exit0。该用例的 handler 释放后退出、lookup executor 与 HTTP executor awaitTermination 断言均通过，证明本次拥有的有限夹具线程退出；这些是测试/退出证据，与上述 classpath 根路径诊断分开记账，不将 raw WARN/ERROR=0 描述为全部输出无警告。
