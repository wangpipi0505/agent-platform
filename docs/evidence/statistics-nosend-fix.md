# T26 / MODEL_SEND 的 NO_SEND 粒度修复

日期：2026-10-20。仅独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform`。已读 AGENTS、implementation-brief、contracts/README、statistics-t26、statistics-api、review-statistics 最后追加 P2 以及 model-quota-nosend-i3。采用系统调试、TDD、最小实现及完成前验证。未访问相邻旧工程，未改配额业务语义、迁移、engine、monitor 或前端，未启动/停止业务服务、未提交 Git。

## 修复与口径

`ModelCalls` 的同步和流式生产者都会先提交 `call_log`，再执行真实 `quotaSending` 的 PG `RESERVING → SENT` CAS；CAS 失败时仍保留 FAILED/null-usage 日志，而独立预留最终是明确 NO_SEND。旧统计把这行当成发送，错误增加 calls/failed/usageMissing 并把同组完整总 token 置 null。

两处统计现在共用一个 `NOT EXISTS` 条件：仅当 `quota_reservation.tenant_id = call_log.tenant_id`、`reservation_id = call_log.quota_receipt->>'id'` 且 `status='NO_SEND'` 时排除日志。使用复合主键上的存在性判断，避免关联放大；不按可能重试的 invocation_ref 关联。真实 SENT 缺 usage、历史空 receipt、receipt 无匹配预留以及尚未确定 NO_SEND 的 RESERVING 日志均保守保留，未知 usage 的 totalTokens 仍为 null。

`ModelStatistics` 的空/全 NO_SEND 运行返回 `byRun=[]`。旧 `ModelStore.statistics(Actor)` 的同粒度汇总返回 calls=0、usage_missing=0、known_tokens=0、tokens=0；只在缺 usage 条数为零时对总和作空集零值处理，不把真实发送未知 usage 伪造为零。API 字段形状不变。已只读核对 monitor `emptyModel/finishModel`：无模型行自然保持 calls/knownTokens/totalTokens=0；未修改或重跑 monitor，不把静态核对称为新的跨服务联调。

状态变化由新 PG 快照重新分类：NO_SEND 持久确认暂时失败时仍保留未知发送统计；租约恢复完成 NO_SEND 后的新查询排除同一原日志。业务异常日志和配额审计流水均未删除或改写。

## 真实生产者与数据边界

新增 `ModelNoSendStatisticsPostgresTest`，使用独立 `agent_platform_model_test`、本工程 PG 25432、Redis 26379 及本地测试 HTTP 对端。fixture 完成供应商真实连通探测后清零业务请求计数。仅在 `ModelStore.quotaSending` 的受控边界将已存在真实 reservation 的 PG lease 置过期，然后调用真实 `super.quotaSending`；`sending` 日志提交、ModelCalls 同步/流式路径、实际发送 CAS、finally 与 Redis Lua 均走生产代码。

覆盖六个执行用例：

- 同步和流式各一次日志已提交后真实 CAS 失败：业务 HTTP 计数 0、FAILED/null 日志保留、NO_SEND/RECONCILED 持久、应用与用户日额归零、TPM/RPM 原桶退款且重复恢复不重复退款。全 NO_SEND 统计无发送行；随后同一运行真正发送一个完整 usage=12 的请求，只统计 calls=1、failed=0、missing=0、known/total=12。
- 同步和流式各一次 NO_SEND PG 确认方法故障：初次为 RESERVING/未知，换新 ModelCalls 执行真实租约恢复后 NO_SEND、账务恢复且统计归零。
- 恢复先于发送 CAS 获得 NO_SEND 的场景，仍阻止网络发送并排除已提交日志。
- 真正已发送但供应商无 usage：业务 HTTP 计数 1、预留为 SENT、统计 calls=1/missing=1/total=null，恢复不零退款。

`ModelStatisticsPostgresTest` 新增三个真实 PG fixture：known12+NO_SEND 的最小复现；相同 reservation ID 外租户以及同 invocation 不同 receipt 的 NO_SEND 不得排除本租户发送，重复 runIds 不放大；SENT 未知、历史空 receipt、孤立 receipt 均保留未知。既有三项权限/规模闸、重复 runIds/缺 usage、跨租户统计继续保留。

## TDD 与验证记录

全部 Maven 命令只加载本工程 `.env` 至当前进程，不回显任何值；Java 21，本工程 `.tools/m2`、`V:/.local/tmp`。按照 root 已安装的父 POM/admin-common 依赖，仅执行 `-pl model-gateway`，不带 `-am`，避免共享 common target。未执行会启停服务的 `scripts/test.ps1`。

| 日志 | 实际结果 |
| --- | --- |
| `.local/statistics-nosend-red.log` | 最小真实 PG fixture，1 test / 1 failure / 0 errors/skips；calls 预期 1，实际 2；05:01:11 |
| `.local/statistics-nosend-producer-red.log` | 6 tests / 5 failures / 0 errors/skips；五个真实未发送路径的统计仍含 FAILED/missing 行，真实 SENT 未知对照通过；05:02:52 |
| `.local/statistics-nosend-targeted-green.log` | 17 tests / 0 failures/errors/skips，统计6 + 新生产者6 + 既有 quota PG5；05:03:48 |

定向命令：

```powershell
mvn.cmd -B -ntp test -pl model-gateway `
  '-Dtest=ModelStatisticsPostgresTest,ModelNoSendStatisticsPostgresTest,ModelQuotaPostgresTest' `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

最小红使用 `-Dtest=ModelStatisticsPostgresTest#confirmedNoSendDoesNotPolluteKnownUsage`；生产者红使用 `-Dtest=ModelNoSendStatisticsPostgresTest`。最初生产者 fixture 曾将供应商探测 HTTP 也计入业务计数，修正探测后清零再跑上述有效红轮；没有把这一 fixture 错误算作产品缺陷证据。

Java 使用缓存 google-java-format 1.25.2 的 `--aosp` 四空格；ModelStore 仅格式化统计方法局部。`node scripts/verify-contracts.mjs` 已实际通过 v1.2 冻结快照、四契约原文哈希与七模块依赖边界检查。

## 验证边界

NO_SEND PG 确认故障为方法边界可控注入；lease 过期、CAS、持久状态、实际恢复及退款执行真实 PG/Redis，不声称做过数据库/Redis 停机、进程 kill 或网络崩溃实验。测试 HTTP 仅证明本地真实 SDK 生产者路径，不证明外部模型、生产身份或容量验收。流式预期发送前拒绝会产生 Spring AI MessageAggregator 错误日志，以 Maven 断言和请求计数判断结果。

本次新增9个测试执行项。root 先前整轮313项不作为本次增量源码的验证，未重跑 engine，也未声称关闭整个 G3。

## 最终 model 全量与评审交接

`.local/statistics-nosend-full-green.log`：**66 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS，2026-10-20T05:05:32+08:00**。构成为原 ModelCalls33、既有 quota PG5、Redis13、policy3、statistics6、新生产者6。原57加本次新增9为66；本轮不含 common 测试。

```powershell
mvn.cmd -B -ntp test -pl model-gateway `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

最终四个源码/测试文件的 SHA256（实际 Get-FileHash，供 root 的 late mutation reviewhash 清单及原评审者复核；该表不冒称既有评审快照仍匹配新源码）：

| 文件 | SHA256 |
| --- | --- |
| `model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStatistics.java` | `78b9511de3fa5dd04fb126058d7c8aa4d8df69d6a0da9d993ef033d6a11668c4` |
| `model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java` | `c6a2ef98b9b214f07d9e7ed63e3214177579f6dba5e2b25c3d0002003f8003d7` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelStatisticsPostgresTest.java` | `f9805694a3622767ac9b6ccdb0bfdc9521067b79717393e2656bd6c98d40045a` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/ModelNoSendStatisticsPostgresTest.java` | `965385ee835a7f4b4c809ed27583fac928c62808a1d4aa38534d279e7c06058a` |

本报告是实现者证据，交 root 安排原统计评审者针对 NO_SEND P2 独立复核；不代替评审者修改 review-statistics 的结论。服务没有重启/重打包，本增量尚未进入此前 root 启动的业务进程。
