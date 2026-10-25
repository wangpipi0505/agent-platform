# 算法进程 runner 窄修复：UTF8 与最终 fact 原代

2026-10-20，依据 root 对首跑根因与独立审查 Minor 的明确授权。本轮仅修改 runner 的 PowerShell stdout 编码及三个最终 fact 等值断言、必要有限测试；原生产/POM/target/fixture/超时/精确归属保护均不变。没有重跑真实入口，待本修复独立审查后由 root 再移交原制品锁。

首跑失败和清理失败完整保存在 `algorithm-process-live.md`、`algorithm-process-live-summary.json`、`algorithm-process-live-execution.log` 及 `algorithm-process-runner/live-bcc9285991eb/`。当时 self-owned PID52436 的归属读取和停止均因损坏的中文路径遭拒，最终记录也报告进程仍存在；这些失败没有被后续状态替换。追加只读诊断 `algorithm-process-runner-fixes/post-failure-ownership-and-encoding.json` 保留 default 编码路径不相等、显式UTF8路径相等，以及稍后 pid=null/listenerPids=[]；root 亦独立核到无残留。未停止其他进程、未重跑创建库、未 drop 首跑新库。

## 行为红与窄实现

先新增 `tests/algorithm-process-runner-fixes.test.mjs`，对原已审 runner 实际执行：4项全失败、退出1，原始 `red.log` 和两份红阶段源码保存于本修复目录。Unicode 检查从真实 runner 提取现有 powershell() 函数，实际调用 Windows PowerShell 仅回显一条无秘密中文 JAR 路径，不读取或停止任何真实 PID；旧实现把中文解码成替换字符，路径等值明确失败。另三项以同一个有效成功观察分别只更换最终 fact 的 attempt_id、execution_owner、call_kind；原实现全部错误接受，测试明确报 Missing expected exception。

实现只在 powershell() 调用的脚本前加 `[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);`，使原 spawnSync 的 utf8 解码与输出一致。没有关闭、放松或绕过 exact JAR、PID、创建时间或 listener 归属断言，也没有新增宽限时间或改动进程脚本。assertScenarioTerminal 直接比较 after.fact 的上述三个字段与 before.fact 原值，错误换代拒绝。原12项测试的观察构造仅补原 fact 三字段和 before.fact 副本，以符合实际 PG 非空 fact 形状；其断言和测试数保持。

修正后实际 `node --test tests/algorithm-process-runner.test.mjs tests/algorithm-process-runner-fixes.test.mjs` 为16/16通过、0 fail/skip/cancel、退出0，见 `algorithm-process-runner-fixes/green.log`。新增 Unicode 行为使用实际修正 helper 回显，三个原代拒绝检查各先确认原始有效观察仍通过。原12项认证、ACK、归属及错误收尾检查全部继续通过。runner/新增测试 `node --check` 退出0，见 syntax.log；重放同时检查三份测试源码语法及16项行为。

## 增量冻结与真实重放

原准备报告/patch/manifest/SHA/12绿、原冻结与重放、原首跑全部证据保持原字节。本轮 `algorithm-process-runner-fixes/baseline/tests/` 是原已审两源码，SHA 与原 manifest sources 相同；`baseline-absence.json` 记录新增测试和本追加报告之前不存在。`frozen/` 保存最终四文件；`algorithm-process-runner-fixes.patch` 是 baseline→frozen 的真实完整 Git diff，包含两文件窄修改及两文件新增；`algorithm-process-runner-fixes-sha256.json` 记录前后、patch、原输入/历史校验、红绿与重放SHA。

本修复 freeze.mjs 会先核对原 manifest 所列 frozen/replay/inputs/evidence/patch/暂停报告字节，再构造实际差异；在新的 replay 目录复制本轮 baseline，运行实际 git apply --check、实际 apply，核验四文件SHA与frozen一致，再只读复制原fixture到 replay并运行16项有限检查。`replay.log` 记录实际命令、退出码和输出；没有执行真实 runner 入口。

本修复不代表首跑成功，也没有任何真实五场景通过结论。固定制品、数据库、事实、MinIO与进程收尾需在 root 再授权真实入口后重新取得。所有尚未执行项继续保持未验证，不能用16项有限绿替代真实纵切结果。
