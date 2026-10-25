# 两个实际引擎进程的流观察验证

2026-10-20，在独立新工程执行；原详设和契约未改，未操作相邻实现。

## 输入与部署

- `review-engine-streaming.md` 两项 P2 已独立关闭；最终 engine 模块 134 项通过之后才部署主进程。
- 主引擎 PID 60608、端口 18082；测试副本 PID 59244、端口 18086。副本使用独立物理 JAR，和主构建 SHA256 一致：`6fb1bf1420ee1b77efe61683f596ea4e14ba8bb43c739fee0292393cd25c0fcf`。
- 共用本工程运行库 `agent_platform_dev_v12` 和专用 Redis 7.2.16。副本身份、绝对 JAR 路径、hash 存在 `.local/engine-secondary.json`；试验完成后，校验路径、PID 命令行和端口参数才停止副本，没有留下第二个调度器。
- 模型是测试脚本在 28413 独占的定时 HTTP/SSE 协议 fixture，结束时关闭；不代表实际供应商能力或吞吐。

## 执行与断言

命令：`node tests/smoke-engine-stream-replicas.mjs prepare`，只在主实例存活时先取得持久 lease owner；启动副本后执行 `node tests/smoke-engine-stream-replicas.mjs verify`。

运行 `r_1db8aff93e8944839d7ea3eebb130f73`，应用 `smoke_stream_131b7df725`。STEP 挂起后，两个端口各开真实认证 SSE，并等到两侧 `chunkAvailable=true` 后才放行。PG 保存的实际执行 owner 与事先校准的主实例一致，因此 18086 是独立远端观察进程。

两侧都恰好收到 `跨进程`、`观察完成` 两片文本，无事实 ID/seq；两侧事实 ID 都为 1–9，与直接读取的 PG run_event 序列逐项一致，最终状态 SUCCEEDED、水位 9、答案 `跨进程观察完成`。恢复端携带 Last-Event-ID=7 后仅得到 8、9，无临时片段重放。正式运行的模型 HTTP 请求增量为 1；provider 连接测试另有 1 次，不混入运行请求计数。

脚本先前两次未通过来自测试准备与计数：漏执行 provider.test 导致冻结依赖拒绝；随后把 provider 探测请求混入运行请求。修正脚本后重建独立测试应用并完整重跑，未放松业务门禁或改业务源码。失败创建的 fixture 记录保留，不删除审计证据。

最终原始证据：`.local/engine-stream-replicas-live.log` 和 `.local/engine-stream-replicas-live.json`，退出码 0。

## 边界

此结果验证实际双 JVM 的 Redis 片段和 PG 事实补发。未进行断 Redis 的双进程重连、进程 kill、5000 SSE/500 运行压测，不替代公平调度、全套内存闸或 T15 完整验收。前端实际长任务恢复另见后续专题报告。
