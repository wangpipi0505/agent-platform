# INT-03 子步2（A 席）：配置发布与已发布应用接线

- 日期：2026-11-03
- 席位：A（AP 后端）
- 基线 HEAD：`878ce093`（父提交）
- 范围：`IntegrationConfigReleases` 引用接线、`PublishedMetadataService` 组合引用/有效性核对、`ConfigReleaseController` 必要扩展（本子步未改）、契约 §2/§3/§4/§10/§11
- 未触：`AppService`、`PublishedMetadataService.published(...)` 的 PORTAL 选择器语义、`SkillExecution`/`SkillAdapters`（O 席 SEM-02.2）、任何迁移

## 一、原责任 → 新责任

| 关注点 | 子步1 | 子步2 |
| --- | --- | --- |
| 发布钉住的版本 | 记录 `appVersion` + `candidateSha256`，创建时只校验行存在 | 不变；另在回显中给出该版本精确引用的技能版本（`skillRefs`） |
| 引用有效性 | `validate` 时核对体验/接入档案 APPROVED、连接引用存在 | 新增：`validate` 额外核对"体验声明的能力必须由该版本的技能引用支持"；`activate` 前重新核对全部引用 |
| 激活 | `store.activate` 只做 APPROVED→ACTIVE 的指针 CAS | 先 `requireReferencesStillValid`（状态、应用版本摘要、体验、接入档案、连接引用 + APPROVED），再做同一 CAS |
| 对外投影 | capabilities 用调用方传入的 `appVersion` 过滤技能 | capabilities 用**当前真实发布版本**（`PublishedMetadata`）的技能引用过滤；版本或摘要不一致时 409 `CONFIG_RELEASE_NOT_ACTIVE` |

## 二、改动文件

| 文件 | 说明 |
| --- | --- |
| `app-management/src/main/java/cn/agentplatform/app/application/IntegrationConfigReleases.java` | 注入 `PublishedMetadataService`；`experience` 增加"当前发布版本 + 候选摘要"核对；`capabilities` 改为按发布元数据的技能引用过滤；`activate` 前新增 `requireReferencesStillValid`；新增 `requireActiveConnection`（连接档案须 APPROVED）与 `requireCapabilitiesBacked`（体验能力须有技能引用支持）；`view` 回显 `skillRefs` |
| `app-management/src/main/java/cn/agentplatform/app/application/PublishedMetadataService.java` | 抽出私有 `read(tenant, code)`；保留 `published(...)`（PORTAL 选择器）不变；新增 `composition(code)`（投影用，同一份读取、不叠加选择器）与 `skillReferences(code, versionNo)`（按版本确定性读取 `app.skill_reference`） |
| `app-management/src/test/java/cn/agentplatform/app/infra/ConfigReleaseFlowTest.java` | 夹具改为"已发布应用 + 指向第 1 版"；新增 `approvedConnection`、带能力清单的体验夹具；新增 3 个用例（见下） |
| `docs/evidence/int-03-20261103/INT-03-A-step2.md` | 本文件 |

## 三、不变量

1. 状态机不变：DRAFT→VALIDATED→APPROVED→ACTIVE 仍是唯一路径，激活仍是作用域指针 CAS，旧 ACTIVE 退位 SUPERSEDED。
2. 激活失败不产生副作用：`requireReferencesStillValid` 在任何写之前抛出，激活指针与 `config_write_request` 都不落。
3. 同一 `requestId` 只产生一次写入：四个写操作仍走 `ConfigWriteLog` 的 replay/remember；本子步未改变摘要输入。
4. 摘要只覆盖声明与版本引用：`digest` 公式未改（应用版本 + 候选摘要 + 体验修订 + 接入档案修订 + 排序后的连接引用），不含任何凭据，也不含本次新增的只读回显字段。
5. 投影只来自当前真实发布版本：应用重发之后旧配置不再对外承诺能力，失败关闭（409），不降级为空体验。
6. 引用失效阻断激活：连接档案停用/未批准、体验被改回草稿、接入档案被改、应用版本候选变化，任一项都拒绝激活。
7. 错误码沿用契约最小集：`CONFIG_REF_NOT_FOUND`(404)、`CONFIG_STATE_TRANSITION_INVALID`(400)、`CONFIG_RELEASE_NOT_ACTIVE`(409)、`CONFIG_VERSION_UNKNOWN`(400)，未新增同义码。
8. 只读投影不含凭据与管理路径：`composition`/`skillReferences` 只读状态与技能引用，不读 `secret_ref`、不读管理配置。

## 四、命令与结果（真实 PG 35432/agent_platform_test，账号 dev_ap_test，口令不回显）

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -o -pl app-management -Dtest='ConfigReleaseFlowTest,AssistantExperienceRulesTest' test` | `ConfigReleaseFlowTest` **8/8**（13.04s）、`AssistantExperienceRulesTest` **8/8**，合计 **16/16** |
| 1 | `mvn -o -pl admin-common -Dtest='RouteCoverageVerifierTest' test` | **10/10**（权限目录未改，确认可加载且模板精确） |
| 1 | `mvn -o -q -pl app-management -am test-compile` | 通过（期间一次失败为同仓其他席位的在建文件 `SemanticBindings.java`，其落盘后即通过；本席未改动该文件） |

新增用例：

- `projectionFollowsCurrentlyPublishedVersion`：发布回显 `skillRefs` 精确到 `customers.read@3`；应用重发第 2 版后，钉在第 1 版的激活配置投影返回 409 `CONFIG_RELEASE_NOT_ACTIVE`。
- `disablingReferencedConnectionBlocksActivation`：正例——引用有效时激活成功；负例——引用方被停用后另一份发布激活被拒（409 `CONFIG_RELEASE_NOT_ACTIVE`），且该作用域**没有**留下激活指针。
- `capabilityWithoutSkillReferenceBlocksValidation`：体验声明 `ledger.write` 但版本只引用 `customers.read`，校验阶段直接 400 `CONFIG_STATE_TRANSITION_INVALID`。

## 五、未覆盖

- 全量回归与真实启动（`ROUTE_COVERAGE_MISSING` 等启动期强校验）仍需 P 串行窗口确认；本席只跑定向用例。
- 未做：前端体验预览、生成客户端同步、L2/L3 前端接入、按环境差异化的投影缓存（投影每次实时读库，未加缓存）。
- `PublishedMetadataService.composition` 的权限语义只做了"不带凭据、不可写"的读取，尚未有跨席复核（见缺口 1）。

## 六、契约缺口（只登记，未私改契约）

1. **投影读取发布元数据的权限口径**：`published(...)` 走 `MetadataSelectors.published`，其 `requirePurpose` 要求 caller=gateway、source/origin=PORTAL；而 `GET /v1/apps/{code}/experience` 是集成侧公开读。本席用 `composition(...)` 同源读取、不叠加选择器，请裁决这是否应写入契约（或改为专用选择器）。
2. **激活前复核未写入 §4 状态机**：契约只写"激活 CAS"，未写"激活前必须重新核对引用何时失效"。建议明确：激活是最后的有效性门。
3. **能力与技能引用的对应关系**：§7 预检清单未含"体验声明的能力必须有该版本技能引用支持"。本席在校验阶段拦截，需回写 §7。
4. **"被引用资源停用"的报码**：契约 §10 把"停用被引用"定为 `CONFIG_RELEASE_NOT_ACTIVE`（本条指环境绑定）；配置发布侧本席沿用同一码（409），请确认是否应改用 `CONFIG_STATE_TRANSITION_INVALID`。
5. **发布回显新增 `skillRefs`**：契约 §2 的 ConfigRelease 字段表未列该字段；本席以只读派生字段加入回显（不入库、不进摘要），请补登。

## 七、回退

`git revert <本子步提交>`：只回退两个产品文件与一个测试文件，无迁移、无数据修复；已产生的发布行与激活指针不受影响（本子步未改表结构）。
