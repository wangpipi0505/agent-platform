# R-48 上游不可达时的 E2 轮询

在受控窗口停用已配置的 DeepSeek 测试代理，恢复后核实其运行状态，回收测试凭据。六秒间隔的前四轮详情及结果均为 RUNNING，第五轮为 FAILED / MODEL_RESULT_UNKNOWN，retryable=false。同一 requestId 重放复用任务。引擎仅记录一次发送，模型调用表无关联行。从运行创建到 NODE_FAILED 为 26.254 秒，超过三次轮询门槛。[现场回执](failure.json) · [事件时序](database.txt) · [确定 503 的隔离回归](../2026-11-27-portal2-R-49/junit.txt)。
