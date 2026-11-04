# ONB-08（A 席）：独立入口交付与操作说明（含 PublishTransaction 授权引导夹具）

- 日期：2026-11-05
- 席位：A（平台/服务端）
- 基线 HEAD：`8b935945`（夹具规范化批次）
- 范围：`docs/onboarding/` 交付与操作说明；`docs/integration/README.md` 总入口指向；`PublishTransactionTest` 授权引导测试夹具
- 未触：产品代码、契约正文、迁移、权限目录、其他席位的在改文件

## 一、原责任 → 新责任

| 原责任 | 新责任 | 证据 |
| --- | --- | --- |
| 独立入口操作路径散落在 ONB-06 页面与 INT-09 交付包 | `docs/onboarding/README.md` 作为总入口，`standalone-entry.md` 给模板→预检→发布→交接→首跑的完整路径 | 本回执 §二 |
| 内嵌交付「待集成」只存在于前端常量 | `embedded-delivery.md` 按“已交付/待集成”分栏，逐项说明缺什么 | `ONBOARDING_UNDELIVERED` 同源 |
| B02 视觉、夹具依赖、手册同步无统一登记 | `known-issues.md` 按“待外部前置/夹具依赖/口径差异/文档同步/运行限制”分类登记 | 本回执 §五 |
| `PublishTransactionTest` 两个用例直连服务、缺可复核授权帧 | 测试内安装授权帧（角色/租户仍来自测试 JWT），三处 `issue/revoke` 经引导层；断言与调用顺序未改 | 测试 12/13，唯一红为模型网关 stub |
| 该类无密钥库配置，`SecretVault.put` 直接失败 | 按既有 `PublishPinAuditPostgresTest` 模式在 `@BeforeAll` 设临时 `agent.secret.dir`/`agent.master.key`，`@AfterAll` 恢复并清理 | 同上 |

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `docs/onboarding/README.md` | 52 | 总入口：两条路径、前置、已验证清单、回退 |
| `docs/onboarding/standalone-entry.md` | 86 | 独立入口全路径、五项首跑清单、常见错误、回退 |
| `docs/onboarding/embedded-delivery.md` | 50 | SDK 已交付能力、示例位置、待集成清单、接线边界 |
| `docs/onboarding/known-issues.md` | 50 | B02/夹具/口径/文档同步/运行限制 |
| `docs/integration/README.md` | +2 | 增加指向 `../onboarding/README.md` 的总入口说明 |
| `app-management/src/test/java/cn/agentplatform/app/application/PublishTransactionTest.java` | 613 | 授权引导夹具 + 密钥库测试配置；无断言改动 |

## 三、不变量

1. 未改任何断言、未改用例调用顺序；未放宽产品侧授权/摘要校验。
2. 测试夹具中的 Actor 仍由测试 JWT 构造（`application-tests/publisher`，`EDITOR+ADMIN`），未伪造角色或租户。
3. `ResourceBinding` 与产品同口径：`app-version = code@current_version_no + candidate_sha256`；`integration-credential = id + sha256(canonical(credentialId, policyVersion, policyDigest))`。
4. 授权帧只在测试调用 `issue/revoke` 期间安装并关闭，不进入产品运行路径。
5. 文档只写逻辑引用与校验摘要；不含密钥、token、内部账号属性。
6. 错误码只引用 `contracts/assistant-integration.md` §6 与 `docs/integration/error-codes.md`，未新增同义码。
7. 「待集成」项如实标注，不用示例页冒充业务页验收；B02 视觉不以视觉稿替代。
8. 未引入新表、迁移或权限条目；回退只回退本次提交。

## 四、命令与结果

| 轮次 | 命令（环境：`AGENT_DB_URL=35432/agent_platform_test`、`AGENT_DB_USER/PASSWORD` 自私有准备根读入，口令不回显） | 结果 |
| --- | --- | --- |
| 1（改造前） | `mvn -pl app-management -am -Dtest=PublishTransactionTest test` | 13 run / 3 errors：2×`缺少当前授权上下文`、1×模型冻结 stub |
| 2（授权引导后） | 同上 | 13 run / 1 error（仅 `candidateAndPublishedVersionUseIdenticalFrozenModels`） |
| 3（含密钥库配置） | 同上 | 13 run / 1 error（同上）；`integrationSecretOnlyShownOnceAndRevocationPersists`、`globalCredentialLookupRequiresDedicatedGatewayIdentity` 转绿 |
| 4（三类别合并） | `-Dtest='PublishTransactionTest,PublishPinAuditPostgresTest,PublishedMetadataHttpTest'` | 31 run / 1 error：`PublishPinAudit` 9/9、`PublishedMetadataHttp` 9/9、`PublishTransaction` 12/13 |
| 5（前端类型） | `web: npx vue-tsc --noEmit` | exit 0 |
| 6（ONB 前端定向） | `vitest run tests/onboarding-launch.test.ts tests/onboarding-templates.test.ts` | 16/16 |

仍红项：`candidateAndPublishedVersionUseIdenticalFrozenModels` — 夹具 stub 返回 `sha256:"freeze-1"`（非 64hex），被产品摘要校验拒绝；需 model-gateway 侧按契约返回合法摘要，本卡保留登记、不改校验。

## 五、未覆盖

1. B02 真机截图与三尺寸视觉（业务栈未启动）；首跑清单页截图同样待补。
2. 宿主真实业务页内嵌联调（CSP/同源代理/跨源 iframe）。
3. `RunAuthorityLiveResumeTest` 真跑（需 `EX26_LIVE_RESUME=true` + authority 18086）。
4. 手册第 13 章同步：手册位于文档库、非本仓库，登记为待同步项。
5. 模型网关冻结契约合法摘要的联调。

## 六、契约缺口（只登记，未私改）

1. 手册/交付文档没有“接入模板向导”章节；本轮在仓库 `docs/onboarding/` 建立口径，文档库待同步。
2. `PublishTransactionTest` 通过反射使用 admin-common 包内 `AuthorityContext.bind(value, refresh)`；仅测试装配使用，未在产品侧新增公开旁路。
3. 管理台无 Authority 身份源只读投影，诊断页 identity 阶段只能标 `UNKNOWN`。
4. 模板种类目前仅 `QUERY` 可登记；`FILE_ANALYSIS`/`CONTROLLED_WRITE` 的开放条件未入契约。
5. 交付文档与前端常量（`ONBOARDING_UNDELIVERED`）需人工保持同步，尚无自动校验。

## 七、回退

- 代码/文档：`git revert <本卡提交>`；删除 `docs/onboarding/` 与 `docs/integration/README.md` 的指向即可恢复原状。
- 测试夹具：回退后该类恢复 3 红（2 授权 + 1 模型网关），不影响产品行为。
- 数据：本卡无数据库改动；无需要清理的运行事实。
