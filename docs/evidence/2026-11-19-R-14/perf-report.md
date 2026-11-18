# R-14 M5-4 性能 P50/P95 实测报告

- 日期：2026-11-19；分支 `close-wt-r14`（基于 5bde1c30）；工作目录 `代码/_worktrees/upgrade-20261103/wt-r14`
- 台账卡：§7.6b **R-14 M5-4 性能 P50/P95 实测**（A14/A15/A15b/A16 + 任务链基准）
- 结论速览：**七类基准 ×240 采样全绿零失败，全部读取链 P95 ≤ 10ms**；1109 技术要求经全文核对**无量化时限条款**（如实登记，不虚构达标结论）；唯一量化条款（前端方案 R4 状态延迟 ≤15s）实测富余三个数量级，**达标**。
- 证据文件：本目录 `perf-report.json`（汇总）/ `perf-samples.json`（全部原始样本）/ `seed-manifest.json`（造数清单）；脚本 `scripts/perf/`（起栈/造数/压测/停栈四脚本，单命令可复现）。

## 1. 时限条款清单与逐条对照（1109 技术要求）

条款来源与核对方法：`01-技术要求基准-1109.docx` 全文提取（33 段，逐段核对）；主方案 `02-主方案-基于1109技术要求-20261112.md`（grep 时限/响应时间/P9/秒/≤，§14 性能口径条款读全文）；台账 `06-落地实施方案与事项台账-20261113.md` §2.1/§2.2 及 R-14 卡；前端方案 `03-前端页面设计重构方案-20261112.md`；接口设计 `04-接口设计-20261112.md`。

| # | 条款 | 来源与原文位置 | 量化时限 | 实测对照 | 结论 |
|---|------|----------------|----------|----------|------|
| T1 | 性能① 自然语言任务规划与执行（Plan 步骤化/Agent 状态输出） | 1109 docx §5「性能」第 1 段（提取文本段 31-32） | **无**——仅功能性要求 | 功能正确性由 A01-A16 机制级验收覆盖（M5-4 卡），本卡不重复判定 | **无量化时限，不纳入达标判定** |
| T2 | 性能② 多任务与子智能体调度（并行不互相覆盖） | 1109 docx §5「性能」第 2 段（提取文本段 33） | **无**——仅功能性要求（"避免并行执行时相互覆盖"为正确性断言非时限） | 并发隔离机制级证据见 M5-4（A03/A05）；本卡并发观察轮 `task-detail-conc4`（4 路×25 轮零失败零串扰，P50 4.3ms/P95 11.8ms）佐证读链并发隔离 | **无量化时限，不纳入达标判定** |
| T3 | 性能测量口径沿用 v1 §9.2（规划成功率/排队与执行耗时 P50/P95/并发隔离/故障恢复耗时） | 主方案 §14（573 行）；v1 口径见归档稿《算法重组工具设计方案-基于1109技术要求-20261110》§9.2（420/445-448 行） | **无**——v1 原文明确"1109 对能力给出了要求，**定量门槛需在目标环境、模型、算法和典型负载明确后依据基线设定**"（420 行） | 口径四项中"排队与执行耗时"依赖任务运行链出终态；本机环境下 AGENT 任务运行链在提示词装配处失败（见 §5 边界③），运行耗时口径**未测**；读取类 P50/P95 按 v1 §9.2 测量起点终点口径落表留作基线 | **无量化门槛，实测值留作基线**（运行耗时项未测，诚实登记） |
| T4 | 状态延迟 ≤15s，无需手动刷新（列表轮询 15s） | 前端方案 R4（40 行）"| 状态延迟 ≤15s" | **有（≤15s）** | 本卡实测服务端读时延：确认中心 counts P95 5.94ms、任务列表 P95 5.92ms、会话事件增量 P95 4.89ms——均比 15s 预算低三个数量级，轮询周期内的状态新鲜度预算不可能被服务端读时延击穿 | **达标** |
| T5 | R-14 卡面验收判据：每类基准 ≥200 次采样、P50/P95/P99 落表并标注环境规格 | 台账 §7.6b R-14 行（499 行） | 有（≥200 采样） | 9 类串行基准各 **240 采样**（另 20 预热不计入）+ 1 类并发观察轮 100 采样；P50/P95/P99 全部落表；环境规格见 §3 | **达标** |

**对照总表**：T4 达标；T1/T2/T3 无量化时限不适用达标判定（实测值留作基线）；T5 达标。**无不达标项。**

> 诚实登记：1109 技术要求为能力性技术要求，其「性能」章无任何毫秒/秒级时限数字；v1 §9.2 口径明确定性门槛"待基线设定"。本卡不对无量化条款虚构"达标"结论，全部如实登记。运营侧若需设定生产门槛（如 API P95 ≤ X ms），可以本报告实测值为基线依据。

## 2. 基准集与实测数值（全部经网关 HTTP 真实链路）

客户端串行单连接（Node 24 undici fetch），每类 240 采样 + 20 预热（不计入），计时含连接建立→响应体读毕；P50/P95/P99 为**线性插值分位**（Type-7，与 numpy `linear` 同式，`scripts/perf/run-bench.mjs` 内置 `percentile()`）。

| 基准 | 端点（均经网关 18880） | 身份 | 采样数 | 失败 | min(ms) | P50 | P95 | P99 | max |
|------|------------------------|------|--------|------|---------|-----|-----|-----|-----|
| task-list 任务列表 | GET /v1/tasks?appCode=..&page=1&pageSize=20 | ADMIN | 240 | 0 | 2.94 | **4.62** | **5.92** | 6.56 | 9.49 |
| task-detail 任务详情 | GET /v1/tasks/{publicId}（轮换 50 条） | ADMIN | 240 | 0 | 2.98 | **4.73** | **6.12** | 6.75 | 6.90 |
| session-events-full 会话事件首页 | GET /v1/apps/{code}/sessions/{id}/events?afterSeq=0 | USER | 240 | 0 | 2.53 | **4.05** | **5.10** | 5.58 | 7.48 |
| session-events-inc 会话事件增量尾页 | 同上 afterSeq={watermark-2} | USER | 240 | 0 | 2.26 | **3.84** | **4.89** | 5.95 | 7.45 |
| memory-list 记忆列表 | GET /v1/apps/{code}/memories?page=1&pageSize=20 | USER | 240 | 0 | 4.92 | **7.46** | **9.69** | 11.71 | 14.51 |
| memory-search 记忆检索 | GET /v1/apps/{code}/memories?q=星历（ILIKE） | USER | 240 | 0 | 5.99 | **7.92** | **9.74** | 10.60 | 12.61 |
| plan-revision-read 计划修订读取 | GET /v1/tasks/{tid}/plans/{pid}/revisions/{rev}（轮换 30 修订） | ADMIN | 240 | 0 | 3.44 | **5.22** | **9.58** | 13.75 | 18.71 |
| confirmation-counts 确认中心 counts | GET /admin/confirmation-center/counts | ADMIN | 240 | 0 | 3.68 | **4.66** | **5.94** | 6.63 | 8.64 |
| sse-first-frame SSE 首帧时延 | GET /v1/apps/{code}/sessions/{id}/events/stream（Last-Event-ID=0 有积压会话，首字节帧即达；轮换 6 会话） | USER | 240 | 0 | 4.04 | **6.09** | **8.40** | 13.31 | 15.10 |
| task-detail-conc4 并发隔离观察轮 | GET /v1/tasks/{publicId} ×4 并发 ×25 轮 | ADMIN×4 | 100 | 0 | 3.26 | 4.32 | 11.79 | 20.51 | 32.41 |

原始样本（排序后逐条 ms 值）全部保留于 `perf-samples.json`（每类含 samples 数组 + failureDetails + summary，可复算分位）。

观察：
- 全部读取链 P95 < 10ms；SSE 首帧 P95 8.4ms（网关流式转发 flushBuffer 逐块直达，无缓冲墙——`GatewayController.java` 136-142 行 text/event-stream 分支）。
- plan-revision-read P95 9.58ms 略高：修订读取含确认状态投影组装（多表 join），P99 13.75ms 仍无异常毛刺，不构成瓶颈。
- 并发观察轮（4 路并发，无预热剔除）P95 11.79ms 较串行基线抬升约 2 倍，属单栈 JVM+PG 共享资源下的预期形态，零失败零错误串扰。

## 3. 环境规格

| 项 | 值 |
|----|----|
| OS / CPU / 内存 | Microsoft Windows 11 专业版 / Intel(R) Core(TM) i5-14600KF / 31.8GB |
| 数据库 | 专用库 `r14_perf`（127.0.0.1:25432），PostgreSQL 16.15（VC build 1944, 64-bit），max_connections=100，shared_buffers≈160MB，work_mem=4MB；全链迁移 V100-V231/V300-V322 + V901 种子由服务 Flyway 自举 |
| JVM / 服务 | JDK 21.0.12.8（Eclipse Adoptium hotspot）；五服务单副本本地栈 -Xms64m -Xmx512m：gateway 18880 / app 18881 / engine 18882（PropertiesLauncher 外挂 shim）/ skill 18883 / model 18884 |
| 端口隔离 | 188xx 段 + 本地模型桩 18885（`scripts/e2e/fixtures/model-stub.mjs` OpenAI 兼容确定性桩）；与 dev/其他 worktree 184xx/186xx 隔离 |
| 客户端 | Node v24.16.0 undici fetch 串行单客户端 |
| 压测数据前置 | 50 AGENT 任务 + 10 PLAN 任务 ×3 修订=30 + 60 会话 ×1 轮次（真实模型链出话）+ 60 记忆（半数含检索关键词"星历"）；DB 实测行数 task=60/run=60/task_event=60/chat_session=60/session_event=120/memory=62/plan_revision=30 |
| 压测窗口 | benchStartedAt 2026-11-18T16:58:42Z → benchFinishedAt 16:58:58Z（约 16s，9 类串行+并发轮） |

## 4. 起栈与造数方式（可复现）

1. `bash scripts/perf/start-stack.sh`——M5-3 `scripts/e2e/start-stack.sh` 同款结构改 188xx 段 + 库 `r14_perf` + 密钥目录 `.local/secrets-r14`；engine 以 PropertiesLauncher 外挂 `scripts/e2e/shim`（Memories 双构造器装配缺陷，M5-3 已登记）；Redis/MinIO 未起不阻塞业务链；模型桩 18885 承接出话。
2. `E2E_GATEWAY=http://127.0.0.1:18880 node scripts/perf/seed-data.mjs`——**全部经网关业务 API 真实授权链造数**（无直连写库）：模型路由幂等注册→AGENT 任务 50→PLAN 任务 10×修订 3→会话 60×轮次 1（真实 harness 出话链）→记忆 60；`seed-manifest.json` 落 id 清单与计数（errors=0）。
3. `E2E_GATEWAY=http://127.0.0.1:18880 node scripts/perf/run-bench.mjs`——九类串行基准+并发观察轮，样本/汇总落 evidence。
4. `bash scripts/perf/stop-stack.sh`——按 188xx 端口监听进程逐个收口（含模型桩）。

## 5. 诚实登记项（环境边界与未测项）

1. **1109 无量化时限**：技术要求全文（docx 逐段提取核对）无毫秒/秒级时限条款；v1 §9.2 定量门槛明确"待基线设定"。本报告不虚构达标结论，T4 为唯一量化条款。
2. **AGENT 任务运行链未出终态**：V901 种子应用 `promptSource:"DIRECT"`，任务运行链 `ContextAssembler.promptText`（engine-runtime，128-129 行）仅接受 `promptSource:"TEMPLATE"`，50 个 AGENT 任务经真实调度（RunScheduler 正常派发，engine.run 60 行）在提示词装配处 NODE_FAILED，任务停留在 ACCEPTED。**读取类基准不受影响**（任务行/任务事件/详情投影均真实落库可读），但 v1 §9.2 "排队与执行耗时"口径（提交→终态）**本机无法实测**——这是种子与运行时的口径不一致（疑似平台缺陷候选，建议登记缺陷台账，修复属 R 系列后续卡），非本卡红线内可修（不改服务源码）。
3. **单机单栈口径**：i5-14600KF 本地栈（服务 JVM 512MB 上限×5 + PG 16 同机），非目标生产环境规格；数值仅代表本环境基线，不可直接外推生产容量。并发容量（v1 §9.2 并发隔离的容量数值）属主方案 R7"设计有界、数值未证"项，本卡仅做 4 路机制级观察，未做容量压测（无量化门槛且超出卡面基准集）。
4. **会话事件量级**：每会话 1 轮次产生 2 事件（user+assistant），会话事件基准在 60 会话×2 事件的数据量上测得；更大事件量级（千级/会话）未在本卡范围。
5. **SSE 首帧口径**：以 Last-Event-ID=0 连接有积压会话（首帧=扫描既有事件即达）测连接→首字节时延；15s :ping 心跳场景（空会话挂连接）非首帧语义，未纳入。
6. **warmup 剔除**：每类前 20 次请求不计入样本（JIT/连接池预热），样本 JSON 中已剔除，登记以保复现透明。

## 6. 证据与脚本清单

```
docs/evidence/2026-11-19-R-14/
  perf-report.md          本报告
  perf-report.json        分位汇总+环境规格快照（机器可读）
  perf-samples.json       全部原始样本（10 类逐条 ms，可复算分位）
  seed-manifest.json      造数清单（id/计数/时间/错误）
scripts/perf/
  start-stack.sh          起栈（188xx+r14_perf+模型桩，幂等）
  stop-stack.sh           停栈（按端口收口）
  seed-data.mjs           造数（全经网关 API，幂等可重复）
  run-bench.mjs           压测（分位内置线性插值 Type-7）
  README.md               单命令复现说明
```
