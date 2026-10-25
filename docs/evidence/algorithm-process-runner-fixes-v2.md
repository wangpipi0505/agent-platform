# 算法进程 runner 第二轮窄修复：显式本工程 Redis 认证

2026-10-20，依据 root 对 v2 实际 `NOAUTH` 的复核与窄修复授权。此次只补 runner 的本工程 Redis 环境与密码脱敏，新增有限假配置检查；未重跑第三轮实际入口。原准备交付、第一次 fix、首跑/v2 全部原始日志与报告均保留原字节。

## 已保存的第二轮失败与清理

`algorithm-process-live-v2.md`、`algorithm-process-live-v2-summary.json`、`algorithm-process-live-v2-execution.log` 和 `algorithm-process-runner/live-49498fe27b72/` 记录使用已审9edbf0c源码的实际退出1：engine-runtime health 超时。原日志明确 RedisReactiveHealthIndicator 连接本工程 `127.0.0.1:26379` 并得到 NOAUTH；runner 的显式私有环境此前遗漏 AGENT_REDIS_HOST/PORT/PASSWORD，实际 engine application.yml 第11—14行将密码默认成空。root 对日志和配置也独立只读确认。

该轮最后两个自有 JVM 59292/36984 均 stopped=true，原私有端口65282/65281及记录 PID 全空；fixture 关闭且业务计数全0。追加只读核验本轮新库 engine200—215、skill300—311迁移成功、业务表全0，库保留，不 drop；`v2-readonly-before-fix.json` 是本次修复目录保存的独立追加副本。日志只输出本工程 Redis 三项存在及端点匹配的布尔值，没有输出密码。没有 Redis 管理操作，没有业务场景通过。

## 新增行为红与修复

新增 `tests/algorithm-process-runner-redis.test.mjs`，只用假本地配置与假密码，不连接 Redis。测试读取真实 runner 中的显式 common/system/secrets/redact 构造表达式并执行，因此能检查实际子进程环境与实际脱敏表，而不是复制一份理想实现。原9edbf0c runner上5项实际全部失败、退出1，原始 red.log 和两份红阶段源码均保存在本轮目录：Redis三字段未透传、父毒化时未得到明确本工程密码、缺配置未拒绝、错端点未拒绝、Redis假密码未脱敏。

新增 privateRedisSettings(local) 仅接收本工程 `.env` 解析对象，要求 host/port/password 都是显式非空字符串，严格只允许 `127.0.0.1:26379`；密码原样传入，不以父环境或默认值补足。在任何唯一库、目录、fixture或 child 创建之前调用验证。common 仅展开这三个已验证字段；父环境 system 白名单保持原样，不继承 AGENT_/Spring/Java 配置。Redis 密码加入现有 secrets 脱敏表。没有改健康端点、健康依赖、等待上限、进程归属、生产/src/POM/target、原fixture、真实制品或主六服务。

修复后实际 `node --test tests/algorithm-process-runner.test.mjs tests/algorithm-process-runner-fixes.test.mjs tests/algorithm-process-runner-redis.test.mjs` 为21/21通过、0 fail/skip/cancel、退出0，green.log 保存完整输出。五项新检查分别验证实际3字段透传、父 AGENT/Spring/Java 毒化不影响、host/port/password缺失或空白拒绝、非本工程 host/port拒绝，以及真实脱敏函数对注入假 Redis 密码和既有假 PG 密码同时脱敏。测试没有读取真实 `.env`。runner/新增测试语法检查退出0，记录 syntax.log。

## 独立增量冻结与重放

本轮 baseline 保存第一次 fix 的完整四交付文件，前 SHA 与第一次 fix manifest sources 逐项匹配；baseline-absence.json 登记新增 Redis 测试与本追加报告之前不存在。frozen 保存最终六文件，包含三个不变的上轮文件。`algorithm-process-runner-fixes-v2.patch` 是本轮 baseline→frozen 的真实完整 diff，仅修改 runner 并新增测试和报告；原准备 patch/manifest、第一次 fix patch/manifest 均不覆盖。

新 freeze.mjs 实际复核第一次 fix 的 frozen/replay/evidence/patch 及其原 manifest，核对本轮 baseline 对上轮 sources，并记录 v1/v2 原始目录和各报告/诊断的 SHA。新 replay 先复制本轮完整 baseline，实际运行 git apply --check 与 apply，最终六文件 SHA 与 frozen 相同；只读复制原fixture到 replay 后，实际检查四份源码语法及运行21项有限检查。原始命令/退出码见 replay.log，所有 SHA 见 `algorithm-process-runner-fixes-v2-sha256.json`。

该修复和21绿都不代表五真实算法场景通过。第三轮入口仍暂停，等待同一独立审查者复核新差异后由 root 再授权。没有猜测修改其他配置；当前已知本工程 engine Redis 必要配置按实际 NOAUTH 证据补全，其他具体异常需以后续真实观察报告处理。
