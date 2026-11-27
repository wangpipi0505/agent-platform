# S-4 八场景运行器预检

候选 `57945918`，2026-11-27。八场景处于**开跑受阻**，E2 场景执行数 0。已静态核查入口、公共夹具和 8 个场景脚本，10 个文件全部通过 `node --check`。本阶段未发出 E2 请求。核查原文见 [runner-precheck.txt](runner-precheck.txt)。

| 启动前置 | 冻结实现 | E2 复跑所需 |
| --- | --- | --- |
| 登录与授权 | `scripts/e2e/lib/harness.mjs:34–51` 自签 HS256 开发票。 | 以真实 user-mgmt 和 authority 签发、续发和撤销测试身份的票，并通过授权矩阵走网关。 |
| 模型 | `scripts/e2e/run-all.mjs:37–92` 启动本地模型桩、注册或测试 `demo-provider/demo-model`。 | 引用已配置的 DeepSeek 及其冻结模型引用。 |
| 夹具 | `scripts/e2e/lib/harness.mjs:96–106` 固定 `m53_e2e@127.0.0.1:25432`。`s1-direct-task.mjs` 和 `s2-plan-task.mjs` 直接修改任务状态。 | 通过公开 API 准备专用数据，需直接写库的夹具放独立库和隔离栈。 |
| 结果报告 | 台账要求八场景逐条 PASS，并逐项核对断言数。 | E2 适配完成后输出 `m5-3-e2e-report.json`，按原场景逐条核对。 |

只设置 `E2E_GATEWAY` 和 `E2E_ENGINE` 无法更换上述授权、模型和库夹具。S-2 页面读取失败、S-4 运行器缺口及 R-22 门禁签认均保留在联合测试记录。
