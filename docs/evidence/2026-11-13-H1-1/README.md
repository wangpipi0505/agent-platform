# H1-1 证据：V229 双路径建库 + harness.session 契约四条

日期：2026-11-13。卡片：台账 §7.2 **H1-1 V229 迁移交付**（泳道 L1 首卡，v1.3 归 后端A）。
场景契约：contracts/session-events.md；字段权威：数据库稿 §4.1；机制：主方案 §5.1。

## 交付物

- `engine-runtime/src/main/resources/db/migration/V229__harness_session_log.sql`——一次交付四对象：`chat_session` 扩列（origin/parent_session_id/delegation_depth/event_watermark）+ `session_event`（27 类目录 CHECK、256KB/2MB 大小分配、surface_op CHECK、投影部分索引、tool/call 条件唯一索引、追加不可变触发器）+ `agent_inbox`（dedupe/claim_generation 两阶段消费列）+ `subagent_catalog`（T2-7 消费，按迁移归口随本卡提前交付）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/harness/session/SessionEvents.java`——事件目录 27 常量 + 补写标注常量（禁止散落字面量）。
- `.../harness/session/SessionStore.java`——append（单写者 FOR UPDATE 锁 + 期望 seq 连续校验）/read（连续前缀，撕裂尾不返回）/deriveMessages 增量 fold（只投影消息类；surface replace 摘区间）/synthesizeMissingToolResult（synthetic+UNKNOWN）/closeInterruptedTurn。
- 测试：`SessionStoreContractTest`（契约四条+折叠 5 用例）、`FlywayLegacyUpgradeEvidenceTest`（迁移卡复用的双路径验证器）。

## 双路径验证（Migration dual-path）

| 路径 | 命令要点 | 结果 | 证据文件 |
| --- | --- | --- | --- |
| 空库初始化 | `drop schema engine cascade` → 跑契约测试（Flyway 全链重建） | **6/6 绿，BUILD SUCCESS**，schema at version 229 | empty-db-path.txt |
| 存量升级 | `ENGINE_PG_URL=<dev库>` → Flyway migrate | **绿**，migratedTo=229（本次干净追加 30 个迁移，只增不改），schema at version 229 | legacy-db-path.txt |

## 契约四条（SessionStoreContractTest）

| 用例 | 契约 | 结果 |
| --- | --- | --- |
| appendRejectsNonContiguousExpectedSeq | ②越 seq 拒绝（409 SESSION_SEQ_MISMATCH） | ✓ |
| readSkipsTornTailAtFirstGap | ①撕裂尾不返回 | ✓ |
| concurrentAppendsSerializeUnderWriterLock | ③单写者锁（4×5 并发 seq 严格连续） | ✓ |
| recoverySynthesisWritesSyntheticUnknownAndClosesTurn | ④补写 synthetic+UNKNOWN（无 error）+ turn/end{interrupted}，已闭合不重复补写 | ✓ |
| deriveMessagesFoldsIncrementallyWithSurfaceReplace | 增量 fold：attempt 不入投影；replace 摘区间后追加替换消息；续读幂等 | ✓ |

## 过程记录（两轮定向测试）

- r1 红：测试夹具 `@BeforeEach` 直接 DELETE 被**追加不可变触发器**拒绝——触发器按设计工作；夹具改为清理期临时禁用触发器（禁→删→复启）。
- r2 绿：6/6 + 双路径。环境备注：专用 PG 16.15 经 `subst V:` 别名 + `Start-Process`（真分离）启动，便携包此前已就位。
- X2-4（新登记，台账 §7.9）：库稿 §4.1 大小分配 OR 表达式对非 assistant 类型不设上限，与字段表 256KB 本意冲突——迁移按下式收紧：`octet_length<=262144 OR (type='assistant/message' AND <=2097152)`。

## 边界声明

- 本卡不实现 loop/tools/consume 语义（H1-3/H1-4 消费）；`agent_inbox`/`subagent_catalog` 仅表结构交付。
- 合入基线以 M1-① 通过为前置（泳道视图"M1-① 签认"行）——M1-① 门测试已绿（7c6e542d）且用户于 2026-11-13 指令启动 L1 泳道，视为签认生效。
