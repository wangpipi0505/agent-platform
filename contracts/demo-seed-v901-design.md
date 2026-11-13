# V901 演示种子设计稿（demo-seed）

C0-3 交付物（台账#C0-3），2026-11-13。实现卡：M5-1（阶段 5）。验收标准：**字段与库稿一致**（数据库稿《05-数据库设计-20261112》§3/§6 为迁移唯一权威）。
依据：主方案 §8.2 融合三步①种子资产化；§8.3 演示数据目录处置；C0-1 裁剪清单（本体线剔除）。

## 1. 目标与原则

1. **一条命令起真实后端 + demo 种子**（主方案 §8.2）：演示与 A16（mock↔真实一致性）跑同一份种子。
2. **幂等可重复**：全部 `INSERT ... ON CONFLICT (键) DO NOTHING`；重复执行零副作用（A16 验收项）。
3. **demo 租户隔离**：所有种子行 `tenant_id='demo'`；不触碰真实租户数据。
4. **生产可跳过**：种子迁移放各模块**链尾 V901**（engine/app/skill 各一段），经 Flyway placeholder 门控跳过（数据库稿 §6 迁移纪律）。
5. **只增不改**：V901 不 UPDATE/DELETE 任何既有行；历史迁移不动。
6. **本体线剔除**（C0-1 裁剪）：种子不含任何本体专用对象——ontology releaseRef、语义绑定、`planning-ontology-rules`/`um-ontology-check` 类本体工具、route2 轨迹数据一律不入种子；对应行为改由 rule_asset 与 GUIDE 技能承载。
7. **业务侧数据不入平台种子**：departments/actors/accounts 等 user-mgmt/业务库数据属外部系统（route3 演示剧本自理），V901 只种平台侧资产。
8. **model-gateway 不动**（红线）：演示模型条目/供应商不入迁移，走启动配置与现状数据；workspace_manifest 的 model 绑定引用其现有条目编码。

## 2. 种子范围总表（按 schema）

| 迁移段 | 目标表 | 种子内容 | 幂等键（ON CONFLICT） |
| --- | --- | --- | --- |
| app V901 | `application` | 两个演示工作区：`satellite-planning-assistant`（卫星规划助手）、`user-governance-assistant`（账号开通与角色授权助手） | `(tenant_id, code)` |
| app V901 | `app_version` | 每工作区 1 个已发布版本（status='PUBLISHED'、published_at、candidate_sha256 预计算常量）——发布行与运行时快照核验都引用它 | `(tenant_id, app_code, version_no)` |
| app V901 | `workspace_manifest` | 每工作区 1 行 manifest（revision=1，digest=预计算常量） | `(tenant_id, app_code, revision)`；digest UNIQUE `(tenant_id, app_code, digest)` |
| app V901 | `integration_config_release` + `integration_config_release_activation` | 每工作区 1 条**已批准并激活**的发布（status='APPROVED' → 经指针激活置 ACTIVE；`manifest_revision=1`/`manifest_digest` 成对，对齐 V122 扩列 CHECK 同空或同非空）。**只有 APPROVED 无指针行的发布运行时读不到**——激活步骤见 §3.1 | 现有唯一键 + demo 专用 release 编码；指针 PK `(tenant_id, app_code, scope)` |
| app V901 | `rule_asset` + `rule_asset_version` | 3 条示例规则（见 §4.3） | `(tenant_id, code)` / `(tenant_id, rule_code, version_no)` |
| skill V901 | `skill`（kind 扩列已由 V321 交付） | GUIDE 2 + TOOL 若干（§4.1/§4.2） | `(tenant_id, code)` |
| skill V901 | `skill_version` | GUIDE 正文进 `contract` jsonb（形状 `{name, description, whenToUse, modelInvocable, content}`）；TOOL 六维契约 + 副作用技能 `businessIntentInput` | `(tenant_id, skill_id, version)`（沿用现有键） |
| skill V901 | `plugin_source` / `plugin` / `plugin_version` / `plugin_grant` | 1 来源（CAPABILITY_PACKAGE，route1 能力包）+ 1 插件 1 版本 + 1 授权 | `(tenant_id, code)` / `UNIQUE(tenant_id, plugin_id, version)` / `UNIQUE(plugin_version_id, grantee_type, grantee_code)` |
| engine V901 | `memory_entry` | 2 条示例记忆（1 私有 1 共享，§4.4） | `(tenant_id, memory_id)`（或库稿唯一键） |
| engine V901 | —（不种） | `session_event`/`chat_session`/task 家族为**运行时事实**，不预置 | — |

> 迁移依赖顺序：V901 各段排在各模块 V229/V230/V231（engine）、V122/V123（app）、V321/V322（skill）之后；引用列以库稿 §4 各表字段为准，一字段一对照（M5-1 实现时逐字段核对并留对照注释）。

## 3. manifest 示例（app V901，结构与 contracts/manifest.schema.json 一致）

```json
{ "metadata": { "name": "卫星规划助手", "category": "算法重组", "tags": ["演示","卫星"] },
  "model": { "defaultBinding": { "providerCode": "<现有供应商编码>", "modelName": "<现有条目名>" },
             "inferencePolicy": { "budget": { "maxTurns": 40, "maxModelCalls": 120 } } },
  "skills": { "guideRefs": ["satellite-planning-guide@1"],
              "toolGrants": [ {"skillCode":"satellite-target-query","version":1},
                              {"skillCode":"satellite-plan-generate","version":1,"businessIntentInput":"planIntent"} ] },
  "plugins": [ { "pluginCode": "demo-capability-pack", "version": "1.0.0", "capabilities": ["SKILL:satellite-target-query"] } ],
  "rules": ["satellite-cloud-limit@1"],
  "memoryPolicy": { "kinds": ["PREFERENCE","EXPERIENCE"], "readScopes": ["PRIVATE"], "writeScopes": ["PRIVATE"], "topK": 5 },
  "contextPolicy": { "maxTurns": 200, "contextBudget": 60000, "compactionThreshold": 0.85, "historyWindow": 20 },
  "permission": { "roles": ["USER","OPERATOR"] } }
```

> **schema 校验注记（2026-11-13 用户评审补正）**：示例已用冻结 `manifest.schema.json`（ajv）校验通过。①无流程引用时**省略 `flowRef` 字段**——契约类型为 string 不收 null，写 `flowRef: null` 校验失败；有流程引用的工作区（user-governance）写字符串值。②`inferencePolicy` 位于 **`model` 层级**（defaultBinding 的平级兄弟，接口稿 §5.5 同构），不是 defaultBinding 的子字段。

`user-governance-assistant` 的 manifest 同构：toolGrants 引用 um-* 技能、写技能带 `businessIntentInput`、`flowRef="flow-user-governance"`、rules 引用账号治理规则、permission.roles 含 `USER`。digest/发布行随 manifest 固化写入 `integration_config_release`（会话受理钉住即真实链路）。

### 3.1 ACTIVE 发布与激活指针建立（M5-1 必读：运行时生效链路，2026-11-13 用户评审补正）

**实际机制**（`app-management/.../ConfigReleaseRepository.java` + V113/V122 迁移）：

- `integration_config_release.status` CHECK ∈ `('DRAFT','VALIDATED','APPROVED','ACTIVE','SUPERSEDED','DISABLED')`，默认 DRAFT。
- **激活指针表** `app.integration_config_release_activation(tenant_id, app_code, scope, release_id, activated_by, activation_rev int NOT NULL DEFAULT 1)`——作用域内指向唯一 ACTIVE 的**权威**；发布行的 status 只是它的投影。指针与状态在同一事务推进（`activate()`：锁发布行→须 APPROVED→upsert 指针行→锁指针→旧 ACTIVE 退位 SUPERSEDED→新发布置 ACTIVE→activation_rev+1）。
- **运行时/对外体验读取路径** = `active(actor, appCode, scope)`：指针 join 发布行 `where r.status='ACTIVE'`。**只有 APPROVED、无指针行的发布运行时读不到**——会话受理钉住不到任何快照。种子只插"已批准"发布不激活，演示链路即断在这。

**M5-1 种子步骤（每工作区，单事务，幂等）**：

1. `application` 行 + `app_version` 行（status='PUBLISHED'、published_at=now()、candidate_sha256 预计算常量）。
2. `workspace_manifest` revision=1 + digest。
3. `integration_config_release` 行：`INSERT ... ON CONFLICT DO NOTHING`，release_id=demo 专用编码，**status 直接='APPROVED'**（等价真实链 VALIDATED→APPROVED 的终态；DRAFT/VALIDATED 是瞬态，无运行时读者，种子不经过），`manifest_revision=1` / `manifest_digest` 成对（V122 CHECK 同空或同非空）。
4. **激活**（照抄 `activate()` 语义，与步骤 3 同事务）：指针行 upsert（`ON CONFLICT (tenant_id, app_code, scope) DO NOTHING`，activation_rev 取默认 1）→ 发布状态 `UPDATE ... SET status='ACTIVE' WHERE status='APPROVED'`。重复执行种子时：发布行已 ACTIVE（更新 0 行）、指针已存在（DO NOTHING）——全 no-op 幂等。旧 ACTIVE 退位（→SUPERSEDED）语句按 `activate()` 原样保留（demo 每工作区单发布，实际不触发）。
5. **验收补充**：种子执行后 `active()` 对两工作区各返回一条 ACTIVE 发布，且 `manifest_revision`/`manifest_digest` 成对可回读；重复执行种子后再查，结果不变。

## 4. 两场景数据映射表（mock 资产 → V901 目标行）

### 4.1 技能（skill V901）

| mock 来源 | 目标 | kind | 说明 |
| --- | --- | --- | --- |
| `satellite-planning.ts` 18 工具（剔除本体类） | `skill.skill` TOOL 行 + `skill_version.contract` | TOOL | 只读查询/规划生成类照搬契约（inputSchema/outputSchema/endpoint 改真实形态）；**副作用技能必须声明非 null `businessIntentInput`**（预检红线）；本体类（`planning-ontology-rules` 等）不入种子 |
| `satellite-planning.ts` 13 问法/规划行为描述 | 新增 GUIDE | GUIDE | SKILL.md 正文：卫星规划"怎么组合"的行为知识（frontmatter name/description/whenToUse/modelInvocable=false） |
| `user-governance.ts` `governanceSkills`（um-identity-resolve 等 12 个） | TOOL 行 + contract | TOOL | readonly=true 保持只读；`um-request-submit`/`um-approval-decide`/`um-account-create`/`um-role-assign` 为写技能——**`um-ontology-check` 剔除**，本体校验改由 rule_asset 承载 |
| （新增）账号治理操作指引 | 新增 GUIDE | GUIDE | SKILL.md：账号开通四步（拉事实→出计划→双人审批→执行读回） |

### 4.2 工作区与发布（app V901）

| mock 来源 | 目标 | 说明 |
| --- | --- | --- |
| `governanceApp`（code/name/description/tags/bizCategory） | `application` 行 | 类型 FLOW、`flowRef` 进 manifest |
| `satellite-planning-assistant` 等三个 appCode 收敛为 1 个演示工作区 | `application` 行 | 演示只保留主 appCode，其余两个 mock 别名不种子 |
| `business-demo.seed.json.releaseRef` | —（剔除） | 本体发布引用随本体线挂起，不入种子 |
| 上述两 manifest | `workspace_manifest` + `integration_config_release` | digest 预计算常量；发布状态=已批准（支撑受理钉住链路） |

### 4.3 规则（app V901，rule_asset/rule_asset_version）

| mock 来源 | code | nature | content（trigger/action/requirement） |
| --- | --- | --- | --- |
| 云量约束（卫星场景问法参数） | `satellite-cloud-limit@1` | ENFORCED | 触发=规划生成动作；处理=云量超限阻断并提示（Guard 结构化校验示例） |
| `um-rule-permission`（本体条件改写） | `um-permission-match@1` | ENFORCED | 触发=审批执行前；处理=权限不匹配拒绝提交（**替换本体条件语义**） |
| `um-rule-revision`（乐观并发改写） | `um-account-revision@1` | GUIDANCE | 提示=账号 revision 过期时先重查再提交（提示装配段示例） |

### 4.4 记忆与插件

| mock 来源 | 目标 | 说明 |
| --- | --- | --- |
| （新增示例） | `engine.memory_entry` ×2 | 1 条 PRIVATE/PREFERENCE（demo 用户偏好）、1 条 SHARED/EXPERIENCE（卫星规划经验，source_task_id 空）；status=ACTIVE，expires_at 空 |
| route1 能力包（演示数据 route1） | `plugin_source`(CAPABILITY_PACKAGE) + `plugin` + `plugin_version`(capabilities=[{type:'SKILL',ref:'satellite-target-query',digest}]) + `plugin_grant`(WORKSPACE→satellite-planning-assistant) | 能力包作为插件发现来源适配器（降级保留口径）；manifest.plugins 引用之 |

### 4.5 不入种子的 mock 资产（明确排除）

| 资产 | 处置 |
| --- | --- |
| business-demo.seed.json 的 departments/actors/accounts/permissions | 外部业务系统数据，route3 演示剧本自理（主方案 §8.3） |
| `planning-ontology-rules`、`um-ontology-check`、ontology.releaseRef、语义绑定 | 本体线挂起（C0-1），不入种子 |
| route2 轨迹 CSV | 随本体线挂起 |
| 会话/任务/运行事实类 mock 数据 | 运行时事实不预置；演示链路真实产生 |

## 5. 实现与验收要点（M5-1 领卡须知）

- 字段逐一对照数据库稿 §4（workspace_manifest §4.6、rule_asset §4.7、skill/plugin §4.8、memory_entry §4.4）；digest 统一 char(64) hex 预计算常量；jsonb 带 CHECK 的列（manifest ≤1MB、payload ≤256KB）种子值留足余量。
- 种子迁移文件命名：`V901__demo_seed.sql`（app/engine/skill 各自模块）；头部注释标注"演示种子，生产经 placeholder 跳过"；启动脚本提供"真实后端 + demo 种子"入口（G5-0 部署清单联动）。
- 验收：A16 mock↔真实一致（同一演示剧本 mock 与真实后端行为一致）；迁移重复执行幂等；demo 租户外零写入。
