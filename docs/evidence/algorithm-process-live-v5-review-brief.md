# 算法第五轮实际结果限定复核

唯一工程 G:/207/项目/算法重组工具/代码/agent-platform；不得读取或关联相邻旧工程。用户授权持续实施与隔离测试端点验证。只在 root 明确移交实际报告后开始，本文件本身不授权提前连接服务。

## 输入与任务

- 实际报告 `docs/evidence/algorithm-process-live-v5.md`、`algorithm-process-live-v5-summary.json`、`algorithm-process-live-v5-execution.log` 及报告所指的新 raw 目录。
- 原要求 `algorithm-live-validation-brief.md`、`algorithm-process-runner-attempt-scope-brief.md` 和独立 `algorithm-live-attempt-scope-review.md` 六项门禁。
- 已审源码与时间窄修复 `algorithm-process-runner-time-precision-fix.md/.patch/-sha256.json`；此前审查 `review-algorithm-process-runner.md`。旧135项准备检查不能代替此次实际结果。
- 固定制品锁 `.local/algorithm-reviewed-artifact-lock.json`，engine SHA d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7，skill SHA 2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196。runner SHA e2878c7ae3275eaa26e6a7e24a178fae79c9bac38c91d9ecdfbe55d7c348a1e4，oracle SHA 4c63d3c66764250211866f36ea511668639c95834acfa07357ec5cd5b4e69abe。

给出 spec compliance 与 quality 两个独立结论，核对实际五case是否闭合以及报告是否准确，不进行全工程重复审查。覆盖原外部call/fact/binding/attempt/owner/两个snapshot/Job UID不漂移、每场景POST一次、终态采纳与唯一逻辑提交、ACK本地恢复no2与原外部代区分、重检后新PG采集无新业务。可信Failed须非重试终态；Running只本地取消而外部仍UNKNOWN；offload须真实MinIO READY和精确字节/SHA，不以状态名代替实际对象证据。校核实际事件seq与微秒时序、fixture请求账及独有JVM/端口清理证据。

## 只读授权与限制

仅可读取本工程 `.env` 内PG/MinIO凭据用于受控只读客户端，不输出凭据；数据库严格取此次summary声明且前缀为 `agent_platform_algorithm_live_` 的唯一新库，不能连接main、此前四轮或其他业务库。PG事务显式 `BEGIN READ ONLY`，statement_timeout=5000ms，客户端进程timeout=10000ms；只SELECT核对本轮记录，不seed/DDL/更新/建库/重检业务。若需要实际对象复核，仅GET本轮READY记录指向的自有MinIO对象并核字节/SHA，有限上界，不list其他对象或写/删。按报告记录的PID、创建时间、精确JAR与端口做只读归属/已退出核对，不停止或接管任何进程。

不重跑135项、不启动JVM/fixture、不发新业务HTTP、不重试算法、不连接真实K8s、不打包/Maven、不改生产/测试源码/旧证据/target。只新增独立复核证据并追加 `docs/evidence/review-algorithm-process-runner.md`。保留四轮原失败记录；即使本轮五case成功，也仅是隔离K8s端点配真实PG/Redis/MinIO的有限链路，不签生产K8s/容器/RBAC/IAM、完整G2或容量验收。
