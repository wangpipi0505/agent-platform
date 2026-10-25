# 真实调度脚本清理P2修复

只在独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 内遵守AGENTS，不读取相邻旧工程/配置/进程。用户授权持续实施与本机隔离端点。此任务在root明确释放实现窗口后启动；不碰Java/Maven/前端生产和正在运行的服务。

需求依据：`review-live-smoke.md` 的调度脚本finally清理P2。输入完整四文件new-file diff `live-smoke-review.patch`、SHA `live-smoke-review-sha256.json` 和实际源。已有真实调度成功证据保留不重写：两个实际JVM/专用新PG库、101有效头+200尾全成功、dispatch代1→2接管；三PID已不存在、测试端口已关闭。问题只在失败清理路径，不能把已成功证据说成失败，也不能以成功覆盖清理问题。

当前finally逐个await stop；首个stop因权限/CIM/归属拒绝/退出等待抛错，会跳过剩余子进程、peer/socket和最终processRecords，同时覆盖原验证异常。修复应独立尝试每项已创建资源的清理，peer/socket和最终记录写入保证尝试；完成全部清理后保留/汇总原验证错误与清理错误。必须保持精确PID+JAR归属校验，归属不符继续拒绝停止该PID，但继续清理其余自己资源；不能通过放宽校验或吞错返回成功。

先写有限纯Node失败回归并实际行为红，再最小修复。注入有界假stop/peer/socket/记录函数，不对真实其他进程试错或杀进程。至少验证第一项stop拒绝时后项及peer/socket/最终记录仍尝试、原错误保留、多个清理错误可检查、正常路径保持、单一资源不存在幂等。必要可抽一个极小私有测试helper（tests目录），不造通用资源框架或新增依赖。最终实用脚本须实际接入被测试的清理逻辑，不写镜像实现测试。

仅允许修改 `tests/smoke-run-scheduler-replicas.mjs` 及新增自己的纯Node清理helper/测试和证据。root其它三个脚本和原始raw/log/截图/旧manifest不得修改。新阶段完整baseline/frozen、实际patch/前后SHA、红绿日志写 `docs/evidence/scheduler-smoke-cleanup-fix.md`。检查脚本语法并运行Node测试；无需重跑真实两JVM矩阵（只有清理逻辑修改，现有成功raw仍是历史源的证据），未做就不声称。不启停服务、不提交发布、不派子agent。冻结后交还，原审查者独立复核。
