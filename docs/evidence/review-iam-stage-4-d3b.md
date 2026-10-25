# D3b task-scoped independent review

## Spec Compliance

- ✅ **Spec compliant（限 D3b 真实 published payload 组件）**。未发现本次范围内的缺失、越界功能或错误实现。`app-management/src/main/java/cn/agentplatform/app/api/AppController.java:157` 的真实 published handler 显式区分 iam/test/dev-hmac，未知 mode 拒绝；IAM 不捕获异常后回落。构造器显式注入 AuthorityProperties。两生产、五测试文件的范围与正式 brief/root-decisions 一致。
- `app-management/src/main/java/cn/agentplatform/app/application/AppService.java:576` 先拒绝 ambient transaction，并核 caller/target/action/root kind/PORTAL origin 与允许 source；`:652` 仅查询 application/current app_version 的窄 metadata，tenant 来自 Actor。`:593` 附近按真实当前 pointer/no/stored SHA 重建 root，旧同 code 版本冲突409，错 code/kind/当前 SHA 拒绝403。停用、归档、缺指针/悬空指针409、缺应用404，DRAFT+pointer 保留。
- `AppService.java:610` 在事务外显式刷新一次；`:617` 先 application FOR SHARE，`:621` 本地 bound，`:626` 下一条独立 READ_COMMITTED JOIN 重读并比完整 metadata record，`:630` 再 bound 后才读 payload。`:41` 设置独立 RC 模板，模板超时3秒、事务内 statement_timeout2秒/lock_timeout1.5秒，未设 readOnly、无 REQUIRES_NEW 或续票重试。
- `AppService.java:631` payload 谓词包含 tenant、app_id、app_code、真实数字 id、version_no、stored candidate_sha256，非单凭签名 root 的数字猜测。`:672` 验证 snapshot appCode/versionNo/candidateSHA/原字符串 appVersionId，再投影 appVersionKey 与真实数字 appVersionId/versionId；未把返回 JSON hash 当候选摘要。`:638` callback 结束前及 `:644` tx.execute 真提交返回后再次本地 bound，完全物化 Map；失败无成功局部结果。
- ⚠ **其它合法 action 的资源绑定与全局 IAM 部署仍未签收**：`AppController.java:134`、`:146`、`:112` 附近的 version/snapshot/input-schema 等 handler 保持各自旧能力；`PublishedPayloadHttpTest.java:175` 仅证明 published 票不能撞这些入口。保留 BLOCKED_IAM_SCOPE_ACCEPTANCE，不得据此改 IAM_SCOPE_ACCEPTED；真实 GatewayController→RunController、engine 接收四元组/E1b、新建运行及后续 obligations 不属于本组件通过范围。
- ⚠ **legacy 测试是 controller 分支回归，不是完整 legacy 部署验证**：`app-management/src/test/java/cn/agentplatform/app/application/PublishedPayloadHttpTest.java:51` 只让注入 controller 的 AuthorityProperties 随 mode 变化，`:66` 的 TrustedJwtDecoder 与真实 authority client/ServiceAuthorization 仍使用 IAM fixture。`:195` 的 test/dev-hmac 成功证明旧 published 投影分支可用，不能证明生产 legacy 认证装配；本次仅要求最小 controller 迁移，无需以此扩大测试或宣称部署通过。
- ⚠ **边界声明正确**：`AppService.java:641` 的返回前检查不覆盖 Servlet 序列化/最后网络字节；`:676` 的 config.guard 仍属 payload，不构成独立 metadata 策略。精确 payload 0行分支由源码核验，未声称有破坏不可变版本触发器的动态覆盖。
- ⚠ **保护全扫描由 root 单独负责**：本 reviewer 未重复135304原始保护项扫描或135302静态原件校验。作者原 after exit1、两个精确授权变化与一次 EBUSY 后窄重读，按 `docs/evidence/iam-stage-4-d3b/root-decisions.md:8` 和作者报告`:70` 保留原事实；任务质量结论不替代 root 的最终保护审计。

## Strengths

- `AppService.java:610`、`:617`、`:626`、`:638`、`:644` 的网络刷新/锁等待/新语句快照/提交边界清晰，既避免在线刷新持锁，也避免把等待锁的 JOIN 列当作最新版本。生产新增集中在指定两个文件，没有新增业务 JAR 互引或故障注入 hook。
- `PublishedPayloadHttpTest.java:47` 的真实 AppController/AppService、MappedInterceptor、RouteCoverageVerifier、security/decoder 与真实 HTTP authority 装配，`:87` 的固定隔离 PG/V100–V104，以及 `:143` 的 metadata→独立 main Grant→engine forward 对照，有效验证组件，而非 Map/H2/伪 handler。`:155`、`:156` 覆盖合法 current 收窄和真实 resume 的 DELEGATED source/PORTAL origin。
- `PublishedPayloadHttpTest.java:203`、`:214` 通过真实另一连接改 pointer/status证明刷新前/响应等待中未持应用锁；新 pointer 恢复先实际 metadata HTTP，再新 main。`:218` 实际 writer SQLState55P03与 reader完成后 writer成功，同时核 pointer/status 两种写；`:229`、`:230` 分别证明 payload已读后、真实commit已完成后的过期不会返回成功 data。`:63` 的测试装饰器先 super.doCommit，再暂停；不以假提交代替 PG。
- `PublishedPayloadHttpTest.java:108` 的逐例 SQL/current/write/commit 记录与有限同一行更新，支持失败后无锁泄漏和生产写0；`:161`、`:164`、`:168`、`:176`、`:189` 验证缺初始 proof、错 root/身份、缺票/错误方法、不可用状态和 ambient Tx 的拒绝路径。所有 CountDownLatch await 都有5秒上限，关键并发释放位于 finally。

## Issues

### Critical (Must Fix)

- 无。

### Important (Should Fix)

- 无。未发现需要阻断本组件的生产正确性、授权绕过或证据适用性缺陷。

### Minor (Nice to Have)

1. **锁等待用例有单次观察的调度窗口** — `app-management/src/test/java/cn/agentplatform/app/application/PublishedPayloadHttpTest.java:223`（结合`:57`、`:226`）。attempting latch 在 beforeSql 回调触发，早于 super.query 真正发送 FOR SHARE；主测试线程随即单次读取 pg_stat_activity，没有 happens-before 保证此时 PG 已进入 Lock 等待。调度差异可能使正确实现偶发失败。现有最终运行实际观测到 Lock/blocker，且断言严格，不会把未等待误判成功，因此不否定本轮证据、级别为 Minor。后续可采用短且明确上限的有限观测等待，确保真实 Lock 状态后再推进 Clock；保持有限截止与 finally rollback，不做压力轮询或为本评审重跑。
2. **最终测试输出并非 pristine** — `docs/evidence/iam-stage-4-d3b/iam-stage-4-d3b-final-payload02/2026-10-20T18-54-20_399.dumpstream:2`。实际344字节文件报告 Boot Manifest-JAR absolute classpath / different-root 信息。对应 `raw.log:126`、`:139` 为24 tests、0 failures/errors/skips、BUILD SUCCESS；没有证据说明它损坏结果，但应保留为工具链噪声而非称“无警告”。本轮无需按提示添加 workaround 或重复运行；后续工具链维护可单独定位 Windows classpath 根处理。作者报告`:78` 也披露旧轮 javac varargs/deprecated/unchecked、green01编译装配失败、green02异常类型断言及采样/helper噪声；这些是不同历史轮次，不是最终源码24项失败。

## Checked scope and evidence applicability

- 唯一 shell workdir 为 `G:/207/项目/算法重组工具/代码/agent-platform`。未读取相邻旧工程、.env、vault/master key或真实凭据；未运行 Git、Maven、测试、DB/HTTP探针或新 agent。仅新增本评审报告，未改 source、target、index、执行文档或旧证据。
- 已先读正式 brief、作者报告、精确 root-decisions 与 task-reviewer-prompt；完整2940行 review package 按不超过250行的连续块读完，无源 diff 输出截断。未另外读取改变文件；之后仅从已评审 diff提取行号定位。实际 package为172707字节/SHA256 `2c46744db2234ae2516e14188f9de12b805d9df282d2c1d5b06a0a4abec9b66d`，无 HEAD/commit。brief SHA `7b1c61112b8a9cd0bfade73c831f7cde1c7afa7582b25e83ffc090fe695bfe48`、报告 SHA `964300173a47aff513abac5f992df77942ce2c4f1e0cd1fccd7c643010082664`、v1.2 snapshot SHA `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f` 均实际核对。
- 聚焦不变代码风险一：**本地 bound 是否真的校验同 frame、初始 proof 和可信 Clock期限，而非仅比较对象**。读 `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:33`–`:65`，核同一 retained 引用、失败 invalidation、刷新前后 actor/call/operation/proof 一致性；聚焦 `HttpCurrentAuthorityClient.java:103`–`:126` 的私有 proof构造与 requireVerified，及 `AuthorityProtocol.java:65`–`:68` 的 current/delegation/operation/ticket期限检查。局部检查无网络调用。
- 聚焦不变代码风险二：**fixture 的 decision mutation 是否跨用例遗留，或“合法收窄”只是未触发的标签**。仅定位 `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:48`–`:49`、`:126`；clearFiniteGrants重置decision hook，narrow函数在第二次有roles的decision后清空roles；与已读新测试seed/两个current断言相符。未扩大 common 审查。
- 阅读 `.local/iam-stage-4-d3b-root-fast-check.json` 的独立root核验结果；357 live/frozen/replay、356/1695 before物理样本、1710 final target及7轮 raw/Running/XML/source的全面核对由root完成，本 reviewer未冒称重做。**最终完全同357源码：final-payload02 payload24/0F/0E/0skip；历史final01为33/0，其中metadata9依赖最终不变356子集；唯一smoke01为1/0，同样基于该不变356子集。不得将这些加成一次最终同源总数。** ApplicationLifecycleTest/PublishTransactionTest仅改构造且testCompile，不声称运行其主库suite。
- 评审自身噪声：一次误把common模块路径写为common，读取/rg报不存在；随后按精确文件名定位时返回包含冻结证据副本的过长路径列表，输出被截断。未读取这些副本内容；之后只读实际admin-common上述指定风险位置。该文件定位噪声不涉及完整diff阅读截断、测试执行或任何修改。

## Assessment

**Task quality: Approved（D3b有限组件；两个Minor保留）。**

**Reasoning:** 生产顺序与授权期限检查符合正式任务，真实HTTP/PG/提交边界对照能支持有限组件结论；历史运行与最终源码的适用性被明确分开。锁观测的偶发调度风险和Surefire噪声不构成本次生产阻断，跨任务/部署/全局保护审计事项继续保留，不签全D3/E或生产IAM验收。

**Ownership:** source/target保持归还root且未触碰；本 reviewer新增报告后归还评审输出，无后台进程或未结束工具任务。