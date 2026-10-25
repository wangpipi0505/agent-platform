# MCP 完整页面布局修复独立审查

审查日期：2026-10-20。范围按 `frontend-mcp-layout-review-brief.md`：独立新工程内冻结的 App 技能详情布局修改及新增真实 Chrome 回归。未读取相邻旧工程，未修改业务源码、启停后端、运行 Maven、提交、发布或占用 MCP peer28418。

## 两项结论

- **需求符合性：通过。** 390px 的完整 App（ResourceDetail 与 SkillStatistics 同时挂载）文档宽度为 390px；1440px 保持 1440px 与桌面原有 1366px 最小宽约束。日期、版本筛选、查询入口、详情与统计内容可见；三张统计表通过内部横向滚动到达末列。没有用 shell/body 的 overflow:hidden 遮蔽溢出。
- **代码质量：通过，附一项非阻断测试建议。** 生产差异仅 App.vue 一处模板 class 和一条 scoped 窄屏媒体规则，直接修复全局 shell 最小宽的根因，无新增依赖、业务请求、权限或数据处理改动。没有发现需要阻断的缺陷。

## 源码、补丁与冻结核验

`git rev-parse --verify HEAD` 退出 128（Needed a single revision），因此未使用空 git diff 作依据。实际读取正向 patch，并执行 baseline/frozen 的 `git diff --no-index`（退出 1，表示存在预期差异）：App.vue 第 648 行仅追加 `skill-detail-mode: path.startsWith('/skills/')`；第 1354 行仅在 <=900px 覆盖 shell min-width 为 0，并让 topbar/actions 换行。

class 仅作用于 `/skills/` 路径族，不命中 `/skills` 列表、应用、模型、运行等其他模块。前缀也包含代码中预留的 `/skills/new`；目前该路径未挂载已有草稿详情，不构成其他模块样式扩散。本次不将前缀判断描述成精确单一详情路由匹配。

逐项使用 `Get-FileHash -Algorithm SHA256` 实际验证，共 31 项全部一致：当前源码 2、冻结文件 2、App 基线 1、保护文件 16、前阶段 MCP 七文件与 SkillStatistics 共 8、补丁 2。核验记录为 `review-frontend-mcp-live-layout-sha256.json`。App 的变化正是明确授权项，其余 15 保护项以及前阶段 MCP 八项均保持原摘要。

App 当前/冻结 SHA256：`69b50ee5ce21c8616292e4ddadf1071bc7867d266e64d35d4ea1ecc560da707b`；基线 SHA256：`405d22aa498311456aff612e9bd730b870f609bada51c08c2e51a045b1e41c33`。

以下命令均退出 0，只检查而未应用补丁：

```text
git apply --reverse --check docs/evidence/frontend-mcp-live-layout-fix-forward.patch
git apply --check docs/evidence/frontend-mcp-live-layout-fix-reverse.patch
```

## 实际独立执行结果

| 命令 | 结果 |
| --- | --- |
| web 目录：`node tests/mcp-live-layout-browser.mjs review-live smoke_mcp_good_f8e15ace6f` | 退出 0；1440→1440，390→390；4 个业务 GET；pageErrors=[] |
| 工程根：`node .local/review-frontend-mcp-live-layout-scroll.mjs` | 退出 0；窄屏重新加载、展开按日与统计口径、三表滚动末列可达；documentWidth=390，shell overflowX=visible |
| web 目录：`npm test -- --reporter=dot` | 退出 0；28 文件、127 测试通过；08:44:47 开始，Duration 1.09s |

独立主回归输出：`frontend-mcp-live-layout-fix-review-live.json`，截图 `frontend-mcp-live-layout-fix-review-live-1440.png`、`frontend-mcp-live-layout-fix-review-live-390.png`。补充只读回归源码保存在 `.local/review-frontend-mcp-live-layout-scroll.mjs`，输出 `review-frontend-mcp-live-layout-scroll.json` 和 `review-frontend-mcp-live-layout-scroll-390.png`。

补充回归实际表内尺寸如下（均为 244px 容器，overflowX=auto）：

| 表格 | 内容宽 | 滚动终点 scrollLeft | 末列表头可达 |
| --- | --- | --- | --- |
| 用途统计 | 480 | 236 | 是，P95 耗时 |
| 按日调用 | 320 | 76 | 是，P95 耗时 |
| 失败样本 | 490 | 246 | 是，运行样本 |

补充脚本同时记录 `/auth/`：仅开发登录的 `/auth/dev-token` 为 POST；业务仍只有两次草稿详情 GET、一次 stats GET、一次 model-entries GET。未触发发现、工具调用、保存或发布。截图均已实际读图，确认完整页面在窄屏换行，以及表格右端内容与展开的统计口径可见。另读原 red-390 图及红绿 JSON/log：修复前 390→1366，修复后 390→390，与源码根因吻合。未覆盖作者/root 原始证据。

## 非阻断建议与限制

- **[P3] 新浏览器回归可等待表布局稳定并断言内部滚动。** 位置：`web/tests/mcp-live-layout-browser.mjs:15`–19。触发条件：1440→390 切换后立即采集 DOM；Element Plus ResizeObserver 尚未完成时，JSON 仍可能记录 1148px 的旧表格内容宽，随后截图已经呈现 480px 的稳定内容宽。影响：诊断尺寸与截图可能不同，现有断言只证明文档宽度，不能独立证明右侧列可达；本次已用独立补充脚本确认可达，因此不影响生产修复结论。建议等待稳定布局后采集，并断言内部 scrollbar 可滚动至末列。
- 本次独立回归只读取指定已保存草稿，统计数据为空；没有声称非空统计数据、完整 MCP tools/list 或全协议通过。真实 MCP 协议验证由 root 单独负责。
- 单测为本次独立重跑；构建仅读取作者的 `frontend-mcp-live-layout-fix-build.log`（三端构建通过），未将作者构建记录冒称本次独立执行。未重放全部历史交互脚本；其关键源文件已逐项核对 SHA。
- 初次读取辅助 CSS/组件时命令工作目录多写了 `web/`，得到路径不存在；随后使用工程根路径重新读取成功。该操作未访问工程外目录，未影响上述浏览器与哈希命令结果。
