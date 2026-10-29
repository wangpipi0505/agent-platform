# EX-27 MCP终态发送修复与PhaseA Full707：非作者最终复核

2026-10-29，审阅者 `algorithm_interrupt_readonly`。结论：接受本次MCP修复子步及PhaseA精确子批的相对验收，可分别准备本地提交。新Full707无新增非通过；唯一旧迁移用例的原文差异，经过本报告限定到精确原件对的逐字符与身份关系复核，可以单独接受为夹具表示变化。原始 `collectorExit=1 / rawReasonComparisonPassed=false / regressionGatePassed=false / allGreen=false` 保持原样；这不意味着原6F、106E、4S已修复，也不关闭完整PROCESS文件I/O、EX-27或EX-06。

本审阅者未编写MCP新增测试、transport生产guard、E675 Green/Class入口或ex04编写的E8D Full入口。曾编写PhaseA GREEN30的4CA采集器，故不把该入口自审当独立批准；其真实非作者复核仍为 `docs/evidence/2026-10-29-EX-15b/phase-a-green-review.md`，SHA `D7F720EE4EE39D9F7E35CBF9E760FC74E4F8012257BE7C3B8CB0D85EE5DCFE02`。相邻88的非作者报告 `phase-a-adjacent-review.md` 保持SHA `0964B62F432DCAB443B61338200A60CDA0C3FC7491829AFF39625B49A839CA6B`。审阅者也曾参与既有SDK测试与取证；本文只重核其本轮实际身份、结果和既定witness，不替代当时的源实现独审。

本文仅读取源码、既有原件及XML，复算身份、SHA、字段和字符关系；没有运行Java/Maven/PG/HTTP/Node/WSL/服务，没有读取环境凭据正文、修改任何旧原件、source、index或三主入口。存储判断来自主线程实际运行的前后收据及已核采集代码，不冒称审阅者重新连接了PG/MinIO。本报告不是后续公开打包清单、Git属性或精确index的独立审查。

## 1. 真实RED、最小guard、GREEN及整类

所有下列MCP actual run均位于 `.local/ex27-mcp-terminal/`，测试实际HEAD为 `73dc4cf37a2894f5c20fe806908011091888383b`。各次原件分开保留，不把较后manifest倒填为前一次输入。

| 阶段及actual run | 实际manifest SHA-256 | 实际runner SHA-256 | 实际结果 |
| --- | --- | --- | --- |
| `mcp-terminal-red-20261029-01` | `6BB982AA8988FD20690C1B7DA876286C28829ECDD54B4C8DACE00A850D12970B` | `4F9EAAF0DB9481494E2597734539C2C6CEDEAD514B2D300DEA31E21752E8055F` | 1F/0E/0S，native1/collector0；collector0仅表示精确目标RED |
| `mcp-terminal-green-20261029-01` | `BAB05CBBF9DFF785ED5D98C8CCD937EAA483FC9D19FA8D5ACDEA499A6F099966` | `E675B8A217A4CE0EBF3E547CE15F68351841744DD82994E58993A6F11E0E1061` | 1P/0FES，native0/collector0 |
| `mcp-terminal-class39-20261029-01` | `C56F864883DF377D1FBACB28BED5A32368016C0E37FD687187F693E60B2ADD67` | 同E675 | 39P/0FES，native0/collector0 |
| `mcp-terminal-full707-20261029-01` | `4B139DFF99633A388575A10FCB141FEFEF3317B6D479C477A963C34FF1AF2E91` | `E8D11D6B1369A3233925C80705E5B75E96AC9D5AA5EB54BB21776B821C9A55A9` | 707=591P6F106E4S，native0(ignore)/collector1，原严格门false |

关键结果原件：

| 对象 | SHA-256 |
| --- | --- |
| RED summary | `CADF072112CF1891711717C85D5A8D29EBDD18BF0E9273362AD91A861D75C7CB` |
| RED唯一XML | `055CD684C9791B6214FD472728EC1B77DC1619210292659EAC0F88EBDCCF87F6` |
| GREEN summary | `331C6FE4BE8B80C710E945F42AD977C2A3E4AED53CEF86B4AEC1689E045ABE17` |
| GREEN唯一XML | `3DDD5297F4AA9EA67B794C68704D4D871AFC230A8376CB8976B53C019BF3F82E` |
| Class39 summary | `10110C5B8AFDA8A2297A5D3895DEB883F95C3D3DA6CEDFCF6686A8C491E1E3EE` |
| Class39唯一XML | `6B41E48785421D6CEC7D4713C8755FCD9024B55C99C6C09CD81A328CE5391CFE` |
| Full707 summary | `EDDAA0996DC6173C2173F263300C54B1D137FD34D7F7342789F1016D7CC1AFF0` |
| Full707原始bounded候选 | `C05049EC2D7DA11BE2A391C2138B8828A12CE026EF76B0DC049F45B4CE398775` |

RED只有 `closedSessionDoesNotDeliverLateAckFailureToCancelledRpc` 最后693行 `MCP_TERMINAL_ACK_LATE_ERROR_DELIVERY ==> expected: <0> but was: <1>` 的AssertionFailedError，唯一failure、无error/skip或suppressed清理失败。此前真实订阅、Peer首ACK写出并flush、send仍阻塞、实际RPC CANCEL、800ms内send返回与doFinally、1800ms内worker/transport实际终止、firstFailure保留和所有RPC/业务零调用断言均已通过。首ACK是服务端实际flush见证，不扩大称客户端已读到正文。真实SDK0.10的sendRequest独立订阅send并安装error consumer，没有把该send订阅登记为RPC取消的子资源；因此该受控窗口确实观察到cancel返回、close后仍交付一次send错误。新方法不要求旧实现必然onErrorDropped。

测试SHA始终 `068002325D7D224FB685DBB3476AEBD27CEDFF170430E115BDF1008ADC9F282F`。原36个注解方法块和AfterEach逐字保留，patch新增203行、删除0；36旧注解块展开38身份，新默认方法增加1。没有修改800/1800ms或原2秒AfterEach预算，也没有用skip、全局Hook吞错或假订阅代替真实SDK/HTTP。

transport由RED的 `44B1A81C554A9BE6B3E1B11A1881D467B347C9D1B2E604FE4147E95FA38C0C77` 变为 `83E9568637311DDAC603B4780124F996C68560B5D6E97C5A1C75CC2E005260B7`，仅catch的3增1删：先快照closed或deadline已到，再fail(ex)保留首次失败，仅非terminal继续throw。不会因fail的后续副作用吞掉活动期错误；没有改变activeSends实际回收、callSent、unknown、SDK/POM或生成RPC成功响应。该快照不是原子取消协议；新确定性向量覆盖closed分支，不能宣称穷尽所有竞争或支持长期共享client。

真实GREEN保持同一测试全部断言，得到1P；真实Class39对原38加新1逐完整身份核齐、全P、无缺失/意外新增/skip，CSV与XML字段一致。原Full706失败的 `blockedAckBodyCannotOutliveTheWholeSessionDeadline` 在Class39及同Full707均恢复P。403的forbiddenPost、429的quota、businessPostRejection三个403/429/500参数、ACK独立上限、读写断连、unknown、droppedBusinessPostAck、writeToolError及不重发断言均实际执行通过，没有用定向1P代替整类。

## 2. HEAD与完整源码交接

原Full701实际HEAD为 `acad6bdee223b23f331420c6de50045cb5fa8941`。历史PhaseA GREEN30实际输入17184/runner4CA，Adjacent88实际输入6E42/runner8A93，Full706实际输入BB6D/runner8A93，三轮均保留HEAD `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`。GREEN30/88/Full706的152源前后完全一致。

当前MCP GREEN1/Class39/Full707为73dc，6ca到73dc在skill-registry/admin-common/pom.xml的提交范围diff为0；历史context仍按旧6ca与旧4CA/8A93验证，不要求旧6ca等于新73dc，不重写旧manifest。实际受测工作区另由152源清单绑定；不能把HEAD本身解释为以下候选已提交。历史PhaseA到当前只有两个精确MCP增量：Bounded transport44B1→83E9及Test EBB60E→068002。

当前相对原Full701恰7个批准路径，三生产、四测试；其它145路径原样：

- `skill-registry/src/main/java/cn/agentplatform/skill/domain/ProcessContract.java`（8D8030…）
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java`（00D30C…）
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/BoundedMcpSseTransport.java`（83E956…）
- `skill-registry/src/test/java/cn/agentplatform/skill/domain/ProcessContractTest.java`（94A0E0…）
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillProcessRegistrationTest.java`（65ED5A…）
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessFilesTest.java`（6E4A4A…）
- `skill-registry/src/test/java/cn/agentplatform/skill/infra/McpAdapterTest.java`（068002…）

本次Full before/after/current152源逐路径SHA零差异。Full采集器为新E8D；此前未执行2E18/495D候选的ownership数量错误及遗漏settings项已修正并另存，不能冒称那些候选运行过。

## 3. Full707完整身份及PhaseA精确子集

独立读取原Full701和本次全部XML，再按classname/method/display_name/suite_path合并原701与PhaseA新增5、MCP新增1：707唯一身份、73类，缺失0、额外0、状态变化0，CSV的status/record_kind/type/cause/首帧/message/detail与XML差异0，解析问题0、类级事件0。全部6新增方法P。原4个skip身份逐项相同，无新增skip；不宣称所有源方法均被发现。

另外重读历史GREEN30的3份XML和Adjacent88的10份XML形成各自的完整身份集，分别映射到本次Full实际XML：30/30与88/88全部P，均缺失0、非P为0、旧解析问题0。这是实际子集复核，不是从总数推断。PhaseA仅覆盖已冻结的布尔开关与输出路径边界；不证明完整文件搬运、持久化、回放和所有路径安全义务。

原116非通过中115项message/detail原始hash均相同，且116项的record_kind/status/type/cause/项目首帧全同。唯一变化为下一节的固定旧迁移F；它仍是F，不被本报告改成通过。MCP39在同Full也逐身份全P，和已独审整类39一致。

## 4. 唯一旧迁移表示差异：只接受此精确原件对

身份固定为 `cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest|v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity||cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest`，两侧XML中该完整身份均唯一，仍是原AssertionFailedError，cause及首帧（原123行断言）不变。

| 固定字段 | 原Full701 before SHA-256 | 本次Full707 after SHA-256 |
| --- | --- | --- |
| 该类原始XML | `86F9B9243C3608D345592F06F101F4E329691876E179460B10D61874962A4CDC` | `0F15AEF88A0B9B098CBE6D2A4024D76A7D26DC5CED66ECA775B2F9F8F1B71B8E` |
| message | `47624B06110E4C148C93CF527F5C9838138E0C64AD39D39FD8692393831BFD2B` | `94752089BD12A0FF5B361AC876C53F28C99530C965856283CF0385B6B9851020` |
| detail | `178D477E0ED474AE85215AB8F903983F8C1679D7E4BA6D39F382A2AF216BD86C` | `49BEE01C99C6EC40A0947058AB75E26BC29FDC56883266DC040667F731C8F3B4` |

message从原XML属性解码，detail采用既有reader的CRLF→LF口径；上述hash与两侧原CSV分别吻合。没有修改原XML、CSV或summary。

独立检查每个字段恰2个UUID槽位及2个时间槽位；进一步只允许完整固定前缀 `tenant_id":"algorithm_upgrade_` 与 `updated_at":"` 后面的值，不把其它字段、UUID、SQL数值、栈行号或文字纳入替换。源码21行确用UUID.randomUUID生成该fixture tenant；V307的external_job_binding.updated_at为timestamptz DEFAULT now()。按照已匹配的四个具体位置替换成各自序号占位，仅作内存验证，两侧剩余全部字符严格相同：

| 字段 | before UTF16偏移（UUID/time/UUID/time） | after UTF16偏移 |
| --- | --- | --- |
| message | 540 / 592 / 1283 / 1335 | 540 / 592 / 1284 / 1336 |
| detail | 577 / 629 / 1320 / 1372 | 577 / 629 / 1321 / 1373 |

固定前缀及顺序相同。时间值精度长度31→32解释后半位置加1；UUID长36不变。这里不是删除所有匹配内容后宽泛放行。

联合message与detail核身份关系：UUID域4次出现，before恰1唯一fixture值、after恰1唯一fixture值；updated_at域同样4次、各1唯一值。两个域的old→new与反向映射均唯一，每一对出现位置的相等/不等关系全部保持，仅映射实际变化值。没有把原同tenant/时间变为不同值的情况吞掉。故接受的是这一个run、这一个身份、这两份原始XML及四个字段hash对所界定的表示差异；不是future豁免或通用normalizer。

原 `bounded-volatile-proof.json` C05049…只提供候选，原collector没有自动接受；本报告补足独立位置、完整剩余字符和联合双向重复绑定审查。summary仍保留unexplainedReasons1及所有原false门。旧迁移失败本身、旧Full706的MCP失败和新受控RED全部继续留存。

## 5. fresh、依赖、存储与安全向量

Full本次native记录区间为 `2026-10-29T14:53:23.0772254Z` 至 `14:56:43.4072913Z`。73份XML均不早于起点，逐份bytes/SHA与本次xml-manifest一致；raw.log现SHA与归档摘要相同。native0是failure.ignore=true的采集结果，不能称全绿；最终collector1与原false门没有改写。

before/after189 context逐项核一致且与当前实文件SHA匹配，包括MCP的11工具/配置文件和179个本AP自有JAR（main重合一次后去重189）。Maven conf/settings.xml固定SHA `2F552325AEDD9A9C9268DF520BE22CFE3AA86260B76A0F830D2796D2F0E22BD4` 未遗漏。全部73份实际java.class.path的完整值SHA为 `C4A9BC77B5786D2084E37124AFE408B048C6CB13371282804C7813FE33B04406`；唯一common的完整路径指向本AP .tools/m2，main JAR为 `0FBB83F6E6ADCF9E36BA27345602AA93AA4769D863CF2635FE0357EC7DA29A93`，tests JAR不在CP。tests JAR仍保守pin0CA864…，不谎称其被加载。旧Full701实际1B依赖历史不倒写。原1024MiB/900秒、default fork/reuse/order、五库原测试范围保持，未clean/install/-am、改预算或跳过断言。

五个固定既有库：agent_platform_v12、agent_platform_skill_algorithm_test、agent_platform_skill_algorithm_migration_test、agent_platform_skill_digest_test、agent_platform_ontology_import_test。前后收据均为PG25432/PID15204/本仓已核数据目录，owner/schema/history owner均agent_v12；逐库1条合法rank0 SCHEMA加16条300–315 SQL，script/version/checksum/rank/installedBy/success吻合固定来源，五组前后17行历史全字段相同。MinIO29000前后健康。采集器Read-Pg/Check-Storage与旧8A93逐字相同，read-only及5秒statement_timeout，没有collector DDL、未知建库、reset或清队列；原Full两命名迁移专库的原测试重放仍在已授权范围，不把重放说成collector写库。

同Full中AlgorithmOffloadInterruptionTest原5身份全P，4个精确stage/handoff outcome齐：PUT32ms、GET24ms、GET_BODY普通24ms、GET_BODY handoff22ms，均interrupted=true、inputClosed=1、firstQuiescent=true；本轮handoff未命中explicitUnconfirmed分支，不能据此覆盖旧取证现场。diagnostic0。AlgorithmOffloadResponseOwnershipTest原1身份P，唯一原witness原文为：

```text
ALGORITHM_RESPONSE_OWNERSHIP awaitInterrupted=true originalFutureSuccess=true sameResponse=true stoppingBeforeCallerClose=false stages=0 calls=0 retainedOriginalCall=false
```

## 6. 接受范围与后续提交

本报告接受MCP guard与对应真实RED→GREEN→Class39，以及PhaseA已冻结边界在同Full707中的精确相对回归；支持MCP、PhaseA两个独立本地提交准备。后续仍需精确公开文件清单、必要Git证据属性、index逐blob审查及真实提交回执。本报告不批准夹入其它卡或原始私密日志/凭据，不替代该index审，也不把提交准备写成已经提交。

完整PROCESS文件I/O、EX-27与EX-06保持开放；本次原6F/106E/4S和历史失败原件保留。未发生无条件修改原严格结果、清理历史测试记录或对未来运行自动接受表示差异。
