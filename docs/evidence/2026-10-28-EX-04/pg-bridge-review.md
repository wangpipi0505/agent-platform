# EX-04 PG 读回桥独立复核

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。只读绿色技能回执、实际 XML/JSON、桥接快照与两 Test 差异；本轮仅新增本文件，未修改产品、测试、index、旧件，未运行 Java/Maven/PG/HTTP、环境或仓库脚本，未读凭据，未使用 Superpower。

**结论：PG 桥来源与两个 consumer 的字节/SHA 一致，未削弱断言或改变原 22 方法/辅助语义；技能侧 33 绿色与四类零写入已由独立只读复核确认。未发现阻断后续最终 47 绿色及完整回归的问题。本报告不预签尚未读取的 green-engine/Full/OR 结果。**

## 实际来源与 consumer 身份

生产者轮次为 `collector/runs/green-skill-20261029-103602-527`。实际 Projection XML SHA256 为 `30220156caf1644beca88d4ef634511a333171d18df84b783ba0bdb4ddae0921`，与该轮 xml-manifest 及桥 provenance 一致。

独立从该 XML 中精确选择 `postgresReadEditPublishKeepsOffsetAndExactApiKeyHeaders` 的 system-out，找到唯一 `EX04_PERSISTED_PROJECTION=` 行；去掉记录分隔换行后的实际输出与该轮 `ex04-persisted-projection.json` 逐字一致，为 137 个 UTF-8 字节。SHA256 为 `0e99aae36146e3b35a06f9755e2532c1fed3202e095cd5b9cbc89efeedf8b2d9`。该 JSON 只含 pagination/exposure：OFFSET、startAt/takeRows、maxPageSize=6、maxRows=2、QUERY，没有凭据、租户、端点或原业务响应。

独立解析两份 Java 的字符串拼接常量（未执行 Java），逐个 JSON 解码字符串字面量再拼接，两个结果均为同一 137 字节，与上述生产者输出逐字相同；两处声明的 SHA 也与独立计算值一致。桥目录中保存的 JSON 同样逐字一致。

| 当前 consumer 测试 | 原始源 SHA256 |
| --- | --- |
| `ToolOutputPolicyTest.java` | `666c61bb5e46290be05921a6f6bdc1ff171bb509fe206adf247b0ea20b8a1b51` |
| `RunToolProfileTest.java` | `53d4c07f97011f0f4a364044419fdd0375622353751755f67b8ff2db854c6afc` |

两个当前源均与 `engine/pg-bridge-prepare-20261029-104115-791837/*Test.candidate.java` 完全一致。这里证明实际 PG 生产者输出到固定测试数据的内容衔接，未新增运行期 PG/HTTP/文件依赖；engine 的技能 client 仍是既有协议替身，不称实时跨服务运行或 J4。

## 桥改动没有削弱测试

桥 diff 仅在新增测试区域增加固定 JSON/源轮次/SHA 标记、每类各一条默认执行的 Digest 断言，用真实字段替换旧 skip/take/37/7 样例，并把对应精确预期改为 startAt/takeRows/6/2。Policy 的有效案例改用包含这两字段的完整 projection 作为 contract；其余错误信封、六键提示、无 page/response/nextOffset、空 observed、原始结果与 20 项数组不变的断言均保留。Profile 继续调用实际 prepareSnapshot 并检查完整 definition 与各字段，再将冻结 tool 交给实际 Policy。

新 offsetDeclaration 每次重新解析固定字符串，参数化畸形案例不会共享被修改的树。21 个畸形实例、原方法签名与 47 报告身份未改，没有条件绕过、移除断言或新增 skip。独立静态计数为 Policy 78→79、Profile 64→65 次 assertion 调用。

再次只读反撤六条新 import 和整个新增块后，Policy 原 10 方法及全部旧 helper 与最初 before 原件逐字符一致；Profile 原 12 方法及全部旧 helper 同样完全一致。因此原 22 方法/辅助语义没有因 PG 桥变化。当前身份仍为 Policy34＋Profile13＝47，原22＋新25。

## 技能绿色回执的独立复核

独立解析该轮 methods.csv：Importer 18、Projection 15，全部 status=passed。native-exit 为 0、failureIgnore=false；test-summary 为 33P/0F/0E/0S，缺失/意外/非通过均为空。Projection 三个 PG 方法在实际 XML 中均存在且没有 failure/error/skipped。

精确选择 `postgresInvalidLaterItemsLeaveAllDraftCountsUnchanged` 的 system-out，获得四条 `EX04_BATCH_COUNTS`，每条均 before=0、after=0、drafts=0、rejected=true，与保存的批次 JSON 一致。结合冻结测试中四项固定顺序，这次分页、QUERY、风险降低、类型组合四类均完整到达；补足真实红只到首类失败处的覆盖边界。

该轮 source-before/after 各 150 项且逐项完全相同。三产品源 SHA 与已签 `skill-candidate-review.md` 一致，Importer/Projection Test SHA 仍为 `4b2573fa…`/`ef0d3b60…`。因此本轮通过的是已审冻结断言：真实 PG 创建/读回、省略保存保留字段与摘要、显式 null 保存失败后原定义/摘要保留、改分页改变摘要、治理/发布/再读、两次实际 HTTP 的准确 X-API-Key 与空 Authorization；高风险只读与 MUTATION 的 confirmRoles 发布门也已到达。这里依据实际方法通过和已核冻结断言签收，不冒称另行直连查询数据库或复抓外部流量。

## 剩余门

最终 green-engine 应使用本表两个 consumer 源身份及已审生产 Policy `5d2596babb01a047fc24f8db4e3534eae22bc4e2820f45231299a2d3f91ffebb`，检查 47P/0F/0E/0S 与源冻结。随后完整 skill/engine 的逐方法/原因门、旧 skip/类事件/未执行源边界及 OR 透传复验仍须独立审阅；不得以本桥报告或 collector 退出码替代这些结果。
