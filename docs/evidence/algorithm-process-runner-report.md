# 算法真实进程 runner：暂停中的准备草稿

2026-10-20。root 因算法独立审查的中断/资源退出路径待返修，要求单实施者窗口；本任务已停止新增源码编辑，等待 root 明确恢复。此报告只保存当前状态，不声明 runner 完成或真实纵切通过。

范围仅本新工程。已读工程 AGENTS、implementation-brief、contracts/README 与 algorithm-live-validation-brief；只读了本工程现有 scheduler 脚本、其私有 cleanup 与第二轮 12 项修复说明、实际草稿/算法/存储接口。没有读取相邻旧工程，没有修改生产/src/POM/迁移/target、package、原 fixture、其他 smoke 或总台账；没有提交或发布，也没有派 agent。

当前新文件：

- `tests/smoke-algorithm-processes.mjs`：尚待核对的完整 runner 草稿，实际执行入口默认关闭，要求 `--execute-reviewed <artifact-lock.json>`；锁需 root-reviewed-algorithm-live 标识、engine-runtime/skill-registry 两份绝对 reviewed 目录及各 JAR 完整 SHA。尚未获得 root 固定制品移交，未运行该入口。
- `tests/algorithm-process-runner.test.mjs`：本轮有限 Node 检查，覆盖清理同时保留原错误及多个清理错误、关闭超时仍写记录、PID/JAR/进程创建时间拒绝、有限 fixture 的缺失/错误 Bearer 拒绝。
- `docs/evidence/algorithm-process-runner/red.log`：先运行三个尚无实现的行为检查，实际 1 通过、3 失败，退出 1；认证检查使用既有真实有限 HTTP fixture，原本通过。
- `docs/evidence/algorithm-process-runner/red/tests/`：上述红阶段两份源码快照。
- `docs/evidence/algorithm-process-runner/green.log`：当前有限检查实际 4/4 通过、退出 0。
- 本报告。

实际已执行 `node --check tests/smoke-algorithm-processes.mjs`，退出 0；`node --test tests/algorithm-process-runner.test.mjs` 4/4 通过。有限 HTTP fixture 已在测试 finally 中关闭，Node 测试进程已退出。未运行 Maven/package、实际 JVM、createdb 或真实 PG/MinIO 请求；没有启停六主服务。未获得或使用旧846制品。

恢复后必须完成的工作：

1. 继续核对所有实际回包和 SQL 字段。暂停时已明确发现 running binding 断言写成 `BOUND`，而现有 AlgorithmAdapter 实际写 `SUBMITTED`；必须先添加有意义的失败检查并修正。内联结果 `fact.result.result` 的实际 envelope 字段也尚未核对，不能据此声称实际场景可通过。
2. 核对 failed 重检的否定断言、ack-loss 前后 UID 采纳过程、每场景最终 fixture/原事实/逻辑提交等全部不变量，确保失败也保存已观察数据与拒绝上游计数。
3. 核对 Windows 启停脚本的字符串、资源收尾与记录路径；增加必要有限测试。已有四绿仅证明它们覆盖的有限行为，未验证 Powershell 或真实 JVM。
4. 完成独立 baseline（新建文件原本不存在）、最终源码 frozen、真实差异 patch、SHA 清单与重放核验；这些交付尚未制作，当前目录中的空 baseline 不构成证据。
5. root 已允许转存正例使用约1.2KiB JSON、outputMaxBytes=1024，仍低于 fixture 8KiB 上限；不修改生产阈值。待 root 修正 brief 后保持一致。
6. 待 root 返修/审查/回归关闭并明确移交固定新 JAR 目录和 SHA，才授权实际执行五个场景。实际场景仍需独立只读审查，不能把有限 fixture reception 当作 K8s、容器、RBAC、生产 IAM、容量或全部 G2 通过。

本报告随暂停写入，后续只在 root 恢复后继续。
