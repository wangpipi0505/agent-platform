# EX-27 Full 新增 outbox 失败：只读归因与测试隔离预检

日期：2026-10-29。范围：只读取源码、迁移、已有 XML/CSV/JSON、Git 状态；只新增本报告。未连接 PG，未运行 Java、Maven、HTTP 或测试脚本，未读取 `.env`，未修改 Test、产品、主入口或 index。本文是下一步技术门的输入，不是新测试结果或失败豁免。

## 结论与证据边界

本次失败直接发生在 **租户级 outbox 认领的持久事实主体与当前测试期望不一致**，不是缺少 `@BeforeEach` 身份初始化。`SkillOutbox` 用认领行的 `principal_id` 构造 SERVICE Actor；`SkillExecution.retry(call, actor)` 再按 tenant/call/principal 三条件读取该事实，并把同一个 Actor 传入 mock。实际 `task-result-tester` 因而说明此轮认领到了该主体的事实，而非当前期望 `skill-tester` 的事实。把产品主体硬改为当前 JWT 会破坏已验身份的持久恢复语义。

`SkillTaskResultTest` 确实在同一固定租户 `skill-tests` 留下可投递的 UNKNOWN 占位事实，是可解释此类串扰的明确源码路径。**尚不能指认本轮具体 callId、创建批次、due 值或历史来源。** 当前失败 XML 不记录这些字段；未执行历史 SQL。不能把“源码存在此写入路径”升级成“已经证明本次选中了该方法某次写入的那一行”。

推荐下一步仅修 `SkillExecutionTest` 的测试数据命名空间：每例建立新的 UUID 租户，统一该例 JWT、SQL、helper、outbox 和 artifact 清理范围。完整审阅未发现必须复用固定租户的跨例契约、跨租户负例或 secret binding。保留原主体/SERVICE/单次业务调用/自身事实 DELIVERED 断言，不清理未知旧队列，不扩大 `src/main`。

## 原始失败与对照

最新归档：`runs/algorithm-fix-20261029-032753-387/`。

| 证据 | 已核事实 |
|---|---|
| `full/xml/TEST-cn.agentplatform.skill.application.SkillExecutionTest.xml` | 25 例，24P/1F，0E/0SKIP；第 21 个报告为 `automaticOutboxRecoversTransientCallbackWithoutRepeatingBusiness`，0.32s |
| 上述 failure | `org.opentest4j.AssertionFailedError`；expected `skill-tester` / actual `task-result-tester`；断言 Test:757；调用 Test:767 |
| 项目调用栈 | `SkillOutbox.drainTenant:69 → SkillExecution.retry:1448 → deliver:1477 → mock SkillClients.post → Test:757` |
| `full/new-nonpass.csv`、`full/status-changes.csv` | 唯一新增非通过；原 PASS → FAILURE |
| EX-00 `baseline/20261028-164553-272/method-summary/methods.csv` | 同一方法原 PASS，0.446s |
| EX-03 `after/full-20261028-234917-584/` | 同方法 PASS，0.891s；该类 25P；当时模块 673=556P/7F/106E/4SKIP，新增 F 是原算法 GET_BODY |
| 最新 `full/summary.json` | 70 类、674=557P/7F/106E/4SKIP；原 664 与全部新增 10 身份齐全；原 116 非通过保留；算法 5 身份 P；门禁失败 |

最新原生 exit0 使用了原有 `failure.ignore`；collector1 与 `regressionGatePassed=false` 仍是有效执行停止。原 Full FAIL 必须保留。其他148源码未变是既有冻结清单事实，本报告没有把它解释成模块通过。

XML SHA256：最新 `9284d75ed367c3a4bf9c9273d9034f9d852b2c323377abbc919263f35c4e63d9`；原 EX-03 同类 `b4669e1f2d73bf079119fa35aaf0d510e182bc13df0bfd7e8a36e33172acbf7a`。

## 源码因果链

以下行号针对本报告记录的当前源码，不依赖 JVM 测试顺序推断。

1. `SkillExecutionTest:59–71` 每例设置 verified JWT，subject=`skill-tester`、tenant=`skill-tests`；`:261–265` 已在结束时 `clearContext()` 并关闭该例 endpoint。因此“加 BeforeEach/AfterEach 清上下文”不是针对本次 Actor 选择路径的修复。
2. `SkillTaskResultTest:47–62` 与前者连接相同 PG/skill schema；`:65–77` 的 verified JWT subject=`task-result-tester`，tenant 也为 `skill-tests`。`:105–110` 仅清上下文/MDC、停 endpoint，未清除持久事实。
3. `SkillTaskResultTest#unfinishedTaskIsAReadable409InsteadOfBlankResult:249–269` 直接插入 `UNKNOWN` fact（principal=`task-result-tester`，run=`r_result`，attempt=1，contract sha 为测试字面 `sha`），省略 callback/due/lease 列，然后只验证 taskResult 返回409。`V300__skill.sql:28` 默认 callback_state=`PENDING`，`V304__fact_delivery_lease.sql:1–4` 默认 due=`now()`、lease为空、attempts=0。该方法没有调用交付路径把这行变为 DELIVERED。`V308` 的一次迁移隔离只覆盖具有 REGISTERED/未发送外部 binding 的旧行，不能视为每例插入后自动隔离。
4. `SkillExecutionTest:747–766` 创建自身事实，模拟一次回调不可用，再恢复 mock，并把自身 due 改成 `now()-interval '1 day'`。`:767` 调用 `drainTenant("skill-tests",1)`，没有把当前 call 作为筛选条件。
5. `SkillOutbox:40–57` 在整个给定租户选择 callback=PENDING、due到期、lease空/过期的事实；按 `next_delivery_at` 排序，`SKIP LOCKED LIMIT 1` 原子认领并增加 attempts。查询不按当前 principal、skill、run 或测试 call 限定；相同 due 没有二级排序。
6. `SkillOutbox:62–69` 从认领行恢复 tenant/principal，仅赋 SERVICE/空 attributes；`SkillExecution:1438–1448` 用该 Actor 三元限定重读；`:1473–1477` 显式把它传给三参数 post。恢复路径不读取 `Actor.current()` 选择主体。执行初始持久化则使用已验 `Actor.current().principalId()`（`:293–300`、`:389–395`）。
7. mock 在 principal 断言抛出 `AssertionFailedError`，是 Error；两个生产 `catch (Exception)` 不会吞掉它，因此 XML 展现该断言的原始栈。此事实不授权把产品改成捕获 Error 或放宽断言。

因此可以确认“共享固定租户 + 全租户 drain + 另一主体持久事实”这个测试隔离缺口。对于“旧行”还需限定：自身 due 被回拨一天，**同一次 Full 刚插入、默认 due=now() 的占位行通常不会先于它被选中**。既有更早 due、历史回拨、lease/并发变化等才可能改变候选先后。仅凭当前源码与 XML，不能证明具体历史行或排除全部外部并发；也不能归咎算法 Test 改了身份。

## 单文件修改白名单及24处租户审阅

待 ROOT 登记并授权下一步后的代码白名单仅：

`skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java`

允许附加独立新运行目录与审阅证据。不改 `SkillTaskResultTest`、`SkillOutbox`、`SkillExecution`、迁移、公共 Actor、凭据、DB helper、SDK、collector 或测试选取/排序/超时/跳过规则。已有其他任务候选保持各自归属。

建议在实例字段声明 `String tenant;`，在 `@BeforeEach` 创建 JWT 前仅一次赋值 `tenant = "skill-execution-" + UUID.randomUUID();`（长度小于 tenant_id varchar128）。同例全部重试、来源绑定、权限收窄、版本更新与 artifact 操作必须复用它；不得在单个方法内部再次调用 prepare 或切换到新 tenant，避免拆散逻辑身份或泄漏 endpoint。

| 当前行号 | 数量 | 语义与处理 |
|---|---:|---|
| 65、399 | 2 | prepare 与实例 `scopeIdentity` 的 JWT tenant 均用实例 tenant；权限收窄仍在同租户进行 |
| 84、90、173、282、360、493、517、559、605、642、661、687、695、726、739、765、774 | 17 | 已参数化 SQL 的租户参数改为 tenant；包括 mock 查询当前 call、fact/history、artifact、本次 due/DELIVERED 查询 |
| 741、767 | 2 | `cleanTenant` 与 `drainTenant` 同步用 tenant；原清理上限8、outbox上限1保持 |
| 756 | 1 | mock tenant 期望用 tenant；principal=`skill-tester`、roles=`Set.of("SERVICE")`原样保留 |
| 853、860 | 2 | 两个 SQL 内嵌 `'skill-tests'` 改为 JDBC 占位符并正确插入 tenant 参数，不能直接替换为字符串拼接 |

两处参数顺序应为：connection_test `values(?,?,?,true,'{}',?)` → `tenant, id, sha, semanticDigest`；acceptance `values(?,?,?,?,true,1,'{}',?)` → `testId, tenant, id, sha, semanticDigest`。原“修改草稿后旧验收不能发布”的断言保留。

全文件只有24处 `skill-tests`。静态部分仅 db/transaction manager/ids 与 migrate，不存在静态 tenant helper；`contract()`、`scopeIdentity()`、mock 闭包均为实例上下文。未发现 authRef、SecretVault、secret binding 或刻意不同 tenant 的负例。`sourcePrincipal=skill-tester` 是同主体 LAST_REAL 绑定，应保持；`runId=r_test`、callId、attempt 与代码 UUID 的原语义无需扩散修改。大文件 MinIO 条件与清理方式保留，只缩到新租户。该改动不增加方法身份，现有25报告仍须全部出现。

只给出当前 outbox 方法一个新 tenant 而保留 setup/helper 的固定字面，会造成已创建版本、mock SQL、artifact 与 JWT 不一致；按方法名条件化 BeforeEach 或重新 prepare 会增加分支与资源管理。这些更窄的表面改动不如单类统一实例 tenant 简单可靠。无需创建测试命名空间框架或批量改其余类。

## 待授权后的执行顺序与验收

1. 冻结当前失败归档和当前四个相关源码 SHA；以该原始 Full 新增 F 作为真实失败证据。登记上述 Test 单文件范围与待执行命令，先做静态差异审阅。不得先改队列历史、重跑原失败凑绿或调整失败基线。
2. 只做该类每例租户隔离与两处 SQL 参数化，保留全部原测试、方法名、参数身份和断言。可在 outbox case 增加严格的“该新租户仅有当前 call 的一条到期 PENDING 事实”检查，并验证实际三参数回调 URL 等于当前 call 的 `/fact`；若加此检查，须按同租户/当前 call 查询，不能把未知旧行改成已完成。该加强只辅助证明选中自身事实，不能替代原 principal/SERVICE/DELIVERED/业务只调用一次断言。
3. ROOT 单独授予一个实际窗口后，先按既有 collector/JDK/环境运行该类（25个原报告，native0且无 failure.ignore）；如 ROOT 选择用同一有界定向命令包含未改的 SkillTaskResultTest，则应为28P，保持默认顺序/fork且保留两个类各自完整身份。不重复采样，不修改顺序来避开旧行。
4. 定向门通过才运行一次默认完整 skill 模块；本轮仍应70类、674身份，全部原664+EX03新增9+算法handoff1齐全，outbox原方法恢复P，算法5原精确身份继续P。若除本次修复没有其他变化，预期674=558P/6F/106E/4旧SKIP；最终按方法身份和原116非通过的类型/原因/项目栈逐项比较，不能只看总数。原 failure.ignore 的 native0仍不代表全绿，模块方法门与 collector 必须单独明确。
5. 新增任何非通过、丢报告、扩大 skip、旧失败原因变化或源码/环境漂移，立即保留原始结果并停在技术门；不关 EX-27 SDK 子步骤或 EX-03。模块门通过后再做独立审阅和主入口进度回填，保留所有既有失败/诊断历史。

当前报告没有执行步骤2–5，没有声称其通过，也没有改变台账状态。

## 可选历史取证：仅方案，本轮未执行

隔离修复不依赖删除或识别那条旧行。若 ROOT 要进一步证明来源，可另授只读窗口，在既有 owner 已核 PG 上开启 READ ONLY 事务，以失败实际窗口 `2026-10-29 03:29:06.8257279+08` 至 `03:32:13.8391840+08` 为边界，查询固定测试 tenant 下 principal=`task-result-tester` 的有限候选列：call_id、skill_version_id、principal_id、run_id、attempt_id、status、callback_state、created_at、updated_at、next_delivery_at、delivery_owner、delivery_lease_until、delivery_attempts；按 next_delivery_at/call_id 排序并 LIMIT 100。可额外输出 request_snapshot/result 的 SHA256 摘要，禁止输出其原文或凭据。

进一步核对候选是否符合 `r_result`/attempt1/`sha` 的直接插入形状，以及 lease 是否曾接近失败时刻+90秒。即使当前只剩一个候选，也必须说明表可变：AssertionError 后未正常释放的 lease 会自然过期，后续运行或调度可能改动 owner/due/state。没有当时调用ID日志、审计快照或唯一可证明的时间链时，只能报告“相符候选”，不能追认唯一历史来源。查询仅 SELECT，不加锁，不认领、不 UPDATE/DELETE、不执行 outbox、不连接其他库。

## 源码指纹

| 文件 | SHA256（本次只读） |
|---|---|
| SkillExecutionTest.java | `14ae4eea65c05ebeebdbdf16d3fc4733d4613fdec20ee7644df56db83627194b` |
| SkillTaskResultTest.java | `600f1a9df2d5f0199a52f7f2152d339f2e6c6d78839382c30c5ba9c5e7fa164f` |
| SkillOutbox.java | `30bae61b51db4df75462c5d92badc5455fbec1fc7437856cd1d9a9da2da32db7` |
| SkillExecution.java | `8cd3d8edae7c0dee54dd562c6f2f3f3664759de85d122f70c1e00b9bc6d25218` |

相关源码在本次开始/写报告前均无 tracked diff；index为空。本报告不提交或暂存文件。后续实际运行仍以重新冻结的源码与证据清单为准。
