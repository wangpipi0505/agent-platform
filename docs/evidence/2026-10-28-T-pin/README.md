# 卡片 18（T-pin）发布绑定：证据与说明（2026-10-28）

## 结论

- 发布 `commit` 在**事务提交后**调用一次本体 `POST /onto/internal/release-bindings`，结果以 `pin` 键并入返回体；默认 fail-open（绑定失败不撤销已发布版本），`agent.pin.fail-closed=true` 时抛 502 `PIN_FAILED`。
- 先红后绿：红阶段 18 个用例 14 失败（骨架恒返回 `SKIPPED`、零 HTTP），实现后 18/18 通过。红绿 `Tests run` 原文见下。
- 改动只涉及卡片允许的 5 个文件；无迁移、无新依赖、未改 pom/contracts/scripts/web/其它模块。

## 改动文件清单（行数为最终工作区）

| 文件 | 变更 | 行数 |
| --- | --- | --- |
| `app-management/src/main/java/cn/agentplatform/app/infra/ReleasePins.java` | 新增 | 189 |
| `app-management/src/main/java/cn/agentplatform/app/application/AppService.java` | +57/-1 | 1647 |
| `app-management/src/main/resources/application.yml` | +11 | 54 |
| `app-management/src/test/java/cn/agentplatform/app/infra/ReleasePinsTest.java` | 新增 | 282 |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishPinTest.java` | 新增 | 356 |

`git diff` 原文（两个已跟踪文件）：`modified.diff`。`ReleasePins` 实现要点：

- 构造注入 `agent.ontology.base-url`、`agent.ontology.api-key-ref`、`agent.pin.enabled`、`agent.pin.fail-closed`、`agent.pin.environment`；`SecretVault.resolve(apiKeyRef, actor)` 解析 `vault:`/`env:` 引用，未配置即 `DISABLED`。
- 自己的 JDK `HttpClient`（connect 3s、`Redirect.NEVER`、请求 5s、`readNBytes(64KiB+1)` 上限）；只发 `X-API-Key`，**不发 `Authorization`**；正文仅 `platformTenant/appCode/environment/releaseId`。
- 状态机：`DISABLED`（开关/配置缺失，零 HTTP）→ `SKIPPED`（未声明 ontology、space/releaseId 缺失或非 UUID，零 HTTP）→ `PINNED`（2xx，透传 `bindingId`/`changed`）→ `FAILED`（异常/非 2xx，reason ≤200 字；fail-closed 时抛 502）。

`AppService` 边界：

- `commit` 中只把"本次发布的候选"带出事务（普通分支 `candidate(app)`；重放分支按 `version_no` 读 `app.app_version.snapshot`，见 `releasedCandidate`），**事务 lambda 内没有任何 HTTP**。
- `tx.execute` 返回后复制成 `LinkedHashMap` 再 `put("pin", ...)`，不改 `publication()` 的不可变凭据；重放与普通返回走同一套逻辑。
- 通过 `@Autowired(required=false) public void pins(ReleasePins)` 注入，构造签名未动（仓内 10 处测试直接 `new AppService(...)`）；未装配时返回 `DISABLED`，既有测试无需改动。

## 红阶段（先写测试 + 骨架）

命令（每条命令前 `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`、`source .tools/env-load.sh`）：

```
mvn -B -ntp -o -pl app-management test -Dtest='ReleasePinsTest,PublishPinTest' -DargLine="-Xmx1024m"
```

`Tests run` 原文：

```
[ERROR] Tests run: 8, Failures: 6, Errors: 0, Skipped: 0, Time elapsed: 2.361 s <<< FAILURE! -- in cn.agentplatform.app.application.PublishPinTest
[ERROR] Tests run: 10, Failures: 8, Errors: 0, Skipped: 0, Time elapsed: 0.034 s <<< FAILURE! -- in cn.agentplatform.app.infra.ReleasePinsTest
[ERROR] Tests run: 18, Failures: 14, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

红的原因：骨架 `pin` 直接 `return Map.of("state","SKIPPED","reason","骨架未实现")`，**不发任何 HTTP**，失败全部是行为断言——`expected: <PINNED> but was: <SKIPPED>`、`expected: <DISABLED> but was: <SKIPPED>`、fail-closed 用例 `Expected PlatformException to be thrown, but nothing was thrown`、非 UUID 用例 reason 不含 `UUID`；无编译错误、无夹具异常（`permit()` 首版漏了 `app_code` 占位参数导致的 `未设定参数值 7` 在红之前已修正）。

## 绿阶段（实现后）

同一条命令，`Tests run` 原文（完整输出：`green.log`，surefire 报告：两个 `*.txt`）：

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.427 s -- in cn.agentplatform.app.application.PublishPinTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in cn.agentplatform.app.infra.ReleasePinsTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

覆盖的 6 条要求：①声明 ontology → 桩服务器恰好 1 个 POST、路径/四键正文/头正确、`PINNED` 且 `changed` 透传；②未声明 → 零 HTTP、`SKIPPED`；③space 非 UUID → 零 HTTP、`SKIPPED`、reason 含 `UUID`、发布成功；④远端 500 → `FAILED` 且发布成功，fail-closed → 502 `PIN_FAILED` 且版本仍已提交；⑤`enabled=false` → 零 HTTP、`DISABLED`；⑥缺 `api-key-ref` → 零 HTTP、`DISABLED`。另含空 ontology、releaseId 非 UUID、缺 base-url、302 不跟随、重放用快照而非当前草稿、未装配客户端 `DISABLED` 等边界。

## 回归

```
mvn -B -ntp -o -pl app-management test -Dtest='PublishTransactionTest,PublishGateTest,PublishedPayloadHttpTest' -DargLine="-Xmx1024m"
```

结果：`PublishedPayloadHttpTest` 24 通过、`PublishGateTest` 11 通过、`PublishTransactionTest` 13 个中 2 errors。两个错误为既有问题、与本卡无关：

- `integrationSecretOnlyShownOnceAndRevocationPersists:315`、`globalCredentialLookupRequiresDedicatedGatewayIdentity:347`；
- 堆栈：`PlatformException` at `AuthorityContext.frame(AuthorityContext.java:70)` ← `refresh(74)` ← `requireFor(41)` ← `IntegrationService.requirePublishedVersion(213)` ← `issue(131)`；
- 两处 `commit`（行 312/344）均已成功，失败发生在直接调用 `IntegrationService.issue` 时缺少 API 过滤器建立的 request-scope authority frame；`admin-common` 未改动。

## 偏差与未覆盖项

1. `agent.pin.*` 键名按父 agent 补充约定与 `engine-runtime` 对齐，便于同一部署共用环境变量；默认 base-url 为空（未配置即 `DISABLED`）。
2. JDK HttpClient 无独立 read timeout：5s 是整次 exchange 的请求超时，响应体另由 `readNBytes(64KiB+1)` 限长（与 `OntologyContextPacks` 同法）。
3. 2xx 但响应体不可解析时仍记 `PINNED` 并附 reason（绑定已生效，不误报失败）；2xx 无 `data` 对象时从顶层取 `bindingId`/`changed`。
4. fail-closed 的 502 发生在事务提交之后，**已发布版本不可回滚**；测试显式断言 502 后版本仍存在，README 与代码注释均说明该有意限制。
5. `pins == null`（既有 10 处手工 `new AppService(...)`）返回 `DISABLED` 而不抛错，避免影响既有用例。
6. Spring 全上下文未能验证：`DraftMetadataHttpTest` 因两个既有测试夹具同名 bean（`PublishedMetadataFixtureMain$Receiver` 与 `PublishedAdmissionFixtureMain$Receiver` 的 `authorityProperties`，被 `AppApplication` 的 `scanBasePackages` 扫到）启动失败，属预先存在的问题；本卡 yml 已用 YAML 解析验证键位，`@Value` 默认值为常规字符串占位符。
7. 未覆盖：真实连接拒绝/超时的时延、超 64KiB 响应、`env:` 形式密钥（测试用 `vault:` 真实解密）、并发 commit 的 pin 次数、base-url 尾斜杠规范化（代码已 `replaceAll("/+$","")`）。
8. 未新增迁移、依赖；未 `git add`/`commit`/`checkout`。

## 回滚

`agent.pin.enabled=false`（或 `AGENT_PIN_ENABLED=false`）即恢复旧行为，零 HTTP、返回 `DISABLED`；无数据库变更需回滚。

## 证据文件

- `green.log`（最终验收命令完整输出）
- `cn.agentplatform.app.infra.ReleasePinsTest.txt`、`cn.agentplatform.app.application.PublishPinTest.txt`（surefire 报告）
- `modified.diff`（AppService、application.yml 的 `git diff`）
