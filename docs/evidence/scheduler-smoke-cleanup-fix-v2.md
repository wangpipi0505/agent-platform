# 调度清理 P2 第二轮追加修复

2026-10-20，依据 root 移交的独占实现窗口继续处理独立审查指出的异步锁流错误与 peer 关闭不结算问题。第一阶段 `scheduler-smoke-cleanup-fix.md`、原 baseline/frozen、旧 patch/manifest、8 绿日志全部保持历史版本不改写；本追加报告及同名 v2 目录保存本轮独立证据。继续使用 systematic-debugging、test-driven-development、ponytail、verification-before-completion；未派子 agent。

## 根因与行为红

第一阶段把 `stdin.end()` 返回的 Writable 当成可等待操作。真实 Writable 的 `_write` 若通过 `process.nextTick` 回调 EPIPE，错误在稍后的 `error` 事件发出，原 helper 的 try/await 捕获不到；Node 因未处理事件退出，原验证异常与后项清理均无法保留。第一阶段的锁 fake 只同步返回/抛错，未覆盖真实流行为。

另一缺口是 `fixture.close()` 回调可能一直等一个无法销毁或未跟踪的 socket；即使记录写入已尝试，直接等待 `peerClosed` 没有上界，已经收集的 socket 错误与原验证异常也无法结算。

新增 `tests/scheduler-smoke-cleanup-async.test.mjs` 后，在未经第二轮修复的 helper 上实际执行 `node --test tests/scheduler-smoke-cleanup.test.mjs tests/scheduler-smoke-cleanup-async.test.mjs`：12 项中原 8 项通过、新增 4 项失败，退出 1，日志 `scheduler-smoke-cleanup-fix-v2/red.log`。完整红阶段 helper、实用脚本与两测试保存在 `red/tests/`。

新增四项的红均为行为失败：真实 Writable nextTick EPIPE 的独立 Node 子进程实际退出 1，stderr 明确 `Unhandled 'error' event` 与 EPIPE；peer 永不回调且首 socket 销毁失败时，200ms 有界观察仍 pending；缺失 tracked socket 时 peer 同样 pending；真实 Writable 永不完成时旧实现直接继续并仅抛原异常，未报告锁完成超时。没有真实 Java、psql、socket 或服务参与这些复现；唯一新增进程为测试自建的 Node 子进程，执行本项目真实 helper，并设 2000ms 上界。

## 第二轮实现

仅修改私有清理 helper 与原回归，新增异步回归；实用 `smoke-run-scheduler-replicas.mjs` 本轮 SHA 不变，继续调用同一 helper。

锁清理先安装本轮 stdin 的 `error` 监听，再把 `end(rollback, callback)` 包装为 Promise，实际等待完成或错误。错误监听有意保留，因为 Writable 可在 end 错误回调之后才发出排队的 `error` 事件；提前移除会再次产生未处理事件。此锁流只属于本轮 psql 夹具，未修改全局进程错误处理。

给这两个回调清理操作分别加默认 1000ms 上界：锁 stdin end、fixture peer close。超时错误带 `code=ETIMEDOUT` 和资源名，仍走原来独立 attempt 与 `AggregateError` 汇总。测试通过私有参数 `closeTimeoutMs=30` 让有限回归快速结算；1000ms 是脚本的关闭等待上界，不是服务性能或优雅停止承诺。每项结束清除对应 timer；不对原 stop/PID 校验或最终记录操作套一个过短超时。

peer 关闭仍先发起，后续各 socket 和最终记录仍独立尝试，最后等待其有限结果。无法销毁的 socket 会同时报告原 socket 错误与 peer 超时，不伪造清理成功。原验证错误继续保留在 `cause` 和 `errors[0]`；仅清理失败也拒绝。锁流永不完成也会超时后继续尝试其余自有资源。正常路径与单项资源缺失、已关闭 peer 的幂等行为保持。

## 实际有限验证

环境 Node v24.16.0。最终命令 `node --test tests/scheduler-smoke-cleanup.test.mjs tests/scheduler-smoke-cleanup-async.test.mjs` 实际 12/12 通过、0 失败、退出 0，完整日志 `scheduler-smoke-cleanup-fix-v2/green.log`。新回归分别断言真实 EPIPE 无未处理事件且所有后项执行、原异常及 EPIPE 原对象可检查、peer 永不回调在界限内拒绝并同时保留 socket 错误/原验证异常、缺 socket 时超时拒绝、真实锁流永不完成也有界。原八项也改用真实 Writable 作为锁夹具，再次全部通过。

`node --check` 实际检查实用脚本、helper、原测试与新异步测试四文件，全部退出 0，日志 `scheduler-smoke-cleanup-fix-v2/syntax.log`。原测试继续逐字比较最初 baseline 的整个 `stop()` 函数，且从真实脚本抽出实际 catch/finally 注入假资源执行；PID/JAR 归属保护没有更改，也没有停止真实进程。

## 第二轮无 HEAD 冻结材料

- `scheduler-smoke-cleanup-fix-v2/baseline/`：第一阶段最终六文件完整副本，SHA 与第一阶段 frozen/manifest 逐项匹配。
- `scheduler-smoke-cleanup-fix-v2/frozen/`：本轮完整七文件，包含三个其他历史脚本、本轮不变的实用调度脚本、helper 与两测试。
- `scheduler-smoke-cleanup-fix-v2.patch`：第一阶段完整 baseline 到本轮候选的真实差异，修改 helper 与原测试，新增异步测试；原阶段 patch 保留，可与其顺序重建。
- `scheduler-smoke-cleanup-fix-v2-sha256.json`：前后 SHA、patch、红绿/语法/重放日志、红快照、历史输入和追加报告 SHA。独立原始 stop 函数 SHA 继续核对。
- `scheduler-smoke-cleanup-fix-v2/freeze.mjs`：有限已知文件的冻结与真实 Git diff 生成器，同时校验旧阶段 manifest、baseline/frozen 与证据全未改写。

在本阶段自己的 `replay/` 复制 v2 baseline，实际 `git -c core.autocrlf=false apply --check --directory=docs/evidence/scheduler-smoke-cleanup-fix-v2/replay docs/evidence/scheduler-smoke-cleanup-fix-v2.patch` 与实际 apply 均退出 0，重放七文件 SHA 7/7 与 frozen 匹配。为重放运行原测试的历史保护检查，另只复制其引用的第一阶段原始 scheduler baseline 到 replay 的同相对证据路径；不修改被重放源码。随后在 replay 工作目录实际运行两份 Node 测试，12/12 通过、退出 0。记录在本轮 `replay.log`。

本轮未 Maven、打包、启停服务、重跑真实两 JVM 矩阵、读取相邻旧工程或提交发布。历史成功 raw/log/summary 和原 manifest 均不改写；真实成功仍只归属于历史源。本报告为第二轮实施追加证据，等待原审查者独立复核，不替代其结论。
