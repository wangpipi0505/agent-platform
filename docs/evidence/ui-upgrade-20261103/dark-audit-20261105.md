# AP 暗夜流光全量视觉验收——2026-11-05

对 agent-platform 管理端 36 个路由做真实浏览器逐页复核，修复浅色残留、对比度、最小字号与
两处布局/深链缺陷。全部页面通过。

## 一、方法与判定规则

| 项 | 值 |
| --- | --- |
| 浏览器 | Playwright + Chromium 1228 |
| 视口 | 1600×900 全量；1920×1080、1366×768 抽查 |
| 路由 | `.tmp-audit/routes-ap.txt` 共 36 条（应用工作区各 tab、能力目录、运行、技能、集成、模型、治理） |
| 切页方式 | 路由是 `createWebHashHistory()`，用 `location.hash` 切换 |
| 判定 | 同 OR：大面积浅底、对比度 <4.5、可见文字 <13px |

## 二、结果

| 指标 | 结果 |
| --- | --- |
| 路由数 | 36 |
| 浅色块 | 36/36 页 = 0（首轮每页 1 处，来自 `.mock-banner`） |
| 对比度不达标 | 36/36 页 = 0 |
| < 13px 可见文字 | 36/36 页 = 0 |
| 控制台错误 | 仅 favicon 404 |

## 三、本轮改动（15 个文件，未提交）

- `web/shared/tokens.css`
  - 新增 `--el-font-size-extra-small/small`、`--el-tag-font-size` 及 `.el-tag/.el-radio-button--small/
    .el-button--small` 兜底（EP 组件内写死 12px）。
  - `.nav small` 显式 `--text-meta`（`<small>` 默认 smaller = 12.5px）。
  - `.nav`/`.nav a` 使用 `--nav-text*`、13px；窄屏折叠导航断点 1439 → 1200（1366 下中文菜单不再逐字换行）。
- `web/admin/src/SystemStatus.vue`：全部浅色 hex 改语义令牌（`--st-ok/--st-fail/--st-suspend` + `*-text`）；
  新增 `latencyText()`（无数字显示 `—`，修裸“ms”）；`ttlSeconds` 为空时不渲染“结果缓存 … 秒”。
- `web/admin/src/RunDetail.vue`：诊断横幅/卡片背景边框、严重度与元信息改令牌。
- `web/admin/src/AppShell.vue`：`.mock-banner` `#fff7e6` → `rgba(251,191,36,.1)` + `--st-suspend-text`。
- `web/admin/src/ResourceDetail.vue`：`.service-url`/`.service-meta` 改 `--surface-2/--muted/--ink`。
- `web/admin/src/router.ts`：`beforeEach` 对 `reason === "session"` 直接放行（由 PortalShell 的 token 门承接），
  修深链 `#/statistics`、`#/system`、`#/apps` 冷启动被判 404。
- `web/admin/src/pages/NotFoundPage.vue`：`.login` → `.notfound`（原 100vh 在壳层内溢出，把“返回工作台”裁出视口）。
- `web/admin/src/PageHeader.vue`：未声明 eyebrow 时不再回退成固定“平台管理”，沿用路由访问表分组。
- 字号：10 个文件的 10/11/12px 提到 13px。

## 四、复核命令与证据

```bash
cd 代码/_worktrees/upgrade-20261103/agent-platform/web
npx vue-tsc --noEmit -p tsconfig.json     # exit 0
node .tmp-audit/crawl.cjs ap               # 36 页 light=0 contrast=0 tiny=0
```

- 逐页截图：`.tmp-audit/ap/*.png`；汇总 `.tmp-audit/ap/report.json`
- 深链/关键页复核：`.tmp-audit/deep_statistics.png`、`deep_system.png`、`deep_runs_run_101.png`、
  `deep_capability_packages.png`
- 三档尺寸：`.tmp-audit/size-ap-*.png`、`recheck-ap-*-1366.png`

## 五、三档尺寸复核

`crawl.cjs` 新增 `W`/`H`/`OUT` 环境变量；按方案 §6 的桌面档位重跑：

| 视口 | 页数 | light | contrast | tiny | 输出 |
| --- | --- | --- | --- | --- | --- |
| 1600×900 | 36 | 0 | 0 | 0 | `.tmp-audit/ap/` |
| 1440×900 | 36 | 0 | 0 | 0 | `.tmp-audit/ap-1440/` |
| 1366×768 | 36 | 0 | 0 | 0 | `.tmp-audit/ap-1366/` |

1920×1080 按页抽查（`.tmp-audit/size-ap-*.png`），无浅色块与遮挡；1366 下顶栏与操作区不换行。

## 六、已知限制

1. `/capability-packages` 的演示数据缺口已补：`web/shared/mock-transport.ts` 增加
   `GET /admin/capability-packages`（空列表枚举）与详情 404，`useCapabilityPackageClient()` 在
   `import.meta.env.DEV && VITE_MOCK` 时懒加载 mock transport，真实环境仍走 `axiosTransport`；
   页面由 500 变为合法空态（“没有匹配的能力包；编辑者可登记完整声明。”）。
2. 画布页（`/apps/:code/orchestration/flow`）导航按既有 `canvas-mode` 折叠为单字短标（`::before` + `data-short`），
   本轮只保证不再出现 0px 文字误判，未重做该模式视觉。
3. 爬虫在切页末尾偶发截到上一页画面（工具时序），相关页已用独立深链复核。
