# 异步响应性测试收尾 P2 返修

2026-10-20，依据同审查者 Needs fixes 与 root 明确授权，本轮只修 `tests/algorithm-process-runner-async.test.mjs` 第一项测试的异常收尾，新增两个有限故障验证文件。已审真实 runner SHA仍为 `257e8545c4818d1d40a235a32a3c10d6912997e10d9a0aacf876bd56d3b75688`，没有任何runner/其他原测试/生产/fixture/4秒期限/env/health/清理预算修改，第四轮继续暂停。

## 复现与红证据

已读最新审查及 `review-algorithm-process-runner-v3/async-cleanup-probe.mjs/.log`。原测试finally先await pending，PS拒绝会跳过closeAllConnections和close；双失败会覆盖先发生HTTP错误，快速拒绝还可能在被await前产生unhandledRejection。

新增 `tests/algorithm-process-runner-async-cleanup-probe.mjs` 提取实际第一测试回调，注入假server/fetch/PS，不创建socket、PowerShell、JVM或任何真实服务；同一probe可读取固定baseline callback。新增 `tests/algorithm-process-runner-async-cleanup.test.mjs` 用有2秒timeout/16KiB输出界限的独立Node子进程执行五种故障：PS拒绝、HTTP+PS双拒绝、立即PS拒绝、PS+两个关闭错误、close回调不结算。它们检查实际关闭调用顺序、原错误对象身份及主错误、无未处理拒绝、关闭超时和有界子进程退出。

实际旧async文件上五项全红、退出1，原始red.log及红阶段三文件保存。本轮又用同一probe运行完整旧baseline callback，原始 `red-baseline-probes.json` 保存：前四case退出0仅表示probe成功捕获错误，其中PS失败case的closeCalls=[]；双失败只剩PS错误，HTTP原错丢失；快速PS拒绝观察到unhandled及rejection-handled警告；never-close case退出13并有未结算top-level-await警告。没有把probe成功捕获缺陷的退出0误作通过。

## 窄修复

pending使用已附双路径结算handler的Promise链启动PS，将成功/失败转为可等待的value/error结果，快速拒绝立即被观察；主体仍检查HTTP/timer确实先于PS结算。主体错误先记录，finally再次读取pending只补原PS错误且去重，不替换原HTTP错误。嵌套finally独立尝试closeAllConnections及server.close，前项失败不跳过后项；server.close有测试专用1秒界限，callback永不结算也记录ETIMEDOUT并继续退出，timer必清除。单错误抛原对象，多错误用AggregateError保留完整errors与第一个原错误cause。listen也移入try，以便其失败进入相同收尾。没有创建通用清理框架。

原PS调用仍是原2000ms有限测试参数；server本身关闭界限仅属于这个测试，未更改runner/helper业务或收尾预算。伪造故障的probe将该测试1秒关闭等待缩为30ms加速有限回归，不改真实测试源码的界限。

## 实际覆盖验证

- `node --test tests/algorithm-process-runner-async.test.mjs tests/algorithm-process-runner-async-cleanup.test.mjs` 实际10/10通过、退出0，见green-directed.log。
- `node --test tests/algorithm-process-runner.test.mjs tests/algorithm-process-runner-fixes.test.mjs tests/algorithm-process-runner-redis.test.mjs tests/algorithm-process-runner-async.test.mjs tests/algorithm-process-runner-async-cleanup.test.mjs` 实际31/31通过、0 fail/skip/cancel、退出0，见green-all.log。
- 同一五case实际回调probe的 `green-current-probes.json` 均退出0、stderr空、unhandled=[]，两项关闭调用均实际尝试；双失败保留原HTTP与PS且HTTP为cause，三错误case保留PS/连接销毁/close原对象；不结算close有ETIMEDOUT，无未结算await。

这些是有限测试自身的护栏，不能替代第四轮真实五场景；本轮没有接触JVM/PG/Redis/MinIO/业务Job。

## 独立冻结与重放

本轮baseline完整保存fix-v3八交付文件，SHA逐项与上轮sources匹配；新两验证文件和报告在baseline-absence.json登记不存在。frozen保存最终十一文件，其中runner等上轮不变文件也完整保留。新 `algorithm-process-runner-test-cleanup-fix.patch` 是baseline→frozen真实完整差异，仅修改async测试并新增两个验证文件和本报告。

freeze.mjs实际复核上轮frozen/replay/evidence/保留v3/raw/root清理/patch/历史manifest字节，确保原fix-v3及三actual历史不变；新manifest保存本轮所有SHA。新的replay复制baseline后实际git apply --check与apply退出0，十一文件SHA与frozen一致；只读复制原fixture后实际语法检查七文件并完整运行31项有限检查。完整命令和结果在replay.log、SHA见 `algorithm-process-runner-test-cleanup-fix-sha256.json`。第四轮仍等待原审查者复核与root另行授权。
