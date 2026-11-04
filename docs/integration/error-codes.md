# 故障码表（从契约 §6 派生）

来源：`contracts/assistant-integration.md` §6「错误语义与新增错误码命名空间」。本表**不新增任何错误码**；宿主按 `code` 分支，不要匹配 message 文案。

## 信封

响应统一为 `ApiResponse`：`code / message / data / traceId`，可选 `userMessage / category / retryable`。`category ∈ AUTH | QUOTA | PARAM | TRANSPORT | SYSTEM | BUSINESS`；仅 **429** 声明 `retryable = true`。401/403 归 AUTH 且 `retryable = false`，不得被声明覆盖。

## 已登记接入/配置错误码

| 错误码 | category | 语义 | 宿主处理 | 可重试 |
| --- | --- | --- | --- | --- |
| `INTEGRATION_SOURCE_NOT_ALLOWED` | AUTH | origin/来源不在接入档案 `allowedOrigins` | 核对精确 origin（协议+端口）、代理是否改写来源；多环境分档案 | 否 |
| `INTEGRATION_CLIENT_IDENTITY_REJECTED` | AUTH | 客户端自报身份/角色/tenant 不被采信 | 从服务端会话取身份，删除浏览器传入的 userId/role/tenant | 否 |
| `INTEGRATION_TICKET_EXPIRED` | AUTH | 短票过期/撤权/策略收紧后刷新被拒 | 重新走短票接口；仍失败则按撤权处理，不换共享账号 | 否（可重新取票一次） |
| `INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE` | TRANSPORT | 身份源不可达，零业务执行 | 展示可解释阻塞；稍后重试或联系平台；不得降级 dev/共享账号 | 否（按运维窗口重试） |
| `INTEGRATION_EVENT_CHANNEL_INVALID` | PARAM | 事件通道 nonce/origin/实例校验失败或未知 schemaVersion | 校验 iframe `event.origin/source`、nonce、实例 ID 与 `schemaVersion`；不执行任何导航 | 否 |
| `INTEGRATION_CALLBACK_POLICY_VIOLATION` | PARAM | 任意回调 JS/HTML/URL 被拒绝 | 改用声明式事件与宿主路由，不注入脚本/URL | 否 |
| `CONFIG_VERSION_UNKNOWN` | PARAM | 引用不存在的配置版本/修订 | 刷新配置发布列表，改用现有版本 | 否 |
| `CONFIG_RELEASE_NOT_ACTIVE` | BUSINESS | 应用无激活 ConfigRelease（或缺前提） | 引导到配置管理台完成激活；SDK 已按回退直接对话 | 否 |
| `CONFIG_REV_CONFLICT` | PARAM | `expectedRev` 不符（409 语义） | 重新读取当前修订，用新的 `expectedRev` 重试；同一 `requestId` 只能重放相同内容 | 否（重新取基线后可重试） |
| `CONFIG_REF_NOT_FOUND` | PARAM | 引用的档案/绑定/版本/修订不存在（404 语义） | 检查引用 ID/版本；不要用默认值伪造 | 否 |
| `CONFIG_STATE_TRANSITION_INVALID` | PARAM | 状态机外迁移（引用未批准修订、SUPERSEDED 复活等） | 先审批再激活；检查当前状态 | 否 |
| `CONFIG_AUTH_SCHEME_UNSUPPORTED` | PARAM | authScheme 为冻结留白值（当前运行仅 NONE/BEARER/API_KEY） | 改用受支持方案或等平台开放 | 否 |
| `CONNECTION_RESOLUTION_MALFORMED` | TRANSPORT | skill 侧解析回执形状不合规（digest 非 64hex、authKind 越枚举、集合非数组），502 | 平台侧修复连接解析；宿主重试前确认修订未变 | 否（修复后重试） |
| `CONFIG_SECRET_PLAINTEXT_REJECTED` | PARAM | 配置中出现明文密钥/secret 值 | 改用 `secretRef`，清理配置与日志 | 否 |
| `CONFIG_SCHEMA_BUDGET_EXCEEDED` | PARAM | Schema 大小/预算超限 | 拆分/精简 Schema，不放宽预算 | 否 |

## 运行时可能观察到、但不在 §6 最小表内的码

以下码由既有实现返回，**不属于**接入命名空间的登记集，宿主只应做通用处理（展示 `message`/`traceId` 并上报平台），不要据其写分支：

- `INTEGRATION_RATE_LIMITED`（429，限流；`retryable` 由服务端声明）——如需宿主区分，须先在 §6 登记。
- `API_KEY_REQUIRED` / `INVALID_CREDENTIAL` / `APP_BINDING_DENIED` / `CLAIM_LIMIT_EXCEEDED` / `ORIGIN_DENIED`（网关集成路径的既有校验码，属实现细节）。
- 平台通用码：`IAM_IDENTITY_UNAVAILABLE`（Authority 侧身份源不可达）、`DEPENDENCY_UNAVAILABLE`（预检代理上游不可用）、`OPENAPI_SIZE`（预检文档超限）。

## 排查顺序

1. 看 `traceId` 与服务端访问日志（脱敏）定位链路阶段：取票 → 会话/运行 → 事件 → 结果回传。
2. AUTH 类先查 origin/身份来源/票据有效期/撤权状态。
3. PARAM 类先查 `expectedRev`、引用版本、schemaVersion。
4. TRANSPORT 类先查身份源/连接可达性；**不要**通过降级身份或跳过审批绕过。
5. 任何情况下都不把 token、Cookie、secretRef 明文写进工单或浏览器控制台日志。
