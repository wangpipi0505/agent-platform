# MCP 真实页面 390 布局修复

2026-10-20。状态：布局修复已冻结，真实完整 App 页面 1440 / 390 只读复验通过，web target 已释放给 root。后续带真实远端发现的完整协议脚本由 root 统一执行。

## 根因与修改

新增回归 `web/tests/mcp-live-layout-browser.mjs` 通过真实开发登录、真实网关读取指定已保存草稿 `smoke_mcp_good_f8e15ace6f`。没有 route mock，也没有点击“检查远端工具”或借用 root 的 MCP peer。页面实际同时挂载 ResourceDetail 与 SkillStatistics。

修改前实际 Chrome 数据（`frontend-mcp-live-layout-fix-red.json/.log`）：

| 视口 | document.scrollWidth | `.shell` 宽度 / min-width | `.main` 宽度 |
| --- | --- | --- | --- |
| 1440 | 1440 | 1440 / 1366px | 1238.40625 |
| 390 | 1366 | 1366 / 1366px | 1294 |

全局 `.shell { min-width: 85.375rem; }` 为桌面工作区保留 1366px 下限；此前 statistics / test-input 路由已有窄屏覆盖，技能详情路由没有进入对应模式。独立 ResourceDetail fixture 未挂载 App 壳，所以没有发现该约束。实际 SkillStatistics 表格使用既有内部滚动，没有单独撑开文档。

唯一生产修改是 `web/admin/src/App.vue`：模板为 `/skills/` 详情路由增加 `skill-detail-mode` 类；仅在 <=900px 的 scoped CSS 中设 `min-width:0`，并复用已有顶栏高度自适应与换行规则。没有设置全局 overflow:hidden，也没有删除内容、改缩放或收窄其他路由。业务、查询、权限逻辑均未改，SkillStatistics、ResourceDetail、McpToolsPanel 与原 127 项测试未改。

## 验证

| 命令/检查 | 结果 | 记录 |
| --- | --- | --- |
| `node tests/mcp-live-layout-browser.mjs red smoke_mcp_good_f8e15ace6f` | 修改前实际复现 390→1366，断言失败；1440 通过 | `frontend-mcp-live-layout-fix-red.log/.json` |
| `node tests/mcp-live-layout-browser.mjs green smoke_mcp_good_f8e15ace6f` | 1440→1440，390→390；真实完整 App 壳含统计；pageErrors=[] | `frontend-mcp-live-layout-fix-green.log/.json` |
| `npm test -- --reporter=dot` | 28 文件 / 127 项通过 | `frontend-mcp-live-layout-fix-unit.log` |
| `npm run build` | vue-tsc 与 Admin / WebApp / SDK 三端构建通过，保留既有 chunk 提示 | `frontend-mcp-live-layout-fix-build.log` |
| 原 MCP Chrome + 历史 Chrome 重放 | MCP 13、异步 3、边界 13、命名输入 18、评测输入 5 项全部通过，pageErrors=[] | `frontend-mcp-live-layout-fix-recheck.log`、`frontend-mcp-live-layout-fix-recheck-*.json` |

旧 Chrome 重放读取 `mcp-registration-browser.mjs`、`mcp-registration-compat.mjs`，仅把临时脚本的证据前缀改为 `frontend-mcp-live-layout-fix-recheck` 并隔离 Chrome profile；原业务断言与原脚本均保留。临时脚本执行后清理。

真实布局回归全程只有 4 个 GET：两次草稿详情、一次该技能 stats、一次 model-entries。没有远端发现 POST、业务调用、保存或发布。截图 `frontend-mcp-live-layout-fix-red-390.png`、`frontend-mcp-live-layout-fix-green-390.png`、`frontend-mcp-live-layout-fix-green-1440.png` 已实际读图核对：390 下侧栏、顶栏、详情与日期筛选可用；统计表保留既有表内横向滚动。红/绿 JSON 同时保存容器宽度、computed min-width 与越界元素，允许核对真正根因，未以裁剪掩盖页面溢出。

## 基线、冻结与限制

修改前完整备份与 SHA：`frontend-mcp-live-layout-fix-baseline/`、`frontend-mcp-live-layout-fix-baseline-sha256.json`。修改后完整副本与新清单：`frontend-mcp-live-layout-fix-frozen/`、`frontend-mcp-live-layout-fix-sha256.json`。

App.vue 原 SHA：`405d22aa498311456aff612e9bd730b870f609bada51c08c2e51a045b1e41c33`；本修复后：`69b50ee5ce21c8616292e4ddadf1071bc7867d266e64d35d4ea1ecc560da707b`。原“保护 16 项全部不变”属于上一阶段历史状态：本次 App 因授权布局修复变化，其余 15 项逐项 SHA 不变；上一阶段 7 个 MCP 文件和 SkillStatistics 也逐项保持不变。新清单明确记录这一区别，没有重写原报告或原清单。

`frontend-mcp-live-layout-fix-forward.patch` / `frontend-mcp-live-layout-fix-reverse.patch` 各 56,346 字节，包含真实完整上下文和新增只读 Chrome 回归，不依赖无 HEAD 仓库的空 diff。`git apply --reverse --check` forward patch 与 `git apply --check` reverse patch 均退出 0；没有实际应用。

未改 root 的 `tests/smoke-mcp-protocol.mjs`、`web/tests/mcp-registration-live-browser.mjs` 或 root 原红证据；未改 Java/契约、未启停服务、未派子 agent、未提交或发布。此次真实验证只证明已保存草稿页面布局；完整实际 tools/list 结果展开及远端协议链路，等待 root 使用其独立 peer 重新执行 `--browser` 统一复验。
