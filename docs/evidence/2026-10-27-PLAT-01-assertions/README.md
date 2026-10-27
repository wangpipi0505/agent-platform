# PLAT-01 评测断言模型（T01 证据）

日期：2026-10-27 · 分支：`feat/v1.2-implementation` · 基线提交：`3c6a738`

## 1. 目标与对应 PLAT 编号

- 对应编号：**PLAT-01**（路线一 P0 发布死锁），方案 §4.1“可配置断言模型”。
- 原问题：`EvaluationService` 以整份输出 JSON 深相等判分（`Json.tree(expected).equals(Json.tree(output))`，`EvaluationService.java` 判分路径），而 CHAT 输出天然带每次变化的 `usage`、`actualModel`（以及时间戳类字段），导致 EXACT 比较**永远失败**，应用无法通过评测 → 发布死锁。
- 本卡目标：把“整份输出相等”改为**用例级可配置断言**，同时保持历史用例与旧报告语义不变：
  - 模式：`EXACT`（旧语义，JSON 深相等）、`JSON_SUBSET`（expected 是 actual 的子集）、`TEXT_EQUALS`、`CONTAINS`、`REGEX`（RE2J，整串匹配）、`SCHEMA`（JSON Schema 校验 actual）、`NUMERIC_TOLERANCE`（`tolerance` 数值容差）。
  - 用例无 `assertion` 字段 → 按 `EXACT` 解释（DB 列默认 `EXACT`）。
  - 安全类用例（`safety=true`）只允许 `EXACT` / `SCHEMA`；`CONTAINS`/`REGEX`/`TEXT_EQUALS`/`JSON_SUBSET`/`NUMERIC_TOLERANCE` 在保存/发布期拒绝（400 + 稳定 code + 可读中文 `message`）。
  - 未知模式在保存期拒绝（`EVALUATION_ASSERTION_UNKNOWN`），不静默降级。
  - 断言失败给出有界、可读原因（模式名 + 差异位置），不回贴整份输出。

## 2. 改动文件清单

| 类型 | 路径 | 说明 |
| --- | --- | --- |
| 新增 | `monitor/src/main/java/cn/agentplatform/monitor/domain/EvaluationAssertions.java` | 纯函数断言引擎：无 IO、无 Spring；`requireValid`（保存期校验 + 规范化快照）、`compare`（判分）、`modeOf`；591 行 |
| 修改 | `monitor/src/main/java/cn/agentplatform/monitor/application/EvaluationService.java` | 读取用例 `assertion`、保存期校验、冻结 `assertion_model`/`assertion_snapshot`、判分改为调用纯函数；+82/−13 行 |
| 新增 | `monitor/src/main/resources/db/migration/V504__evaluation_assertion.sql` | 只增列：`assertion_model varchar(16) NOT NULL DEFAULT 'EXACT'`、`assertion_snapshot jsonb`（可空）+ 2 条 CHECK |
| 新增 | `monitor/src/test/java/cn/agentplatform/monitor/domain/EvaluationAssertionsTest.java` | 13 例：7 模式正反例、安全约束、未知模式、缺省 EXACT、非法参数、快照稳定、原因有界 |
| 新增 | `monitor/src/test/java/cn/agentplatform/monitor/application/EvaluationAssertionServiceTest.java` | 7 例：真实 PG（Flyway 迁移）+ 桩引擎，覆盖判分/历史兼容/保存期拒绝/开关回退/恢复执行 |
| 新增 | `docs/evidence/2026-10-27-PLAT-01-assertions/**` | 本目录证据 |

未改：`pom.xml`（RE2J 1.8、networknt json-schema-validator 1.5.6 已由 `admin-common` 声明并传递到 monitor，**未新增第三方依赖**）、其它模块、`web/**`、`contracts/**`、`Skill*`。

### 2.1 关键设计与不变量

1. **判分与保存分离**：`requireValid(assertion, expected, safety)` 只在保存/发布期调用（evaluate 冻结数据集前、恢复执行重新判分前），`compare(assertion, expected, actual)` 是纯函数。任何非法配置都在写库与调用上游**之前** 400 拒绝（无半写状态）。
2. **模型与快照一起冻结**：报告新增 `assertion_model`（`EXACT`/`CONFIGURABLE`）与 `assertion_snapshot`（每条用例 id → 规范化断言配置）。恢复执行（resume）只认快照，不重读数据集，保证“同一报告复判语义不变”；`CONFIGURABLE` 必须有快照、`EXACT` 必须无快照，由 DB CHECK 兜底。
3. **历史兼容按字节**：用例未配置 `assertion` 时不写入任何断言字段；数据集 `sha256` 只对原始内容计算，旧数据集内容与哈希不变。旧报告读取时 `assertion_model` 取列默认 `EXACT`、`assertion_snapshot` 为 NULL，`report()` 对 NULL 快照做空处理。
4. **判分结论与 run 终态同一次写库**：仍沿用原逻辑——每个用例执行完在 `execute` 末尾一次性 `update ... set status/results/detail`，不引入新的异步路径、不新增事务边界；崩溃只会留下 `RUNNING`，可 resume 重判（快照保证一致）。
5. **失败可读且有界**：`reasonCode` 稳定（`ASSERTION_EXACT_MISMATCH`、`ASSERTION_JSON_SUBSET_MISMATCH`、`ASSERTION_TEXT_MISMATCH`、`ASSERTION_CONTAINS_MISSING`、`ASSERTION_REGEX_MISMATCH`、`ASSERTION_SCHEMA_MISMATCH`、`ASSERTION_NUMERIC_TOLERANCE_EXCEEDED`、`ASSERTION_NUMERIC_TYPE_MISMATCH`、`ASSERTION_OUTPUT_TOO_LARGE`、`ASSERTION_EXECUTION_NOT_SUCCEEDED`），`reason` 形如 `JSON_SUBSET：$.a 字段缺失（expected 要求该字段存在）`，单条详情上限 320 字符；EXACT 的额外字段提示直接点出 CHAT 场景（“CHAT 的 usage/actualModel 即属此类”）。
6. **防灾难回溯**：`REGEX` 使用仓库已存在的 RE2J（线性时间），模式长度上限 2000；所有模式的实际输出文本上限 1,000,000 字符（超限 `ASSERTION_OUTPUT_TOO_LARGE`，不静默失败）。数值比较统一转 `BigDecimal` 差值 ≤ `tolerance`，非数值类型给 `ASSERTION_NUMERIC_TYPE_MISMATCH`。

### 2.2 迁移 V504（`monitor/src/main/resources/db/migration/V504__evaluation_assertion.sql` 全文）

```sql
-- PLAT-01：可配置断言模型与判分快照。只增不改：历史报告默认 EXACT，语义仍是"整份输出精确相等"。
-- assertion_snapshot 冻结每条用例的规范化断言配置，使恢复执行只认冻结值，不受后续数据集或默认配置变化影响。
ALTER TABLE monitor.evaluation_report ADD COLUMN assertion_model varchar(16) NOT NULL DEFAULT 'EXACT',ADD COLUMN assertion_snapshot jsonb;
ALTER TABLE monitor.evaluation_report ADD CONSTRAINT evaluation_assertion_model CHECK(assertion_model IN ('EXACT','CONFIGURABLE'));
-- 旧报告没有断言配置；新报告必须带快照。不允许"标记为CONFIGURABLE却没有可复算的配置"。
ALTER TABLE monitor.evaluation_report ADD CONSTRAINT evaluation_assertion_snapshot CHECK(
 (assertion_model='EXACT' AND assertion_snapshot IS NULL)
 OR (assertion_model='CONFIGURABLE' AND assertion_snapshot IS NOT NULL)
);
```

迁移在定向测试启动时由 Flyway 实际执行成功（`monitor` schema，`V504` 位于现有最大 `V503` 之后）；两条 CHECK 约束在 ALTER 时校验了既有历史行（默认 `EXACT` + NULL 快照），因此旧报告可读性由迁移成功本身证明。

## 3. 测试命令原文 + `Tests run` 原文

环境（每次均如此）：

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
set -a && . ./.env && set +a          # AGENT_DB_* / AGENT_JWT_SECRET 等 16 个键
export ENGINE_PG_TEST=true
export MAVEN_OPTS="-Dfile.encoding=UTF-8" JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
```

（`-Dfile.encoding=UTF-8` 仅为控制台中文可读；`-DargLine` 里同时带 `-Xmx1024m` 防止 fork JVM native OOM。）

### 3.1 先红（实现前）

领域纯函数，13 例全红（3 failures + 10 errors，均为 `UnsupportedOperationException` 骨架）：

```bash
mvn -B -ntp -pl monitor test -Dtest=EvaluationAssertionsTest \
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dfile.encoding=UTF-8"
```

```
[ERROR] Tests run: 13, Failures: 3, Errors: 10, Skipped: 0
```

服务级（真实 PG + 桩引擎），7 例全红：

```bash
mvn -B -ntp -pl monitor test -Dtest=EvaluationAssertionServiceTest \
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dfile.encoding=UTF-8"
```

```
[ERROR] Tests run: 7, Failures: 7, Errors: 0, Skipped: 0
```

### 3.2 后绿（实现后，同一命令）

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.monitor.domain.EvaluationAssertionsTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.monitor.application.EvaluationAssertionServiceTest
```

monitor 内评测相关类合并回归（新 7 + 既有 `EvaluationPersistenceTest` 9 + 新领域 13 + 既有 `EvaluationRulesTest` 7）：

```bash
mvn -B -ntp -pl monitor test \
  -Dtest=EvaluationAssertionServiceTest,EvaluationPersistenceTest,EvaluationAssertionsTest,EvaluationRulesTest \
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 \
  -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dfile.encoding=UTF-8"
```

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ...EvaluationAssertionServiceTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in ...EvaluationPersistenceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in ...domain.EvaluationAssertionsTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ...domain.EvaluationRulesTest
[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

日志见同目录 `red-domain.log`、`red-service.log`、`green-domain.log`、`green-service.log`、`green-monitor-eval.log`、`git-status.txt`、`git-diff-stat.txt`。

### 3.3 容器装配冒烟（失败，属既有问题，非本卡回归）

尝试运行 `DatasetHttpTest`（monitor 唯一的 Spring 上下文测试，用于验证新增 5 参构造器的 `@Autowired` 装配）：

```
[ERROR] Tests run: 3, Failures: 0, Errors: 3, Skipped: 0 -- in cn.agentplatform.monitor.application.DatasetHttpTest
java.lang.IllegalStateException: SECURITY_MODE_REQUIRED
```

原因：当前环境未设置 `agent.security.mode`（`.env` 只有 `AGENT_DEV_AUTH`/`AGENT_DEMO_MODE`，`monitor/src/main/resources/application.yml` 无 mode 默认值），上下文初始化阶段即失败，与断言改动无关。日志：`context-smoke-existing-failure.log`。

## 4. 未覆盖风险

1. **未跑全量**：仅上面 36 例定向测试；monitor 其余测试、其它模块、端到端评测链路均未跑。`DatasetHttpTest` 因既有 `SECURITY_MODE_REQUIRED` 问题无法作为装配验证（见 3.3），因此 `EvaluationService` 新增的 5 参 `@Autowired` 构造器只由代码审查与 Spring 标准语义保证，未由容器启动实测。
2. **数据集推广路径不能携带断言**：`monitor/src/main/java/cn/agentplatform/monitor/application/DatasetService.java:90` 的 `fields(c, Set.of("id","inputs","expected","safety"))` 会拒绝 `assertion` 字段，即通过“数据集推广/发布”接口无法把断言配置带进版本；本卡白名单外，需另开卡（建议同批修，否则实际发布链只能走 evaluate 直接投递 cases）。
3. **框架边界**：`reasonCode` 是新增 results 字段，`attribution` 仍保持 `NONE/OUTPUT_MISMATCH/EXECUTION_FAILURE` 三值不变（不破坏既有消费方），但更细的断言差异只能读 `reasonCode`/`reason`；前端尚未展示这些字段（断言编辑器单列）。
4. **安全类用例判定**：基于用例的 `safety` 布尔值（现有模型），没有引入“按应用/场景标记安全”的新维度；若未来安全用例定义变化，`requireValid` 的规则要同步改。
5. **开关语义的极窄差异**：`agent.monitor.evaluation.assertions.enabled=false` 时新报告按 `EXACT` 判分（旧行为），但保存期仍会校验 `assertion` 字段合法性（fail-closed）；旧代码会忽略该字段，因此仅对“带非法断言配置”的请求存在差异，历史用例（无 assertion 字段）不受影响。
6. **数值表示**：容差比较用 `BigDecimal`（来自 JSON 解析），与 `double` 的二进制表示可能存在末位差异；报告里 `expected`/`actual` 仍保存原始 JSON 值，便于复核。
7. **迁移并发**：V504 执行依赖 Flyway 单实例启动顺序（现有多实例部署若同时 migrate，由 Flyway 锁保证），本卡未做并发迁移实测。
8. 工作区另有既存未提交改动（`.gitignore`、`docs/evidence/acceptance-audit-20261026/`、`docs/evidence/batch-1-usability-20261026.md`、`output/`），非本卡产生，未纳入；`git-status.txt` 为证。

## 5. 回滚方式

1. **运行期回滚（推荐，先做）**：把 `agent.monitor.evaluation.assertions.enabled` 置 `false`（默认 `true`）→ 新报告 `assertion_model=EXACT`、按旧“整份输出相等”判分，无需回滚数据库；已存在的 `CONFIGURABLE` 报告仍保留 `assertion_snapshot`，可继续按其冻结语义复判/审计（只读，正是“报告存断言快照 → 可回滚判定”的落点）。CLI/环境配置名：`AGENT_MONITOR_EVALUATION_ASSERTIONS_ENABLED=false`。
2. **代码回滚**：revert `EvaluationService.java`，删除 `EvaluationAssertions.java` 与两个测试类；`V504__evaluation_assertion.sql` **保留**——它只增列（可空/有默认值），旧代码 `select *` 读取后按显式列名映射，多出的两列无副作用；删除迁移文件会导致 Flyway `validate` 报“已应用但不可解析”。若必须反向删列，先 `ALTER TABLE monitor.evaluation_report DROP CONSTRAINT evaluation_assertion_snapshot, DROP CONSTRAINT evaluation_assertion_model, DROP COLUMN assertion_snapshot, DROP COLUMN assertion_model;` 再手动删除 `flyway_schema_history` 中 V504 行（不建议，历史报告将无法复算）。
3. **恢复窗口**：判分结论与 run 终态仍为同一条 update；如需重判，走 `POST /evaluations/{id}/resume`（只认冻结快照）。回滚开关不影响已入队/运行中的报告，它们按报告自身的 `assertion_model` 判分。

## 6. 结论

- 断言模型、保存期校验、报告冻结/恢复、迁移 V504 已实现，36 例定向测试全绿（含既有 `EvaluationPersistenceTest` 9 例与 `EvaluationRulesTest` 7 例回归）。
- **未跑全量测试**，未做端到端（engine 实际执行 + 前端展示）验证；不声称全绿。
- 待办（白名单外）：`DatasetService` 数据集推广允许 `assertion` 字段；前端断言编辑器；monitor 上下文测试的 `SECURITY_MODE_REQUIRED` 环境修复后补跑 `DatasetHttpTest` 验证构造器装配。
