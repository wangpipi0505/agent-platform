# 接入配置对象标准（INT-01 工程增补，2026-11-03，v0.1 草案）

> 状态：**草案，未激活**。新增工程增补，不改四契约原文、`capability-package.schema.json` v1、历史发布物与既有迁移；激活时按 `freeze.json` engineeringAddenda 模式登记。激活前不得写入操作手册宣称可用。
> 唯一归属：INT-01 定义边界；INT-02 实现 ConnectionProfile/EnvironmentBinding；INT-03 实现 AssistantExperience/IntegrationProfile/ConfigRelease。ONB/SEM 只引用，不建第二套连接/模板/配置发布引擎。
> 本文与 `assistant-integration.md`（同日 v0.1 草案）配套：错误码、路由登记、Schema 通用边界以该文件 §6/§8/§9 为准。

## 1. 共享对象与唯一实施归属

| 对象 | 归属卡 | 存储归属 | 一句话定义 |
| --- | --- | --- | --- |
| `ConnectionProfile` | INT-02 | app-management（app schema） | 平台管理员预置的批准连接：协议、操作白名单、端点与凭据规则 |
| `EnvironmentBinding` | INT-02 | app-management（app schema） | 环境物理地址、secret_ref 引用与受控运行环境的版本化绑定 |
| `AssistantExperience` | INT-03 | app-management（app schema） | 纯展示与推荐问法的版本化配置，关联应用发布版本 |
| `IntegrationProfile` | INT-03 | app-management（app schema） | 宿主挂载方式、来源白名单与上下文声明档案 |
| `ConfigRelease` | INT-03 | app-management（app schema） | 上述对象引用的不可变组合快照，按环境激活 |

唯一性规则：普通 HTTP/MCP/PROCESS/ALGORITHM 能力保留原四 kind 声明（capability-package.schema.json v1 枚举），本组对象不新建第五种协议 kind；技能契约归 skill-registry、运行事实归 engine-runtime、OR 空间/数据/端点授权归 OR，各自不迁移。

## 2. 对象最小字段（v1 冻结草案；实现不得增删顶层字段，扩展=新修订版本）

| 对象 | 最小字段 | 约束要点 |
| --- | --- | --- |
| `ConnectionProfile` | `id, tenantId, profileRev, protocol, operationAllowlist, endpointPolicy, authScheme, identityPolicy, inputContractRefs, outputContractRefs, allowedUses, status` | 协议与操作白名单为不可变版本；`protocol` ∈ 既有四 kind；`allowedUses` 声明允许的用途子集；不保存明文凭据，凭据规则只含 secret_ref 策略 |
| `EnvironmentBinding` | `id, environment, bindingRev, connectionProfileId, connectionProfileRev, baseEndpoint, secretRef, runtimeRef, originPolicy, healthPolicy, status` | `baseEndpoint` 受现有 URI/来源策略限制；`secretRef` 仅引用；健康探针只读；每次变更产生新 `bindingRev`，不改旧行 |
| `AssistantExperience` | `appCode, experienceRev, title, welcomeText, capabilityDescriptions, promptRefs, inputPresentation, resultPresentation, helpRefs` | `promptRefs` 复用既有 PromptTemplate 版本体系（不另造模板引擎）；能力描述由已发布能力核对，不得宣传未授权/未交付功能 |
| `IntegrationProfile` | `id, appCode, bizSystemCode, environment, mode, allowedOrigins, contextSchema, credentialPolicyRef, experienceRef, status` | `mode` ∈ `L0/L1/L2/L3`（assistant-integration.md §2）；`allowedOrigins` 精确匹配；`contextSchema` 只含声明字段（见 assistant-integration.md §5）；禁止任意可执行 JS/HTML、任意回调 URL、新增授权角色 |
| `ConfigRelease` | `id, appCode, appVersion, candidateSha256, experienceRev, integrationProfileRev, connectionProfileRefs, activationScope, createdBy, digest` | 不可变组合快照；`digest` 只含声明与版本引用，**不含 secret 值**；不改写 app_version/skill_version/OR 已发布 manifest |

`connectionProfileRefs` / 运行绑定引用的精确形态：`bindingRefs[{profileId, profileRev, bindingId, bindingRev, operation, resolutionDigest}]`（INT-02 冻结实现；`resolutionDigest` 为带独立摘要的临时执行描述，与历史契约摘要分别保存，不混比）。

## 3. 版本、修订与幂等规则

1. 修订不可变：`profileRev` / `bindingRev` / `experienceRev` 变更即新修订，旧行永久保留可追溯；不原地 UPDATE 已生效修订的业务字段。
2. 写操作携带 `expectedRev` 与幂等 `requestId`：修订冲突返回 409 `CONFIG_REV_CONFLICT`；同 `requestId` 重放只产生一次对象/发布（不同 tenant 同名互不影响）。
3. 引用完整性：创建/修订时校验全部引用（应用版本、PromptTemplate 版本、ConnectionProfile 修订、IntegrationProfile 修订、环境）当前有效；引用失效阻断激活，不静默替换。
4. 行为变更分类：同语义部署地址/凭据轮换走绑定审批；操作、输入输出、模型提示或能力选择语义变化必须进入新技能/应用草稿，重走原评测发布门禁——不得伪装成"只改环境配置"绕过发布（只改标题/帮助说明可单独发体验修订，但仍检查已发布版本与授权兼容）。
5. 迁移编号在实施前按各仓/各服务实际最大版本分配并登记；不预占、不修改旧 V 文件、不跨库写。

## 4. ConfigRelease 状态机与运行快照

| 状态 | 进入条件 | 允许迁移 |
| --- | --- | --- |
| DRAFT | 新建配置草稿 | → VALIDATED（校验通过）、→（废弃） |
| VALIDATED | 引用/权限/来源/契约校验通过 | → DRAFT（修改使旧检查失效）、→ APPROVED |
| APPROVED | 有权人员审阅差异 | → ACTIVE（CAS 激活）、→ DRAFT（退回修改） |
| ACTIVE | CAS 激活不可变快照（integrationId+environment 行锁） | → SUPERSEDED（新版本激活）、→ DISABLED（停用/撤权/安全事件） |
| SUPERSEDED | 被新版本替换 | → ACTIVE（重新核当前授权后回退指针）、→ DISABLED |
| DISABLED | 停用 | → DRAFT（新草稿重新检查） |

- 激活为 CAS：防重复点击与并发覆盖；同环境同时只有一个 ACTIVE 指针。
- 旧配置不原地改写；**回退不恢复已撤销权限**——撤销凭据、关闭集成、撤回授权、禁用应用、收紧来源始终优先于历史快照；不能通过恢复旧 ConfigRelease/旧票据/旧会话恢复已撤销权限。
- 凭据轮换记录引用与策略修订，不固化密钥明文；凭据值不进任何快照。

### 4.1 运行事实引用（实现归 INT-06）

新会话/运行由**服务端**解析有效 ConfigRelease、环境绑定和当前授权，在运行事实中保存 `configReleaseId / configDigest / environmentBindingRevs`，并保留既有 `versionRefs`（应用、技能、提示词、`ontologyRelease`）。客户端只能提出配置引用，不能决定 tenant、版本可用性或实际物理地址。

- 环境绑定切换只影响未来新运行；已受理运行使用自身冻结引用，避免中途切换端点。
- 同 run 相同引用沿用确定修订；旧 run、同 call 重试、进程恢复、异步观察及确认后执行只解析原绑定修订，并**复查当前**授权与撤销状态。
- 老修订不存在、被停用或撤权：返回可定位错误或按既有语义保留 UNKNOWN；不以最新绑定补洞，不自动改端点，不重发副作用请求。
- 引擎/技能侧字段缺省兼容原调用：旧客户端不带 `configReleaseId` 保持当前已发布版本行为；不接受浏览器覆盖引擎权威绑定。

## 5. 与既有对象的关系

| 既有对象 | 关系 | 边界 |
| --- | --- | --- |
| PromptTemplate 及其版本 | AssistantExperience.promptRefs 引用 | 不复制模板正文，不另建模板引擎 |
| CapabilityPackage v1（冻结） | 不修改 | 新受控引用用独立信封（INT-02 `approved-connection-reference`，另文 Schema），不向 v1 未知字段塞字段 |
| `current-identity-mapping.md` | 当前主体登记/反查继续有效 | 本组对象不提供任意外部 subject 分配；外部账号对应归可信身份源+明确协议（SEM-11） |
| 既有发布门禁（候选/评测/审批） | 行为变更必经 | 体验修订 ≠ 应用评测替代；ConfigRelease 激活前预检引用有效性 |
| IntegrationService 凭据 | EnvironmentBinding.secretRef 引用 | 撤销凭据即时优先；明文仅签发一次的既有语义不变 |

## 6. 体验描述对外 JSON Schema v1（网关 `GET /v1/apps/{code}/experience` 返回，本文冻结草案）

对外**脱敏**描述：不含 secret_ref、物理服务管理路径、管理员配置；能力按当前用户过滤。

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:agent-platform:assistant-experience-description:1.0",
  "title": "AssistantExperienceDescription",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "appCode", "experienceRev", "configReleaseRef", "title"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "appCode": { "type": "string", "maxLength": 64, "pattern": "^[A-Za-z0-9._-]+$" },
    "experienceRev": { "type": "integer", "minimum": 1 },
    "configReleaseRef": {
      "type": "object", "additionalProperties": false, "required": ["configReleaseId", "digest"],
      "properties": { "configReleaseId": { "type": "string", "maxLength": 64 }, "digest": { "type": "string", "maxLength": 128 } }
    },
    "title": { "type": "string", "maxLength": 128 },
    "welcomeText": { "type": "string", "maxLength": 2048 },
    "capabilities": {
      "type": "array", "maxItems": 32,
      "items": { "type": "object", "additionalProperties": false, "required": ["code", "label"],
        "properties": {
          "code": { "type": "string", "maxLength": 64 }, "label": { "type": "string", "maxLength": 128 },
          "description": { "type": "string", "maxLength": 512 },
          "readOnly": { "type": "boolean" }, "requiresApproval": { "type": "boolean" },
          "inputSchema": { "type": "object", "maxProperties": 64 }
        } }
    },
    "prompts": {
      "type": "array", "maxItems": 16,
      "items": { "type": "object", "additionalProperties": false, "required": ["ref", "title"],
        "properties": {
          "ref": { "type": "string", "maxLength": 128 }, "version": { "type": "integer", "minimum": 1 },
          "title": { "type": "string", "maxLength": 128 },
          "variables": { "type": "array", "maxItems": 16, "items": { "type": "object", "additionalProperties": false, "required": ["name"],
            "properties": { "name": { "type": "string", "maxLength": 64 }, "required": { "type": "boolean" }, "description": { "type": "string", "maxLength": 256 } } } },
          "readOnly": { "type": "boolean" }, "hasSideEffects": { "type": "boolean" }, "example": { "type": "string", "maxLength": 1024 }
        } }
    },
    "inputPresentation": { "type": "object", "additionalProperties": false, "maxProperties": 16 },
    "resultPresentation": { "type": "object", "additionalProperties": false, "maxProperties": 16 },
    "helpRefs": { "type": "array", "maxItems": 8, "items": { "type": "string", "maxLength": 256 } }
  }
}
```

- 无激活配置时端点按 `CONFIG_RELEASE_NOT_ACTIVE` 语义返回（说明缺哪个发布/配置前提），不返回草稿冒充已发布助手。
- 用户选择推荐问法后先生成可编辑输入/结构化表单，不自动执行写操作；没有可用模板时允许直接提问，不伪造默认能力。

## 7. 预检与校验清单（INT-02/03 实施时逐项落地）

1. 引用存在性：appVersion 已发布、模板版本存在、连接修订有效、环境绑定有效。
2. 权限：操作者对涉及资源具备对应授权；业务配置者使用连接是管理员授予的资源权限，不是假扮 ADMIN；原 raw authRef 手工接口保持 ADMIN 门。
3. 来源：`allowedOrigins` 精确匹配；`mode` 与宿主前提声明一致。
4. Schema：输入输出契约引用可解析且在预算内；坏 Schema 阻断，不放宽。
5. 差异：VALIDATED→APPROVED 必须展示与前一激活版本的差异；修改后旧检查失效须重新校验。
6. 激活：CAS + 行锁；激活者、时间与摘要写入审计；停用/回退展示影响范围。

## 8. 存储归属与迁移建议（拟设计，不是已建表；迁移号开工时分配）

app schema：`connection_profile` / `connection_profile_version` / `connection_profile_grant`、`environment_binding` / `environment_binding_revision`、`assistant_experience` / `assistant_experience_revision`、`integration_profile` / `integration_profile_revision`、`integration_config_release` / `integration_config_release_activation`。engine 仅增加运行/会话配置引用字段及必要索引；skill 侧绑定引用持久化按 INT-02 白名单实施。不给旧历史伪造绑定；历史运行无引用字段即表示原行为。

## 9. 验收锚点（引用 INT 方案，勿在此重复展开）

- INT-02 五条最小运行路径（批准只读调用、A/B 绑定切换、伪造拒绝、撤权即时生效、存量技能不受影响）以 INT 方案 §5.2 为准。
- INT-03（可还原激活版本、预检失效、幂等一次、tenant 隔离）、INT-06（真实读写闭环、恢复不重复副作用）以其各自卡验收为准。
- 本标准自身的验收：一个**非三路线命名**的示例应用可按 §6/§2 Schema 完整描述；非法来源、客户端身份字段、未知版本、任意回调 JS、明文 secret 均命中 §6/assistant-integration.md §6 的登记错误码；每条新增 API 在权限目录有精确条目。

## 10. INT-02 子步1 契约确认（2026-11-03，P 归口裁决；实现侧已按此对齐）

INT-02 子步1 证据登记的 5 项缺口裁决如下，实现不得偏离；后续扩展回本节追加登记：

1. **嵌套结构采纳回写**：`ConnectionProfile.authScheme = {kind ∈ NONE|BEARER|API_KEY|OAUTH2_CLIENT_CREDENTIALS|MTLS|SIGNED_REQUEST, headerName?}`、`identityPolicy = {mode ∈ PLATFORM_PRINCIPAL|SERVICE_ACCOUNT, subjectSource?}`、`endpointPolicy = {hosts?, pathPrefixes?, httpMethods?}`、`runtimeRef = {runner?, namespace?, engine?}`、`healthPolicy = {path?, intervalSeconds?, timeoutMs?}`。**当前运行仅支持与既有适配器 AuthScheme 对齐的 `NONE/BEARER/API_KEY`**；其余三值为冻结留白，resolve/校验遇留白值返回 `CONFIG_AUTH_SCHEME_UNSUPPORTED`，不静默当 NONE。
2. **错误码补充**：对象不存在 → `CONFIG_REF_NOT_FOUND`（404）；状态迁移非法 → `CONFIG_STATE_TRANSITION_INVALID`（400）；停用/未激活档案被引用 → `CONFIG_RELEASE_NOT_ACTIVE`（沿用）。通用字段错误仍用既有 `INVALID_INPUT`。
3. **`connection_profile_grant` 随子步2 落地**：批准（APPROVED）≠授权使用；grant 行与 `/{id}/grants` 端点、resolve 时的"被授予版本"投影在子步2 以**新迁移号**（app V112）实现，不回改 V111。
4. **绑定引用状态下限**：`EnvironmentBinding` 引用的 `connectionProfileRev` 必须处于 **APPROVED 及以上**；引用 DRAFT 拒绝（`CONFIG_STATE_TRANSITION_INVALID`），引用 DISABLED 拒绝。先建目录后批准的流程改为"目录与批准连续完成后再建绑定"，不以放宽状态下限换取便利。
5. **environment 取值**：格式沿用 `^[a-z][a-z0-9-]{0,31}$`；取值必须与既有 `publish_pin.environment` 值域一致（默认 `production`），不私造新环境名；两处值域的一致性由启动校验与发布预检共同保证。

## 11. INT-02 子步2 契约确认（2026-11-03，P 归口裁决）

1. **skill 侧入口冻结**：`GET /internal/skills/{code}/approved-connections/{profileId}/versions/{profileRev}?environment=`（已实现，assistant-integration.md §8 已同步登记）；skill→app 内部 `GET /internal/connection-profiles/{profileId}/versions/{profileRev}/resolve`（caller=skill，委派精确登记）。
2. **解析回执形状错误码**：`CONNECTION_RESOLUTION_MALFORMED`（502，TRANSPORT）已登记 assistant-integration.md §6；冻结鉴权形态沿用 `CONFIG_AUTH_SCHEME_UNSUPPORTED`。
3. **grant 幂等用天然键**：`profileId+profileRev+grantee+payload摘要` 天然键幂等，不进 `config_write_request`；同载荷重放 `replayed=true`、异载荷 409 `CONFIG_REV_CONFLICT`。写入幂等表的覆盖范围（档案/绑定）不变。
4. **resolve 不含健康判定**：解析回执只含声明、钉住的已批准绑定、有效授权与稳定摘要；`healthPolicy` 仅为声明，**只读健康检查归 INT-02 子步3 单独端点**，结果不进解析回执，不缓存。
5. **resolve 为一次性读取**：不缓存（撤权下次解析即生效）；reserve/commit 两段式仅当后续卡（SEM-07 执行条件链）出现长事务窗口需要时回本节定义，当前不建、不预留字段。
