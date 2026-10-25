### Spec Compliance

- ✅ **Spec compliant（仅 Task5 N1 有限材料/status 组件）**。按正式 brief 与 root-decisions 的具体裁决核对，未发现 Missing / Extra / Misunderstood 的阻断项。受保护材料真实写读、原 RunService.status 分支、初始三材料兼容及明确的未接线边界符合本段要求；不代表全分支、全 Task5、E、RR1/RR2 或生产 IAM 签收。
- 审查基线：D3b 不可改的 357 源物理基线 → N1 最终 362 源；无 HEAD / commits / remote，不使用虚构 Git 范围。完整 context 包 `docs/evidence/review-iam-stage-5-n1-package.diff:1`，355,030 bytes，SHA256 `45ddc6df523d63573effbbe061e07597118fe6a977a38cd8a6cfd223b756e552`。已从头到尾顺序读完（每块 250 行，最后不足 250 行），未另读 14 个改动文件，未重新生成 diff。
- 正式依据：`docs/evidence/iam-stage-5-n1-flow-material-status-brief.md:15`、`:17`、`:25`、`:36`、`:42`、`:44`、`:52`、`:56`；具体裁决 `docs/evidence/iam-stage-5-n1-flow-material-status/root-decisions.md:5`、`:11`、`:19`。已读既定 seam proposal 的 N1/N2 接口及后续依赖，未读 root 并发新增的未来 N2 提案。
- 已核作者报告 SHA256 `41b9ec7d5e36e7737f9e91ca038f142936d46e64a5a40ac5fb967bcd687d5f10`；其结论未作为代码正确性的替代。final01 raw SHA256 `ddd435ff17527e74fa44d6bb91b1de329f3e386d54bd36f2758130f1ea81c0e5` 与 root audit 对应。

### 逐项合规证据

- **初始/派生分离与真实 locator**：`engine-runtime/src/main/resources/db/migration/V217__run_flow_materials.sql:2` 增加同表 identity revision，`:11` 区分 SHA64 初始 version 与等于 revision 的规范十进制 dynamic version，`:15` 仅三 initial 部分唯一；既有 tenant/producer/kind/id/version、scope 唯一和触发器未在此迁移撤除。`MaterialScopeStore.java:136` 的 scopes 查询仍精确限定三种且要求恰好 3，初始 validate 保留原 owner/publicId/snapshotSHA/admission/initial 条件（完整路径及 context：`docs/evidence/review-iam-stage-5-n1-package.diff:1925`、`:1973`）。Binding 未塞入 dynamic scopes，避免事件引起 immutable binding CAS 比较变化（包 `:2539`）。
- **私有证明与不同用途**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:41` 保留 VA-only 签名并直接拒绝；`:47` retained 入口先 requireVerified，`:217` 普通 main 绑定当前 frame，local 则进入严格 writer 校验。`:233` admission 比对原 delegation/full initial/origin，worker 严格 original binding 并核持久 blocked_reason/last_decision_id。fresh-main read 无历史 grant 有效性/等值要求，亦不更新初始绑定；真实历史过期且后台阻断正例见 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FlowMaterialStatusPostgresTest.java:177`。
- **新增材料与义务不丢**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:303` 核 dynamic row/ref/revision/owner/full-initial hash、真实 FK locator、同 run 且较早 revision 的父 metadata；`:355` 核 initial floor 与初始固化 labels/父义务；`:411` append 重读真实父记录，加入本次 current labels、保存本次 source decision/version。`FlowMaterialTransactionsPostgresTest.java:405` 的 derivedOnly 首次派生、后续 current 不再携带仍保留的用例未退化成 initial 已有标签。
- **只拥有真实 INSERT**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:69` 在私有 createBound 实际新 run INSERT 后写四 dynamic scopes；`engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:628` 的 createNode 自己 INSERT 新 node 后写四份初始 scopes，先拒绝缺旧材料的 run，没有 initializeExistingNode/upsert 补权入口。局部失败将同 DataSource holder 标 rollback-only；原受理外层 commit 终检明示留给 N3（root-decisions `:11`）。
- **真实写点/事务/期限**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:510` 先锁 run→run_authority observation→node，再核所有实际 metadata/scope，最后 payload；`:550` 顶层同 TM RR、拒绝 ambient RC/RR/Serializable、callback 末与真实 execute 返回后检查同 proof。`:679` claim、`:787` commit、`:841` fail、`:884` consume 及 `:622` event 都走该路径，payload/scope/事件/CAS 同 Tx；无事务内联网。RunAuthority 最小提取见 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:23`，原 UPDATE 后及 commit 后终检保留。
- **原 CAS 与失败事实**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:787` 保留 lease/generation/committed CAS；CAS0 仅 SUPERSEDED_SUBMIT，不生成成功 scope/事件。`:841` node CAS0 仍记录原 attempt failure、不给 node 写失败 scope/逻辑 NODE_FAILED。对照见 `FlowMaterialScopePostgresTest.java:120`（精度/claim/commit/旧 ref）、`FlowMaterialTransactionsPostgresTest.java:450`（失败代事实）。纯 lease 控制不混 node-state digest，固定投影见 `MaterialScopeStore.java:542`，对照 `FlowMaterialScopePostgresTest.java:342`。
- **原 status 安全组合**：`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:1308` 实际调用 protectedRun/statusPublished，原 Map 字段、nodeView/paused/event 投影保留，`:1365` 响应物化后本地终检。`engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:322` 拒绝 ambient，窄 owner/root/purpose 后在事务外刷新，再单个只读 RR 中枚举、完整性/覆盖校验、payload/hash/物化视图；`:434` 在真实读 commit 后终检。无 tool_call 经窄 exists 核验才提供空 fileRefs；controlled/tool 用途在 payload 前拒绝。最终 RunService Map 仅组装已经完整物化的同快照值，不再补查 protected payload。
- **精确数值、NULL、时间**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:524` 固定列投影，使用私有 EXACT/normalize 摘要；SQL NULL 作为 JSON null 参与材料摘要，sqlNull 标记在同一次 SELECT 保留原响应 SQL NULL→{} / 显式 JSON null→null 差别。UTC 微秒投影和 Timestamp 恢复见同文件 `:576`、`TraceStore.java:453`；原 output_digest 仍沿原计算。高精度实际 PG 往返见 `FlowMaterialScopePostgresTest.java:152`，NULL 展示回归见 `FlowMaterialStatusPostgresTest.java:17`。
- **旧入口 fail closed 与 legacy**：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:58` run、`:71` nodes、`:77` event、`:93` events 及原 claim/commit/fail/freeze/response/consume/lockRun 都先窄判断 authority，未先 SELECT * protected payload。无 authority 的实际 legacy claim/freeze/response/commit/CAS0/consume/status 对照见 `FlowMaterialStatusPostgresTest.java:430`；未自动回填历史 run。
- **测试保留**：初始 12 例仅将真实 proof 传入新 retained overload，scope/错 owner/root/purpose/version/digest/RC/精度负例仍到达对应检查，而非统一落 VA-only 拒绝；见 `docs/evidence/review-iam-stage-5-n1-package.diff:5344`、`:5361`、`:5384`、`:5472`。原持久化全表 3 改精确 initial 3 并新增 dynamic 4，见 `RunAuthorityPersistencePostgresTest.java:17`；原 LocalCurrent 两期限及 403/503/CAS 负例仍保留（包 `:5614`、`:5641`）。

### Strengths

- `TraceStore.java:510` 的 observation 行锁解决了 run 锁不能阻止并发 recordCheck 的真实 RR 旧快照问题；`FlowMaterialTransactionsPostgresTest.java:17` 用真实 PG Lock 观测与有界同步验证阻断 CAS 等待，不用固定睡眠冒充锁已建立。
- `FlowMaterialTransactionsPostgresTest.java:130` 对 claim/commit/fail/event/consume 分别注入实际 scope INSERT 失败，核业务事实/材料/事件共同回滚；`:198` 另验 admission 调用者 catch 后依然 rollback-only；`:337` 分清 payload 后过期回滚和真实 commit 后过期保留历史但拒交付。数据库错误保留 DataAccessException，没有泛化成权限 503。
- `FlowMaterialStatusPostgresTest.java:45` 经实际 RunService.status 检查原字段、别名、Timestamp，以及 14 份真实 payload 同物理连接/隔离 4/readOnly/非 autoCommit；`:217` 缺 nullable scope/错 digest/伪 parent/丢 labels/错 initial hash 为零 payload，`:349` 内容摘要损坏明确是读后拒绝。测试不仅验证 helper 或 mock。
- `MaterialScopeStore.java:41` 与 `FlowMaterialScopePostgresTest.java:417` 明确 public record 不是 proof；保留旧安全负例的真实 retained 路径，避免“统一入口拒绝”造成测试看似全绿却丢原校验覆盖。

### Issues

#### Critical (Must Fix)

- 无。本段 diff 中未发现可证实的越权交付、原子性破坏或应阻断的安全缺陷。

#### Important (Should Fix)

- 无。现有未接线路径属于正式 N2/N3/N4 边界，未把它们反向归为 N1 漏实现；本结论不能用于批准那些路径。

#### Minor (Nice to Have)

- **新增测试有 unchecked 编译提示，final 输出并非 pristine。** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FlowMaterialStatusPostgresTest.java:93`、`:111`、`:538` 等直接将 Object 转为带泛型 Map/List；`.local/iam-stage-5-n1-final01.log:66`、`:67` 实际报告 unchecked/unsafe operations。它不使现有行为断言失效，但后续类型变动更难由编译器发现，且违反模板的干净输出目标。建议后续以 `List<?>` / `Map<?, ?>` 检查和局部 typed 提取消除新增提示，并如实登记当前噪声；不为此改本次冻结源或重跑 Maven。
- **既有编译提示单列，不归因本次新增。** `.local/iam-stage-5-n1-final01.log:55`、`:56` 指向 RunAuthorityStore 的 deprecated API，`:57`、`:58` 指向未改 ContextProjection 的 unchecked 操作。本次未用 -Xlint 重跑定位，也未将它们冒称新缺陷；root 的“无 WARN 行”字面结论成立，但不能推导完全无编译噪声。建议证据索引保留两类提示来源；无需扩大当前 N1 范围。

### ⚠️ Cannot verify from diff / 跨度与证据边界

- **N2 owned scheduler/FlowDriver 尚未签收。** `docs/evidence/review-iam-stage-5-n1-package.diff:945`、`:1288`、`:1607` 中原 drive/aggregate/guard_ctx/直接 SQL 仍是后续责任；`root-decisions.md:11` 明示 prepare/aggregate/DEFAULT_VALUE 不能简单嵌套顶层 writer。N1 runningFixture / paused fixture 是有限 PG 组件准备（`FlowMaterialScopePostgresTest.java:53`、`FlowMaterialStatusPostgresTest.java:517`），不能替代原 owned worker 接线验证。
- **N3 admission 与 N4 gateway/HTTP 未签收。** 包 `:246`、`:380`、`:508`、`:536` 显示原 requestId 查询、受理 INSERT、节点 INSERT 和调度通知仍未迁移；新 createPublished/createNode 只有局部检查，外层真实 commit 后终检须未来原受理拥有者落实。独立 GET/debug/v1、模型/工具/批准/恢复/stream/observer、RR1/RR2 及生产 IAM 闭锁不由本 diff 推导完成；正式 brief `:46`、`:48`、`:56` 与报告 `:91`、`:93` 边界一致。
- **固定版本/四契约/七模块 HTTP/生产闭锁及历史字节完整性** 超出本 diff 能独立证明的范围。14 文件清单未见 POM/yml/路由 JSON/其它模块/driver 改动；root 的 `.local/iam-n1-root-fast-check.json`、`root-protected-check.json`、`root-audit-addendum.json` 已独立核 357/1710 物理 before、362 live/frozen/replay/final source、1740 targets、65,381 静态 + 28 runner + 8 执行文档，共 4,574,232,441 bytes 零漂移。本审查读取该机器证据但没有再做全库扫描，不把 root 核验冒称本人复验。
- **最终执行证据与早期红的强度不同。** `.local/iam-stage-5-n1-final01.log:75`、`:121`、`:187`、`:262`、`:313`、`:367` 实际 Running 六类；`:430` 为 69/0F/0E/0skip，`:439` BUILD SUCCESS。实际 XML/当轮 source 一致性采用 root 的逐项审计，57 incidental XML 未计为执行。报告 `:73`、`:75`、`:77` 已明确 red02/red07 观测噪声、red05 尾计数未到达、preflight 事后整源归档不能代表完整编译输入；这些历史局限仍保留，不用 final 绿反写历史。
- **输出与采样修正**：root 首次 inventory 漏根 pom 的 361/362 属工具噪声，root-audit-addendum 如实保留；实际 changed target module 为 engine-runtime，作者所称 common/engine 是允许范围，并非证明 common target 发生变化。未据此要求源/target 重做。

### 只读核验范围

- 唯一具名未改接口风险检查：**N1 是否误把 public VA/currentOnly 当私有 proof、把 fresh-main frame 刷新误作历史 grant 绑定，或误把 dataLabels 义务当权限集合**。仅核 `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:103` 私构造/私 Clock/currentOnly/requireVerified、`AuthorityContext.java:34`、`:38`、`:48` 的同 frame 刷新与绑定、`MaterialAuthorization.java:18`、`:25` 的覆盖/labels 义务语义。其接口支持本段使用方式；未读取 common 其它实现或测试。
- 该检查定位时先误用不存在的 common/ 路径，随后一次 filename-only `rg --files` 找到 admin-common，同时输出本工程历史证据副本的路径，造成截断噪声；未读取那些副本内容。随后仅读上述精确三接口的相关方法。未访问相邻旧工程/配置/数据/进程。
- 未运行 Maven、测试、probe、数据库、网络或 Git 命令；未修改 source/target/index/旧证据。仅新写本报告 `docs/evidence/review-iam-stage-5-n1.md`；root 并发未来提案不纳入 N1 diff 或冻结物。

### Assessment

**Task quality: Approved（附非阻断 Minor 与明确跨段边界）**。

**Reasoning:** 本段在原 writer/status 入口落实了可追溯材料、显式不可伪造证明、metadata-first 和同一 RR 快照/真实 commit 后终检，真实 PG 测试覆盖了核心成功、拒绝、回滚与期限竞态。新增 TraceStore 约 696 行、MaterialScopeStore 约 533 行主要承担固定投影、授权/事务与原方法接缝，未引通用 SQL 回调或新框架；未来 N2 组合事务应复用这些职责并避免扩大同类逻辑重复，当前无须因此阻断有限 N1。

**权限归还：** 任务级只读审查结束，本报告之外无写入；只读审查权限归还 root。
