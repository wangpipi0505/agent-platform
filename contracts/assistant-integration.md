# 业务软件接入集成标准（INT-01 工程增补，2026-11-03，v0.1 草案）

> 状态：**草案，未激活**。本文件是既有工程契约的**新增增补**，不改四契约原文（graph-definition / trace-model / skill-invocation / authorization-confirmation）、`capability-package.schema.json` v1 及历史发布物；激活时按 `freeze.json` engineeringAddenda 模式登记本条目 sha256 与变更控制。激活前不得写入操作手册宣称能力已可用（INT 方案 §1.1 第 4 条）。
> 唯一归属：INT-01。快速接入（ONB）只引用本标准，不另建同义标准；语义增量（SEM）在 `semantic-runtime`（SEM-01，另行增补）只增字段，不重建本标准对象。
> 依据：优化方案/业务软件集成与配置管理能力提升实施方案-20261103 §1～§5；事实基线 AP `5da6bd99` / OR `91935bb`。

## 1. "当前已支持"与"新增标准"逐项区分

| 项 | 当前已支持（基线已存在，沿用不改语义） | 新增标准（拟新增，本文冻结边界） |
| --- | --- | --- |
| 集成凭据 | `IntegrationService` 签发/撤销/来源/声明上限/策略修订；明文仅签发一次，PG 存 secret_ref 与摘要 | 无新凭据对象；新配置仅引用既有凭据生命周期 |
| 业务出票 | `POST /embed/ticket` + `IntegrationAccess` ApiKey 校验、声明裁剪、有效期与应用绑定 | 短票客户端规范、宿主 BFF/同源代理配置例（INT-04）；不新增按业务软件命名的服务 |
| 门户身份 | `GET /auth/session`；Authority `SessionService` 的 user-mgmt API-Key 适配；`portal-auth.ts` 同源 provider | 身份源档案与有限协议适配登记（INT-04）；新增协议仅 Authority 模块内适配器 |
| 宿主 SDK | `init({endpoint,appCode,ticket,context})` 返回仅 `destroy()`；Shadow DOM 悬浮按钮/抽屉 | `open/close/updateContext/on/off/destroy` 增量 API、宿主按钮绑定、体验获取（INT-05） |
| 会话/运行 | CHAT 会话/轮次、FLOW 运行、SSE `run-observer` 权威恢复 | 运行事实新增 `configReleaseId/configDigest/environmentBindingRevs` 引用（INT-06） |
| 确认与产物 | `approval.ts` 双主体审批、`artifact.ts` 快照摘要与受控下载边界 | 复用，不重做；SDK 复用完整行动计划视图 |
| 发布事实 | `AppPublishPanel` 预检/评测/发布；`PublishedMetadataService` 不可变已发布版本 | 体验与接入配置版本关联已发布应用（INT-03） |
| 技能协议 | HTTP/MCP/ALGORITHM/PROCESS 四 kind 声明层枚举 + 运行层适配器注册表（skill-invocation §15） | 批准连接受控引用（INT-02，`approved-connection-reference` 信封，另文 Schema） |
| 当前主体 | `current-identity-mapping.md`：当前已验 Actor 登记/反查、快照变化拒绝 | 不变；外部 subject 对应关系属可信身份源+明确协议（SEM-11 另建），不并入本标准 |

IAM 集成短票由 authority-service 签发，绑定接入凭据的策略摘要与策略版本。策略修订后，即使策略内容相同，旧票的换票和服务侧复核仍会被拒。宿主可按现有 `POST /embed/ticket` 契约获取当前短票。

集成 `AGENT_OPERATOR` 或 `AGENT_ADMIN` 可通过 `POST /v1/apps/{code}/tasks` 在凭据绑定的应用上提交任务。请求包含 `mode: "AGENT"`、`requestId` 和 `goal`，可选对象 `inputs`。省略或传空 `inputs` 时以 `goal` 作为对话输入。首期支持已发布的单步 CHAT 应用。可省略 `toolPolicy`，或明确传入 `{"allowedToolRefs":[]}`。请求工具授权返回 403 `INTEGRATION_PATH_FORBIDDEN`。

受理时锁定应用当前发布版本，在同一事务内写入任务、运行、凭据归属、数据标签和调度记录。相同租户、主体、软件及应用下，同一 `requestId` 与相同请求重放返回原任务。异参重放返回 409 `REQUEST_REPLAY`。执行委派仅开放当前运行所需的发布元数据、模型和材料操作，调度续权在原授权有效期内复核当前凭据及策略版本。

任务详情、增量事件与结果读取使用任务业务号。读取要求当前验签凭据与任务的租户、应用、业务系统相符，主体为任务所有者，且当前标签覆盖任务受理时记录的标签。事件增量可传 `afterSeq` 和 `limit`，每项至多一次。任务存量缺接入归属、身份跨边界或标签不足时统一返回 403 `APP_BINDING_DENIED`。平台授权行不参与此判断。

集成 `AGENT_ADMIN` 可通过 `POST /v1/tasks/{taskId}/cancel` 中断同租户、同应用及同业务系统的任务运行。请求可省略正文或传空 JSON 对象。响应只有任务号和状态。仍在途而关联运行不可中断时返回 409 `TASK_NOT_RUNNING`。中断与业务审计同事务提交，跨边界拒绝也记录审计，台账约定的 `audit.read` 可经 `GET /admin/audit` 查询。

## 2. 业务源码免改分级（L0～L3）

| 等级 | 平台交付与用户操作 | 宿主前提 | 可否称业务源码免改 |
| --- | --- | --- | --- |
| L0 独立助手 | 已发布应用的受控独立入口；统一登录或平台可信身份；可配置菜单链接 | 已有菜单/导航入口 | 可。须明示是独立页面，不得冒称原页面弹窗集成 |
| L1 配置式嵌入 | 受控 iframe 页面或 SDK 挂载配置 | 宿主已有扩展位/组件槽；来源与 CSP 由部署人员配置 | 前提成立时可。身份来源、挂载入口不得凭前端配置伪造 |
| L2 最小前端接入 | 固定版本 SDK + 已有按钮 `open()` + 标准 provider 短票 | 一处 script/组件挂载与按钮绑定 | 属少量前端改动，不得写成零代码；不改业务算法/API |
| L3 最小身份适配 | 通用身份协议适配或同源票据代理 | 原生身份接口、合法服务账号、网络/授权配置 | 优先平台承担；确需宿主修改时单列原因/接口/兼容范围/责任人 |

无菜单、无扩展位、不可改页面的封闭软件：先交付 L0，不计 L1/L2 验收成功。算法类无 Web 页面的业务，用独立助手或既有算法管理控制台，不要求业务团队另写 Web 壳。

## 3. 信任边界与身份/票据两条受控路径

1. **既有集成凭据路径**：`/embed/ticket` 保留。ApiKey 仅存于可信后端；后端从自身已验证会话构造声明；网关核凭据绑定、来源、声明上限与实时状态。该模式信任的是被授权签发者，**不等于**浏览器上报 `userId` 已获业务身份验证。
2. **门户/原生身份路径**：复用 Authority 与 portal session 接线；新增身份源仅按已批准有限协议（可信身份查询、标准签名令牌）接入，协议实现归平台适配器，不按业务软件名称分支。会话映射、audience/issuer、租户/应用/环境上限、过期与撤权写入接入档案。
3. 主体恒等 tenant+principal，来自已验 JWT（AGENTS.md 约束）；`ticket()` 只回短期运行票据，绑定 tenant+principal+app+integration+environment 上限及有效期；origin 精确匹配。
4. **硬性拒绝**（各实现卡必须落地为确定错误码，见 §6）：
   - 浏览器接收集成 ApiKey、模型密钥、vault 内容、Authority 服务凭据；
   - 票据进 URL、页面日志、localStorage 或可导出配置；
   - 以 iframe 来源或 `postMessage` 本身作为身份凭据；
   - 客户端指定实际角色/tenant，或以 pageContext 改变 Actor；
   - 明文 secret_ref、任意回调 JS/HTML、任意回调 URL 进入任何配置对象；
   - 缺可信身份依据时降级 dev 身份、服务高权账号或共享 "operator" 账号——必须返回可解释阻塞。

## 4. 标准结果事件（宿主回传，实现归 INT-07）

事件名固定枚举：`assistant:ready` `assistant:opened` `assistant:closed` `assistant:run-started` `assistant:approval-required` `assistant:result` `assistant:error`。

通道边界：同页调用注册明确回调；iframe 使用精确 `targetOrigin`，校验 `event.origin`、`event.source`、一次通道 nonce 与实例 ID；只允许声明的事件与字段大小，拒绝未知 `schemaVersion`。**服务器不得下发回调函数、任意宿主 JS 或导航 URL**；宿主只将验证后的记录引用交给自己的路由/读取 API。一次权威终态只触发一次 result；message/模型输出不是"业务已提交"的授权依据；业务效果 UNKNOWN 如实传递。

### 4.1 结果事件信封 JSON Schema v1（本文冻结草案）

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:agent-platform:assistant-host-event:1.0",
  "title": "AssistantHostEvent",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "event", "integrationId", "appCode", "traceId", "occurredAt"],
  "properties": {
    "schemaVersion": { "const": "1.0" },
    "event": { "enum": ["ready", "opened", "closed", "run-started", "approval-required", "result", "error"] },
    "integrationId": { "type": "string", "maxLength": 64, "pattern": "^[A-Za-z0-9._-]+$" },
    "appCode": { "type": "string", "maxLength": 64, "pattern": "^[A-Za-z0-9._-]+$" },
    "traceId": { "type": "string", "maxLength": 64 },
    "occurredAt": { "type": "string", "format": "date-time" },
    "sessionId": { "type": "string", "maxLength": 64 },
    "runId": { "type": "string", "maxLength": 64 },
    "configReleaseId": { "type": "string", "maxLength": 64 },
    "status": { "enum": ["SUCCEEDED", "FAILED", "UNKNOWN", "WAITING_APPROVAL", "CANCELLED"] },
    "structured": { "type": "object", "additionalProperties": true, "maxProperties": 32 },
    "fileRefs": {
      "type": "array", "maxItems": 16,
      "items": { "type": "object", "additionalProperties": false, "required": ["artifactId", "name"],
        "properties": { "artifactId": { "type": "integer", "minimum": 1 }, "name": { "type": "string", "maxLength": 256 }, "sizeBytes": { "type": "integer", "minimum": 0 } } }
    },
    "businessRecordRefs": {
      "type": "array", "maxItems": 16,
      "items": { "type": "object", "additionalProperties": false, "required": ["source", "recordId"],
        "properties": { "source": { "type": "string", "maxLength": 64 }, "recordId": { "type": "string", "maxLength": 128 } } }
    },
    "error": { "type": "object", "additionalProperties": false, "required": ["code"],
      "properties": { "code": { "type": "string", "maxLength": 64 }, "message": { "type": "string", "maxLength": 512 }, "retryable": { "type": "boolean" } } }
  },
  "allOf": [
    { "if": { "properties": { "event": { "const": "result" } } }, "then": { "required": ["runId", "status"] } },
    { "if": { "properties": { "event": { "const": "error" } } }, "then": { "required": ["error"] } }
  ]
}
```

禁止远程 `$ref`；`structured.additionalProperties` 仅为既有四段输出的结构化透传，宿主不得将其当业务权威（业务权威以 `businessRecordRefs` 反查为准）。

## 5. 页面上下文 pageContext（实现归 INT-05/06）

`pageContext` 只允许接入档案（IntegrationProfile.contextSchema）**显式声明**的业务上下文字段（如当前记录 ID、选定时间范围）；长度、类型、字段白名单与敏感级别在接入档案定义。

### 5.1 通用信封 JSON Schema v1（本文冻结草案）

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:agent-platform:assistant-page-context:1.0",
  "title": "AssistantPageContext",
  "type": "object",
  "maxProperties": 16,
  "propertyNames": {
    "pattern": "^[a-z][a-zA-Z0-9]{0,31}$",
    "not": { "enum": ["tenantId", "userId", "roles", "permissions", "token", "authorization", "cookie", "secret", "secretRef"] }
  },
  "additionalProperties": { "type": ["string", "number", "boolean"], "maxLength": 256 }
}
```

说明：键名由 `propertyNames` 约束（模式 + 身份/凭据语义黑名单，非完整清单，以接入档案白名单为准）；字段值仅限标量且各 ≤256 字符；`tenantId/userId/roles/permissions` 或任意 Header 一律不得作为上下文授权输入。服务端按当前主体重新核目标授权，敏感数据先最小化。

## 6. 错误语义与新增错误码命名空间

- 响应信封沿用 admin-common `ApiResponse`（code/message/data/traceId 四字段 + 可选 userMessage/category/retryable，见 error-envelope-boundaries.md）；category ∈ AUTH/QUOTA/PARAM/TRANSPORT/SYSTEM/BUSINESS；仅 429 声明 `retryable=true`。
- 401/403 归 AUTH 且 retryable=false，声明 retryableCodes 不得覆盖；带副作用 5xx 的 RESULT_UNKNOWN 规则保持。
- 新增错误码统一前缀 `INTEGRATION_` / `CONFIG_`，**最小登记集**（实现卡不得私造同义码；扩展须回本标准登记）：

| 错误码 | category | 语义 | 归属卡 |
| --- | --- | --- | --- |
| `INTEGRATION_SOURCE_NOT_ALLOWED` | AUTH | origin/来源不在接入档案 allowedOrigins | INT-04 |
| `INTEGRATION_CLIENT_IDENTITY_REJECTED` | AUTH | 客户端自报身份/角色/tenant 不被采信 | INT-04 |
| `INTEGRATION_TICKET_EXPIRED` | AUTH | 短票过期/撤权后刷新被拒 | INT-04 |
| `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE` | TRANSPORT | 身份源不可达，零业务执行 | INT-04 |
| `INTEGRATION_EVENT_CHANNEL_INVALID` | PARAM | 事件通道 nonce/origin/实例校验失败或未知 schemaVersion | INT-07 |
| `INTEGRATION_CALLBACK_POLICY_VIOLATION` | PARAM | 任意回调 JS/HTML/URL 被拒绝 | INT-03/07 |
| `CONFIG_VERSION_UNKNOWN` | PARAM | 引用不存在的配置版本/修订 | INT-03 |
| `CONFIG_RELEASE_NOT_ACTIVE` | BUSINESS | 应用无激活 ConfigRelease，明确缺哪个前提 | INT-03/06 |
| `CONFIG_REV_CONFLICT` | PARAM | expectedRev 不符（409 语义） | INT-02/03 |
| `CONFIG_REF_NOT_FOUND` | PARAM | 引用的档案/绑定/版本/修订对象不存在（404 语义） | INT-02 |
| `CONFIG_STATE_TRANSITION_INVALID` | PARAM | 状态机外迁移（如引用未批准修订、SUPERSEDED 复活） | INT-02/03 |
| `CONFIG_AUTH_SCHEME_UNSUPPORTED` | PARAM | authScheme 为冻结留白值（当前运行仅支持 NONE/BEARER/API_KEY） | INT-02 |
| `CONNECTION_RESOLUTION_MALFORMED` | TRANSPORT | skill 侧收到的解析回执形状不合规（digest 非64hex、authKind 越枚举、集合字段非数组），502 | INT-02 |
| `CONFIG_SECRET_PLAINTEXT_REJECTED` | PARAM | 配置中出现明文密钥/secret 值 | INT-02/03 |
| `CONFIG_SCHEMA_BUDGET_EXCEEDED` | PARAM | Schema 大小/预算超限，不放宽放行 | INT-01/02 |

## 7. SDK 与平台版本兼容矩阵

| SDK 版本 | 平台网关/应用版本 | 行为 |
| --- | --- | --- |
| 旧版（仅 `init`→`destroy`） | 任意已发布应用 | 原行为保持：浮球+抽屉+对话/确认/下载；平台不得破坏旧契约 |
| 新版（`open/close/updateContext/on/off/destroy`） | 网关无 `GET /v1/apps/{code}/experience` | 优雅回退：不取体验描述，直接对话；无模板时允许直接提问，不伪造默认能力 |
| 新版 | 网关有体验端点且应用有激活 ConfigRelease | 按体验修订渲染欢迎语/能力卡/推荐问法；能力按当前用户过滤 |
| 新版 `autoLauncher:false` | 任意 | 不自动挂浮球，等待宿主按钮 `open()`；同 DOM 位置重复 init 有明确复用/销毁规则 |

兼容规则：SDK 增量 API 全部为**附加式**演进；平台旧字段不删除、不改变语义；SDK 对未知字段必须忽略而不是报错；体验/事件 schema 演进 = 新 `schemaVersion`，不原地覆盖。

## 8. 新增路由、权限目录与资源摘要选择器登记要求

所有新增 API 必须同步以下登记，缺一即不得合入（联动白名单由各实现卡精确列出）：

1. `admin-common/src/main/resources/agent-service-authority.json`：每个新增 Controller 方法的**精确** HTTP 方法、模板路径、caller/target/action、委派模式与 integrationAllowed；禁止 `ANY` 或通配路径。
2. `ServiceRoutePolicy` / `RouteDirectory`：保持现有目录消费与校验；仅确需新资源类型时扩展有限校验，不加旁路目录。
3. `IntegrationPolicy.allowedPath`：仅放行集成票据运行所需的体验/运行精确路径；管理配置、凭据与环境接口不得向集成票据开放。
4. 前端 `web/shared/routes.ts`、`permission.ts` 与 `web/admin/src/router.ts` 同一权限语义；前端隐藏只改善体验，后端始终独立验证。
5. `web/shared/api.ts`、`generate-api.mjs`、`generated/api.d.ts`：Controller/OpenAPI 先定，用现有生成链路同步；生成脚本仅可在获授权开发环境执行。
6. 本标准冻结的受控登记入口：`POST /admin/skills/from-approved-connection`（细分 action 在 INT-02 实施时按本路径展开登记，不得另立同义路径）。
7. 拟新增路由总表（归属卡在实施时登记 OpenAPI 与服务目录）：`/admin/connection-profiles`、`/admin/environment-bindings`、`/admin/apps/{code}/experiences`、`/integration-profiles`、`/config-releases`、`GET /v1/apps/{code}/experience`、`GET /internal/connection-profiles/{profileId}/versions/{profileRev}/resolve`（app 内部，caller=skill）、`GET /internal/skills/{code}/approved-connections/{profileId}/versions/{profileRev}?environment=`（skill 侧受控解析入口，INT-02 已实现，本文正式冻结）、engine 会话/运行创建可选 `configReleaseId` 扩展。**以上均为拟新增，未实现前手册不得宣称可用。**

## 9. Schema 通用边界（对所有新增配置/事件 Schema 生效）

- 禁止远程 `$ref`；`additionalProperties: false` 为默认，动态业务字段必须显式建模为受控容器（如 `structured`）并说明边界。
- 不放宽既有 Schema 预算；R11/PROCESS Schema 工作预算与 OpenAPI 支持子集的原限制继续如实诊断，不在接入配置中绕过。
- 字段可追溯到唯一对象归属（configuration-profile.md §1）；不允许同一语义字段在两个对象中各自定义。
- 校验失败给可定位错误（字段路径 + 上述错误码），不静默截断或忽略未知字段。

## 10. 兼容、激活与回退

- 本标准 v0.1 激活前：撤回草案即可，无产品回退；激活时在 `freeze.json` engineeringAddenda 登记 sha256 与变更控制条目，版本不可原地覆盖（演进 = 新版本文件 + 新登记）。
- 不改写冻结 CapabilityPackage v1、旧四制品、历史 manifest 与历史摘要；新受控引用（INT-02）使用独立版本化信封，不向 v1 未知字段塞 `connectionRef`。
- 已激活配置的停用/回退遵循 configuration-profile.md §4 状态机；**回退不恢复已撤销权限**。
