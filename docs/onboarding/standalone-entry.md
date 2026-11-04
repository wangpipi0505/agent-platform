# 独立入口：从接入模板到业务人员可用

本文只描述**独立入口**（平台内使用，不改造业务系统）的完整路径。内嵌交付见 `embedded-delivery.md`。

## 0. 准备

1. 应用已发布一个版本（`app.application.current_version_no` 存在且候选摘要已冻结）。
2. 体验修订 `APPROVED`：管理台「应用体验」或 `/admin/apps/{code}/experiences`。
3. 接入档案修订 `APPROVED`：`/admin/integration-profiles`（`bizSystemCode/environment/mode/allowedOrigins` 等）。
4. 能力包版本已登记且被当前账号授予；模板只能引用，不复制其字段定义。
5. 账号为 `ADMIN`；页面访问键 `onboarding-templates`（读 `session`、写 `admin`）。

## 1. 进入接入模板

- 地址：`/onboarding/templates`（管理台「平台管理 → 接入模板」）。
- 顶部两个区块是登记向导与已登记模板列表；下方依次是连接自检、组装登记与验证发布、使用端交接与首跑验证。

## 2. 选择来源能力包（向导第 1 步）

- 只列出当前账号被授予的能力包版本；选择后来源身份在后续步骤不可改写。
- 能力包 SHA256 随选择固定，用于发布前复核。

## 3. 登记契约字段（向导第 2 步）

填写并校验：

- `code`：`^[a-z][a-z0-9_]{1,63}$`；`name` 非空；`kind` 只能 `QUERY`（其余种类在选项与提交两处拒绝）。
- `appCode`、`experience.appCode` 同形；`experience.experienceRev` 为正整数且等于已批准修订。
- `integration.bizSystemCode`、`integration.environment`、`integration.mode` 非空，且与已批准接入档案一致。
- `description` 可选（≤2048）。不接收凭据、token、可执行脚本或任意 HTML。

## 4. 核对并导出（向导第 3 步）

- 预览只含逻辑引用：来源包版本与 SHA、体验引用、接入引用、目标应用；不含密钥与内部账号属性。
- 「复制登记 JSON」用于评审或变更单留痕；该 JSON 不是运行凭据。

## 5. 组装登记与发布

- **组装登记**（`DraftAssemblyStep`）：`POST /admin/onboarding-templates`，body 字段与上一步一致，另带 `requestId`。成功返回模板 `code/rev/status`。
  - 同一 `requestId` 重放：返回首次结果（幂等）；不同内容复用同一 `requestId` → `CONFIG_REV_CONFLICT`。
- **发布前预检**（`VerifyPublishStep`）：`POST /admin/onboarding-templates/{code}/versions/{version}/preflight`，body `{document, connection?, requestId}`。返回逐操作 `supported/requiresAuth/reasons`、`documentSha256`、`planSha256`、`zeroCreated: true`；预检不写业务数据。
- **修订**：`POST /admin/onboarding-templates/{code}/versions`，必须带 `expectedRev`（当前修订）。旧修订并发写返回 `CONFIG_REV_CONFLICT`，不得静默覆盖。
- **发布**：`POST /admin/onboarding-templates/{code}/publish`，服务端在发布前复核：能力包存在且 SHA 一致、应用当前发布版本可用、体验修订 APPROVED、接入档案修订 APPROVED；任一失效即阻断，不产生可用模板。
- 发布后不可原地修改；升级请建新修订并重新发布。

## 6. 交接

发布成功后，`AssistantLaunchStep` 展示：

- 应用/模板精确版本、体验引用与使用范围；
- 独立入口 URL：`assistantEntryUrl(appCode)` = `VITE_WEBAPP_BASE_URL`（若配置）或 `协议//当前主机名:15174`，路径 `/w/{appCode}`；
- 首跑五项清单（见下）。

把 URL 交给业务人员即可。正式运行时使用**其本人**授权身份，不复用接入人的高权身份。

## 7. 首跑验证（五项，逐项人工核验）

与 `ONBOARDING_LAUNCH_CHECKS` 同源：

| 项 | 预期 | 位置 |
| --- | --- | --- |
| 欢迎语渲染 | 首屏显示体验配置的欢迎语，无占位符残留 | webapp 首屏 |
| 能力卡展示 | 与已发布技能一致，不宣传未授权/未交付功能 | webapp 欢迎区 |
| 推荐问法 | 可点击并填入输入框，来自体验的 `promptRefs`/示例 | webapp 欢迎区 |
| 模板变量表单 | 按发布声明渲染标签/单位/枚举/文件控件；`false/0/null` 按原类型提交 | webapp 输入区 |
| 只读调用真实执行 | `QUERY` 发起一次真实受控运行并返回结果/文件引用；失败原样展示 | webapp 会话 |

五项全部通过后，才算「业务人员可用」。任一项失败时按错误码定位，不伪造成功、不以占位数据补位。

## 8. 常见错误

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| `CONFIG_REF_NOT_FOUND` | 引用的模板/能力包/档案不存在 | 核对编码与租户 |
| `CONFIG_VERSION_UNKNOWN` | 修订不存在 | 刷新列表后重选 |
| `CONFIG_REV_CONFLICT` | `expectedRev` 过期或 `requestId` 内容冲突 | 重新拉取当前修订，换新 `requestId` |
| `CONFIG_STATE_TRANSITION_INVALID` | 状态不允许该操作（未批准/已停用） | 先批准或重新建修订 |
| `INVALID_INPUT` | 字段/种类/引用形状不合法 | 按页面校验提示修正 |

故障码全集以 `contracts/assistant-integration.md` §6 与 `docs/integration/error-codes.md` 为准；本表不新增码。

## 9. 回退

- 停止新增接入：关闭「接入模板」入口或停用模板；既有应用与已发布版本不受影响。
- 运行侧回退：撤销接入档案/停用凭据后，使用端得到 `CONFIG_RELEASE_NOT_ACTIVE` 或对应拒绝，不降级到 dev/共享账号。
- 已撤销权限不会因回退恢复。
