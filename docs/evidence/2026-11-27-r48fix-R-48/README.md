# R-48 E2 模型失败终态复验

在 E2 对同一 E2 专用 DeepSeek 代理实施停用、恢复，使用真实 user-mgmt 和 authority-service 签发的集成凭据提交同应用任务。提交及同号重试后按原口径每隔 6 秒获取详情与结果。

| 版本 | 详情、结果首次同时失败 | 库内 NODE_FAILED 距 run 起跑 | 失败码 |
| --- | --- | --- | --- |
| `e122a508` | 第 5 轮 | 26.491 秒 | MODEL_RESULT_UNKNOWN |
| `3350ac4e` · 首验 | 第 1 轮 | 5.578 秒 | SYS-MODEL-503 |
| `3350ac4e` · 三轮复验 | 第 1–3 轮均为 FAILED | 3.112 秒 | SYS-MODEL-503 |

失败均经详情和结果以 HTTP 200 返回，状态 FAILED，retryable=false。两轮修复版各记录一次 engine 到 model-gateway 的调用，无供应商发送流水和配额预留。同号重试复用任务。模型发送前的 tokenizer 不可用或超时现在通过已确认的 SSE 失败帧上报。回归另外覆盖确实缺失回执时的 MODEL_RESULT_UNKNOWN 恢复，并验证无重发。

- [旧版轮询](baseline.json) · [旧版库内时序](baseline-db.txt)
- [修复版首验轮询](fixed.json) · [首验库内时序](fixed-db.txt)
- [三轮连续采样](repeat.json) · [复验库内时序](repeat-db.txt)
- [定向回归](targeted-tests.txt) · [分词超时与连接释放](tokenizer-timeout.json) · [真实宿主与下载回归](../2026-11-27-r48fix-R-23/README.md)
- [远端源码锚点](remote.txt) · [切换前备份](prebackup.txt) · [八容器及入口](final-health.txt) · [监控目标](prometheus.txt) · [清理](cleanup.txt)

冻结标签 `r48-20261127-failure`，源码 `3350ac4eee3518d238baa1761c6c72a004d426ce`。R-48 开发及 E2 判据达成，移交待验收。
