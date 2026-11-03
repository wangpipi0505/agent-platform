# INT-03 子步1（A 席）实施记录：应用体验 / 集成档案 / 配置发布

- 日期：2026-11-03
- 基线：AP `upgrade/20261103`，父提交 `836c6b7d`
- 范围：app 侧三对象（assistant_experience、integration_profile、integration_config_release）
  的 ADMIN 目录与对外体验投影 + V113 迁移 + 权限目录 + 网关路由。
- 不在范围（按派单）：不改 `AppService` / `PublishedMetadataService` 的发布方法（与 MNT-06 发布子步串行，
  子步2 再接线）；不做前端页面、不做生成客户端同步、不做连接档案的运行时解析（已在 INT-02 子步2/3 完成）。

## 1. 责任归属（原责任 → 新责任）

| 关注点 | 原状态 | 本子步之后 |
| --- | --- | --- |
| 对外体验文案/能力清单 | 无（只有应用 metadata） | `app.assistant_experience` 不可变修订 + 批准状态，对外只经 ACTIVE 配置发布 |
| 嵌入方式与来源白名单 | 散在集成档案设想里，无对象 | `app.integration_profile` 修订 + L0–L3 mode + 精确 origin |
| 配置组合快照 | 无 | `app.integration_config_release`（引用应用版本/体验修订/接入档案修订/连接档案引用），不可变 |
| 激活 | 无 | `app.integration_config_release_activation` 指针行，`(tenant, app_code, scope)` 唯一 ACTIVE |
| 前端读取体验 | 无 | `GET /v1/apps/{code}/experience`（脱敏，无 secret_ref） |

## 2. 改动文件

app-management：
- `src/main/resources/db/migration/V113__assistant_experience_and_config_release.sql`：六张新表（父表 + 修订表 ×3）与索引、外键、手写回滚注释。
- `src/main/java/cn/agentplatform/app/infra/ConfigWriteLog.java`：`requestId` 幂等账本（`app.config_write_request`），摘要不同即 409。
- `src/main/java/cn/agentplatform/app/infra/AssistantExperienceRepository.java`、`IntegrationProfileRepository.java`、`ConfigReleaseRepository.java`：行锁 + CAS 推进 `current_rev`、修订追加、激活指针迁移。
- `src/main/java/cn/agentplatform/app/application/AssistantExperiences.java`、`IntegrationProfiles.java`、`IntegrationConfigReleases.java`：白名单校验、摘要、引用完整性、状态机、对外投影。
- `src/main/java/cn/agentplatform/app/api/AssistantExperienceController.java`、`IntegrationProfileController.java`、`ConfigReleaseController.java`、`ExperienceController.java`。

platform-gateway：
- `src/main/java/cn/agentplatform/gateway/domain/Routes.java`：新增 `/v1/apps/{code}/experience` 精确分支与两条 `/admin/*` 前缀（只决定转发方向）。

admin-common：
- `src/main/resources/agent-service-authority.json`：追加 19 条精确条目（`caller=gateway,target=app,mode=delegated`），
  只有 `/v1/apps/{code}/experience` 的 `integrationAllowed=true`。

测试：
- `app-management/src/test/java/cn/agentplatform/app/application/AssistantExperienceRulesTest.java`（8 例，纯规则）。
- `app-management/src/test/java/cn/agentplatform/app/infra/ConfigReleaseFlowTest.java`（5 例，真实 PG）。

## 3. 不变量（本子步守住的行为）

1. 修订不可变：只 `insert`，从不 `update` 修订行；改成新修订。
2. 父行 `current_rev` 用 CAS 推进（`where current_rev = ? and status = 'DRAFT'`），并发写冲突 409 `CONFIG_REV_CONFLICT`。
3. 状态机：DRAFT→VALIDATED→APPROVED→ACTIVE；ACTIVE→SUPERSEDED/DISABLED；非 DRAFT 不再追加修订（400 `CONFIG_STATE_TRANSITION_INVALID`）。
4. 同一作用域只有一个 ACTIVE：由 `(tenant_id, app_code, scope)` 指针主键保证，发布行状态是它的投影。
5. 发布 `digest` 只含声明与版本引用（`appCode/appVersion/candidateSha256/experienceRev/profileId/profileRev/refs`），不含任何 secret。
6. `candidateSha256` 只从 `app.app_version` 读，不取请求体；`validate` 时再核一次，防止"批准的是一个、激活的是另一个"。
7. 对外体验只来自 ACTIVE 发布：无激活 → 409 `CONFIG_RELEASE_NOT_ACTIVE`；草稿不对外。
8. 引用完整性：`promptRefs` 必须指向既有模板版本（400 `CONFIG_VERSION_UNKNOWN`）；`experienceRef` 必须指向既有体验修订；
   连接引用必须指向既有连接档案修订。
9. 明文凭据一律拒绝（400 `CONFIG_SECRET_PLAINTEXT_REJECTED`），配置对象里只允许 `secretRef` 这类引用名。
10. 跨租户不可见（404 `CONFIG_REF_NOT_FOUND`），租户只从已验 JWT 的 Actor 取。

## 4. 功能检查（两轮定向）

命令（真实 PG：`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`、`AGENT_DB_USER=dev_ap_test`，
口令从私有准备根读入，未回显）：

```
mvn -o -q -pl app-management -am \
  -Dtest='AssistantExperienceRulesTest,ConfigReleaseFlowTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test
```

| 轮次 | 结果 |
| --- | --- |
| 第 1 轮 | 编译失败（`actor()` 重名、`Actor` 第三参是 `Set<String>`、`migrate()` 未声明受检异常）→ 修正后 8/8 规则通过、5 个 PG 用例全红 |
| 第 1 轮修复 | 夹具补齐 V102 之后的 `app_id`/`app_draft_id`/`app_version_id` 外键；`headForUpdate`/`lockRelease` 误用对外投影（camelCase）导致 CAS 读到 null；激活用例自身重复激活 |
| 第 2 轮 | `AssistantExperienceRulesTest` 8/8、`ConfigReleaseFlowTest` 5/5（真实 PG，5.96s） |

权限目录校验（第 2 轮同批执行）：

```
mvn -o -q -pl admin-common,app-management,platform-gateway -am \
  -Dtest='RouteCoverageVerifierTest,AssistantExperienceRulesTest,ConfigReleaseFlowTest' ... test
```

→ `RouteCoverageVerifierTest` 10/10（精确模板匹配 + 目录可加载）、app 侧 8/8 + 5/5。

## 5. 未覆盖 / 待验收

- 全量回归与真实启动（`ROUTE_COVERAGE_MISSING`、Spring 装配）只能由 P 的构建窗口确认；本席只跑了定向用例。
- 前端页面（配置者编辑体验/接入档案）与生成客户端同步不在本子步。
- 体验投影里的 `capabilities` 依据 `app.skill_reference` 过滤，但"技能是否已发布"的最终核对仍待发布子步接线。
- 健康探测、凭据注入在 INT-02 侧完成，本子步不重复。

## 6. 契约缺口（只登记，未自行改契约）

1. `configuration-profile.md` §6 的 `prompts[]` 要求 `readOnly`/`hasSideEffects`，但 PromptTemplate 侧没有"是否有副作用"的声明位，
   本子步按只读模板统一置 `readOnly=true`、`hasSideEffects=false`，与"技能可能有副作用"的语义不完全对齐。
2. §6 未规定 `capabilities[].inputSchema` 与技能 `input_schema` 的裁剪关系，当前直接透传应用版本引用的技能声明（未裁剪）。
3. §4 状态机未说明 `SUPERSEDED` 的发布能否被 `disable`；本子步只允许 ACTIVE/APPROVED 停用。
4. `activationScope` 的取值域（是否等同 environment）未在契约冻结，本子步按 `^[a-z][a-z0-9-]{0,31}$` 与 environment 同源。
5. `CONFIG_RELEASE_NOT_ACTIVE` 用于"没有激活发布"时是 409，契约只标注 BUSINESS，未固定 HTTP 码。

## 7. 回退

- 代码：`git revert <本子步提交>`。
- 数据：V113 末尾注释附手写回滚（按外键逆序 drop 六表）；
  若只想停用而不删数据，把 `app.integration_config_release_activation` 指针删掉即可让对外体验立即 409。
