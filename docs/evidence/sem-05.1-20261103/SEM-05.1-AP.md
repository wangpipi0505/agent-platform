# SEM-05.1 AP 侧补验子步 — 实施证据

- 席位：A（AP 后端）
- 日期：2026-11-03
- 基线：AP worktree `代码/_worktrees/upgrade-20261103/agent-platform`，开工 HEAD `b6fbd7ac`
- 前置：SEM-01.1（`fd384653`）、SEM-05.0（`c88c56bd`）、OR 侧 `ddea47a`、SEM-03.2
- 依据：`contracts/semantic-runtime.md`、`contracts/semantic-runtime-v1.schema.json`、OR `onto-trace/.../domain/SemanticReceipt.java`

## 1. 原责任 → 新责任

| 原责任 | 新责任 | 文件 |
| --- | --- | --- |
| 语义回执只有 OR 侧领域口径，AP 无对应结构 | AP 侧新增同口径领域类，纯规则、可单测 | `skill-registry/src/main/java/cn/agentplatform/skill/domain/SemanticReceipt.java`（新增） |
| 适配器只认 `ontologyMeta`，语义片段被丢弃 | 按 `return_spec` 声明的三条语义路径解析 `data.semantic.*`，产出投影或拒绝标记；未声明则零变化 | `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` |
| 执行事实里没有语义位 | `result.semantic = {valid,digest,record,callId,runId}` 随执行事实落库 | `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java` |
| 清单导入不校验语义路径 | 导入时校验三条路径均为 `$.data.semantic.*` 且互不重复 | `skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java` |
| 语义回执无受控读口 | 按 run 归属返回脱敏语义结果（成功/拒绝分组） | `engine-runtime/src/main/java/cn/agentplatform/engine/api/SemanticResultController.java`（新增）、`.../infra/TraceStore.java` |
| 新端点未登记 | 权限目录精确追加一条（`GET /v1/runs/{id}/semantic-results`） | `admin-common/src/main/resources/agent-service-authority.json` |

## 2. 不变量

1. **同一 call/run**：投影里的 call 证据恒为本 call（`callId`+`runId`），`recordId` 由 `callId` 与回执摘要共同决定。
2. **不假成功**：缺 `meta`、`preview=true`、四元组不符、摘要不符、字段越界一律产出 `valid=false` 的拒绝标记；拒绝标记不含 `releaseRef`、`factSet`，不冒充完整回执。
3. **不伪造事实**：OR 片段不含事实集时产出 `PARTIAL` + 空 `items` 的 `factSet` + `SEMANTIC_SOURCE_UNAVAILABLE`，不把空集合说成"无匹配"。
4. **不填 now**：`timeliness` 只接受来源给出的 `dataAsOf`；`UNKNOWN` 必须带 `unknownReason` 且禁止 `dataAsOf`。
5. **零变化**：未声明语义路径的技能，`Raw.semantic()` 为 `null`，原 `structured/summary/fileRefs/ontologyMeta` 投影逐字不变。
6. **方言封闭**：`decisionRef` 出现即非法；未登记错误码、状态×事实集×错误联动不符一律拒绝。
7. **读口按当前主体**：运行不存在或非本主体一律 404 `运行不存在`，不提供"存在但无权"的探测面；不回传 attempt/节点主键。
8. **只读 profile**：`profile="read-only"`、`schemaVersion="1.0"`，不接受 `decision@1.0` 以外的解释。

## 3. 命令与结果

环境：`JAVA_HOME=jdk-21.0.12.8-hotspot`；`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`、`AGENT_DB_USER=dev_ap_test`、`AGENT_DB_PASSWORD` 自私有准备根读入（未回显）、`ENGINE_PG_TEST=true`。

| 轮次 | 命令（`mvn -o -pl ... -am -Dtest=... test`） | 结果 |
| --- | --- | --- |
| 第一轮 | `skill-registry,engine-runtime -Dtest='SemanticReceiptTest,SemanticProjectionTest,SemanticResultsReadTest'` | 7/7、3/3、1/1 全绿（engine 用例 0.95s 真实 PG） |
| 第二轮 | `admin-common,skill-registry,engine-runtime -Dtest='RouteCoverageVerifierTest,SemanticReceiptTest,SemanticProjectionTest,SemanticResultsReadTest,OntologyManifestImportsTest,OntologyProjectionImportTest'` | 目录 10/10、7/7、3/3、1/1 绿；两个导入用例类各 3 error（共 6），原因见 §4 |

首轮编译前修正两处自身缺陷：私有 `rejected(...)` 与公有同名方法擦除后签名相同 → 私有改名 `marker`；`PARTIAL` 分支手搓 `factSet` 漏 `factSetDigest` → 抽出 `emptyFactSet(callId)`。

## 4. 未覆盖

- **两个导入用例类的 PG 分支未跑通**：`OntologyManifestImportsTest`（20 跑 3 error）、`OntologyProjectionImportTest`（15 跑 3 error），错误统一为 `org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection`。原因是这两类把库地址写死 `jdbc:postgresql://127.0.0.1:25432/...`（`OntologyManifestImportsTest.java:365/376/449/460/490/496`），并需连 `postgres` 管理库建库；本环境该端口无服务、DEV 账号 `createdb=false`。**失败发生在 `@BeforeAll` 建连阶段，早于任何断言**，与本次改动无关，但本席未取得基线对照运行，只按代码位置判定。
- 未跑全量回归与真实启动（`ROUTE_COVERAGE_MISSING` 等启动期强校验需 P 串行窗口）。
- 未接真实 OR 端到端（`ONTO_*` 双端联跑属验收窗口）：本席只验证了"给定 OR 形态片段 → 投影正确"，未验证"真实 OR 服务 → HTTP → 适配器"。
- 前端/生成客户端未触（不在白名单）。

## 5. 契约缺口（只登记，未私改契约）

1. **事实集缺省表达**：schema 要求非 `ERROR` 必须带 `factSet`，而 OR 当前片段只给 `releaseRef/evidenceRefs/timeliness`。本席以 `PARTIAL` + 空 `items` + `SEMANTIC_SOURCE_UNAVAILABLE` 表达；"空事实集是否可接受"需契约确认。
2. **`recordId` 生成规则**未冻结（本席用 `sr_` + `sha256(callId:digest)` 前 32 位），契约只要求是同形标识符。
3. **`factSetDigest`** 的算法名与"缺失时按 `sha256(canonical(factSet.items))` 现算"未在 schema 说明。
4. **读口路径与响应包装**未进契约路由表：`GET /v1/runs/{id}/semantic-results` 返回 `{runId,count,results,rejected}`。
5. **call 证据是否允许附加 `runId`**：schema 的 `call` 分支只要求 `callId`；若 `additionalProperties=false`，本席附带的 `runId` 需删除或由契约增设字段。

## 6. 回退

`git revert <本次提交>`；无迁移、无数据变更，回退后旧 `ontologyMeta` 投影不受影响（`SemanticReceipt` 与语义位均为新增分支）。
