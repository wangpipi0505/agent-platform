# D3b 真实 published payload 组件实施报告

状态：**DONE_WITH_CONCERNS（有限组件，待独立双审）**。真实 `GET /internal/apps/{code}/published` 在明确 IAM 模式使用 `AppService.publishedAuthorized`：实际 metadata 重建 root、事务外刷新、短事务锁与重核、payload 前局部校验、提交后服务返回前终检。保留全局生产 IAM 闭锁；不宣称全 D3/E、真实 Gateway→Engine/FLOW、Task5、portal 或真实 IAM 验收完成。T01 仍是唯一整体完成项，没有部署、推送、Git index/提交或业务 JAR 依赖操作。

唯一工程/每个 shell 显式 workdir：`G:/207/项目/算法重组工具/代码/agent-platform`；未读取或关联相邻旧工程。正式 brief SHA `7b1c61112b8a9cd0bfade73c831f7cde1c7afa7582b25e83ffc090fe695bfe48`，proposal SHA `e8d613dd6b534723c7a175697617aef8dcdedb1503311afc73c460bcf1eb5e09`；已全文读取 AGENTS、implementation brief、contracts README。按 TDD、Java/Spring、ponytail、系统调试及完成前验证技能执行；无嵌套实现者。精确追加授权见 [root-decisions.md](iam-stage-4-d3b/root-decisions.md)。

## 交付代码与不变量

仅两生产文件、五测试文件改变，完整清单及 SHA 在 `iam-stage-4-d3b/changed-files.json`。

- `AppService.java`：窄 metadata 不调用 get/version、不读取 draft/a.*/snapshot；tenant 来自已验 Actor/frame。拒绝 ambient transaction；核 engine→app / app.published.read / app-version / PORTAL origin，允许 PORTAL 及真实 resume 的 DELEGATED source。同 code 版本变化409，错误 root/code/同 no SHA403；无 app404，停用/归档/无 pointer/悬空 pointer409；DRAFT+pointer 保留。
- 事务外唯一显式 `requireInitialFor` 保留同 frame 刷新对象。专用 writable READ_COMMITTED 模板 timeout3秒，PG statement_timeout2秒、lock_timeout1.5秒；先 FOR SHARE application，再局部 bound，再单独 JOIN metadata 并核原观察的 a.id/status/pointer/v.id/no/SHA 全等；payload 前再 bound。精确 payload SQL含 tenant/app_id/code/数字 versionId/no/stored SHA。回调结束前及实际 execute 提交返回后、Map 返回前再次本地 bound；不续票、不重试切版，不返回异常路径局部 Map。
- snapshot 读取后核原 appCode/versionNo/candidateSHA/appVersionId=code@no，完全物化；保留 appVersionKey 字符串，appVersionId/versionId 为真实 PG 数字 ID。SHA 是已保存候选 SHA，不是整个返回 JSON 的 hash。数字 ID 由 PG 关联确定，不能声称它是 root.id 的签名数字。
- `AppController.java` 构造注入既有 AuthorityProperties，无隐式 legacy 构造器。iam 必走安全方法；显式 test/dev-hmac 沿旧 published；未知 mode/缺 frame 失败，没有 catch 回落。只迁移 published 真入口，未把 snapshot/draft/candidate/version/admin/input-schema 偷合并成其能力。
- 原 metadata HTTP/child fixture 仅补 AuthorityProperties bean。ApplicationLifecycleTest 与 PublishTransactionTest 只补显式 test,app 构造参数；通过 testCompile，未运行其固定主库 suite、未改 DB helper。

线性化点是锁内 payload 读取。提交后检查覆盖到服务返回前，不能声称覆盖 Servlet 序列化/最后网络字节。config.guard 仍为内嵌 payload；未假装提前读取它做独立策略投影，后续 engine guard/材料义务保持。

## 实际运行与来源区分

全部通过既有 `.tools/run-iam-module-tests.mjs`、Java21、offline Maven；无复制 env/凭据解析、无凭据输出。标准命令：

`node .tools/run-iam-module-tests.mjs <stage> <module> <tests>`

| stage（均前缀 iam-stage-4-d3b-） | module / 实际指定测试 | 真实结果 |
|---|---|---|
| red01 | app-management / PublishedPayloadHttpTest | 23；17F/0E/0skip。可编译真实行为红；旧票实际返回新版本、错 root/缺 proof200、显式 current 未执行。缺锁/刷新接缝的有限 latch 超时单列，不伪装 PG 竞争成功。 |
| green01 | app-management / PublishedPayloadHttpTest | testCompile setup失败，Running0/XML0；全限定 PublishTransactionTest 构造点未补。不是行为红。 |
| green02 | app-management / PublishedPayloadHttpTest | 24；1F/0E/0skip。真实 PG 写已因55P03 lock timeout阻塞；测试误期望 CannotAcquireLockException，实际 UncategorizedSQLException。 |
| green03 | app-management / PublishedPayloadHttpTest | 24；0F/E/skip。改按底层 SQLState55P03核锁，pointer/status 两对照。 |
| final01 | app-management / PublishedPayloadHttpTest,PublishedMetadataHttpTest | 一次受影响整组33（payload24+metadata9），0F/E/skip。随后仅测试补强，原33轮 source 与最终有一项测试差异。 |
| smoke01 | platform-gateway / PublishedMetadataSmokeLauncherTest | 唯一一次受影响原 launcher；1执行壳，0F/E/skip，两个自有 JVM 正常退出。 |
| final-payload02 | app-management / PublishedPayloadHttpTest | 自审仅补 pointer2 新 metadata HTTP→新 main 恢复；只复验受影响24，0F/E/skip，最终357源码完全一致。 |

各轮不可变 `raw.log`、实际 Running 对应 XML/TXT、完整357 source及 source-sha.json 已逐轮归档；见 `iam-stage-4-d3b/<完整stage>/` 与 `test-audit.json`。不把 target 旧 XML 当本轮结果，incidental 执行0；不将跨轮数量、旧962或 launcher 壳加总成一次套件。

final01 和 smoke01 相对最终357只差 PublishedPayloadHttpTest.java。其余356项目逐项 SHA相等，故未变的 metadata9和唯一 smoke1保留各自来源；`test-final-source-comparison.json`/机器验证给出确切差异，不能称33与24同源双跑。未重跑完整 common、D2、七 reactor或旧成功 smoke；`-am` 仅带既有依赖构建，实际 Running记录没有全 common 测试。

## 真实 HTTP、PG、并发与期限证据

新 payload 专用 `127.0.0.1:25432/agent_platform_iam_app_payload_v12` 首轮实际不存在，使用正式授权既有受控 agent_v12 凭据，仅在 postgres 执行该固定库 exists/create；实际只应用 app V100–V104 五迁移到104。后续复用同固定库。metadata 固定独立 `agent_platform_iam_metadata_v12` 用于原9项与 child；未访问 policy/D2、engine216或主 `agent_platform_v12`。PG实际版本16.15。测试 fixture 以唯一 tenant/app 隔离，版本受原不可变触发器约束；有限 fixture 行保留，不停触发器伪造删除清理。连接、事务、executor、latch及自有进程均有限关闭/释放。

真实 AppController/AppService、TrustedJwtDecoder、ServiceSecurity、全局 MappedInterceptor、RouteCoverageVerifier、HTTP current、真实 PG/TM均进入执行；测试只观察真实客户端与 JdbcTemplate，不返回伪造 current/payload。保存同一 PORTAL source，实际 metadata exchange+HTTP获取真实四元组，再独立 engine main Grant、实际 engine authorize/forward app.published.read。另真实 resume 的 DELEGATED source/PORTAL origin成功。这里模拟 caller 的实际 authority+app 组件，未运行真实 GatewayController/RunController，不计 FLOW 首链。

`pg-cases.json` 保存最终24项实际 SQL分类/连接/事务/isolation/readonly/counter，raw保留相同遥测：

- 正常200返回完整 config/definition/layout 与数字 ID、no、SHA、appVersionKey。preHandle及显式共2次 current；初始 metadata 在事务外，application-lock/locked-metadata/payload为同一真实 PG connection、READ_COMMITTED、autoCommit=false、readOnly=false，锁内和 postcommit 无联网续票。
- 缺票、portal直传、伪签名、caller/target/action/aud/tenant/method、metadata票等实际401/403且业务 SQL0；published票撞 snapshot 两别名/candidate/version/admin/input-schema 被实际 guard拒绝。此结果只证明发布票不能获取其它 action，不证明其它合法票已资源安全。
- 错root/code/kind/SHA、隐藏租户、缺初始 origin proof、第二次 current403/503/过期均 payload0。合法收窄不误拒。404/409、DRAFT+pointer、显式test/dev-hmac、未知mode、缺frame及ambientTx均有实际对照。
- 在线刷新开始前、真实 authority response 等待中、刷新后锁前，真实其它 PG 连接可更新 pointer/status；恢复后409/payload0，证明等待网络时未锁 application。自审补强后新 metadata HTTP实际返回 pointer2、versionId479、SHA2，再新 main成功，旧票没有切新版本。
- 持 FOR SHARE 时另一连接改 pointer及status均实际55P03有限 lock_timeout；reader恢复正常读并提交后 writer成功。等共享锁时单次 PG观察 `wait_event_type=Lock`、blocking_pids基数1，推进可信Clock6秒再释放写锁，局部bound拒绝且payload0。无压力轮询。
- payload后/回调结束前Clock推进：HTTP403无成功data，payload1、commit0；`super.doCommit`实际完成后闩锁暂停时同一行writer成功，推进Clock6秒，HTTP403无成功data，payload1、commit1。两例不能谎称payload0。每例最终同一行有限更新成功证明无锁泄漏；生产业务写计数0，fixture并发写另记。
- snapshot结构身份四处错误实际409；它们已各读一次payload，这是读后完整性验证。未人为破坏不可变版本制造“payload0行”比赛；生产0行路径拒绝、无fallback由真实精确谓词和源码提供，未声称该不可合法制造的分支有额外动态用例。

## 唯一跨 JVM 受影响验证

执行前保存原 D3a0目录每一既有文件SHA/精确集、受控命令/起始时间/owned runner PID2008。原 launcher内部生成 UUID，结束差集恰2文件，旧文件0变更/0删除：

- `docs/evidence/iam-stage-4-d3a0/smoke-process-f456419e-d46f-4cc4-8d17-9f6516864359.log`，SHA `c30936ae726b31814225c36a587fb4a9420c0c1d32df3cc0a05a312fab33af77`。
- `docs/evidence/iam-stage-4-d3a0/smoke-result-f456419e-d46f-4cc4-8d17-9f6516864359.json`，SHA `504ea4b7faa88653bd474eba8f02261d227c2c2a2d679b4edb3d6633b73e1005`。

原件保留并复制 `iam-stage-4-d3b/smoke/`。实际 gateway PID53176、app PID64904、exit0/ownedExited=true；后续精确PID/TCP核验均不存活、TCP0。未操作六主服务。原元数据 smoke只证明既有metadata链与误用途拒绝，没有变成真实 published FLOW测试。

## 原始采样、完整补丁与保护处置

时间及不可补写的采样事实见 `sampling-truth.md`：attempt01 18:35:26开始，枚举后 MinIO临时文件消失 ENOENT、无 before落盘；丢失内存采样不追认为原SHA。结构failure字段 endedAt是失败归档观察时间；无伪造原始raw stderr文件。attempt02 source356/target1695于10:42:04.570Z完成物理拷贝，先于首测；protected逐项采样10:42:56.723Z–10:48:40.773Z，按实际窗口报告，非同一瞬间快照。

最终 source356→357、恰7文件；全部357 live/frozen/replay字节相等。完整上下文 `change.patch` 172477字节，SHA `e38bd6ed9e762843011ef6bcfcf66dfed6319020571aa7465a73c3d007541ab6`；真实 `git diff --no-index --binary -U100000` exit1（有差异）、真实 `git apply --binary --whitespace=nowarn` exit0。没有HEAD/remote/commit/虚构空diff。whitespace=nowarn是实际命令参数，未据此宣称警告自由。target1695→1710，33变更，完整manifest+frozen-target；旧一次freeze完整保留在 freeze-attempt01，其后只测试/报告变化。

protected共135304，含正式brief、全部既有证据、根audit、1253份.local/artifacts及903/906等物理冻结、旧backups/checkpoints/tool文件。八执行文档均SHA/字节一致。开始仅精确排除获准6个runtime目录及本任务精确自有路径；未宽排同stem brief或整个.local。唯一whole-after原扫描exit1保留：135301即时相等、2变化、1 EBUSY。root授权将两变化单列：精确 current-work-state.json由root写；精确 postgres.log原353992字节前缀SHA保持，只追加运行期日志。原after样本追加2058字节/4 lock_timeout+4 update app.application ERROR/0FATAL；最终窄观察追加2424字节，同4类ERROR/0FATAL。原JAR EBUSY后仅1次窄重读即87,276,524字节/SHA全等，最终135302静态项目一致。没有重写before/after为0差异，没有重扫或停止主服务。

精确并发新增 next proposal SHA独立核为 `5a7bef43ea57290367895cef38a029437f66733e0c6a3a636e34d1fc9aa9d308`；未读其内容扩大本组件。保护原结果、根追加处置、窄核验均在自有目录，机器交接另见 `handoff.json`/`verification.json`。

## 开源实读、自审与关注点

实际阅读固定 Spring6.2.7 TransactionTemplate.execute/rollbackOnException、AbstractPlatformTransactionManager.commit/processCommit/cleanup、DataSourceTransactionManager.doBegin/doCommit/cleanup、DataSourceUtils连接holder/timeout；文件原许可证、URL、SHA在 `upstream/manifest.json` 和 `upstream/READING.md`。生产注释对应事务边界、实际提交、独立语句重核及有意限制；root后续登记共享来源索引，作者未改其受保护正文。

自审 `self-review.md`；测试补强只增加真实pointer2 metadata恢复断言，生产实现未再变。保留的噪声包括：attempt01采样失败、green01 testCompile、green02异常映射断言、初轮javac varargs/既有deprecated/unchecked输出、几个错误文件名/Windows wildcard只读探查、汇总helper初次误把pom.xml当SurefireXML的TypeError（已单列tooling-noise）、final-payload02的344字节Surefire absolute-classpath/different-root dumpstream（完整冻结，无workaround/重跑）。构造未补处通过真实编译发现，不造stub绿；所有实际运行保持原raw/XML/source。

仍需后续真实gateway可信realm/metadata→main、engine收到snapshot后比真实四元组并接E1b、全status/requestId/TraceStore/material/owned调度、全Task5/portal/RR1/RR2/真实IAM验收。其它handler需逐条禁用/迁移后才能解除生产门禁。组件不承担序列化发送期撤权原子性，也不把内嵌guard预读当策略。

交付完成后归还 source/target 独占写权，等待独立Spec/Task quality双审。后续root执行文档登记及全局状态更新不包含在本作者source patch中。
