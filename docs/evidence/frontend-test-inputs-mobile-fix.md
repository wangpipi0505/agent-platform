# 真实FLOW调试手机布局最小修复

2026-10-20。真实联调暴露390px页面宽528的失败，root读图后明确允许：仅DebugPanel scoped mobile CSS，使运行ID/动作折行、只读FlowDesigner单列且实际画布/两节点可见，不改FlowDesigner核心、全局tokens、后端、SDK或events。此次源码增量严格限定此边界；原8文件manifest及历史23项报告保留。

## 原因与修复

真实FLOW运行后会挂载只读FlowDesigner。既有属性列为`minmax(22rem,35%)`，窄屏主内容区不足22rem时压缩画布并撑宽页面；运行ID和动作同行另有溢出。此前受控手机场景为CHAT或无运行，未覆盖该只读画布状态。

`web/debug-panel/DebugPanel.vue`仅追加≤600px的scoped规则：运行标题块纵向排列，ID允许换行、动作允许折行；调试内canvas-layout采用单列，canvas/properties-panel保留min-width0、max-width100%。没有用隐藏页面溢出替代真实可见性，没有修改FlowDesigner的X6行为或模板。既有ResizeObserver按真实新宽度resize/zoomToFit。

红证据：`frontend-test-inputs-live-flow-mobile-red.json/.log/.png`。绿证据：真实live脚本验证documentWidth390、canvas244、两个X6节点各约75.37×27.72且全部位于画布边界内；已读`frontend-test-inputs-live-flow-canvas-390.png`与完整`frontend-test-inputs-live-flow-390.png`，开始和完成节点及连线真实可见。提升弹窗和ACCEPTED报告手机图均已读图。

## 当前修复回归

| 执行 | 新结果与证据 |
| --- | --- |
| `node web/tests/named-inputs-live-browser.mjs --engine-ready` | 真实API9项检查通过，含FLOW390实际节点几何与完整原评测ACCEPTED；`frontend-test-inputs-live.json`、`frontend-test-inputs-live-browser.log`。 |
| `node web/tests/named-inputs-mobile-regression.mjs` | 原受控18+5共23项原样重放通过，pageErrors为空；`frontend-test-inputs-mobile-recheck-regression.json`、`frontend-test-inputs-mobile-recheck-evaluation.json`、`frontend-test-inputs-mobile-recheck.log`。runner只更换证据文件前缀，原两份冻结测试源码、报告和截图不覆盖；临时执行文件finally清理。 |
| `npm test` | 25文件83项全部通过；`frontend-test-inputs-mobile-fix-unit.log`。 |
| `npm run build` | vue-tsc、Admin、WebApp、SDK及复制脚本均退出0；`frontend-test-inputs-mobile-fix-build.log`。既有chunk体积提示仍如实保留。 |

原DebugPanel SHA=`81281d6a57decdd8f7219a43a2645b0b2d4d6a6ef96a5e55a77c426cfe98380b`；本修复SHA=`577e27f3c62dd574a997034d3d9909683c6082f897555b47298a7fca1f56351e`。旧manifest仍为原证据，当前8文件中其余7项与旧摘要相同。增量源码、live脚本和回归runner摘要见独立`frontend-test-inputs-mobile-fix-sha256.json`。无提交、无发布、无服务启停。独立复核由root另行安排，本报告不冒称已经过新增人审。
