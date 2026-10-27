# 2026-10-27 后端测试基线（S0 / G-4）

> 结论先行：**本机为仓库当前状态（`3c6a738` 之后的 `661ec3`/`dde22f4` 时点）跑不出"全绿"全量基线**。
> 失败不是单一缺陷，而是六个可分辨的根因桶（见 §4），其中 3 个是**测试基座缺陷**、2 个是**环境/夹具缺失**、1 个是**产品缺陷**（Y-1）。
> 因此 P0 期间不以"全量绿"为验收判据，改用**定向测试 + 每项自证 + 与本文件对照的"未新增失败类"**（口径见 §5）。

## 1. 目的与口径

- 目的：给出可复现的既存失败清单，使后续每个派发单元能回答"我的改动有没有新增失败"。
- 判定：以 `Tests run: / Failures: / Errors: / Skipped:` 原文为准；**定向测试与全量测试分开报告**（计划 §7.4）。
- 不以"某个类曾经绿过"作为基线——只认本次实际运行输出。

## 2. 环境与命令

- JDK：`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`（默认 `java` 是 17，必须显式设置）。
- 仓库：`代码/agent-platform`，分支 `feat/v1.2-implementation`。
- 本地仓库：`-Dmaven.repo.local=.tools/m2`；`admin-common` 的 `tests.jar` 已 `install`（`mvn -pl admin-common install -DskipTests`）。
- 环境变量：`.env` 全量导出（`.tools/env-load.sh`），另 `ENGINE_PG_TEST=true`。
- 统一堆参数：`-DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"`（理由见 §4-Y-2）。
- 数据库：本机 PG 25432 已运行；测试库 `agent_platform_model_test`、`agent_platform_engine_test` 由 `scripts/test.ps1` 白名单创建。

复现单模块（示例 skill-registry）：
```bash
cd 代码/agent-platform
. .tools/env-load.sh .env
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -pl skill-registry test \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
  -Dmaven.repo.local=.tools/m2
```

## 3. 各模块结果（如实记录，未跑完的注明）

| 模块 | Tests | Failures | Errors | Skipped | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| admin-common | 319 | 10 | 1 | 0 | 跑完 | 5 个坏类，集中 `common.authority.*` |
| platform-gateway | 259 | 9 | 8 | 0 | 跑完 | 8 个坏类，多为子进程 fixture |
| app-management | 90 | 0 | 20 | 0 | 跑完 | 4 个坏类；`PORTAL_CEILING_INVALID` 主导 |
| engine-runtime | 540（部分） | 26 | 115 | 0 | **未跑完** | 在 `RuntimeStreamTest` 挂起后被我终止；已产出 74 个类报告 |
| skill-registry | 440 | 8 | 112 | 17 | 跑完 | 64 次 `依赖服务暂不可用`（见 Y-3） |
| model-gateway | 173 | 7 | 83 | 72 | 跑完 | 需 Redis；未载入 `.env` 时为 175/6/129/13 |
| monitor | — | — | — | — | **未取到基线** | 被 T01 改动覆盖；T01 定向 36/36 绿，既有 `EvaluationPersistenceTest` 9/9、`EvaluationRulesTest` 7/7 |

> monitor 基线缺失是排程错误（T01 先于基线完成），已按"定向 36 例 + 既有两类回归"补记；模块级 baseline 待下一轮补跑。

## 4. 失败分类与根因（按可修复性排序）

### Y-0 fork JVM 原生内存崩溃（已绕过，非缺陷）
默认堆上限按物理内存 25% 设定，多模块并发 fork 时 mmap 4 MiB 失败：
`insufficient memory … Native memory allocation (mmap) failed to map 4194304 bytes … G1 virtual space`。
`-Dmaven.test.failure.ignore=true` 对 fork 崩溃无效。绕法：显式 `-Xmx1024m`。**建议写进 `scripts/test.ps1`**（G-8）。

### Y-1 产品缺陷：`/internal/runs/{id}/wake` 未登记路由目录
`ROUTE_COVERAGE_MISSING: engine POST /internal/runs/{id}/wake … RunController.wake(String,JsonNode)`。
`admin-common/src/main/resources/agent-service-authority.json` 无该行 → 测试 fixture 启动即失败，
且线上 `ServiceAuthorization` 按同一目录判权，端点实际不可达。详见计划 §2.5、台账 G-7。
受影响类：`GatewayRunStatusChainPostgresTest`、`PortalRunEventsJoinedChainTest`(5/5) 等。

### Y-2 测试基座缺陷：`RuntimeStreamTest` 挂起且无 fork 超时
该类在本机运行 16 分钟零输出、无 surefire 超时约束，导致全量门禁被无限期阻塞（本次直接终止）。
修复：给 surefire 配 `forkedProcessTimeoutInSeconds`/`junit5 timeout`，并把流式测试改为有界等待。

### Y-3 测试基座缺陷：`ServiceTokens` 部署模式未在纯单测登记 → 403 被吞成 502
非 Spring 单测里 `ServiceTokens.issue()` 抛 `403 AUTHORITY_CONTEXT_MISSING`（`deploymentMode==null`，
只由 `SecurityConfiguration.java:65` 在容器里注册），`SkillClients.request` 的 `catch (Exception)` 再把它
转成 `502 DEPENDENCY_UNAVAILABLE`（`依赖服务暂不可用，请稍后重试`）。skill-registry 112 errors 中 64 条是该文本。
修复：测试基座统一注册 deployment（JUnit extension / `@BeforeAll`），并让 `SkillClients` 不再吞 4xx（对应 PLAT-16）。

### Y-4 环境/夹具缺失：Spring 上下文属性不全
`IllegalStateException: SECURITY_MODE_REQUIRED`（`DatasetHttpTest` 等）、`IAM_DEV_AUTH_FORBIDDEN`（model-gateway 3 例）、
`PORTAL_CEILING_INVALID`（app-management 15 例，`AuthorityProperties.validate` 对 portal 白名单的强校验与测试构造的
`LoopbackAuthority.config(...)` 不一致）。属"测试构造与守卫演进不同步"，同一类问题。

### Y-5 环境缺失：model-gateway 依赖 Redis 认证
未载入 `.env` 时：`RedisCommandExecutionException: NOAUTH HELLO must be called with the client already authenticated`
（38 次）+ `RedisConnectionException: Unable to connect to 127.0.0.1:6379`。载入 `.env`（`AGENT_REDIS_*`，端口 26379）后
错误降到 83、跳过升到 72，但仍有 `IAM_DEV_AUTH_FORBIDDEN` 等。`scripts/start-cache.ps1` 存在但 `test.ps1` 不启动缓存。

### Y-6 夹具链路：子进程不可用
`GatewayPublishedChainPostgresTest` → `FATAL: database "agent_platform_iam_admission_engine_v12" does not exist`；
`PublishedMetadataSmokeLauncherTest` → `app fixture exited before ready`；
`PortalRunEventsJoinedChainTest` → `missing … engine-ready.json; child alive=false`。
这些类要求预先存在的专用库与就绪文件，属测试夹具契约，需在 G-8 里统一成"自建库 + 显式就绪探针"。

## 5. 对 P0 的口径（本基线如何使用）

1. 每个派发单元只跑**自己的定向测试**（`-Dtest=...`），并在证据 README 里贴 `Tests run` 原文与命令。
2. 提交前对照本文件：**不得新增坏类名**；若新增，必须在该提交里给出根因或降级说明。
3. 阶段出口（S1）才要求"路线一重跑 + 相关模块全量"，全量口径修复列为 G-8 前置。
4. 任何"全绿"表述必须附模块级 `Tests run` 合计，且 Skipped 必须为 0 或逐条解释。

## 6. 后续动作

- **G-8（新增，S0，约 3 人日）**：①`test.ps1` 增加 `-Xmx1024m` 与 surefire fork 超时；②测试基座统一注册 deployment-mode / security-mode；
  ③`SkillClients` 停止把 4xx 吞成 502；④fixture 自建专用库与就绪探针；⑤model-gateway 测试启动缓存或显式跳过。
- G-7：Y-1 路由目录补登记（需先定 caller/action）。
- 下一轮补跑 monitor 与 engine-runtime 完整基线（engine 用 `-Dtest=!RuntimeStreamTest` 先绕开挂起类）。
