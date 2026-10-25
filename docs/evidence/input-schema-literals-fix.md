# 输入声明 Schema 字面量误拒修复

本次只修改 `engine-runtime/src/main/java/cn/agentplatform/engine/domain/InputDeclarations.java`，新增独立测试 `InputDeclarationSchemaPositionsTest.java` 和本组证据。对应原评审 `review-test-inputs.md` 的 P2；不覆盖原评审、`test-input-report.md`、`test-input-verification.json` 或原冻结输入包。未修改 OutputPolicy、RunService、调度器、迁移或契约，未提交、部署或启停现有开发服务。此修复不表示全部 34 个任务完成。

## 根因与处理

旧 `localOnly` 对每个 JSON 容器递归，因而把 `properties` 内名为 `$ref` / `$dynamicRef` / `$recursiveRef` 的字段，以及 `const/default/enum/examples` 中同名业务数据当成 Schema 引用。使用真实 networknt 1.5.6 的红测试实际复现：合法数据误拒 27 项；另发现未使用 `$defs` 内缺失本地引用漏拒 1 项。

新遍历只进入标准 Schema 位置：Schema 映射的值、组合数组的元素和单个子 Schema；兼容既有 legacy `definitions/dependencies/items` 数组形式。映射键是业务名称，实例字面量保持原义。收集这些位置后逐一 `initializeValidators`，包括未使用定义；保留原有拒绝外部加载的 schemaLoader，因此本地 `$ref` 即使跳到 `default` 等非标准位置，再转外部引用仍被拒绝。Schema 深度上限仍为 64，按实际子 Schema 深度计算。

保留完整已解析值校验：不补默认值、不把字符串转换成数字、不改写 inputs；合法本地 `$defs` 仍按其类型、minimum 等约束校验。错误文案由“当前草稿声明”改为“当前输入声明”，通用 helper 可供后续运行入口复用，但本报告不声称已接入 RunService。

已完整读取本工程当前 `OutputPolicy.java` 的 Schema 位置遍历与显式初始化机制后借鉴，未修改该文件，也未声称通读或复制未阅读的上游实现。已读 AGENTS、implementation-brief、contracts/README 及原输入交付证据；未访问相邻旧工程。

## 实际验证

Java 21.0.12，Maven 离线使用本工程 `.tools/m2`，networknt 仍为 1.5.6。进程内读取本工程 `.env`，不打印凭据。PG 位于本工程独立 25432；定向 HTTP 测试使用 `agent_platform_test_input_test`，全模块测试显式 `AGENT_DB_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test`、`ENGINE_PG_TEST=true`。既有统计测试使用自身专用 `agent_platform_statistics_test`，未接入 `agent_platform_dev_v12` 运行库。无 H2 或内存数据库替代。

公共 Maven 参数（PowerShell 参数分别传入）：

```text
mvn.cmd -o -B -ntp -f engine-runtime/pom.xml
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2
  -Djava.io.tmpdir=V:/.local/tmp
  "-DargLine=-Djava.io.tmpdir=V:/.local/tmp -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"
  test
```

| 证据 | 额外选择 / 结果 | 完成时间（+08:00） |
| --- | --- | --- |
| `input-schema-literals-red.log` | `-Dtest=InputDeclarationSchemaPositionsTest`；183 项，28 failures，0 errors，0 skipped，预期 BUILD FAILURE | 06:12:15 |
| `input-schema-literals-green.log` | `-Dtest=InputDeclarationSchemaPositionsTest,InputDeclarationsTest,TestInputHttpTest`；195/0/0/0，BUILD SUCCESS | 06:12:57 |
| `input-schema-literals-full.log` | 无 test 选择；engine 全模块 330/0/0/0，BUILD SUCCESS | 06:14:44 |
| `input-schema-literals-final-green.log` | 格式化后的最终源码重新编译，原定向三类 195/0/0/0，BUILD SUCCESS | 06:15:11 |
| `input-schema-literals-format.log` | 本工程缓存 google-java-format 1.25.2 `--aosp --dry-run --set-exit-if-changed` 对两个 Java 文件验证，exit 0 | 本轮 |
| `input-schema-literals-contracts.log` | `node scripts/verify-contracts.mjs`；v1.2 快照、四契约原文哈希、七模块依赖边界通过，exit 0 | 本轮 |

全模块回归编译后仅运行上述两个文件的 AOSP 格式化，最终定向轮再次编译并验证格式化结果；未将纯格式后的定向轮写成又一次全模块回归。日志中保留既有 Mockito/JDK 动态 agent 警告；不能称为零警告。

新增 183 项包括：3 个保留字属性名；24 个注解/字面量组合，检查允许合法值并继续执行 const/enum 约束；150 个外部引用矩阵覆盖三种引用关键字、HTTP/file 两种地址、25 种直接/嵌套 Schema 位置；4 个缺失本地引用；1 个本地引用跳至注解再转外部的 loader 边界；1 个合法本地定义及严格数字类型约束。原 3 项输入声明测试与 9 项真实 HTTP 测试一起复验。

## 最终文件摘要

`input-schema-literals-verification.json` 列出本次两个源码文件、报告和日志的 SHA256。原 `test-input-verification.json` 不重写，原 InputDeclarations 摘要为 `a3c850ced7c647bf9bcf224322d8f33c808fe122a71c173ef6b2d200a642ea65`；本次最终值为 `d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89`。本轮只证明该 P2 修复与 engine 回归，不替代独立复核、运行入口接线或完整业务验收。
