# R-48 上游断开时的 E2 终态
在原有 DeepSeek 代理受控停机期间，通过正式临时 ApiKey 重放同 requestId，taskId 相同。按六秒间隔轮询，详情和结果在前四次均为 RUNNING，第五次均为 FAILED / MODEL_RESULT_UNKNOWN / retryable=false。代理已恢复，凭据已回收。[完整时间戳](failure.json) · [任务与模型发送数据库行](database.txt)。本样本超过 §7.11.9 的三次轮询门槛。
在 E2 隔离库使用同一冻结源码执行确认 503 和中断恢复两个 JUnit 用例，均通过。[隔离回归](../2026-11-27-R-49/junit.txt)。
