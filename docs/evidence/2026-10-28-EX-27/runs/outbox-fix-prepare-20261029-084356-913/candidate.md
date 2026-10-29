# EX-27 outbox 测试租户隔离候选：待独立审阅

HEAD：`83726d50a6cce116251c03c5c193080d8ed5aad2`。本轮只有单文件源码写窗和准备证据写窗；没有实际运行窗口，未准备 runner，未运行 Java/Maven/PG/HTTP，未读取 `.env`，未触碰 index、主入口、其他候选或任何产品代码。

## 最小改动

源码白名单：`skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java`。

实例字段 `tenant` 在每例 `@BeforeEach` 创建 verified JWT 前生成一次 `skill-execution-<UUID>`。原24处固定租户全部同步为该实例值，包括初始及权限收窄 JWT、mock、版本/fact/history/artifact SQL、outbox 与 artifact 清理。两处 SQL 内嵌租户已改 JDBC 参数：connection 为 `tenant, id, sha, semanticDigest`；acceptance 为 `testId, tenant, id, sha, semanticDigest`。

原 `skill-tester`、`Set.of("SERVICE")`、自身事实 DELIVERED、业务只调用一次及其他97条原断言调用全部保留。额外增加两条 assertEquals：回调 URL 必须为本例 call 的 `/fact`；drain 前完整同租户到期/未租约队列必须只有本例 call。生产的全租户选择谓词未变，旧租户/队列未作清理、认领或状态更新。

真实 Git 差异：1个源码文件，42行新增/24行删除；见 `candidate.diff`。未新增框架、依赖、方法身份或配置分支；所有方法签名/顺序、注解、参数、跳过条件、等待与原测试逻辑保持。该类仍为23个普通 Test + 一个 CHAT/TASK 参数化方法，即25个报告；另一未改 SkillTaskResultTest 为3个报告。

## 原字节与冻结证据

| 文件 | SHA256 |
|---|---|
| `SkillExecutionTest.before.java` | `14AE4EEA65C05EBEEBDBDF16D3FC4733D4613FDEC20EE7644DF56DB83627194B` |
| `SkillExecutionTest.candidate.java` 与当前白名单源码 | `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8` |
| SDK AlgorithmOffloadInterruptionTest（只读） | `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D` |
| ArtifactStore（只读） | `9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D` |
| SkillTaskResultTest（只读） | `600F1A9DF2D5F0199A52F7F2152D339F2E6C6D78839382C30C5BA9C5E7FA164F` |
| SkillOutbox（只读） | `30BAE61B51DB4DF75462C5D92BADC5455FBEC1FC7437856CD1D9A9DA2DA32DB7` |
| SkillExecution（只读） | `8CD3D8EDAE7C0DEE54DD562C6F2F3F3664759DE85D122F70C1E00B9BC6D25218` |

`tenant-mapping.json` 逐条列出原24处行号/原文与候选对应行号/原文。`module-source-sha256.json` 冻结149份当前 Java 源码；与上次失败 Full 的149份清单比较，只有本次白名单 Test 改变，其余148份完全相同。

## 文件级静态核对

`static-checks.json` 记录30项文件/Git检查全部通过，**不等于 Java 编译或实际回归通过**。主要证据：

- 原字节快照先核对14AE再写候选，UTF-8/LF保持。
- 将候选中的新增字段、一次初始化、两条加强断言、两处 SQL 参数化和22处普通租户替换逐项逆向还原后，逐字节等于14AE原文件。这约束了所有其他语句、断言、等待、资源关闭与测试身份没有额外变化。
- 原79个 assertEquals、1个 assertNotNull、4个 assertFalse、5个 assertTrue、8个 assertThrows 保留；候选仅 assertEquals 增至81。
- 29个方法/辅助方法签名与顺序相同；原测试注解、CHAT/TASK参数及 MinIO/PG条件未改。
- 两处参数化 SQL 的占位符与参数顺序逐字检查；24个旧固定租户无残留。
- `git diff --check` exit0无输出，HEAD未变，index为空。

本轮保存的源码快照、diff、映射与静态清单是独立审阅输入；没有编译报告、XML或运行结果可冒充回归。

## 下一技术门

ROOT 已在三份主入口登记28→674门。本候选交回后先独立审阅，尚未授权准备或执行 runner。

独审通过且 ROOT 授予实际窗口后，按原环境/默认顺序/fork/超时先运行完整 SkillExecutionTest + 未改 SkillTaskResultTest：原28身份全P、native0且不使用 failure.ignore；其中 outbox 原 principal/SERVICE/DELIVERED/业务一次以及新增精确call断言全部到达。

定向门通过才运行一次默认完整 skill 模块：70类、674身份齐全，原664+EX03新增9+算法handoff1均保留；outbox恢复P、算法5身份P；原116非通过逐项保留且无新增非通过，预期558P/6F/106E/4旧SKIP。原生 failure.ignore exit0不能替代 collector/方法门。任何新失败、丢身份、skip扩张、原因变化或冻结漂移，保留原始结果后停止。

既有 `algorithm-fix-20261029-032753-387` 的 outbox 新增F及旧算法失败/历史证据保持原样；本次准备不关 EX-27 SDK 子步骤或 EX-03，不宣布模块通过，不提交。
