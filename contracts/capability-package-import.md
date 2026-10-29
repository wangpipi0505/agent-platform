# 能力包导入工程增补（EX-10）

本增补将既定能力包装载分为三个实施阶段。冻结的 `capability-package.schema.json`、其 `$id`、原四契约和最终验收不变。本文不把声明登记当作已发布技能绑定、应用创建或运行验收。

1. **Phase 1：声明登记。** app-management 校验并持久保存租户内的包版本；状态为 `UNRESOLVED`。本批实现与测试范围只到这里。
2. **Phase 2：真实绑定。** 按 `docs/evidence/2026-10-29-EX-10/engineering-mapping-candidate.md` 只读核验精确已发布技能版本、摘要及实际注册表，解析软件环境、路线和工具映射；不写入技能、不作跨服务补偿。当前未交付。
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
