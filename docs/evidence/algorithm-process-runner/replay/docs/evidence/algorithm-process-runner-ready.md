# 算法进程 runner：待固定制品的准备交付

2026-10-20。本轮只完成 Node runner 准备，没有真实 engine/skill JVM、数据库或 MinIO 通过结果。此前暂停报告 `algorithm-process-runner-report.md`、原 `red.log`/`green.log` 和暂停源码已保留，未覆盖历史。root 恢复独占 Node 实施窗口后，生产源码继续冻结，本轮不访问 target、不打包、不派 agent、不提交或发布。

交付源码为 `tests/smoke-algorithm-processes.mjs` 和 `tests/algorithm-process-runner.test.mjs`。runner 默认只显示准备状态，实际入口要求 root 另行移交固定 reviewed artifact lock，再显式执行 `node tests/smoke-algorithm-processes.mjs --execute-reviewed <artifact-lock.json>`。该命令本轮没有执行。

artifact lock 形状如下；directory 必须是本工程 `.local/artifacts/` 内的两份固定目录，runner 分别读取目录内的 `engine-runtime.jar` 与 `skill-registry.jar`，比较完整 SHA，并在每次 spawn 前复核。不接受 target、默认旧 JAR、跨目录符号链接或任意在用服务。

```json
{
  "handoff": "root-reviewed-algorithm-live",
  "engine-runtime": { "directory": "<root提供的绝对目录>", "sha256": "<64位小写SHA256>" },
  "skill-registry": { "directory": "<root提供的绝对目录>", "sha256": "<64位小写SHA256>" }
}
```

## 实际接口与行为修正

重新核对冻结的实际 SkillExecution envelope，内联原始 JSON 位于 `structured`，转存结果 `structured=null` 且 `fileRefs` 绑定真实 artifact。原草稿误读的 `result.result` 已修正。AlgorithmAdapter 的 Running 绑定实际为 `SUBMITTED`；本地取消后只要求 run=CANCELLED、call/fact=UNKNOWN、绑定保留原 UID/SUBMITTED，明确不声称 Job 删除、停止或效果撤销。

Failed 场景同时检查原事实/引擎调用 FAILED、`NON_RETRYABLE_FAILURE`、DONE/Failed 终态、连接重检 `ok=false`，且不能把对账回包当作 NOT_APPLIED。所有场景保存原 call/key/attempt/Job/namespace/identity；已捕获 UID 不得变化，丢 ACK 前 UID 可尚未绑定，后续必须精确采纳 fixture 的原 UID。成功场景核对一条非 Mock DEBUG fact、一次 tool 逻辑提交、一次 accepted terminal fact、一条 tool call、一个原 attempt。重检只提供原 controlledCallId，不创建新 run。

五场景各有独立有限 fixture/namespace，顺序最多一 engine 与一 skill JVM。只有新唯一 `agent_platform_algorithm_live_<suffix>` 库会被 createdb 创建；服务自身 Flyway 建 schema，所有核验 SQL 都显式 `begin read only`，不存在事实 SQL seed 或数据库 drop。真实 PG/MinIO 值仅从本新工程 `.env` 读取、只注入子进程环境；父 AGENT_/Spring/Java 配置不会整包继承。JWT/主密钥/fixture 假 Bearer 随本轮随机生成；假 Bearer 以与 SecretVault 一致的 AES-GCM 格式存本轮私有 vault，命令行不传真实密钥。未使用 app/model/monitor 指向本轮有限 503 拒绝端点，并要求计数为零。转存正例约1.2KiB JSON、outputMaxBytes=1024，仍低于 fixture 8KiB 限制；只核验本轮 tenant/run/call 的 READY 登记及受控下载完整字节、SHA。

每次实际 HTTP 前核对记录 child 的 PID、创建时间、精确 `-jar` 参数和端口全部 listener PID，避免把同端口健康服务误当自有进程。停止前再次核验 PID/创建时间/JAR，在 PowerShell 内停止前再核验一次。端口启动前拒绝已有监听。只记录与停止本轮 child，不枚举、接管或启停六主服务。stdout/stderr 各写独立原始日志，API/只读 PG/fixture/process/SHA 以本轮独立 JSON 保存；最终保留完整错误及清理错误。逐个 child、fixture 与最终记录各自尝试；fixture 关闭有上限，调用原 fixture 的 closeAllConnections 路径。失败时也保存已有场景观察及拒绝上游计数，不将明确 UNKNOWN/丢 ACK 用作业务重试许可。

## 有限红绿证据

原四项有限检查在暂停前已运行：最初 `red.log` 是 1 通过/3 失败，`green.log` 是 4/4 通过。恢复后新增四项检查，`resume-red.log` 实际 4 通过/4 失败、退出1，具体捕获错误 inline 字段、错误 BOUND 状态、Failed 重检假成功，以及原 UID 被替换但假通过；红阶段完整两源码保存在 `resume-red/tests/`。

修正后，`node --test tests/algorithm-process-runner.test.mjs` 最终实际 12/12 通过、退出0，日志 `resume-green-all.log`。除上述四项，还覆盖清理同时保留原错误及多个清理错误、fixture 关闭超时仍写记录、最终写记录失败仍保留原错误、PID/精确 JAR/创建时间拒绝、真实有限 HTTP 的认证拒绝、真实有限丢 ACK 的读404→接收一次POST→原 UID GET。PowerShell 实际脚本由解析器检查；停止拒绝测试将 Get-CimInstance/Stop-Process 全部替换为合成函数，错 JAR 必须在 Stop 前拒绝，没有查询或停止真实系统进程。artifact lock 的缺失 root 标记也必须拒绝。有限 HTTP fixture 均在 finally 关闭，测试进程已结束。

这些是 runner 护栏和有限协议的验证，不是 JVM、迁移、PG、MinIO 或 Kubernetes 结果。没有创建数据库、连接真实 PG/MinIO、运行 Maven/package 或真实 JVM；没有使用旧846制品。真实五场景必须待 root 明确移交不可变 JAR/SHA 后执行，任何不符实际路径的场景都应保留红或限制。

## 冻结与重放

`algorithm-process-runner/baseline/absence.json` 记录本任务三份交付文件原本不存在；`resume-baseline/` 保留暂停时两源码与暂停报告。`frozen/` 保存最终两源码与本追加报告；`inputs/` 保存只读原 fixture 以供重放。`algorithm-process-runner.patch` 是空源码 baseline 到完整交付的真实 Git diff，`algorithm-process-runner-sha256.json` 记录源码、历史、输入、patch、日志及重放文件 SHA。

使用 `algorithm-process-runner/freeze.mjs` 可在本轮证据目录生成冻结与真实 patch；重放通过实际 `git apply --check` 与 `git apply`，逐文件 SHA 与 frozen 对比，随后在独立 replay 目录执行同一 Node 检查。最终 `syntax.log`/`replay.log` 记录实际命令退出码和12项检查。原 fixture 只读复制到 replay，不属于本任务修改；原 smoke、scheduler helper、其他证据和总台账均未修改。

本准备交付等待独立只读审查与 root 固定制品移交，不替代真实纵切验收，更不代表真实 Kubernetes/容器/RBAC、生产 IAM、容量或全部 G2 通过。
