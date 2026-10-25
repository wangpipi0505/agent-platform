# PG 微秒时间顺序窄修复

本轮只修 `tests/algorithm-process-attempt-scope.mjs` 的时间换算，并新增 `tests/algorithm-process-attempt-time.test.mjs` 11 项有限行为测试。原 runner SHA 保持 `e2878c7ae3275eaa26e6a7e24a178fae79c9bac38c91d9ecdfbe55d7c348a1e4`；capture SQL、原 93 项 scope 测试和旧 31 项测试均未改。没有读取真实凭据、连接服务/数据库、启动 JVM、创建新库或执行第五轮。

独立审查给出的反例成立：终态 note `2026-10-20T11:23:56.064001+08:00` 晚于 READY `.064000+08:00`，但原 `Date.parse` 将二者截到同一毫秒并接受。新增测试直接调用原 oracle，复用旧测试的 v4 归档行与明确标注的 before-node 合成补充，只替换四个阶段的时间。

修复将带时区的整秒交给标准库 `Date.parse`，再以 `BigInt(整秒毫秒) * 1000n + 六位补零小数` 得到整数微秒。PG 的 0–6 位小数不再经过毫秒截断。原相邻 `>=` 门禁继续适用，真实相等合法，时区按绝对时间比较；没有引入依赖或日期框架。

实际执行命令和原始输出位于本报告同名目录，命令/退出码亦记录于 `commands.json`：

```text
node --test tests/algorithm-process-attempt-time.test.mjs
旧 oracle：exit 1，11 项中 7 通过、4 失败（Missing expected exception），见 red.log。
修复 oracle：exit 0，11/11，见 green-time.log。

node --test tests/algorithm-process-runner.test.mjs tests/algorithm-process-runner-fixes.test.mjs tests/algorithm-process-runner-redis.test.mjs tests/algorithm-process-runner-async.test.mjs tests/algorithm-process-runner-async-cleanup.test.mjs tests/algorithm-process-attempt-scope.test.mjs tests/algorithm-process-attempt-time.test.mjs
exit 0，135/135，无取消/跳过，见 green-all.log。
```

四个反例分别是同毫秒内 note 晚于 READY、READY 晚于第二次 START、START 晚于 COMMIT，以及跨时区的同毫秒逆序。七个正例覆盖相邻微秒正序、全等、PG 不同小数位、跨毫秒边界、跨时区等时与正序、整秒与零小数等时。旧 124 项完整运行并保留原语义。

`algorithm-process-runner-time-precision-fix/baseline` 保存本次修改前完整 14 个 source；`frozen` 保存本次 16 个 source。完整 patch 仅含 oracle 替换、新测试和本报告。`freeze.mjs` 实际执行 `git apply --check` 和 `git apply` 重建 `replay`，核对 16/16 SHA，并在 replay 执行所有 MJS syntax 与同一 135 项测试。详见 `replay.log` 及独立 `algorithm-process-runner-time-precision-fix-sha256.json`。

冻结过程逐项核对前一 scope manifest 的 frozen/replay/evidence/只读依据及 patch、四轮实际执行的原始目录和报告/summary/日志快照；历史证据保持原字节，v4 仍记录失败。本轮有限测试通过只说明 P2 时间比较修复，不代表真实第五轮通过。冻结后移交原 reviewer 关闭 P2，真实执行仍暂停。
