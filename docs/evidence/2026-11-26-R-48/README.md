# R-48 模型失败终态开发自测

开发库的真实 HTTP 链覆盖 gateway、authority、app、engine、model、PostgreSQL 和 Redis。供应商故障由回环测试端点注入。隔离样本的两次并发提交共用一个 requestId，各自收到同一个 taskId。

| 构建 | 模型端事实 | 任务详情与结果 |
| --- | --- | --- |
| 修改前 | 已登记 SYS-MODEL-503 | [三次均 RUNNING](red-polls.json) |
| 修改后 | 延迟 10.5 秒返回 503，调用日志记录 FAILED | [第三次读取均 FAILED](green-polls.json)，两处均返回 reason=SYS-MODEL-503、retryable=false |

模型服务在登记调用失败后发送终止帧。引擎复核当前授权与租约，在一个数据库事务中提交调用、尝试、节点、运行及事件。任务分发器沿原入口步骤投影终态。未收到确认的发送在原节点租约有效期内等待，过期后以 MODEL_RESULT_UNKNOWN 收口，禁止重发。

在独立开发栈配置能返回 503 的测试应用和已获提交权限的接入票，执行 `node scripts/acceptance/verify-model-failure.mjs`。环境变量和时序见脚本。原始 E2 对照在 [R-23 宿主超时记录](../2026-11-26-R-23/e2-host-timeout.json)。

附加的[引擎中断回归](interrupted-worker.json)在模型已记 SENDING 后终止开发引擎并重启。恢复代次为 2，运行和节点均 FAILED，原因是 MODEL_RESULT_UNKNOWN。调用事实保留 UNKNOWN，实际只有一次 SDK 发送和一条模型日志。首次模型请求的 I/O 上限为 15 秒，25 秒的节点和调度租约给引擎留下在原 60 秒委托内恢复的时间。恢复仍须持有有效委托。
