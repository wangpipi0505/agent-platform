# EX-27：SSE 等待修正与最终 Full 933 非作者复核

日期：2026-10-29。审阅者：`ex04_pg_scope`，不是本次 `B633` SSE collector、`6D892893` 原因派生器或 `0216` SSE 测试修改的作者。

**结论：这组四个源码文件及明确安全的配套证据，可以进入本地提交；本次定向通过、相对原基线的完整回归通过。原 Full 原始原因门仍为 false、collector 仍为 1、allTestsGreen 仍为 false，不能写成全绿或关闭整个 EX-27。新空库 migrate 仍由 EX-06 后续实际验收承担。**

此次仅读取文件、Git HEAD 中指定源码、XML、SHA 与 PowerShell AST，未执行任何 Java、Maven、PG、HTTP、Node、WSL、服务或采集器，没有改动 index、三主入口、产品、测试、既有报告和原件。唯一新增文件为本报告。这里给出实质审阅结论，不替代稍后精确 staged 集合和秘密检查。

## 1. 准予提交的源码范围

| 路径 | 当前原始 SHA-256 | 接受范围 |
| --- | --- | --- |
| `engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql` | `419C753C652B326DBD59DE40409F080A16F26B98787A7F8CF870B6ECF2859A90` | 恢复已执行历史/正式包使用的精确 286 字节兼容资源，checksum `701700386`；不是新增业务写入能力 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineMigrationsTest.java` | `66EF434AB8ABB818599C69C9E0BF1D80C02087CFB7CD05CFD8AB577F2FF6109F` | 仅新增资源存在、286 字节、固定 SHA 和版本 222 断言，保留原 3 个方法 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java` | `021629A09E978A177F98764AF28BA7B96569F707F27DAE4DC85994657F1747F8` | 两个原等待条件加真实 owner 计数，不改生产生命周期、不删断言、不延长限时 |
| `scripts/verify-cache.ps1` | `0A2A9402D6B9D34116874ED644E8FDB956FA326642CF431910305B8AB690D920` | redis 登记 JSON 读取仅增加 `-Encoding UTF8`，归属比较、命令与无 `-Stop` 使用不变 |

V222 的原件、历史库只读 validate、24 SQL/列结构与旧失败保留，沿用已完成的 [history-and-reasons 独审](v222-history-and-reasons-review.md)，SHA `677CFEDACFA42BD2E0A5B77FC3D688C78BED421998C57CE9E765496F819858DF`。缓存编码修复沿用 [cache-utf8 独审](cache-utf8-review.md)，SHA `59FE8890134479821B35DC85D498D28AA03CB35F155A17954F30A17603B48372`。两份报告本轮字节未变，未重跑历史库或缓存命令。

### 1.1 SSE 原失败的原因与补丁强度

旧实际 Full 目录 `.local/ex27-v222-recovery/v222-full-933-20261029-02/` 保留原失败 XML：

`xml/TEST-cn.agentplatform.engine.infra.PortalRunEventsLifecycleTest.xml`

SHA `EC99D4336BF480BA950BB4150E4B290B0FF9B86386349B4EBC7D1EC42339D399`。实读该方法原 failure 仍为最终 frame 断言 `expected 62 / actual 61`，旧原始 gate=false 没有被覆盖或移走。

源码因果链支持测试观察时点不完整的归因：

- `PortalRunEventsHttpTest.java:85` 的 `h.exits` 在 `AsyncContext.complete()` 包装器 finally 中增加；它只说明 complete 已返回。
- 生产 `application/PortalRunEventsService.java:194–197` 在 complete 返回后才调用 `release()`；`:136–137` 按顺序关闭 pending reader、frame、response、slot，最后 `owners.remove(this)`。
- 旧测试只等 `h.exits`，可在实际资源 finally 尚未完成时读取 frame 数。新增 `actualOwnerCount(service)==1/0` 将该观察点推进到对应资源归还之后；第二 getter 仍由原独立 latch 阻塞。

补丁只增加计数 helper/说明、保留 service 引用，以及把原两个等待条件分别加强为 `exits>=1 && owners==1`、`exits==2 && owners==0`。本复核将这几项修改精确逆变换后，与 Git HEAD 的原测试全文比较一致（仅统一文件行结束以作文本比较）。因此原 11 个方法、所有原断言、两个原 4 秒 await、两个 `Future.get(4, TimeUnit.SECONDS)`、容量失败、frame `60/61/62`、writes=0 和原 finally 放闩行为均保留；没有新增 sleep、重试轮次或放宽预期。

生产 `PortalRunEventsService` 没有因这次测试修正而改变。反射只读取本次实际 service 的现有 owners 集合，没有新生产接口或伪造资源计数。

## 2. 两次实际运行与新鲜来源

两次实际 collector 均为 [run-sse-regression.ps1](v222-source-recovery/run-sse-regression.ps1)，SHA `B6336D82040C5192BF781956822A48E6C652D51AE1053EBB981405945D634B08`。

| 实际目录（相对 AP `.local/ex27-v222-recovery/`） | 执行时 manifest SHA | 实际结果 |
| --- | --- | --- |
| `sse-owners-target-20261029-01` | `331ECB1FC00362EA7C8222BC44EEC33C2742CEE233DA93443AFD8E10998B0A5E` | 1 新鲜 XML，原 11 方法全部 P，0F/0E/0S，native 0、collector 0 |
| `sse-owners-full-933-20261029-01` | `C5FB5C6777B0EF596182CDB3A5ED00ACFBACCCCE558DA61BC6257079C53FF225` | 93 新鲜 XML，933 = 818P + 35F + 80E + 0S；931 具名方法 + 2 原类事件；native 0、collector 1 |

Target 真实运行窗口为 `2026-10-29T13:18:51.9565185Z` 至 `13:19:50.8204884Z`；Full 为 `13:29:30.6558605Z` 至 `13:41:15.6092788Z`。逐份核验 XML manifest 的 SHA、长度和 mtime：各自全部报告的时间均落在本次 native 开始/结束之间，未借用旧 surefire 文件。

Target XML 中实际 11 个 testcase 无 failure/error/skipped，方法集合与 EX04 原基线相同。Target `summary.json` SHA 为 `5E31A001555F79CB2F4AA947B36CB68CD596ACD370D5EA18827A066A226DDB59`。

Full `summary.json` SHA 为 `69EC49AF9956F764B896FCD3D5DEEF656632B4849254BC3DA5F13441F8414AFA`；`methods.csv` SHA 为 `356C10B1EF91C84F9824B51ECBEF408A76C1AD4E8D167D5E4C1B847E6E82B740`。其 method gate=true、原 strict reason gate=false、raw normalization=false、collector gate=false、allTestsGreen=false 均保留原值。

Target 明确 `failure.ignore=false`；Full 仅为保全原失败而使用既定 `failure.ignore=true`，因此 Full native 0 不是“所有测试通过”。两次均为原 `-Xmx1024m`、surefire timeout 900 秒、默认 fork/reuse/order；本 collector 不重新 clean/build/install，不增加 registry 参数。

Full 消费真正完成后的 `sse-target-receipt.json`，SHA `0861FF54262C5112A237603D7ECFDE7C42A2657112EE20B2E59FAD88BD1B7A71`，其 24 个 proof pin 全部独立核对。Target 执行时的 331E manifest 与后续封 Full 的 C5FB 各自保留原件，未把 Target 记成使用 C5FB 运行。manifest 中“封版时尚未执行 Full”的 provenance 是准备时记录，实际结果以本节真实 run 原件为准。

## 3. 源码、依赖、夹具和存储没有借新基线漂移

两次 run 的 source-before/source-after 均为 306，完整文件 SHA 同为 `390A04051EBEAA4AF91B8E56E38276F54C1CAFBA963684F763B316028908ACC8`。列表与各自 manifest 一致，本复核又逐一核对 306 个当前源码文件 SHA 均匹配。

与原 resource Green 的 306 列表比较，只有 SSE 测试路径一项变化：原 `3FFC0280107643AD942557F1EECF5A6DF5DA760EE9BB5979603DEAF973F3FBE8` → 本次 `021629A09E978A177F98764AF28BA7B96569F707F27DAE4DC85994657F1747F8`；其他 305 个文件原样。历史 validate 使用的是原 3FFC 快照，本次没有把 0216 倒填到旧 Green 或历史 validate 中。

两次 run 均独立核对以下 before/after 完整字节相同，并核对清单所指当前文件：

| 范围 | 数量 / 同一 SHA-256 |
| --- | --- |
| 已安装依赖 | 6，`11236879CBFB1DB4FF05B5FF4986A454AC55F3C9F1A04B5B8A36657B3784CCAE` |
| app fixture 实际 classpath 文件 | 317，`72D24D60C561DE08EDBA070067AEB30940F09151CFCF17CBDBE70F2B7AF65339` |
| built resources 证明 | `9F3A5C10831D23734EBAE2D91FA8C798578178951A8B43A796FE49FE2B811795` |

29 项固定 SQL catalog 为 engine 24 + app 5，当前每份源码 SHA 与冻结 catalog 一致；实际 built 证明记录 source/target/JAR 24 一致、CRC 校验通过，V222 仍为 286 字节。正式 engine JAR 仍为 `3D2BD7B517A5115725CE9ACE62A10CC7A2AE2816458AFAEFAA909B050B2D56C7`。这不意味着新建了数据库或重新执行迁移。

分别核验 Target 的 85 项、Full 的 110 项 context/history pin，以及工具指纹均匹配。两次存储原件的固定部分前后逐字段相同：

- 原 14 个业务/fixture 数据库 + `agent_platform_dev_v12` 的 admin/catalog 连接，共 15 个登记元数据项；角色/owner 均为 `agent_v12`、只读 on、5 秒、其他 client 0。
- `postgres` 仅查询系统 catalog，不计为新业务库或 DDL 授权。`test_input` 原 engine/history 缺失例外不变；没有补 schema 来消除其原 9 个 context error。
- Redis 登记 `AgentPlatformV12 / 7.2.16 / 26379` 的鉴权、INFO/CONFIG GET 元数据相同；MinIO PID 7860、创建时间及 health 200 相同。没有 stop/start/FLUSH/CONFIG SET 或对象操作。
- Target timeout 库清单 **9→9**、新增 0；Full **9→10**，只新增 `timeout_fix_261e227b71f14e7ca41116e62fd556fe`，owner 为 `agent_v12`。旧 9 库身份和 owner 全保留，新增项满足原 fixture 的精确命名域，collector 未清理。

这部分接受范围是已固定的两次实际窗口和既有 fixture 写入/重放边界；不授权未知库、旧队列清理、其他服务启停或新的迁移动作。

## 4. 新 23 项原因证明的独立重算

实际输出为 `.local/ex27-v222-recovery/sse-owners-full-933-20261029-01-reason-proof/`。

- [derive-sse-full-reasons.ps1](v222-source-recovery/derive-sse-full-reasons.ps1)：`6D8928936C53F3B24F7D5AD1F12DF26F335F1F5D63531AEEDB9B5B2FED8DE717`。
- [sse-full-reasons-manifest.json](v222-source-recovery/sse-full-reasons-manifest.json)：`268E04950049F0004D581C24318422EC54492A8F38DDFAC50EFCDBC0BECB7679`。
- `bounded-representation-proof.json`：`0E6414A16A1957A4AC262E759B1E48924D40F1281FCE791DB1FA49D28A3B9B96`。

实际输出目录中的 deriver/manifest 副本与上述 SHA 一致。三个原精确绑定函数与已审版本逐字相同；纯 AST 解析无错误，没有执行派生器。主线程已实际执行该文件派生 native 0；本复核另以独立内存逻辑直接读取原 XML，不调用作者正规化函数，得到：

| 独立重算 | 结果 |
| --- | --- |
| 固定输入 / 原 XML | 44 个 pin / 186 个 XML SHA 与长度全匹配 |
| XML 与 CSV | 原 932、本次 933 的每个身份、record kind、status、原始非通过 message/detail SHA 均一致，无重复或缺失 |
| 原基线状态变更 | 0；唯一新增为 V222 resource 测试且 P；SSE 原 P 在本次仍 P |
| 原 115 非通过 | 92 个 message/detail 原文全同；另 23 个只有限定身份值差异 |
| 差异字段 / 类别 | 46；16 个 Spring 对象身份 case + 7 个测试 connection identity case |
| 捕获 / 真正改变 | 438 / 306 次；未改变的 132 次仍参与身份一致性检查 |
| 捕获外剩余文本 | 484 段逐字符相同，未删栈或其他字符 |
| message/detail 联合身份关系 | 1,148 个同前缀成对比较一致，保持正反向唯一映射 |
| 原类事件 / 原未执行 source methods | 精确 2 / 14，身份和范围不变 |

每个捕获独立核 offset、长度、原值 SHA、固定前缀与顺序、严格 hex/有符号十进制形状；只允许固定这次 raw hash 对。代表性新 pair 为 `WebMergedContextConfiguration@1970351a` → `@3df1a517`，以及测试 `connection=29487705` → `connection=1840554474`。后者仍有冻结源码 `System.identityHashCode(connection)` 支持，未把业务值作为任意数字消除。

因此本次 `adjustedOriginalReasonComparisonPassed=true`、`adjustedRelativeRegressionGatePassed=true` 有独立证据支持；原始 strict reason=false、原 collector 1、allTestsGreen=false 均不能改写。旧 Full run 02 的 SSE 失败、旧 23 项 proof、旧历史失败/通过原件继续留存；新的相对通过没有覆盖旧结果。

## 5. 本地提交与剩余验收边界

同意将本报告第 1 节精确四源纳入本地提交，配套保存已审 collector、冻结 manifest、明确安全的 SHA/计数/元数据摘要和审阅报告，保留真实失败→修正→定向/全量→有限原因证明的来源链。冻结文件的行尾/字节保真应按既有精确路径规则处理，不能借此变更生产源码的仓库过滤规范。

本报告没有进行最终 index 审阅，也没有读取环境秘密做值扫描；它不构成对整个证据目录或 `.local` 的批量入库许可。私密 raw XML、raw log、环境/凭据文件以及尚未审查的其他卡文件不属于这里批准的安全证据集合；原件应继续保留，安全摘要不得反写原件。

本子步可记为：V222 兼容资源及缓存前置编码修复获得实际证据；SSE 测试观察窗口修正后 Target 11P；Full 在原失败保留的前提下相对基线通过。**新空库 migrate、EX-06 后续联合验收，以及 EX-27 其他残项仍未完成；不能把这次局部提交标成整个 EX-27 完成。**
