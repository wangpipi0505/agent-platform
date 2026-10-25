# 算法真实进程首跑：失败，待 runner 编码修复

2026-10-20 10:50:57—10:51:07（Asia/Shanghai），按 root 正式移交的固定锁运行已审 runner 原源码（SHA `a29c1e0d0a0e6c68bed1238e9655ea3843de1eb590599468ab205f8e8fe891c4`）：`node tests/smoke-algorithm-processes.mjs --execute-reviewed .local/algorithm-reviewed-artifact-lock.json`，实际退出1。完整原始执行输出为 `algorithm-process-live-execution.log`，本轮原始目录为 `algorithm-process-runner/live-bcc9285991eb/`。本报告不覆盖其中任何错误或清理记录。

固定制品目录 `.local/artifacts/algorithm-protocol-20261020-1047`，engine SHA `d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7`，skill SHA `2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196`。唯一新库 `agent_platform_algorithm_live_bcc9285991eb` 已创建并保留。第一个 skill JVM 实际创建，记录 PID52436、端口54504、创建时间 `2026-10-20T02:51:00.8469670Z`；engine JVM 未创建。原结果中的 `actualJvms=false` 表示尚未完成启动两进程，不应解释成未创建任何 JVM。

五场景均未开始业务执行。complete 场景仅到 preparing，fixture 的 POST/Job GET/Pod list/log GET 均0，拒绝上游计数0。没有 run/call/attempt/UID 或逻辑提交可报告，也没有 offload READY/下载结果；不能把未执行项列作通过。仅对本轮新库做显式只读查询：skill/执行事实/绑定各0行，Flyway 300—305已成功记录；原 skill stdout 当时进行到306，进程退出导致该迁移未登记完成。查询原输出保存在 `algorithm-process-live-first-pg-readonly.json`，未接触旧库或运行库，未 drop 新库。

根因是 Windows PowerShell 默认 stdout 编码与 runner 的 `spawnSync encoding:'utf8'` 不一致。含中文的本工程 JAR 路径被解码为替换字符，精确 `-jar` 参数校验正确拒绝了损坏的观察值。原 finally 通过同一个解码路径再次拒绝停止该记录 child，且最终记录在当时仍发现 PID52436，因此 AggregateError 同时保留原错误、自有 PID 清理错误和最终记录错误。

后续仅对记录 PID52436/端口54504做只读核验，两者已不存在；root 亦独立确认无残留。诊断 `algorithm-process-live-encoding-diagnostic.json` 实际将同一无秘密中文路径经 PowerShell stdout 往返：默认编码 exactPath=false，显式 `[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)` 后 exactPath=true；包含后续 pid=null、listenerPids=[]。有限 fixture 已按原 finally 关闭。后续无残留不能改写原 finally 的清理失败，也未证明进程是由该失败清理逻辑停止。

root 已授权只修 runner 的 PowerShell UTF8 stdout 与最终 fact 原代直接断言，并要求独立新 baseline/patch/manifest/报告；本首跑源码及准备交付、全部失败证据保持。暂不重跑真实入口，待修复独立复核后 root 再次移交。未改生产/POM/target/契约/原 fixture，未 package、未操作六主服务、未派 agent。
