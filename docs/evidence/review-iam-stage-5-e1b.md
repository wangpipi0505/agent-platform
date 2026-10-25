# E1b independent task review — Spec compliance + Task quality

## Spec Compliance

- ❌ **Issues found**：成功检查缺少数据库写入后的证明期限终检，并且提交返回后直接交付原 VA。`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:108` 只在 UPDATE 前检查；`:110` 的 UPDATE 可以等待行锁，`:112` 随后提交；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:33` 直接返回。违反 `docs/evidence/iam-stage-5-e1b-brief.md:81` 的新证明期限检查与可推进 VA 返回要求。详见 Important I1。
- 已核对本切片的限定目标：真实 INSERT RETURNING、同 DataSource 调用方事务、完整 initial/origin 与三种真实初始材料、普通 read 和后台 current 的不同绑定规则、唯一 local tuple、不可变元数据与版本 CAS。对应 `RunAuthorityStore.java:22`、`:54`、`:57`、`:80`、`:98`，`MaterialScopeStore.java:30`、`:59`，`RunAuthority.java:20`，均为上述 engine-runtime 生产目录下文件。
- ⚠️ 本次不能签收完整 Task5、真正 MVC/filter/controller 方法体、已发布 FLOW、owned scheduler、全材料或 RR1/RR2；`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityFixture.java:50` 使用真实 guard 和真正 handler Method 建立 frame，但没有执行 controller 方法体。真实 app 发布指针、显式重新授权代际、全材料和真实调度仍是必要后续，见 `docs/evidence/iam-stage-5-e1b-brief.md:109`、`docs/evidence/iam-stage-5-e1b-report.md:92`。I1 是现有组件自身的问题，不是将后续 scheduler 工作提前要求到本切片。
- ⚠️ 原 v1.2、四契约、固定依赖版本、生产 IAM 启动闭锁及全工程保护清单的跨任务事实，按 root 已实际核验的交接证据接收；本次未重新遍历或重跑这些跨任务检查。变更包本身显示有限 16 文件及 engine POM 仅新增 existing common test-jar 的 test scope，见 `docs/evidence/review-iam-stage-5-e1b-package.diff:3`、`:1083`；三新生产类均无 stereotype。

## Strengths

- **新 run 和授权材料具有明确的事务归属。** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:22` 要求同 DataSource 真实写事务，`:29` 对 RuntimeException/Error 标记其 ConnectionHolder rollback-only，`:35`、`:69` 前后 requireBound；`:54` 持有真实 INSERT RETURNING，`:57` 写 immutable admission，`:63` 写三 scope。没有 public 任意 runId 补权或 upsert。`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityPersistencePostgresTest.java:38` 验证调用方捕获授权失败仍回滚；`:45` 验证 authority、三 scope 和随后 node SQL 故障全回滚。
- **快照和预算校验符合正式澄清。** `RunAuthorityStore.java:40` 复用 MetadataSelectors.code，`:41`–`:46` 校验真实正整数版本、两 ID 一致、root/candidate/appVersionKey；`:47` 保留 requestId 原值且仅 FULL；`:130`–`:136` 检查快照预算/default、consumed 四项零及 deadline 上界。正式裁决在 `docs/evidence/iam-stage-5-e1b/root-decisions.md:4`。无额外生产装配或业务调用图接线。
- **历史材料读取没有被错误钉死到旧 Grant。** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:30`–`:42` 先查 metadata/scope、用途、owner/root、current 覆盖，后固定 switch 读单列并核 SHA，返回前再次检查 current。`:95`–`:103` 使用同 DataSource 的 REPEATABLE_READ，并拒绝低隔离级别 join。`InitialMaterialScopePostgresTest.java:20` 证明旧 Grant 已过期、新 same-owner initialABC/Grant 可读旧 AB；`RunAuthorityLocalCurrentPostgresTest.java:26` 证明后台永久阻断不剥夺合法普通读取。
- **真实 PG 内容和历史 scope 绑定完整。** `MaterialScopeStore.java:23`、`:83`–`:92` 对实际 JSONB text 作 BigInteger/BigDecimal 精确解析和私有规范化；`RunAuthorityStore.java:56`、`:64` 分别计算真实 snapshot version 与各列 payload SHA。`MaterialScopeStore.java:71`–`:80` 验 owner、admission、initial floor/hash，`:67`–`:68` 校验同 run 的真实 parent；`RunAuthorityStore.java:61` 保留 initial/current/config labels 并集。`InitialMaterialScopePostgresTest.java:54`、`:104` 的测试包含高精度相邻小数、超 double 范围整数、数组顺序、nested null 和 signed zero；`:116` 的重算 scope hash 负例不能伪造 parent/owner/admission/floor。
- **原绑定不可被普通检查修改。** `engine-runtime/src/main/resources/db/migration/V216__run_authority_initial_materials.sql:24`–`:45` 保护 run authority 绑定、材料行及已有 authority run 的 owner/version/三 payload，同时保留业务状态等字段可变。`RunAuthorityStore.java:80` 只读窄 run metadata、authority 和 scope，不从旧 payload 回填；`RunAuthorityPersistencePostgresTest.java:86` 验历史 run 无 authority 时拒绝且 payload SELECT=0，`:124` 验数据库不可变保护与状态仍可更新。
- **local 权限是受限的组件能力。** `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceRoutePolicy.java:52` 只定义固定 tuple，不新增 HTTP route；`HttpCurrentAuthorityClient.java:190` 仅允许 local resume/authorize；`TrustedJwtDecoder.java:106` 在签名、时间和 profile 验证之后处理 local scope。`admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:356` 要求 PORTAL-origin main originalCall 且相同 root，首次 exchange 的 gateway 身份校验仍在 `:187`。`LocalRunAdvanceAuthorityTest.java:35` 覆盖 app-read、metadata、debug、status 原 Grant 拒绝；`RunAuthorityLocalCurrentPostgresTest.java:89` 验真实 HTTP guard 拒绝 local 票。这些证明不等于生产 IAM server 验收。
- **current 的网络与 CAS 边界大体正确。** `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:21` 拒绝调用方事务；`:29`–`:30` 仅 resume、authorizeRetained 各一次；`:24`–`:25` 拒绝永久阻断和未到期重试。`RunAuthorityStore.java:99` 验公共成功入口必须有 Retained proof，`:101` 不允许清永久/未到期临时阻断，`:105`–`:111` 重读完整 binding、验 scope、按 checkVersion CAS。`RunAuthorityLocalCurrentPostgresTest.java:57` 覆盖并发旧 CAS 不返回授权，`:67` 覆盖缺 proof 拒绝，`:80` 验未知 DB 错误原样传播。

## Issues

### Critical (Must Fix)

- 无 Critical。I1 会使本组件返回过期授权，属于本切片必须修复的 Important；没有把未部署组件描述为已发生生产越权事件。

### Important (Should Fix)

- **I1 — 数据库锁等待期间证明到期，仍可提交成功检查并返回过期 VA。**
  - 位置：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:108`–`:112`；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:32`–`:33`。
  - 已由代码确定的触发：事务 A 对同一 `engine.run_authority` 行持锁，但不改变 checkVersion 或任何 binding 字段。事务 B 已完成网络检查，`:105` 的普通 SELECT 可读到原已提交行；`:108` 的 proof 尚有效。B 在 `:110` UPDATE 等待 A，等待超过当次 decision/ticket/operation 任一期限；A 释放锁后版本仍匹配，UPDATE 返回 1。没有 UPDATE 后的 `requireVerified`，TransactionTemplate 提交该成功观察，随后 current 返回原 VA。权限检查的网络和 SQL 先后顺序不能消除此等待窗口。
  - 事务内缺陷与提交返回边界分别处理：在取得更新结果后、仍在事务内再验 proof 和可信 clock，期限失效应抛异常回滚此次成功写入，不能清旧 503 或增加成功 checkVersion。即使增加这一点，commit 自身也可能耗时；提交返回后、向调用方交付 VA 前还应再验当次 proof。提交前有效而提交返回后才过期时，已提交的历史检查记录无需伪称可以回滚，但该调用绝不能返回可推进 VA。无需承诺返回之后永不失效；后续实际推进时的检查仍属于 scheduler/driver 接线义务。
  - 现有证据不能回答此疑点：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityLocalCurrentPostgresTest.java:57` 在网络期间写入永久阻断并提高 checkVersion，验证的是 stale CAS0；`RunAuthorityPersistencePostgresTest.java:80` 验 create 的终检。两者都没有覆盖本例的“只持锁、版本不变、UPDATE 等待后到期”。
  - 建议有限具名验证（本审查未执行）：新增 `successExpiresWhileCasWaitsOnUnchangedRow`，用两连接与闩锁持锁，在 CAS 真正进入等待后推进可信 fixture Clock 超过 proof 期限再释放，断言不会提交成功/清旧阻断、不会返回 VA；另用有限连接包装在 commit 返回前推进 Clock，新增 `currentDoesNotReturnProofExpiredDuringCommit`，断言不交付过期 VA。无需重复绿色 suite 或增加全 scheduler 测试。

### Minor (Nice to Have)

- **M1 — 早期 XML 归档窗口和补丁字节重放噪声已保留，后续不得将其误计为绿色执行证据。** `docs/evidence/iam-stage-5-e1b-report.md:74` 记载 120 秒 mtime 归档夹带未执行 XML；`:88` 记载 attempt01 apply exit0 但字节不一致、局部证据 `.gitattributes` 修正，以及最终 apply 的 13 行 CRLF whitespace 警告。逐轮 execution-audit 已区分实际 Running 类和 incidental XML，最终两轮 incidental=0，最终实际重放逐字一致由 root 核验。作为历史证据质量 Minor 保留；不要求倒跑测试或为消噪修改冻结源码/工程属性。
- **M2 — 测试失败和启动诊断必须继续按真实原因分类。** `docs/evidence/iam-stage-5-e1b-report.md:69`–`:73` 分开记录 local-red01 运行错误、persistence-red01 setup 失败、两个新 API stub 红、精确数值 JsonNode 类型断言噪声，以及真正发现半成 run、无 proof 成功入口、非法 appCode、guard_ctx owner 缺失的红测。common179 中旧启动闭锁负例为 39 条 ERROR，ERROR/Exception 合并匹配 77 行，并非新增 XML 失败；PG 初建 Flyway WARN、Surefire Windows 诊断保留。最终 engine35 无 ERROR/WARN。它们属于日志质量 Minor，不据此禁用 JDK 检查、补默认 IAM bean 或重跑旧绿色轮。

## Focused checks and evidence boundary

- 审查输入实际 SHA256 已核：`docs/evidence/iam-stage-5-e1b-brief.md:1` 为 `68be5835eb98f01a31ab56fe43d15af228a9e7341aaca0bb561b4083cdca0f60`；`docs/evidence/iam-stage-5-e1b-report.md:1` 为 `7070a68c00a6b0f52d14735ac0a78ae39d407ff729981d50f9d627e2efdca5db`；`docs/evidence/review-iam-stage-5-e1b-package.diff:1` 为 `cb2d6e4490165e0bc11327e69b17d3b48510a6dbd4457b8899184587707b1410`。
- 完整 1952 行 context 包按不超过 280 行的连续片段读完一次；没有再读变更源文件，也没有重新执行 Git。初次 brief/template/report 合并输出有截断，随后补齐 brief 的第 64–119 行；审查包本身无截断补读。基线是独立通过的 E1a 346 项，最终 356 项、16 文件变化，无 HEAD/remote，见包 `:1`。
- 具名风险“跨 JVM 集合顺序导致 CAS/摘要误拒”：变更包中的 `RunAuthorityStore.java:107` 使用解析后 Binding.equals，SQL 条件只有 run_id/checkVersion，没有把重序列化 JSONB 数组用于相等条件。聚焦检查未变 `admin-common/src/main/java/cn/agentplatform/common/Actor.java:14`、`authority/MaterialScopeV1.java:7` 的 Set/Map 值语义，以及 `authority/AuthorityProtocol.java:39`–`:46` 的集合递归排序和 `common/Json.java:57` 的规范化；本实现没有此类缺陷。不做跨 JVM 探针。
- 具名风险“Binding/proof 的日志串泄露可用 Grant”：聚焦检查未变 `admin-common/src/main/java/cn/agentplatform/common/authority/DelegationRef.java:7`、`VerifiedAuthority.java:6`；Grant 引用的 toString 已 redacted，变更包中 RetainedAuthorization 的 toString 只显示 initialProof 布尔值（`HttpCurrentAuthorityClient.java:128`）。Binding 默认输出可见 owner/initial/scope 审计信息，但本变更未新增日志调用或输出原票/原 Grant。未据假设日志使用路径提出 Important。
- 具名风险“公共 recordCheck 缺 fresh proof、scope 或可清阻断”：由变更包 `RunAuthorityStore.java:99`–`:116` 检查通过，唯独期限在数据库等待之后仍有 I1。具名风险“同 DS 加入后调用方 catch 仍提交”由 `:24`–`:30` 的 rollback-only 和 `RunAuthorityPersistencePostgresTest.java:38` 已覆盖；没有重新运行原探针。
- 具名风险“历史 scope floor/hash 或假 parent 被重算掩盖”：`MaterialScopeStore.java:67`–`:80` 将 scope 的 floor/admission/owner/version 和原完整 initial 绑定，同时验证同 run parent；`InitialMaterialScopePostgresTest.java:116` 有重算 hash 后负例。普通 read 在 `MaterialScopeStore.java:42` 再次验期限，未发现等同 I1 的 payload SELECT 后漏检。
- 具名风险“迁移预检在 V216 已存在后的可重复性”：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityDatabasePreflightTest.java:9` 只在固定库不存在时创建，`:17` 明确 target215；它是分配 V216 之前的预检，不在最终三类 engine35 中。未在现有 V216 库重跑，也不把一次预检成功外推成全 engine suite 可重复性；现有代码与本任务有限执行范围不足以认定阻断缺陷。
- 已阅读最终两个 `execution-audit.json`：`docs/evidence/iam-stage-5-e1b/iam-stage-5-e1b-final-common01/execution-audit.json:4` 列 18 XML/179 tests，`docs/evidence/iam-stage-5-e1b/iam-stage-5-e1b-final-engine02/execution-audit.json:4` 列 3 XML/35 tests（12+10+13），均 0 failures/errors/skipped、incidental 0。root 已实际核验 raw Running/XML SHA/计数和全部 20 轮，本审查没有再称自己重核原 raw/XML 字节或重新执行这些测试。
- 证据适用性严格限定：common179 当轮完整356快照与最终356只有两个 engine 文件不同，精确77项 common+root POM 一致；最终 engine35 对应最终356。故两个最终结果分别适用于 common 和 engine，不能说 common179/engine35 是最终全356同码双跑。见 `docs/evidence/iam-stage-5-e1b-report.md:38`。
- root 交接已核 live/final-frozen/replay-final/final-engine02 的356源字节一致、311 compiled freeze、1692 target 实际 SHA、其他789 target不变、common+engine903 target物理备份、26367原保护文件不变及唯一新proposal SHA、8 upstream SHA。审查读取了 `docs/evidence/iam-stage-5-e1b/upstream/READING.md:5` 的具体方法/不变量记录，不冒称再次全文阅读八份上游源码或独立重做 root 的文件审计。
- 本审查仅写 `docs/evidence/review-iam-stage-5-e1b.md`，未修改 source/target/index/Git/数据库，未运行 Maven、作者绿色 suite、原探针或生产部署；只读审查所有权现归还 root。

## Assessment

**Task quality: Needs fixes**

**Reasoning:** 持久绑定、精确 JSON、普通历史 read、local tuple 和大部分事务/CAS保护实现具体且有真实 PG 组件证据。但 I1 允许数据库等待跨过证明期限后仍登记成功、交付过期 VA，必须补齐事务内及提交返回边界的期限验证并用有限具名测试确认，随后再审此切片；完整 Task5 门禁继续保留。

---

## 2026-10-20 — I1 fix independent re-review (append-only)

### Spec Compliance

- ✅ **Spec compliant — I1 Closed.** 本次仅复审正式 I1 fix，原唯一 Important 已关闭。`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:110` 先取得真实 UPDATE 结果，`:114` 在同一事务内验证同一 Retained proof；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:35` 在 recordCheck 的真实提交返回后、交付 VA 前再次验证同一 proof。满足正式 fix brief 第11–16行所要求的两个边界，未加入生产 hook、授权延期、锁内网络或公共 API 形状变化。
- ✅ 原 E1b 审查前缀中的 Needs fixes 是修复前历史结论；结合原审查其余通过项与本次有限复审，E1b 组件当前两门为 **Spec compliant / Task quality Approved**。原 M1/M2 历史噪声继续保留，本次没有将它们改写成已从历史日志消失。
- ⚠️ 完整 Task5、真实 MVC/filter/controller 方法体、真实业务调用图/调度、显式重新授权、全材料、RR1/RR2 和生产部署仍 pending。此结论只闭合 E1b 组件及 I1，不签全任务；见 `docs/evidence/iam-stage-5-e1b-i1-fix-report.md:80` 及本报告原 Spec Compliance 的范围说明。

### Strengths

- **公共仓储入口自身堵住锁等待后的成功提交。** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:98` 保留成功/失败参数互斥、fresh proof、全部 scope、完整 Binding、可清阻断及 checkVersion 条件；`:114` 只在实际 UPDATE 成功且有 proof 时执行同一 proof 的 requireVerified。异常离开 TransactionTemplate callback 使此次成功更新回滚，未增加新事务或补偿框架。版本不匹配的 CAS0 仍返回 false，不被误当成功。
- **提交后的交付检查保持数据库事实。** `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:32`–`:36` 明确区分已提交检查和可交付 VA。proof 在 commit 期间才到期时，`:35` 抛原 AUTHORITY_EXPIRED，`:41` 的失败记录沿用旧 Binding；`RunAuthorityStore.java:107` 重读后发现版本不同即返回 false，因此不覆盖已提交成功观察，随后仍抛原到期异常。没有伪称可以回滚已经完成的 commit。
- **行锁测试确实复现原 Important，而不是改版本的旧 stale-CAS 场景。** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityLocalCurrentPostgresTest.java:19` 创建旧临时503，等其 retry 到期后取得真实 signed local proof；`:28` 锁住确切 run_id，`:30` 在另一线程调用公共仓储。`:34` 按该连接 backend pid 查询 PG Lock 等待且匹配实际 authority UPDATE；`:36` 确认 Binding 未改变才推进原可信 Clock 六秒，finally 释放锁。`:38` 断言原 AUTHORITY_EXPIRED，`:42` 断言完整 Binding 和旧临时阻断保持，`:43` 断言业务 PENDING。测试既覆盖 public recordCheck，也不会靠 application 的 catch 伪装回滚成功。
- **commit 测试先真实提交，再推进期限。** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunAuthorityFixture.java:66` 先 method.invoke 委托实际 Connection 方法，`:68` 仅在本连接成功 authority UPDATE 已标记时执行 afterSuccessCommit；`:72`–`:74` 检查非空成功 decision 参数和实际 updateCount=1，load/create/失败检查不会误触发。`RunAuthorityLocalCurrentPostgresTest.java:49` 用底层 DataSource 的独立连接读到 checkVersion+1，确认数据库已经提交，然后推进同一 Clock。`:50`–`:55` 验到期拒绝、hook仅一次、已提交版本/decision保留、没有新增永久阻断、initial/scopes不变。hook未在事务 callback 中提前模拟 commit，未替换真实 PG/repository。
- **修改限于四个授权文件。** `docs/evidence/review-iam-stage-5-e1b-i1-fix-package.diff:2`、`:53`、`:217`、`:309` 分别是两个生产类和两个测试文件，生产逻辑只增加两个本地期限终检。原网络在调用方事务外、构造注入、未装配状态和未知 DB 异常传播均保持；没有共享生产变更要求扩大 common 测试。

### Issues

#### Critical (Must Fix)

- 无。

#### Important (Should Fix)

- 无未闭合 Important。原 I1 已由两个实际代码边界和两个真实 PG 行为红→绿覆盖关闭，见 `RunAuthorityStore.java:114`、`RunAuthority.java:35`、`RunAuthorityLocalCurrentPostgresTest.java:19`、`:46`。未因未来 scheduler 的检查义务重新提出同一组件问题。

#### Minor (Nice to Have)

- 原 M1/M2 保留为历史证据/日志质量项；本次 `docs/evidence/iam-stage-5-e1b-i1-fix-report.md:37` 的 launcher-red01 使用不符合闭合文法的加号，未执行 Maven，不算行为红；`:38` 的 red02 commit 侧是测试 hook 错用绑定 RC 事务造成的 REPEATABLE_READ_REQUIRED，不算第二个边界的行为红。`:39` 的 red03 才是两个原实现真实行为红，`:40` 的 green01 为受影响 current 全类12/0F/0E/0S。分类透明，不要求为历史噪声复跑。
- `docs/evidence/iam-stage-5-e1b-i1-fix-report.md:50` 的三份 Surefire Windows 根路径 dumpstream 是保留的诊断，不是 XML 错误；green raw 无 ERROR/WARN。未禁用 JDK URL 检查，亦不以此要求重跑 common179、旧35或无关 suite。
- `docs/evidence/iam-stage-5-e1b-i1-fix-report.md:65` 明确披露初始保护采样遗漏两个 .local parent artifact 路径。随后 `.local/iam-e1b-i1-parent-artifacts-check.json:3` 对903旧target备份核对历史不可变清单，`:7`–`:10` 记载原root audit从原完整tool result及旧输入确定性重建后逐字匹配，同时明示 directPreFixRootAuditShaSampleAvailable=false。本次接受 root 的后续独立核验，并保留“没有直接事前SHA采样”的证据边界；不是伪造事前保护，未发现需要重开 I1 的源码/target漂移。

### Verification and evidence limits

- 正式 fix brief 实际 SHA256：`72b1738cf225468fb6031cc294341be5c213abd6f22a2b4be50ca34aaf71957b`；fix 报告实际 SHA256：`3a9e1bf262afbf286265149d664abcb91630b7356e06aa908c4b667038485891`。完整 fix-only 包42170字节、453行，实际 SHA256 `d8e7f32deaba4c014f18563ef34d981682d0b86c6f8c06c56cd2c87e046b3659`；按225行、228行两段完整阅读一次，仅另作包内行号映射以核引用。没有重读变更源文件或原1952行包，也没有执行 Git/Maven/探针。
- 已阅读全文 `.local/iam-e1b-i1-fix-root-check.json:1` 和 `.local/iam-e1b-i1-parent-artifacts-check.json:1`，接收 root 独立核验的356 live/final-frozen/replay/green源字节一致、77项 common179适用源不变、1695target、1132非engine target不变、906 common+engine target物理冻结、311compiled、36384采样保护项不变。没有声称本复审另做了一轮全树字节审计。
- 已阅读 `docs/evidence/iam-stage-5-e1b-i1-fix/iam-stage-5-e1b-i1-fix-red03/execution-audit.json:4`：唯一实际 current 类，2/2F/0E/0S，incidental0；green01同目录审计 `:4`：唯一实际 current 类，12/0F/0E/0S，incidental0，sourceSha256为 `ee0d2c6d95bfef26ae5185b41252bba1ea46a7971d6d6a2dcefcdc42ff6803ab`。raw/XML及本轮源字节已由root独立核验；复审未重复运行。
- 测试适用范围沿 `docs/evidence/iam-stage-5-e1b-i1-fix-report.md:52` 保留：旧 common179 的精确77项源仍一致；旧engine35与修复后356有明确四文件差异，新12只验证受影响current全类。不能合并旧35+新12为一次47项完整suite，也不能声称common179和新12是最终全356同码双跑。
- 没有新增需检查的未变源码风险；两个窗口及同一proof、失败CAS、fixture提交顺序均可由fix-only完整上下文判定。本次没有读取相邻旧工程，没有写source/target/Git/DB，没有新增生产hook或测试运行。
- 本章节按root精确append授权写入原同一review；追加前实际文件恰为15557字节，SHA `bb1322991e9983d874108c4bc5da04502784dc538c800e7720a745457a7d19e9`。原前缀保持逐字不动，追加后再次核验该前缀；只读审查所有权归还root，由root核前缀后登记组件闭合与下一作者授权。

### Assessment

**Task quality: Approved**

**I1: Closed**

**Reasoning:** 最小修复分别在实际UPDATE后事务内及真实commit返回后验证同一proof，正确区分可回滚成功写入与已提交历史观察；实际PG行锁/独立连接commit测试证明两个原实现窗口均有行为红、修复后受影响类通过。E1b组件通过两门，完整Task5、真实调度和部署门禁继续保留。
