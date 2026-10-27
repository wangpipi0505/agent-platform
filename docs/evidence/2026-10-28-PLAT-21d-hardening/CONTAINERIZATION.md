# 容器化评估：本地进程执行的隔离边界与容器化收益/代价（PLAT-21d，只评估不实现）

- 日期：2026-10-28
- 结论先行：**本轮不实现容器化。** v1 的 PROCESS 执行保持"同 OS 用户 + 独立工作目录 + 环境白名单 + 可执行文件白名单 + 并发配额 + 超时杀树"的既有隔离组合；需要强多租户隔离的算法应走 `ALGORITHM` 适配器（K8s Job，镜像白名单 + requests/limits 已有），而不是把 PROCESS 容器化。

## 1. Windows 下的隔离边界（现状，v1）

| 维度 | 现状 | 边界说明 |
| --- | --- | --- |
| 文件系统 | 独立 `call-*` 工作目录（允许根 `agent.process.work-dir` 之内，realpath 防 symlink/.. 逃逸） | 进程与平台**同 OS 用户**，子进程可读该用户可读的一切文件——`ProcessBuilder` 不提供文件系统沙箱，工作目录只保证"互不污染"，不保证"读不到别处" |
| 环境变量 | 白名单最小必需集 + 契约 `env`（保留变量禁止覆盖，键值均不进审计事实） | 信任根是"算法与平台同机"，防的是密钥/代理意外泄露，不是恶意算法 |
| 可执行文件 | `allowed-executables` 白名单（注册期 400 / 发布期 400 / 执行期 403），Windows 大小写归一、整路径精确匹配 | 白名单只允许已审核程序，不允许用户上传可执行文件；Windows 无 exec 位（见 `executable-bit-probe.md`），Unix 语义更严 |
| 输出 | stdout/stderr 定长尾部缓冲（默认 1 MiB），超限杀树 | 防 OOM 与"半截信封当结论" |
| 并发 | 全局 + 每租户两层信号量，排队 30s 后 429 `PROCESS_BUSY` | 防资源挤兑；不持有数据库事务等待进程 |
| 终止 | Windows `taskkill /T /F` 杀整棵树；非 Windows 先子后父 destroy→destroyForcibly | 孤儿进程树是本机最大的资源风险，T21a 已实机验证父子三进程全灭 |
| 取消 | 适配器层取消句柄（`cancel(callId)` → 杀树，502 `PROCESS_CANCELLED`） | 引擎取消事件出站接线为已登记缺口；超时杀树仍是唯一自动终止路径 |

v1 明确**不是**强多租户安全边界：`agent.process.enabled` 默认关闭，只对可信算法、可信租户开启（契约 §5.6）。

## 2. 容器化的收益（如果做）

1. 文件系统/网络/进程命名空间级隔离：子进程读不到平台数据盘，泄露面从"OS 用户可读"缩到"显式挂载"。
2. 硬资源上限：CPU/内存/进程数 cgroups 限额，比信号量配额更硬。
3. 杀树变简单：杀容器 = 杀整棵树，不依赖 `taskkill /T /F` 的父子枚举。
4. 镜像即信任根：可执行文件白名单升级为镜像摘要白名单，与 `AGENT_ALGORITHM_ALLOWED_IMAGES`（ALGORITHM 侧既有）同构。

## 3. 容器化的代价（Windows 宿主上尤其重）

1. **Windows 容器不是 Linux 容器**：本平台当前在 Windows 实机运行（`taskkill /T /F` 证据、Windows 路径专项证据均在 Windows 11）。Windows 进程隔离模式容器体积大（数 GB 基础镜像）、Hyper-V 隔离又要求嵌套虚拟化，CI/客户现场启用门槛高。
2. 算法 worker 交付形态要变：现在算法侧交付"可执行文件 + 契约清单"，容器化后要交付镜像、镜像仓库、版本与摘要管理——与 P5（算法接入交付物）的现有口径冲突。
3. 文件交换复杂化：stdin/stdout 协议不变，但 `call-*` 工作目录要变成 bind mount；Windows bind mount 的路径映射与权限（gMSA/凭据）是新的错误面。
4. 运维面翻倍：容器运行时（Docker/containerd）成为 skill-registry 的强依赖，`enabled=false` 的默认关闭语义、故障恢复、磁盘回收都要重新设计。
5. 与 ALGORITHM 适配器重叠：需要容器级隔离的算法今天就可以走 `ALGORITHM`（K8s Job），PROCESS 容器化会造出第二条重叠通道。

## 4. 与 P5（算法接入）的关系

P5 的交付口径是"算法进程以 v1.1 契约接入本地执行"。本轮不改变该口径：PROCESS 仍是"同机本地进程 + 协议信封"。若未来决定容器化，触发条件与入口是：

- 出现第一个"多租户互不可信但必须用 PROCESS 形态"的真实需求（而不是假设需求）；
- 算法侧能交付镜像并接受镜像清单管理；
- 届时应复用 ALGORITHM 的 K8s Job 链路（镜像白名单、requests/limits、external_job_binding 语义），而不是在 skill-registry 里内嵌容器运行时。

## 5. 决定

维持 v1 本地进程语义（本轮 T21d 的配额/白名单/转储/取消句柄已把"可信租户"场景的工程风险收敛到可运营水平）；容器化**只评估、不实现**，不在 PLAT-21 范围内排期。
