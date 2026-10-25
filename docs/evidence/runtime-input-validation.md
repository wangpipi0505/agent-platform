# 运行入口输入声明校验与 START 一致性

本次只改本工程 `RunService.java` 的共有创建入口与 `FlowDriver.java` 的 START 分支，并新增 `RunInputValidationPostgresTest.java`。复用已独立审核通过的 `InputDeclarations`，没有改 helper、迁移、其他节点、业务提交、服务契约或冻结详设。未访问相邻旧工程，未启停、部署或提交代码。

## 问题与实现

root 已通过真实网关及运行服务验证：DEBUG 缺必填/错误类型/minLength、EVALUATION 缺必填、正式 CHAT 缺必填共五类请求均创建新 run，各留下模型调用。根因是 `createInternal` 只检查 inputs 为对象，准备快照后直接进入模型冻结与持久化，没有执行应用输入声明。

共有入口现于准备快照及受控复现守卫之后、模型冻结和 PG run/attempt 创建之前执行 `InputDeclarations.validate(snapshot, req.inputs)`。DEBUG、评测、正式 TASK→CHAT、受信会话快照、重执行和受控复现均走此入口。已接纳 requestId 的幂等读取仍在此前返回，不因当前草稿声明变化改写或重做旧请求；未修改幂等冲突判断或越权边界。校验不补默认值、不转换类型、不改写输入。

同时发现旧 START 的 `hasNonNull` 将冻结图契约明确允许的 `type: NULL, required: true` 显式 null 当成缺失，而其逐字段 JsonGate 子集会漏掉 minLength 和额外 config.inputSchema。该问题由 root 扩围授权同批修复：START 也调用同一完整 helper，保留旧持久化 READY 节点恢复时的防线；helper 的业务异常转换为 `RuntimeFault(PARAM_ERROR, inputs, reason)`，继续走原节点失败与无隐式重试链。没有删除 START 校验，也没有放宽必填或 STRING 的非 null 类型约束。

实现仅复用本工程已阅读的声明转换及 networknt 1.5.6 边界，不新增依赖，不声称通读未阅读的上游源码。原 graph 契约的 NULL 和其他类型枚举保持不动。

## 验证范围

测试以真实 PostgreSQL 16.15 的本工程专用 `agent_platform_engine_test` 执行迁移及持久写入；由 `EngineTestDatabase` 限制为本工程 PG25432。完整 engine 回归另有既存独立输入/统计测试库，未对开发运行数据做清理。HTTP 边界使用明确的 RuntimeClients 协议 fixture，分别计数应用元数据读、模型绑定冻结、所有 POST 和模型发送；它们不代表真实供应商/IAM 验收。

新增 27 项：CHAT 三入口×缺失/错误类型/minLength 共九项；FLOW 三类入场拒绝；受信会话入口拒绝；两套声明同时有效；非法外部 Schema 拒绝；三入口完整 typed 值保留且不注入默认值；显式 required NULL、false、0、空字符串/数组/对象执行正例（并验证缺失 NULL 字段与 STRING=null 仍拒绝）；持久 READY START 的必填、类型、minLength、额外 Schema 必填和非法声明五项；有效会话 TASK→CHAT 一次模型调用；旧 requestId 优先读取；有效冻结 FLOW 的重执行和零业务 HTTP 受控复现。

所有新拒绝场景断言没有新增 run/node/attempt/model_invocation/tool_call，也没有模型冻结或付费发送。持久恢复场景则断言既有 START 以 PARAM_ERROR 失败且无 POST/模型调用。正例等待终态，失败测试收尾只取消本测试租户的非终态运行，避免与后续清理竞态。

Java 21.0.12，Maven 3.9.16，依赖缓存为本工程 `.tools/m2`。本工程 `.env` 仅载入进程环境且不打印内容，`ENGINE_PG_TEST=true`。基础命令：

```text
mvn.cmd -B -ntp -pl engine-runtime test
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2
  -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

定向命令增加 `-Dtest=RunInputValidationPostgresTest`；无 `-am`，不占其他模块 target。日志原始输出在本工程 `.local/`，并复制到本报告同目录保留证据；日志含既有 JDK/Mockito/Lettuce 警告，不称零警告。

| 证据 | 实际结果 | 完成时间（+08:00） |
| --- | --- | --- |
| `runtime-input-validation-pg-red.log` | 原生产代码：21 项，15 failures，0 errors/skipped，预期失败 | 06:20:34 |
| `runtime-input-validation-pg-green.log` | 仅入口修复：21/0/0/0，成功 | 06:21:02 |
| `runtime-input-validation-engine-full.log` | 第一阶段 engine 全模块 351/0/0/0，成功；尚未修改 START | 06:22:39 |
| `runtime-input-validation-start-harness-red.log` | 首轮恢复用例误沿 fixture 默认 STEP 暂停；保留诊断，不作为正确恢复红证据 | 06:23:15 |
| `runtime-input-validation-start-red.log` | 显式修正 fixture 为 FULL：27 项，4 failures，0 errors/skipped；NULL 误拒、三项恢复漏验，预期失败 | 06:23:44 |
| `runtime-input-validation-start-green.log` | START 修复：27/0/0/0，成功 | 06:24:17 |
| `runtime-input-validation-final-engine-full.log` | 最终生产源码 engine 全模块 357/0/0/0，成功，包含 required NULL 与五类持久恢复 | 06:26:05 |
| `runtime-input-validation-final-green.log` | 全模块后仅补强同一 NULL 测试中的缺失/STRING=null 反例，格式化后的最终测试源码再次编译：27/0/0/0，成功 | 06:26:37 |
| `runtime-input-validation-format.log` | google-java-format 1.25.2，三个 Java 文件 `--aosp --dry-run --set-exit-if-changed`，exit 0 | 本轮 |
| `runtime-input-validation-contracts.log` | 快照、四契约原文哈希、七模块依赖边界通过，exit 0 | 本轮 |

最终生产代码先通过 357 项全模块回归，再仅增强测试中的两个反例并运行最终 27 项定向轮；没有把最后定向轮写成又一次全模块回归。本报告不把局部回归当作 34 项任务或生产验收全部完成；真实服务重新部署后的五项 API 复验由 root 执行。

## 文件边界

冻结摘要随 `runtime-input-validation-verification.json` 交付。只读依赖 `InputDeclarations.java` 必须保持已审 SHA256 `d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89`；本次未修改该文件。原 contract 快照及四契约原文摘要不变。
