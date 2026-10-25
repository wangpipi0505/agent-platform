# 命名测试输入与数据集提升独立评审

评审时间：2026-10-20 06:07–06:11（Asia/Shanghai）。评审者：独立 review_test_inputs agent。结论：**REQUEST_CHANGES：1 项 P2；未发现 P0/P1。** 本报告针对 `test-input-verification.json` 冻结的 18 文件版本，不将随后修复自动计入通过。

## 范围与依据

仅访问新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 及评审技能；未读取相邻旧工程。已阅读 AGENTS.md、docs/implementation-brief.md、contracts/README.md、test-input-plan/report/verification/e2e/migrations 证据、冻结详设快照册30 §1.7、§4.2、§5.4。采用 code-review-expert 的权限、并发、边界与代码质量检查流程。仓库尚无初始提交，依据明确的 18 文件 SHA 清单评审，而非虚构已提交 diff。

已逐文件计算 SHA256：**18/18 匹配，0 不匹配**，包含 root 的 Routes.java / RoutesTest.java。InputDeclarations.java 审阅版本 SHA256：`a3c850ced7c647bf9bcf224322d8f33c808fe122a71c173ef6b2d200a642ea65`。执行 `node scripts/verify-contracts.mjs` 返回：`v1.2快照、四契约原文哈希与七模块依赖边界通过`。

## 发现

### P2：按整个 JSON 树扫描引用，错误拒绝合法业务字段及注解数据

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/domain/InputDeclarations.java:87–93`，尤其第93行递归进入每个容器、第90–92行对所有对象中的 `$ref/$dynamicRef/$recursiveRef` 作引用判定。

`properties` 是字段名称到子 Schema 的映射，其自身包含名为 `$ref` 的字段完全合法。`const`、`default`、`enum`、`examples` 中的对象属于实例数据，也可以合法包含这些名称。现有递归没有区分 Schema 位置与数据位置，因此合法草稿声明在命名输入保存、读取、更新、首次提升时统一返回400 `INVALID_INPUT_DECLARATION`，无法使用这些业务输入。

独立 Java21 JShell 探针直接调用冻结版本 `InputDeclarations.validate`，classpath 为现有 `engine-runtime/target/classes` 加 `engine-runtime/target/test-input-classpath.txt`，未写业务代码：

| 探针 | Schema / 触发点 | 实际结果 | 应有结果 |
| --- | --- | --- | --- |
| business-property | `{"type":"object","properties":{"$ref":{"type":"string"}}}`，输入 `{"$ref":"business-value"}` | 400 INVALID_INPUT_DECLARATION | 接受 |
| business-const | `{"type":"object","const":{"$ref":"http://example.com/business"}}`，输入为同一个实例对象 | 400 INVALID_INPUT_DECLARATION | 接受 |
| business-default | `{"type":"object","default":{"$ref":"business-value"}}`，输入 `{}` | 400 INVALID_INPUT_DECLARATION | 接受且不补默认值 |
| network-ref | `properties.q.$ref = http://127.0.0.1:1/private` | 400 INVALID_INPUT_DECLARATION | 拒绝 |
| file-ref | `properties.q.$ref = file:///C:/Windows/win.ini` | 400 INVALID_INPUT_DECLARATION | 拒绝 |
| missing-unused-ref | `properties.q.$ref = #/$defs/absent`，输入 `{}` | 400 INVALID_INPUT_DECLARATION | 拒绝 |
| local-ref | `$defs.word.type=string`、`properties.q.$ref=#/$defs/word`，输入 `{"q":"ok"}` | 接受 | 接受 |

建议按 JSON Schema 关键字语义递归：映射容器只遍历其子 Schema，数据与注解不进入引用扫描；保留加载器阻断外部解析和 `initializeValidators()` 对未出现属性引用的提前初始化。增加上述正负回归，再考虑复用至通用运行入口。不能为修复误拒而直接放开网络／文件引用。

## 已核对的不变量

- `TestInputService.actor`、`DraftMetadataService.get`、`DatasetService.actor` 显式检查 EDITOR；内部入口另需 SERVICE，不通过 ADMIN 隐式替代。真实 HTTP 测试覆盖验签后身份、普通用户调用内部入口、ADMIN-only 拒绝。
- engine 的草稿信息通过独立 ServiceTokens 票据走 HTTP，app 以 tenant + 真实 draft ID 查询自己的 schema；engine 再核对返回 draftId 与原 appCode，拒绝不存在／不可用草稿。新业务代码不存在跨 schema SQL 或跨服务 JAR 引用。
- 命名输入 CRUD SQL 均限定 tenant 与 created_by；更新与删除以 revision CAS；列表限定 1–100 项并使用 ID 游标，剥离 inputs。详情、更新与首次提升校验当前草稿声明。inputs 为已解析对象，有64KiB与16层限制；不存在默认值注入或字符串到数字／对象强转。
- 提升请求限定字段集合，expected 必须显式出现（显式 null 合法），safety 必须布尔，拒绝请求自行覆盖 inputs。源 inputs 来自 PG 指定 revision；请求摘要含 inputId 与完整目标请求。
- `debug_input_promotion` 在短事务内以 INSERT SELECT 的 revision 条件冻结源与目标完整 payload；tenant + owner + requestId 唯一。HTTP 在事务结束后执行。重试先比对完整请求摘要，再走持久 payload，不因源更新／删除改成新 revision；同 requestId 不同内容409。重试仍核对草稿可用与归属。
- monitor 按 tenant + app + datasetVersion 的已有唯一约束，以及新增 tenant + promoted_by + requestId 唯一索引争用版本；ON CONFLICT 不覆盖内容，随后比对摘要与 owner。其他 owner 即使提交相同内容也不能认领已有版本。读取／列表限定 owner，列表去除 cases。
- V502 只添加可空来源列、完整性约束与部分唯一索引；旧 dataset 允许全部新增字段为 null，未重写 cases。评分仍消费原 `{id,inputs,expected,safety}`，来源独立保存。
- 既有 EvaluationService 的不可变 cases 比对、安全用例要求、候选摘要／修订绑定、安全通过率与基线规则未被本增量放松。新代码未增加 MinIO 操作；提升链不存在事务内 HTTP。网关只新增指定管理入口，`/internal/datasets/promotions` 不透出。

未发现需要删除的闲置实现或为本增量引入新框架的必要；保留现有分层与依赖。

## 本评审亲自执行的定向测试

Java21；模块独立 Maven `-o -f <module>/pom.xml`，**无 -am**；仓库专用 `.tools/m2`；临时目录 `V:/.local/tmp`。载入本工程 `.env` 后显式将 `AGENT_DB_URL` 指向 `jdbc:postgresql://127.0.0.1:25432/agent_platform_test_input_test`；三个测试类也显式绑定该测试库。没有连接 `agent_platform_dev_v12`，没有启停、替换六个开发服务，只有测试用随机端口 Spring 上下文。

各命令追加：`-Dmaven.repo.local=<新工程绝对路径>/.tools/m2 -Djava.io.tmpdir=V:/.local/tmp "-DargLine=-Djava.io.tmpdir=V:/.local/tmp -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"`。

| 命令 | 完成时间 | tests / failures / errors / skipped | 结果 |
| --- | --- | --- | --- |
| `mvn -o -f engine-runtime/pom.xml -Dtest=TestInputHttpTest test` | 06:09:29 +08:00 | 9 / 0 / 0 / 0 | BUILD SUCCESS |
| `mvn -o -f monitor/pom.xml -Dtest=DatasetHttpTest test` | 06:09:43 +08:00 | 3 / 0 / 0 / 0 | BUILD SUCCESS |
| `mvn -o -f app-management/pom.xml -Dtest=DraftMetadataHttpTest test` | 06:09:57 +08:00 | 1 / 0 / 0 / 0 | BUILD SUCCESS |

覆盖真实 PG 持久化、CRUD 与 CAS 并发单赢家、tenant/owner隔离、ADMIN-only拒绝、输入大小／深度／类型、声明变更复用、响应中断502后旧快照恢复、目标内容冲突、源 revision 冲突、monitor 请求争用两个版本单赢家及服务票据边界。Surefire 结果已当场核读。测试中的草稿／monitor替身仅存在测试代码；跨域真实评测另见作者证据。

## 证据层级与范围限制

作者全模块证据分别为 app35、engine147、monitor18，root gateway15；它们各自时间和范围独立，**不声称同轮418或本评审重新执行全部回归**。

已阅读三临时 classpath 进程的 E2E 脚本与最终 JSON：独立测试库／随机端口；monitor 实际提交后代理断 TCP；编辑源后仍恢复旧 revision；实际 START→END 得到 ACCEPTED，错误 expected 得到 REJECTED，无安全用例400，同版本改内容409；expected 在执行前硬编码而非从输出回填。最终记录 cleanedUp=true。本评审未再运行该脚本，避免覆盖作者证据。

已阅读迁移脚本、SQL和结果 JSON：空库103/214/502与升级103/213/501→103/214/502，升级保留旧dataset；脚本拒绝覆盖已存在库。本评审未重新执行创建库迁移，未将作者运行当成本人运行。

本轮不声称正式／DEBUG／EVALUATION通用 RunService 输入声明校验已经接入，不声称完整多用例数据集治理、真实供应商评测或容量验收完成。原18文件版本应先修复上述 P2；后续修复必须带新 SHA 与定向正负回归复核。

## 附录：P2 修复独立复核与闭环（2026-10-20）

**本附录结论：APPROVE；上述唯一 P2 已关闭，本次修复范围未发现遗留 P0/P1/P2。** 前文 REQUEST_CHANGES 保留为原18文件冻结版本的历史评审，不覆盖其原始证据。

复核输入是 `input-schema-literals-fix.md` 和 `input-schema-literals-verification.json`。实际重算清单内两个源码文件、报告和六个日志：**9/9 SHA256 匹配**。`InputDeclarations.java` SHA256 为 `d7e3ea02fc12fb6574513fabc759fd978c2c42b4c3c340e9bfb46207644b5e89`；独立新增矩阵测试 SHA256 为 `713c4eaae7104ea60d7d671ea14f688a2dbfe90fd29bd614e6a062776697635e`。修复仅涉及 helper 与该测试，未将 root 后续 RunService 入口接线工作纳入本结论。

源码复核确认：遍历只进入 Schema 映射的值、组合数组的元素和单个子 Schema；properties 等映射键不被当成引用；const/default/enum/examples 数据保持原义。真实 Schema 位置仍拒绝非本地 `$ref/$dynamicRef/$recursiveRef`，收集的子 Schema（含未使用定义）逐一初始化，外部 schemaLoader 阻断仍保留。不存在默认值补入或输入强制转换。

本评审使用独立 Java21 JShell 进程，classpath 取已编译 helper 与原 dependency classpath，**没有运行 Maven、没有占用或修改 target、没有连接数据库、没有改业务源**。运行23项带断言的探针，全部符合预期，且每项检查 inputs 调用前后相等：

| 独立探针组 | 数量 | 结果 |
| --- | --- | --- |
| `$ref/$dynamicRef/$recursiveRef` 三种合法属性名及其 minLength 失败分支 | 6 | 合法值接受，短值400 INPUT_SCHEMA_CHANGED |
| const/default/enum/examples 中业务 `$ref`，另含 enum 不匹配 | 5 | 字面量接受，enum 不匹配400；default 不修改空输入 |
| 未使用 `$defs` 中三种真实引用关键字 × HTTP/file | 6 | 全部400 INVALID_INPUT_DECLARATION |
| 未出现属性与未使用定义中的缺失本地引用 | 2 | 全部400 INVALID_INPUT_DECLARATION |
| 本地引用跳到 default 再转 file 引用 | 1 | 400 INVALID_INPUT_DECLARATION |
| 合法本地 `$defs` 及错误类型 | 2 | 合法值接受，错误类型400 INPUT_SCHEMA_CHANGED |
| 本地递归对象树 `$ref:#` | 1 | 接受有限嵌套对象，未出现初始化递归异常 |

JShell 最终输出 `PROBE_PASSED=23`，无断言失败。原报告中的 business-property、business-const、business-default 三个误拒均已由独立探针转绿；网络／文件拒绝、缺失引用拒绝与合法本地引用仍成立。

另外按摘要核读作者日志：183项矩阵先出现28 failures/0 errors/0 skipped；修复后 engine 全模块330/0/0/0于06:14:44完成，格式化后重新编译定向195/0/0/0于06:15:11完成。它们属于修复作者执行，本评审不将其重记为本人回归，也不与前轮 app35/engine147/monitor18/gateway15 汇总成同轮结果。

此附录只关闭输入声明 Schema 字面量误拒及相应引用初始化边界问题；不代表后续运行入口校验、完整项目交付或外部验收完成。
