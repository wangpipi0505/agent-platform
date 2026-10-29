# 能力包导入工程增补（EX-10）

本增补将既定能力包装载分为三个实施阶段。冻结的 `capability-package.schema.json`、其 `$id`、原四契约和最终验收不变。本文不把声明登记当作已发布技能绑定、应用创建或运行验收。

1. **Phase 1：声明登记。** app-management 校验并持久保存租户内的包版本；状态为 `UNRESOLVED`。本批实现与测试范围只到这里。
2. **Phase 2：真实绑定。** 已实现单路线的精确已发布技能/摘要/真实注册表只读核验及本地映射保存，23项定向验证通过；不写技能、不执行工具。多路线/完整应用计划与运行闭环继续按原候选推进。
3. **Phase 3：应用与运行。** 在真实绑定后创建可编辑草稿，保护编辑/复制/删除时的来源一致性，并将映射冻结到发布和 run 快照。网关精确路由、服务授权、完整应用计划幂等、HTTP/MCP/PROCESS 实际链路及最终 EX-10 验收仍待；不能用 Phase 1 的成功回包替代。

## Phase 1 API

`POST /admin/capability-packages/import`，`Content-Type: application/json`，请求只接受：

```json
{"package": {"apiVersion": "platform/v1", "kind": "CapabilityPackage"}}
```

以上只展示信封，`package` 必须提供冻结 schema 所要求的完整声明。tenant/principal 只来自既有验签上下文，要求 `EDITOR`（沿 `Actor.requireRole` 的既有 ADMIN 规则）；无身份/无角色默认拒绝。不能提交 tenant/principal、服务 URL 或权威 SHA。当前只交付 app-management 入口，不宣称网关/IAM 的新授权路径已开通。

首次登记及相同内容重试均沿现有管理面约定返回 HTTP 200、`ApiResponse.code=OK`。data：

```json
{
  "packageVersionId": 123,
  "packageId": "example-package",
  "version": "1.0.0",
  "sha256": "服务端计算的64位小写摘要",
  "softwareId": "example-software",
  "reused": false,
  "bindingStatus": "UNRESOLVED",
  "verificationStatus": "NOT_EVALUATED",
  "appCreated": false,
  "applications": []
}
```

声明含 `software.version` 时原值作为 `softwareVersion` 返回；不将其当包版本。HTTP/MCP/PROCESS 等合法声明都能登记；endpoint、serverRef、command、skillRef、verification 开关仅作为声明保存，不发 HTTP、不启动进程、不核技能发布、不生成绑定。包内 TASK/混合路线/符号引用等可保存声明，不因此承诺 Phase 2/3 可装载。

## 校验与持久身份

- classpath schema 为仓库冻结文件原字节副本，SHA-256 `0456799d04deb57dfb114d169d8a37c7b57a49cad3996fee917a93efa704a634`；使用已有 networknt，不启动 Node、不读取部署机源码路径、不加载外部 schema。包内 input/output schema 作为声明对象保存，不作为本批可执行 validator 编译。
- 复核包内 interface/tool/route/agent 标识唯一、工具 binding 及 route/agent 引用存在、引用的工具启用、只读与写入/副作用不矛盾、NONE 本体不含 contextPack、路线本体引用与本包一致、verification 不全部关闭且关闭项有豁免。identity 中疑似凭据字段仅接受 `secret://`/`cred://` 引用（含字符串数组），拒绝明文；不读取引用值。
- `packageId=metadata.name`，`version=metadata.version`。运行登记的 name 必须完整匹配其标识模式；version 完整匹配三段数字且最多 128 字符；canonical UTF-8 声明最多 2 MiB。这些是存储入口限制，不改冻结 schema。
- `sha256=Digest.sha256(Json.canonical(package))`，递归对象键排序、数组顺序和值保留；不补默认值、不删描述，不保证 `1`/`1.0` 等不同数值表示相同。完整 canonical 声明存入 JSONB。
- V108 新增 `app.capability_package_version`；唯一键 `(tenant_id, package_id, package_version)`。同键同 SHA 返回原 id 且 `reused=true`，不更新内容、创建人或时间；异 SHA 返回 409。不同租户可独立登记相同包版本；新版本不覆盖旧记录。
- PG 唯一键负责竞争；单个短事务 INSERT ON CONFLICT DO NOTHING 后再读取并核摘要，不使用进程内 Map。锁等待 2 秒、语句 3 秒、事务 5 秒，冲突超时返回可重试错误。本入口没有跨服务写入、应用/技能副作用或补偿任务。

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 | `SYS-AUTH-401` | 无有效主体，沿现有身份规则 |
| 403 | `SYS-APP-4031` | 无 EDITOR/ADMIN 权限 |
| 400 | `INVALID_INPUT` | 请求信封含未知或自报身份字段 |
| 400 | `CAPABILITY_DECLARATION_INVALID` | schema、包内引用、声明语义、凭据或入口大小/身份限制不满足 |
| 409 | `CAPABILITY_VERSION_CONFLICT` | 同租户同包同版本已有不同 SHA |
| 409 | `CAPABILITY_IMPORT_BUSY` | 数据库争用超时；可按同一请求重试 |
| 409 | `CAPABILITY_BINDING_REQUIRED` | 提交 environment/bindings/applications 计划；必须等待真实绑定及应用子批，不能忽略这些字段后返回成功 |

本批不新增 app 关联表、不改 AppService/SkillCatalog/RunService，不声称生成可编辑草稿。后续恢复既定应用组装信封时须显式实现其 plan SHA、真实绑定和原子创建，不得把本批登记记录直接升级为 READY。

## Phase 2：选定单一路线的绑定解析

本子批将已登记的一个包版本解析到真实精确发布技能，仍不创建应用、不执行工具、不生成评测结果。Phase 1 的声明入口和冻结 schema 保持兼容。原完整应用组装与 run 快照目标仍由 Phase 3 承担。

`POST /admin/capability-packages/{packageVersionId}/bindings/resolve`，JSON 请求：

```json
{
  "environment": "dev",
  "routeProfileId": "primary",
  "bindings": [
    {"toolId": "example-tool", "skillRef": {"code": "existing_skill", "version": 1}}
  ]
}
```

`bindings` 可省略，由选中工具的 `skillRef` 提供精确引用；可带已有候选定义的可选 `sha256`。声明和请求同时提供引用时必须一致。不能提交 tenant、principal、URL、任意 `binding_ref`、应用计划或附加权威字段。仅 EDITOR/ADMIN，包版本始终按 Actor 租户读取。网关与 IAM 新授权入口仍未包含在本子批。

- environment 必须是原包中的明确键；仅冻结该键及声明值，不能覆盖已发布技能 endpoint。有 routeProfiles 时只选指定路线；没有时按 interface id 选择默认组合。缺省工具为该 interface 的全部启用工具；显式空集合拒绝，混合 interface 路线返回 `CAPABILITY_ROUTE_BINDING_CONFLICT`。未选路线/工具仍只是原声明，不称已解析。
- app 仅请求配置中的固定技能服务 origin：`GET /internal/skills/{code}?version=n&requireRegistered=true`。用 `ServiceTokens.issue(Actor)` 签独立内部票据，禁止重定向；总预检预算 5 秒，单次连接/读取最多 2 秒且受剩余总预算限制，单响应最多 2 MiB。先完成全部远端只读校验，再进入原本地短事务。
- 技能读口保留 SERVICE 门。`requireRegistered` 缺省/false 沿原行为；true 必须给正整数精确版本，要求版本 PUBLISHED、主技能未停用/归档、definition 的 canonical SHA 与持久摘要一致，并由实际注入的 AdapterRegistry 同时核组合与 `find`。只追加 `registered=true`，不改 definition/digest、不执行 adapter、不调用写入 normalize。PROCESS 历史契约省略 protocol 时，追加 `resolvedProcessProtocol`，由原 ProcessContract.parse 得出默认协议；app 比该解析值，不重写历史契约。
- app 核 code/version/id/skillVersionId/sha256/digest/definition/registered 返回一致。HTTP/MCP/PROCESS 分别按已定案候选比较传输形态、endpoint/toolName、command/解析协议；HTTP_ASYNC 对账声明逐字段核，ALGORITHM 核 jobTemplate。注册资格由技能服务决定，app 没有第二套适配器注册表。
- 显式读写/幂等/确认角色/风险、非空 inputSchema/outputSchema/errorSemantics、分页模式、outboundIdentity/guard 均与发布契约比较。本子批 guard 非空时采用精确比较，不推断更宽权限。未解析 secret/cred 引用、额外 scopes、非 none 本体引用、无权威字段的 operation、非空 artifacts 要求明确拒绝。TASK/模型/提示词/agent 模板不在这个解析请求中选用；不因此承诺整份声明可生成应用。
- V109 仅扩原 `capability_package_version`：request_plan_sha256、resolved_mapping、resolved_by、resolved_at，binding_status 允许 RESOLVED。计划摘要覆盖 environment、routeProfileId 和 bindings（省略 bindings 视为空数组）；首次解析后同计划返回原收据且 reused=true，不重新读取目录；异计划返回 `CAPABILITY_IMPORT_PLAN_CONFLICT`。每个包版本本子批仅一份选定路线计划，更多应用/组合计划留后续阶段，不隐式覆盖或重新绑定。
- 写入 WHERE 同时限定 tenant、packageVersionId、原包 SHA、UNRESOLVED；并发败者回读后核原包 SHA 与同计划，不覆写。绑定成功响应 HTTP 200/OK，包含原包版本/软件身份、environment、routeProfileId、`bindings[{routeProfileId,toolId,interfaceId,skillCode,skillVersionId,version,sha256}]`、bindingStatus=RESOLVED、verificationStatus=NOT_EVALUATED、appCreated=false、applications=[]、reused。RESOLVED 只表示这份计划已解析，不能视作 READY、发布、执行或连接测试通过。

新增/沿用错误：400 `SKILL_VERSION_REQUIRED`（精确内部读缺版本）；404 `CAPABILITY_PACKAGE_NOT_FOUND`、`SKILL_VERSION_NOT_FOUND`；409 `SKILL_NOT_PUBLISHED`、`SKILL_ADAPTER_NOT_REGISTERED`、`SKILL_DIGEST_MISMATCH`、`CAPABILITY_BINDING_DIGEST_MISMATCH`、`CAPABILITY_BINDING_CONFLICT`、`CAPABILITY_BINDING_REQUIRED`、`UNRESOLVED_SECRET_BINDING`、`SKILL_VERSION_CONFLICT`、`CAPABILITY_IMPORT_PLAN_CONFLICT`；502 `DEPENDENCY_UNAVAILABLE` 用于传输超时或不完整/不一致的元数据回复。错误不回显票据、秘密或下游原始消息。Phase 1 的同包同版本异 SHA 409 和未解析声明仍保留。

## Phase 3a：由已解析绑定创建真实应用草稿

`POST /admin/capability-packages/{packageVersionId}/applications` 接受 `{applications:[{appCode,name,type,routeProfileId,config,definition?,layout?,description?,domain?,bizCategory?,tags?}]}`，返回统一 `OK` 信封。身份来自 Actor，要求 EDITOR；客户端不能指定 tenant/principal。一个包版本首次只能使用一个完整应用计划，所有应用选择 Phase 2 已解析的同一路线。`agentCode`/TASK 模板转换、向既有应用合并来源、多路线重新绑定均不在本子批内。

必须明确提供用户完整配置：CHAT 必须有真实可解析的 `modelBinding.modelEntryId`，FLOW 必须提供原格式完整图。服务复用 AppService 的结构、参数、Schema、技能/模型/提示词和 guard 校验；远端只读检查先完成，再开始本地事务。不猜模型、不调用工具、不伪造评测或发布结果。首次创建还通过原 `requireRegistered=true` 读口复核精确技能 ID/版本/摘要与已存解析一致。依赖失败不创建任何应用。

配置不得自行提供 `capabilityPackages`、`capabilityBindings`、`skillRefs`、`tools`、`frozenPrompts`、`dependencyVersions`、`ontologyContext` 等服务端来源或冻结字段；skillRefs 从解析映射生成。FLOW 显式技能节点必须与该映射的 code/version 一致。首版不在这个入口接受工具档案切换、停用或隐式重绑。

全部草稿、逐应用来源关联和首次创建收据在同一 app PG 事务写入。返回 `applications:[{appId,appCode,draftRev,status}]`、`appCreated=true`、`reused`、`verificationStatus=NOT_EVALUATED`；首次状态为 DRAFT。同一完整计划重试读取原应用当前草稿信息，不覆盖其后编辑，也不再创建应用；不同计划为 `409 CAPABILITY_IMPORT_PLAN_CONFLICT`。目标已删除或同名应用已换身份为 `409 CAPABILITY_APPLICATION_MISSING`，不会通过重试复活。

服务端 `config.capabilityPackages` 保存包版本/摘要、软件、环境、路线、映射摘要和逐工具精确来源，PG 关联为权威校验源。普通 save（含 FLOW 图）不能删除/伪造来源、改绑定版本或改变已固定工具策略，否则 `409 CAPABILITY_SOURCE_CONFLICT`；无包旧应用不新增此字段、原构造及保存行为保持。copy 在原事务为新应用复制来源关系，不复制发布/评测许可；删除从未发布草稿时，仅本应用关联随外键删除，包版本、其他应用和发布版本保留。precheck/commit 再核来源关系，发布快照沿原完整 config 固化并进入候选摘要。

本子批证明草稿与发布来源保护，不宣称真实评测或执行已完成。RunService 原深复制可保留 config 字段，但独立 `versionRefs`、执行 tools 的来源对应与重放自洽检查仍待后续子批；IAM 原准入仍拒绝不支持的包/技能配置。EX-10 原完整运行验收保持未完成。

## Phase 3b：运行快照来源与精确版本一致性

本子批补充上一节保留的运行冻结缺口，不改变执行器或 PG 事实权威。带 `config.capabilityPackages` 的新运行先核包身份、环境/路线、原映射摘要及全部逻辑 tool 引用，再以 `requireRegistered=true` 读取精确发布技能；返回 code/version/versionNo/id/skillVersionId、契约 SHA 与原映射必须一致，扁平执行/guard/Schema 字段也必须与同摘要 definition 一致。失败为 `409 CAPABILITY_SNAPSHOT_CONFLICT`，在 run/节点写入和模型冻结之前拒绝。旧无包应用的原读取与冻结形状保持。

运行 `snapshot.capabilityBindings` 保存原包版本、软件版本、环境声明、路线和逐工具精确绑定，并记录本次实际选择 `SELECTED`、`GUARD_DENIED`、`DISABLED` 或 `PROFILE_NOT_MEMBER`。同 code/version 只取一次技能，但保留每个逻辑 tool 的来源；相同技能引用的 enabled/profiles 策略冲突时拒绝，不能靠去重吞掉差异。选中 `config.tools[]` 增加 `capabilityOrigins[]`，`version_refs.capabilityPackages` 保存同一冻结来源；未选工具不伪装成已装载。

所有新运行在入库前核冻结来源与 tools 的 ID、契约摘要及 origins 自洽。受控录制回放仅检查原快照，不读当前包/技能目录、不重新解释历史选择；损坏或缺失的包冻结材料明确拒绝。已有 requestId 保持原幂等读取，不重写原记录；重新执行仍沿原目录解析规则核对冻结来源。无包旧回放不新增来源键。IAM 原入口对不支持的技能/包配置继续拒绝，本子批不宣称实际模型/工具调用、容量或完整 EX-10 联验完成。

## EX-12：运行观测查询（AP）

沿现有 `GET /admin/runs/page` 增加可选 `packageId`、`routeProfileId`、`toolId`、`releaseId`、`traceId`（各最多128字符），原 appCode/status/q/time/page/size保持。tenant/principal只取当前身份，显式提交范围字段拒绝。包/路线必须来自同一 `version_refs.capabilityPackages` 元素；toolId另需同来源binding与该run实际 `tool_call` 的精确skill code/version对应，不从技能编码猜toolId，也不把声明当成已调用。release仅取原已消费的 `snapshot.contextPack.releaseId`。

列表及legacy运行详情新增 `observation`，含 `traceId/releaseId/spaceId/capabilityPackages`；历史缺字段为null，读取时不以当前MDC或目录补值。仅新运行生成versionRefs时捕获 `TraceContextFilter.current()` 并与原事务共同持久；IAM protected详情原形状保持。本批未接OR的可信package/route/app-tool执行挂点，也不宣称trace已经跨服务贯通。

## EX-13：请求 trace 与冻结 worker 上下文

网关既有合法 X-Trace-Id 规则保持。新run沿versionRefs冻结trace，legacy与已验IAM worker均在各自run执行边界绑定并finally恢复MDC；旧run缺字段仅生成当次关联，不回填PG。RuntimeClients的GET/POST、流、取消和受控异步发布读传同一头，SkillAdapters HTTP最后设置一个上下文trace，不能由技能鉴权配置覆盖。关联值不参与身份或授权。

OR请求边界用相同长度/字符约束，成功/错误信封和响应头携当前请求trace，结束清理；旧三参Envelope构造保持。OR原执行UUID不强制改成AP opaque trace；请求trace持久化及执行查询关联仍待EX-12后续，不把HTTP透传当作已完成四段真实业务联验。
