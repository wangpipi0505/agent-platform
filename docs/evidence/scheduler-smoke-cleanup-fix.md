# 真实调度脚本失败收尾 P2 修复

日期：2026-10-20。实施范围为 `scheduler-smoke-cleanup-fix-brief.md`，在 root 交出唯一实现窗口后进行。仅修改 `tests/smoke-run-scheduler-replicas.mjs`，新增该脚本私有的 `tests/scheduler-smoke-cleanup.mjs`、纯 Node 回归 `tests/scheduler-smoke-cleanup.test.mjs` 和本阶段证据。遵守本工程 AGENTS，使用 systematic-debugging、test-driven-development、ponytail、verification-before-completion 技能；未委派子 agent。

## 问题与修复

原 `finally` 顺序等待每个 `stop()`。第一项的 CIM、权限、归属拒绝或退出等待异常会中断整个收尾，跳过其余 JVM、peer、socket 与最终进程记录，并覆盖原验证错误。本阶段先保存历史四脚本完整基线，再写失败回归，将原清理语句原样提取到私有 helper 并立即接入脚本，得到真实行为红；此时没有改变顺序清理语义，也没有触碰真实进程。

修复后的脚本在 `catch` 保存原验证异常，在 `finally` 调用同一个被测试的 helper。锁释放、每个拥有的 child stop、peer 关闭、每个 socket destroy、最终记录写入分别尝试并捕获错误。peer 先发起 close，再处理 socket；最终记录在等待 close 回调之前尝试，避免它依赖 peer 先完成。peer 已关闭的 `ERR_SERVER_NOT_RUNNING` 作为幂等情况处理，其余关闭失败均记录。

没有清理错误时，原验证异常按原对象重新抛出；两类错误同时出现时抛出 `AggregateError`，原异常保留在 `cause` 和 `errors[0]`，后续每项错误含资源标签且以 `cause` 保留原清理异常。仅清理失败也抛出 `AggregateError`，不会将验证成功误报为脚本整体成功。只有两者均成功才正常返回。用显式失败容器保留 `throw undefined/null/false` 的异常语义。

`stop()` 函数从原基线到最终候选逐字一致，包括 ChildProcess 退出状态检查、精确 PID 查询、`java.exe` 和已审 JAR 归属检查、拒绝无关 PID、只停止该 PID、退出等待与 stopped 标记；没有添加杀进程后备路径或放宽保护。三个其他历史脚本 SHA 不变，旧四文件 manifest、review patch、成功 raw/log/summary 没有覆盖。

## 红绿与有限验证

环境为 Node v24.16.0。所有假 stop、peer、socket、记录函数均有界；前七项用例各有 1000ms 超时，不连接数据库、HTTP 或真实操作系统进程。

| 实际命令 | 结果与证据 |
| --- | --- |
| `node --test tests/scheduler-smoke-cleanup.test.mjs`，原逻辑提取版本 | 8 项中 2 通过、6 失败，退出 1。第一 stop 拒绝后实际调用只有 lock、stop:101；其余 stop、peer、socket 与 records 均缺失。完整日志 `scheduler-smoke-cleanup-fix/red.log`，原逻辑 helper/脚本/测试完整保存于 `red/tests/`。不是缺模块、编译或真实进程失败作为红。 |
| `node --test tests/scheduler-smoke-cleanup.test.mjs`，最终候选 | 8/8 通过、0 失败、退出 0，完整日志 `scheduler-smoke-cleanup-fix/green.log`。覆盖首 stop 归属拒绝后全部后续尝试、原异常保留、多项同步/回调/写入错误可检查、正常路径 stopped 记录、仅验证失败、仅清理失败、等待 peer 回调期间写记录、单项资源缺失与重复 peer 关闭。 |
| `node --check tests/smoke-run-scheduler-replicas.mjs` | 退出 0。 |
| `node --check tests/scheduler-smoke-cleanup.mjs` | 退出 0。 |
| `node --check tests/scheduler-smoke-cleanup.test.mjs` | 退出 0。后三项完整记录于 `scheduler-smoke-cleanup-fix/syntax.log`。 |

第八项最终测试还核对原 `stop()` 函数逐字不变，并从实用脚本读取真实声明及 catch/finally，替换其真实验证主体为受控失败后执行该收尾源码，注入假资源与假 `fs.writeFile`。验证脚本确实向被测试 helper 传递原错误、继续清理且最终 JSON 只给成功停止的进程标记 stopped；没有写镜像清理实现。红日志对应最初的静态接入检查版本；其后补强该检查为实际 catch/finally 执行，并将假 sockets 改为与实用脚本相同的 Set，最终绿日志对应冻结候选。

## 无 HEAD 交付与冻结

`git rev-parse --verify HEAD` 实际返回退出 128 / `fatal: Needed a single revision`。因此不用 HEAD 或旧提交充当基线。本阶段目录 `docs/evidence/scheduler-smoke-cleanup-fix/` 保存四历史脚本的完整 `baseline/`，其 SHA 与原 `live-smoke-review-sha256.json` 4/4 匹配；`frozen/` 保存最终四脚本及两个新增文件。

- 实际基线到候选补丁：`docs/evidence/scheduler-smoke-cleanup-fix.patch`（一项修改、两个新增，包含新增文件全文）。
- 前后 SHA 与完整路径：`docs/evidence/scheduler-smoke-cleanup-fix-sha256.json`，还包含未改变 stop 函数 SHA、补丁 SHA、红绿/语法日志和红版本等证据 SHA。
- 可重建冻结材料：`node docs/evidence/scheduler-smoke-cleanup-fix/freeze.mjs`，只复制上述有限已知源文件、核对基线并调用 Git 生成真实 diff，不调用服务或杀进程。

另在本阶段自己的 `replay/` 目录复制完整 baseline，实际执行 `git apply --check --directory=docs/evidence/scheduler-smoke-cleanup-fix/replay docs/evidence/scheduler-smoke-cleanup-fix.patch` 与对应的 `git apply --directory=...`（均指定 `-c core.autocrlf=false`），两步退出 0；重放后六个文件 SHA 6/6 与 frozen 相同。日志 `scheduler-smoke-cleanup-fix/replay.log`。本次只核对补丁应用与 SHA，没有把重放检查表述为再次运行 Node 套件。

此阶段仅修复失败收尾；没有重跑两个真实 JVM 的调度矩阵，没有 Maven/打包，没有启动或停止服务，没有读取相邻旧工程，没有提交或发布。历史成功证据仍只对应历史源：两个真实 JVM/专用新 PG 库、101 有效头与 200 尾成功、dispatch 代 1→2 接管、本轮三 PID 与端口已清理。本阶段不把这些成功说成失败，也不将本次纯 Node 回归说成重跑了真实矩阵。

独立审查仍由原审查者执行；本报告是实施证据，不冒充独立复核或生产验收。
