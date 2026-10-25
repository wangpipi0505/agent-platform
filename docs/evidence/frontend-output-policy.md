# 输出策略的前端消费者验证

范围：本独立工程WebApp、嵌入SDK及管理运行详情；不改后端策略或原契约。

`PASS_WITH_FLAG`的原文本保留，格式失败原因独立作为可见提示。`outputWarning`/`finalAnswer`不把模型路由或usage混入业务正文。恢复历史保留各轮自己的提示。管理运行详情同样显示顶层结果的格式失败提示。

流式文本是临时观察值。收到RUN_FINISHED或权威终态快照时，用最终结果完整替换气泡，不再仅在空气泡时补值；同时取消排队的animation frame、清空临时缓冲并忽略终态后的chunk，防止无效输出重试的片段附在修正结果后面。空字符串也是合法最终结果，不能用真值判断略过它。SDK采用同一最终展示规则，Markdown仍走消毒器，失败原因使用textContent/Vue插值。

## 实际执行

- `.local/frontend-output-policy-red.log`：新增两项行为检查先失败，缺少输出提示及最终展示函数；既有业务文本隔离正例通过。
- `.local/frontend-output-policy-green.log`：3项全绿。
- `.local/frontend-output-policy-all.log`：04:49:24，21文件/41项全绿。之后继续接SDK与提示样式，并由下面的真实浏览器和三端构建验证；后续页面增量结束后还需统一整轮。
- `.local/frontend-output-policy-final-build.log`：vue-tsc及admin/WebApp/assistant三个产物构建退出0；原有bundle体积提示仍保留。
- `node tests/output-policy-browser.mjs`：本机Chrome真实操作WebApp组件和原生SDK，各发两轮。先注入无效CHUNK，再返回修正终态，断言无效片段已清除；下一轮返回PASS_WITH_FLAG并核提示可见。页面不存真实票据/长密钥。所有请求由Playwright路由fixture提供，**仅组件集成测试，不冒充真实后端或供应商验收**。
- 两种页面均实际测量提示文字与背景WCAG对比度≥4.5:1；截图已逐张查看，更新为现有平台token的深色提示。

截图：`frontend-output-policy-webapp.png`、`frontend-output-policy-sdk.png`。测试页面与脚本仅位于`web/tests/`，临时独立Vite使用15175/本工程目录。小结果存于`.local/frontend-output-policy-browser.json`。

文件：`web/shared/content.ts`、`Chat.vue`、`tokens.css`、`web/assistant.js/src/main.ts`、`web/admin/src/RunDetail.vue`及output测试/fixture。独立复核和真实输出策略后端联通仍须与本轮G1评审结果合并核验。

## 追加：真实网关与正式应用

G1两个Schema P2已独立修复关闭。主任务通过真实候选评测及发布创建`smoke_output_pass_with_flag_4d0ffb0927` v1，随后实际执行`node tests/output-policy-live-browser.mjs`：WebApp开发登录→填发布输入→运行→显示格式失败提示与原文；刷新并重新登录后，恢复的历史仍带提示。另从真实`assistant.js`构建产物加载SDK，开发出票函数调用本工程网关短用户票据，运行后同样显示提示。两页面localStorage均为空。

该脚本没有HTTP路由拦截；网关/引擎/模型服务及PG为真实独立服务，模型供应商仍是故意返回无效JSON的协议fixture。SDK使用开发身份，不能替代真实宿主后端短票据全链验收。结果`.local/frontend-output-policy-live.json`、截图`frontend-output-policy-live-webapp.png`及`frontend-output-policy-live-sdk.png`已实际查看。前端新统计/对照评审另有两项异步状态问题正在修复，不因本次输出UI联通而报全部前端通过。
