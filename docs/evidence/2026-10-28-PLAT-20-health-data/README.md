# T20.1 / PLAT-20：actuator `data` 健康贡献者 + `/health/data`（无迁移）证据

## 1. 范围依据

- 方案 §4.20 实现项 1："`/actuator/health` 增加 `data` 组件：数据源类型、数据库名、Flyway 版本、关键表行数（脱敏）；`/health/data` 只对 ADMIN"。
- 台账 §3 T20.1（深度健康替代 /actuator/health 的本仓半：只做健康收口；不做 run 结果 environment/dataset 标注——engine 侧另卡 E-3；Orekit 为路线一专属，本仓无）。
- 约束：复用 `SystemStatusController` 的数据访问方式（同一 JdbcTemplate），不重复建数据源探测；`AGENT_DEMO_MODE` 等既有环境标记入健康详情。

## 2. 环境

- 仓库 `G:\207\项目\算法重组工具\代码\agent-platform`，分支 `feat/v1.2-implementation`，基线 app-management HEAD `c94f2f1`（T12a V107），仓库 HEAD `bcd9184`；T18.3 的 V106 同工作树（本卡无迁移，健康面读到的最高 Flyway 版本含 106/107）。
- JDK 21（Eclipse Adoptium jdk-21.0.12.8-hotspot）、真实 PG 16.15 @ 127.0.0.1:25432；`.tools/env-load.sh` 载入 `.env`（`AGENT_DB_PASSWORD`/`AGENT_JWT_SECRET` 等，只导出不打印）。
- 本卡专用测试库 `agent_platform_health_data_v12`（Fresh DB 顺序迁移；HTTP 夹具以 `@DynamicPropertySource` 指向，绝不复用可能已到 V107 的既有库）。
- 鉴权面：生产同一条 `SecurityConfiguration` 门户链（dev-hmac 模式 + JWT roles claim），HTTP 夹具为最小装配上下文（不扫 `cn.agentplatform.common` 全包，避开既有全量上下文的 fixture Receiver 冲突，见第 5 节）。

## 3. 命令原文

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
source .tools/env-load.sh

# 红例（编译期红）：DataHealthService/DataHealthController/DataHealthIndicator 不存在
mvn -B -pl app-management test-compile -Dtest=DataHealthHttpTest,DataHealthPostgresTest "-DargLine=-Xmx1024m"
#   → red-compile.txt：找不到符号 8 处（两个新测试类全部引用悬空）

# PostgresTest 定向（服务层：库名/Flyway 版本/行数取整/demo 标记/缓存）
mvn -B -pl app-management test -Dtest='DataHealthPostgresTest' "-DargLine=-Xmx1024m"
#   → green-pgres-run1.txt（首跑暴露 flyway_schema_history rank0 行 version=NULL 的 NPE，属实现缺陷，已修）
#   → green-pgres-run2.txt 全绿

# HTTP 定向（/health/data 401/403/200 + /actuator/health 不泄露明细）
mvn -B -pl app-management test -Dtest='DataHealthHttpTest' "-DargLine=-Xmx1024m"
#   → green-http-run1.txt（首跑暴露双构造歧义 No default constructor found，已修）
#   → green-http-run2.txt 全绿

# 最终多类同跑（含 T18.3 新类与既有 pin/gate/相邻 PostgresTest）
mvn -B -pl app-management test -Dtest='PublishPinAuditPostgresTest,DataHealthPostgresTest,DataHealthHttpTest,ReleasePinsTest,PublishPinTest,PublishGateTest,PublishTransactionTest,IdentityMappingPostgresTest,AuditLinkPostgresTest,IntegrationPolicyDatabaseTest,AppCopyConcurrencyTest' "-DargLine=-Xmx1024m"
#   → final-acceptance-combined.txt：Tests run: 72, Failures: 0, Errors: 3
```

## 4. 结果原文

- 红（编译期，red-compile.txt）：`DataHealthHttpTest.java:[5,40] 找不到符号`（DataHealthService）等 8 处。
- 绿（PostgresTest 5 例，green-pgres-run2.txt）：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.app.application.DataHealthPostgresTest
```

- 绿（HTTP 4 例，green-http-run2.txt）：匿名 `/health/data` → 401 `SYS-AUTH-401` 可读 JSON；EDITOR → 403 `SYS-APP-4031`；ADMIN → 200 且 `environment=DEMO`、`demoMode=true`（夹具注入 `AGENT_DEMO_MODE=true`）、`database=agent_platform_health_data_v12`、`flywayVersion>=106`、`dataSourceType` 以 `PostgreSQL` 开头、`rowCounts` 含 `app.application` 等 4 表取整数；`/actuator/health` 匿名 200 `status=UP` 且无 `rowCounts`/`components` 泄露（show-details=never 维持）。

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.app.api.DataHealthHttpTest
```

- 最终验收（final-acceptance-combined.txt）：`Tests run: 72, Failures: 0, Errors: 3`；与干净基线 worktree @ `bcd9184`（54 例 3 Error）逐行同签名 → **新增失败 = 0**。三条既有失败（Y-4 口径）：
  1. `AppCopyConcurrencyTest.database:40 ? FlywaySql ... database "agent_platform_app_copy_test" does not exist`；
  2. `PublishTransactionTest.globalCredentialLookupRequiresDedicatedGatewayIdentity:347 ? Platform 缺少当前授权上下文`；
  3. `PublishTransactionTest.integrationSecretOnlyShownOnceAndRevocationPersists:315 ? Platform 缺少当前授权上下文`。

## 5. 未覆盖与已知失败

- 上列 3 条既有失败原样保留（Y-4 口径），本卡不修。
- 既有环境事实（记录不修改）：`DraftMetadataHttpTest`（@SpringBootTest(AppApplication) 全量上下文）在当前环境本就起不来——component-scan 把 test-jar 的 `PublishedMetadataFixtureMain$Receiver`/`PublishedAdmissionFixtureMain$Receiver` 一并扫入，`authorityProperties` bean 冲突（BeanDefinitionOverrideException）。本卡 HTTP 夹具因此采用最小装配 + 生产安全链，行为面（鉴权/信封）与生产一致，但不覆盖全量上下文装配（该装配现状不可用，属 Y-4 既有问题）。
- 行数脱敏口径（定死）：`roundCount` 只保留首位数字量级（12→10、1234→1000、0→0），从不泄露精确行数；Flyway 版本取"已成功应用的最大整数版本"（rank0 的 `<< Flyway Schema Creation >>` 行 version=NULL 已显式跳过）；数据源类型只报 `PostgreSQL 16.15` 量级，不带完整 `version()` 串（防构建指纹泄露）。
- 未覆盖：`data` 贡献者在真实网关轮询下的长期负载（TTL 缓存 15s 已在单测证伪逐次 count，但未做压测）；`/health/data` 在 IAM（trusted）模式下的 portal-scope 行为仅由安全链代码推证，本环境无 IAM 部署可测；Redis/各服务探活仍由既有 `/admin/system/status` 承担，本卡未合并两者（避免动既有管理台页面契约）。
- 红例口径：编译期红（T12a 先例）+ 两轮实现缺陷的真实运行红（NULL version NPE、双构造歧义）——首跑记录保留在 run1 文件中。
- 运行方式敏感：需 `AGENT_DB_PASSWORD`/`AGENT_JWT_SECRET`（env-load 载入），缺失时按 `@EnabledIfEnvironmentVariable` 静默跳过，不构成通过证据。

## 6. 回滚方式

- 代码：删除三个新文件 `app-management/src/main/java/cn/agentplatform/app/application/DataHealthService.java`、`app-management/src/main/java/cn/agentplatform/app/infra/DataHealthIndicator.java`、`app-management/src/main/java/cn/agentplatform/app/api/DataHealthController.java` 与两个测试类、本证据目录；`git checkout -- app-management/src/main/resources/application.yml`（T18.3 与本卡共用该文件改动，回滚时留意另一卡的 `agent.pin.record-enabled` 段）。
- 无迁移：本卡不涉及任何 DDL，无 down 脚本。
- 行为回滚效果：`/actuator/health` 恢复为仅内置指示器聚合；`/health/data` 端点消失（401/404 由安全链 anyRequest 语义决定，与改动前一致）。
