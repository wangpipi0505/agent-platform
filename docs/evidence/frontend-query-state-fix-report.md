# 前端消费者两项 P2 定向修复

日期：2026-10-20。唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。本次修复对象为 `review-frontend-consumers.md` 的两个 P2；本报告是实现者验证和自审记录，独立 reviewer 结论仍由原 reviewer 定向复核。

## 改动与边界

- `web/admin/src/StatisticsPanel.vue`：将编辑表单与 `appliedFilters` 分开。查询时校验、冻结条件并重置第 1 页；翻页读取已冻结条件。快照读取直接使用 snapshotId/page/pageSize，继续绕过未提交日期校验，并回填实际快照条件。重建仍冻结请求体、同条件失败复用请求号、成功清空请求号并接受服务端 pageSize。查询/重建、快照列表分别按序列和当前登录身份丢弃失效响应；ADMIN 权限或 token 变化、卸载时清空状态并失效请求。
- `web/admin/src/ApplicationHistory.vue`：比较请求冻结 appCode/from/to/token，使用独立序列；appCode/from/to/token 变化同步清空结果/错误和 loading。只有当前序列的成功、错误和 finally 可更新状态；卸载时失效比较与日志请求。版本读取保持现有已认证用户接口权限，不新增 EDITOR/ADMIN 限制。
- 新增 `web/tests/frontend-query-state.test.ts`：直接编译真实 SFC setup，用真实 Vue renderer/watch 执行状态行为；API 使用可控异步 fixture，不引入新依赖、不修改产品组件用于测试。9 项与原 48 项 helper/输出测试不同，覆盖组件请求和响应归属。
- 新增 `web/tests/frontend-query-state-browser.mjs`：真实 Chrome、隔离 `.local/chromium-query-state-{red,green}` profile、15175 组件 fixture；所有业务请求由 route 拦截，脚本输出 JSON/截图均明确标 `componentFixtureOnly`。

未修改 App 主接线、Chat/content/tokens、SDK、RunDetail、后端或既有测试；未操作 Git，未启停任何服务。构建会正常生成本工程既有 dist 产物。未读取旧工程代码/配置/存储/浏览器 profile，未对真实服务做新增业务写入或清理数据。

## 红绿证据

先新增测试与 Chrome 探针，再修改产品代码。以下命令均在 `web` 下执行。

1. `npm test -- --run tests/frontend-query-state.test.ts`，修复前：**8 failed / 1 passed**。失败分别为未提交 appCode 混入页2、未提交无效日期阻断 live 翻页、权限撤销后旧统计响应恢复、比较迟到成功/错误恢复、旧 finally 结束新请求 loading、切应用后旧比较恢复、卸载后旧比较恢复。原有重建请求号/pageSize 边界先行通过。完整失败输出：`frontend-query-state-fix-unit-red.log`。
2. `node tests/frontend-query-state-browser.mjs red`，修复前退出 1。两个问题均在同一次真实 Chrome 中采集：页 2 带 `appCode=unsubmitted_app`；选择器为 v1→v1，`staleResultVisible=true`。`frontend-query-state-fix-red.json` 和两张 `frontend-query-state-fix-red-*.png` 保存 wire 和界面，断言按预期失败。
3. 修复后定向单元命令：**9 / 9 passed**。
4. `node tests/frontend-query-state-browser.mjs green`：退出 0，两个同路径断言通过，pageErrors 为空。输出 `frontend-query-state-fix-green.json` 与两张 `frontend-query-state-fix-green-*.png`。已用 view_image 实际复核绿截图：表单仍显示未提交编码时页2总数保持原41分组；v1→v1 下没有旧差异表。界面沿用现有样式，没有新布局。
5. `npm test`：**23 files / 57 tests passed**，即原 22/48 加新增 1/9；输出 `frontend-query-state-fix-unit-green.log`。
6. `npm run build`：退出 0，包含 `vue-tsc --noEmit`、admin/webapp/assistant.js 三构建及已有 SDK 复制步骤。输出 `frontend-query-state-fix-build.log`。admin/webapp 既有大块体积提示仍出现，无编译错误。
7. 重跑既有 `tests/statistics-lifecycle-browser.mjs` 的全部行为，退出 0，24 条 fixture 请求；为避免覆盖既有证据，执行内联源只改输出前缀为 `frontend-query-state-fix-preserved-`、Chrome profile 为 `chromium-query-state-preserved`，并将 import.meta.dirname 的根定位等价替换为当前 web 的上级。断言未改。结果 `frontend-query-state-fix-preserved-browser.json`：snapshotIndependentOfUnsavedFilters、independentPaging、rebuildFilterOnly、readerDenied、sixFilterWire、copyWhitelist、archivedServerFilter、lifecycleJsonBody、adminFailureDialog、mobileChecked、skillVersionAndOwnerLink、failureHasNoFakeZeros 全为 true。生成截图均为同前缀的新证据。

## Chrome 请求对账

| 行为 | 红 | 绿 |
| --- | --- | --- |
| 进入统计 | GET /admin/statistics，page=1,pageSize=20，无 appCode | 相同 |
| 编辑 appCode 但未查询，点页2 | GET /admin/statistics，page=2,appCode=unsubmitted_app，误换 cohort | GET /admin/statistics，page=2，无 appCode，沿原 cohort |
| 点击查询，fixture 新 cohort 总数缩为1 | page=1,appCode=unsubmitted_app | 相同，仍从第一页开始 |
| 发起 v1→v2 比较后把目标改成v1 | GET /admin/apps/source/versions/diff?fromVersion=1&toVersion=2 返回后显示 stale v2 | 请求参数相同，返回结果因选择已变而丢弃，无新比较请求 |

所有日期仍使用原 UTC `[from,to)` 编码。两个独立 total、未知 Token 与来源时刻口径未改。本次 Chrome 数据来自组件 route fixture，只证明组件状态与 wire 归属；真实网关统计/复制/发布等已有证据见原生命周期报告，本次没有将 fixture 扩大为真实 API 验收。

## 自审与定向复核交接

已逐项检查：applied 条件复制在 await 前；翻页不引用编辑日期/应用等维度；已存快照无日期校验；重建 body 白名单仍由原 helper 构造；失败重试同号且 server pageSize 生效；比较成功/错误/finally 同一 current 谓词；选择 A→B→A 仍因序列递增失效；应用变化和卸载不能恢复旧比较；统计权限丢失不能恢复 report 或快照列表。

请原 reviewer 只定向复核上述两处 P2、相关异步失效边界及新证据，不扩大为全包重新设计。实现者未自行改写原评审结论。
