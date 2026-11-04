# 业务接入模板：交付与操作总入口

本目录是 ONB（业务人员快速接入）链路的**交付与操作说明总入口**，面向平台管理员与业务系统对接人。它回答两件事：

1. 从「能力包 + 体验 + 接入档案」到「独立助手可用」的完整路径在哪里、每步看什么；
2. 内嵌交付（SDK）哪些已交付、哪些仍标注「待集成」。

与相邻交付包的关系：

- SDK/宿主接入细节：`docs/integration/`（L0～L3、宿主自检、示例、故障码、checksum）。
- 配置对象契约：`contracts/configuration-profile.md`；接入契约：`contracts/assistant-integration.md`。
- 本目录只做**操作路径与交付口径**，不复制契约字段表，也不替代契约正文。

## 两条交付路径

| 路径 | 适用 | 入口 | 状态 |
| --- | --- | --- | --- |
| 独立入口 | 先让业务人员在平台内用起来，不改造业务系统 | 管理台 `/onboarding/templates` → 发布后 `/w/{appCode}` | 已交付，首跑按清单人工核验 |
| 内嵌交付 | 业务系统内嵌 SDK/iframe | `docs/integration/` 示例 + 宿主按钮接线 | SDK 能力已交付；宿主接线标注「待集成」 |

## 文档索引

| 文件 | 用途 |
| --- | --- |
| `standalone-entry.md` | 独立入口：模板 → 预检 → 发布 → 交接 → 首跑五项验证 |
| `embedded-delivery.md` | 内嵌交付：已交付能力、示例位置、待集成清单 |
| `known-issues.md` | 已知问题与挂起项（B02 视觉、夹具依赖、手册同步等） |

## 前置条件（两条路径共用）

- 应用已有**已发布版本**（发布指针存在）；接入模板的引用完整性在发布时复核，草稿不生效。
- 体验修订为 `APPROVED`；接入档案修订为 `APPROVED`；连接档案为 `ACTIVE/APPROVED` 且环境绑定已批准（内嵌路径需要）。
- 当前账号具备管理台写权限（`ADMIN`）；业务人员使用端只获得其自身授权，不复用接入人的高权身份。

## 已验证清单（本卡交付范围内）

- 管理台路由 `/onboarding/templates` 可达，访问键 `onboarding-templates`（读 `session`、写 `admin`、菜单可见）。
- 模板登记只开放 `QUERY`；`FILE_ANALYSIS`/`CONTROLLED_WRITE` 在选择器与提交校验两处均被拒绝，并给出原因。
- 登记向导三步：选择来源能力包 → 登记契约字段 → 核对并导出登记内容；导出的登记 JSON 只含引用与校验摘要。
- 发布前服务端复核：能力包版本与 SHA、应用已发布版本、体验修订、接入档案修订；不满足即阻断。
- 同一 `requestId` 重放返回同一结果；`expectedRev` 不匹配返回 `CONFIG_REV_CONFLICT`。
- 预检为只读：返回逐操作支持子集与 `planSha256`，`zeroCreated: true`，不创建技能/应用。
- 独立入口 URL 由 `assistantEntryUrl(appCode)` 生成：`VITE_WEBAPP_BASE_URL` 优先，否则 `协议//当前主机名:15174`，路径 `/w/{appCode}`。
- 首跑五项清单来自 `ONBOARDING_LAUNCH_CHECKS`，页面（`AssistantLaunchStep`）与文档同源。

未在本卡真机执行的项（B02 业务栈未启动）见 `known-issues.md`，不得以视觉稿替代。

## 数据与回退

- 本卡不引入新表、不改生产数据；模板登记写入既有 `app.onboarding_template*`（ONB 服务端子步交付）。
- 回退：关闭管理台「接入模板」入口即可停止新增接入；既有应用来源与已发布版本不受影响；已登记模板保留但不再被引用。
- 已撤销的权限不会被回退恢复。
