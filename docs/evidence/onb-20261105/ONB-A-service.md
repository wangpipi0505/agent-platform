# ONB 服务端子步（A 席）：业务接入模板目录与服务端预检

- 日期：2026-11-05
- 席位：A（服务端）
- 基线 HEAD：`033926ce`（父提交 `f87b3cb3`，INT-07 交付）
- 范围：app-management 的模板目录（V116 + domain/infra/application/api）、服务端 `OnboardingPreflightService`、网关路由与 IAM 路由目录条目。
- 未触：其他席位的 `docs/evidence/int-07-20261104/assistant-sdk-structure.json` 改写、`web/admin` 页面实现、`web/shared/onboarding.ts`。

> 说明：本卡开工时发现上一轮同名草稿未落盘（worktree 无 onboarding 相关类、迁移止于 V115、无提交），本轮按同一设计重新实现，旧草稿不计入交付。

## 一、原责任 → 新责任

| 原责任 | 新责任 |
| --- | --- |
| 无（ONB 目录未交付） | `OnboardingTemplate` 领域对象：模板种类白名单、声明摘要（`onboarding-template.v1` canonical）。 |
| 无 | `OnboardingTemplateRepository`：head/版本两表、`expectedRev` CAS、`DRAFT→PUBLISHED→DISABLED` 状态机、租户隔离。 |
| 无 | `OnboardingTemplates`：管理员编辑面（list/get/create/revise/publish/disable），`ConfigWriteLog` requestId 幂等；发布前复核能力包摘要、体验修订、接入档案修订。 |
| 无 | `OnboardingPreflightService`：以服务身份调用 skill-registry 既有 OpenAPI 预览，归一到 F 席消费形状并附 `planSha256`；网络/5xx 归一 502，不伪造“全部不支持”。 |
| 无 | `OnboardingController` + 网关前缀 + 7 条 `agent-service-authority.json` 精确条目（ADMIN-only）。 |

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `app-management/src/main/resources/db/migration/V116__onboarding_templates.sql` | 49 | `app.onboarding_template` / `app.onboarding_template_version`，含回滚注释。 |
| `app-management/src/main/java/cn/agentplatform/app/domain/OnboardingTemplate.java` | 76 | record、种类白名单、声明摘要。 |
| `app-management/src/main/java/cn/agentplatform/app/infra/OnboardingTemplateRepository.java` | 366 | 事务内 head 锁、CAS、状态迁移、租户过滤、档案修订存在性查询。 |
| `app-management/src/main/java/cn/agentplatform/app/application/OnboardingTemplates.java` | 346 | 编辑面、幂等、发布前引用复核、预检编排。 |
| `app-management/src/main/java/cn/agentplatform/app/application/OnboardingPreflightService.java` | 198 | skill 预览代理与归一。 |
| `app-management/src/main/java/cn/agentplatform/app/api/OnboardingController.java` | 69 | 7 个 ADMIN 端点。 |
| `app-management/src/test/java/cn/agentplatform/app/application/OnboardingTemplatesTest.java` | 311 | 15 例定向单测。 |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/domain/Routes.java` | +2 | `/admin/onboarding-templates` 转发到 app（只决定方向，不放权）。 |
| `admin-common/src/main/resources/agent-service-authority.json` | +84 | 7 条精确路由（GET/POST、delegated、integrationAllowed=false）。 |

## 三、不变量

1. 模板只做“包装引用”：能力包精确版本 + 体验修订 + 接入档案三元组（bizSystemCode/environment/mode），不新建连接或模板执行引擎。
2. 仅 `QUERY` 可登记；`FILE_ANALYSIS`/`CONTROLLED_WRITE` 返回 400 `INVALID_INPUT`（“该模板种类尚未开放”），不静默降级。
3. 发布前复核全部引用：能力包不存在 → 404 `CONFIG_REF_NOT_FOUND`；摘要不符 → 400 `INVALID_INPUT`；体验修订未批准 / 接入档案修订未批准 → 400 `CONFIG_STATE_TRANSITION_INVALID`；引用失效时不迁移状态。
4. 状态机只允许 `DRAFT→PUBLISHED→DISABLED`（停用也允许从 DRAFT），其余 400 `CONFIG_STATE_TRANSITION_INVALID`；发布/停用均要求 `expectedRev`，当前修订变化 → 409 `CONFIG_REV_CONFLICT`。
5. 写入幂等：`create/revise/publish/disable` 均经 `ConfigWriteLog`，同 `requestId` 同载荷回放并标记 `replayed=true`，异载荷 409 `CONFIG_REV_CONFLICT`。
6. 租户隔离：全部查询带 `tenant_id`；跨租户不可见，HEAD 行锁只在本地事务内。
7. 预检只读：只返回逐操作支持子集、诊断与计划摘要；`zeroCreated=true`，不生成技能/应用，不落库。
8. 预检不回显上游原始消息：上游 4xx 有登记码时透传，网络/5xx 归一 502 `DEPENDENCY_UNAVAILABLE`。
9. 权限：控制器每方法 `requireRole("ADMIN")`；网关仅转发，集成票据仍被 `IntegrationPolicy` 挡住（`integrationAllowed=false`）。
10. 公开接口/数据库既有表不变；V116 仅新增两表与索引，回滚注释随迁移文件保存。

## 四、命令与结果

真实 PG 窗口：35432 与 25432 均 down（本机无 postgres 进程/安装目录/docker），本轮无法执行数据库定向用例。

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `JAVA_HOME=<jdk21> mvn -q -pl app-management,platform-gateway -am -DskipTests compile` | 失败：`OnboardingTemplates` 两个模式变量同名 `number`；改名后通过。 |
| 2 | 同上 | exit 0 |
| 3 | `mvn -q -pl app-management -am -Dtest=OnboardingTemplatesTest -Dsurefire.failIfNoSpecifiedTests=false test` | 首轮 3 例因 `requestId` 形状（`req-1` 少于 8 字符）失败；改用 `req-0001`/`req-000p` 后 15/15 通过。 |
| 4 | 同上（补 optional connection 用例后） | 15/15 通过（`Tests run: 15, Failures: 0, Errors: 0`）。 |
| 5 | `mvn -q -pl platform-gateway -am -Dtest=RoutesTest ... test` | 7/7 通过（`/admin/onboarding-templates` 分发）。 |
| 6 | `mvn -q -pl admin-common -am -Dtest=AuthorityPolicyTest ... test` | 13/14：既有失败 `catalogCoversEveryActualControllerTemplate...`，扫描到 `engine-runtime` 多个 `public final class ...Controller`（`ModelInputMaterialController` 等）时 `indexOf("public class ")` 为 -1；这些文件非本卡改动，属基线脆弱断言，未改断言。 |
| 7 | Python 校验 manifest | 269 条、JSON 合法、无重复 (method,template,caller,target)；onboarding 7 条齐全。 |

## 五、未覆盖

1. 真实 PG：模板创建/引用失效阻断/幂等重放/预检形状（`AGENT_DB_*`+`ENGINE_PG_TEST`）未执行；本轮只有 Mockito 单测。
2. 控制器 HTTP 层（ADMIN 鉴权、请求体形状、分页）未做 `@SpringBootTest` 用例；app 现有 Spring 测试均以 `AGENT_DB_PASSWORD` 为前置，当前环境不可用。
3. 与真实 skill-registry 的联调（`POST /admin/skills/import-openapi/preview`）未跑；`OnboardingPreflightService` 用 mock 单测覆盖归一路径。
4. 前端 `OnboardingTemplatesPage.vue` 尚未接本 API（F 席范围）。
5. 真实 OpenAPI 文档（oneOf/超预算/坏 JSON）的支持子集形状只按契约构造，未对真实解析器验证。

## 六、契约缺口（只登记，未私改）

1. `contracts/assistant-integration.md` §6 未登记 `OPENAPI_SIZE`（skill-registry 既有 413 码）与 `DEPENDENCY_UNAVAILABLE` 在 ONB 预检场景的语义；本卡沿用既有码，未新增。
2. 方案 §4.2 的 `templateId/sha256` 与前端契约的 `code` 标识存在两种称呼；本卡以 `code` 为稳定标识、`template_rev` 为版本，`declaration_sha256` 为声明摘要。
3. 预检响应尚未定义 `planSha256` 的登记口径（本卡以 `onboarding-template.preflight` + 模板声明 + 文档摘要 + 操作子集 + 连接提议 canonical 计算），需在契约确认。
4. 方案提到“所需连接类型/风险/前置/确定性用例骨架”，前端当前契约未消费；本卡未存这些字段，避免建第二套模型。
5. `sourcePackageSha256` 要求模板与能力包精确一致；能力包升级后旧模板需新建修订，契约未写升级指引。

## 七、回退

- 代码：`git revert <本提交>`；V116 按文件头注释逆序执行回滚 SQL（先删 version 表、索引，再删 head 表）。
- 数据影响：回退只删除本卡新增的模板目录两表，不触碰既有能力包/体验/接入档案/配置发布数据。
- 入口：网关 `/admin/onboarding-templates` 前缀与 7 条 manifest 条目随提交一并回退；回退后前端页面自然回到“服务不可用”提示，不影响既有应用来源。
