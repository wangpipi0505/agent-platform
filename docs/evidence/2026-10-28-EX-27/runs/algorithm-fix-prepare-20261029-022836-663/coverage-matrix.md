# 五报告候选：默认覆盖矩阵

本表是**代码覆盖设计**，尚未编译/执行，不将“存在断言”冒充断言通过。Test SHA `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D`；产品ArtifactStore仍9DEB，只读。

| 默认报告身份 | 用途与硬门 |
| --- | --- |
| 原 `interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]` PUT | 保留原正常严格首次q=true及停止/安全义务 |
| 原同方法 `[2]` GET | 同上，原响应头停点 |
| 原同方法 `[3]` GET_BODY | 改为有意建立客户端正文正常前提：一个总1s预算内的peer.entered＋同次worker栈readJson/Exchange.ResponseBodySource.read/Okio.InputStreamSource.read/socket读；stages0、原Calls恰持有GET、owned dispatcher running/queued0、原executor active/queue0；满足即interrupt，不等q或Calls空，未达即FAIL |
| 新 `originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety` | 原始GET_BODY peer停点后即interrupt；永不事前取栈/反射/PG/诊断，包括旧debug模式。该向量每轮可确认或显式未确认，按下表双向分类，不保证命中未确认 |
| 原 `unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit` | 确定性有限Source未确认；原12处断言、retained/q=false、两处routes0、500ms真实退出/retained=false、800ms join及1500ms有限source等待保留，补完整后置；无HTTP暖场 |

三个@Test声明（一个ParameterizedTest＋两个Test）对应3＋1＋1＝5报告；仍70源测试类。未来完整skill预期664原＋9导入＋1新handoff＝674，4旧SKIP/原116非通过另核。原Full F不覆盖，不称原场景未变的复现或产品修复。

| 义务 | 原正常向量 | 新handoff：无码确认 | 新handoff：精确未确认 | 原有限Source |
| --- | --- | --- | --- | --- |
| 首次q固定在PG/后续诊断前 | 原别名/原严格q=true保留 | 必须true | 可false或已恢复true；不抹去已报未确认 | 原q=false断言改用同位固定布尔，义务相同 |
| 异常/PG码 | 共用同一分类，严格q不放宽 | 异常图无精确码且目标PG为OFFLOAD_FAILED | 精确class=IllegalStateException、message全等ALGO码；PG必须同ALGO码 | 新增精确异常图断言＋保留原PG ALGO断言 |
| 双向一致/不按q简单二选一 | 异常码与PG ALGO布尔相等，其他码严格核 | q=false/无码FAIL；异常/PG不一致FAIL | q=true仍按ALGO核持久态及完整后置 | 两端都ALGO |
| 输入关闭、caller中断、无成功返回 | 原断言 | 同原断言 | 同原断言 | 原flag/failure＋新增close计数 |
| 目标PG唯一pending、精确码、无READY | 共用queryForMap＋rows1/ready0/state/code | 同左 | 同左 | 同左，另保留原state/code查询 |
| 延后/无提前认领删除 | cleanup_after=updated＋1h且仍未到期、attempts0/deleted NULL；主动cleanTenant=0且route/DELETE不变，再核PG | 同左 | 同左，即使原IO尚未静止也不得早清理 | 同左，停点基线0，不发HTTP |
| 固化停点路由基线 | peer每个目标停点在countDown前CAS(-1,当前已接收序号)一次 | 同左 | 同左，晚请求不能重置基线 | 基线0，不清routes/计数 |
| 返回后至显式释放前无新请求 | 在PG前、PG/早清理之后核同一固定序号与routes/DELETE | 同左 | 同左，不能用晚计数当基线 | 原routes0＋新增accepted0/DELETE0 |
| 释放后原真实资源退出 | peer.finished与原operation.q共用原1s收尾预算，证明原stages/Calls/owned executor终止；40ms保留 | 同左 | 同左，未确认不等于已释放 | 原500ms循环/断言原字节语义保留 |
| retained精确边界 | 不对network一律要求true | 不要求 | 不统一要求true（允许executor单独尾段） | 原确定性Source仍要求retained=true→真实退出后false |
| 释放后至有意shared前无新请求/READY | 同固定基线，40ms后、PG后、诊断后及shared helper入口再次核；pending/ready0 | 同左 | 同左 | 原两次routes0保留，500ms后pending/ready0、accepted0/DELETE0 |
| 原shared真实后续使用 | 已暖场共享对象再次实际PUT/GET，各artifact计数精确＋1、返回引用非空 | 同左 | 同左 | 无暖场的原shared首次真实PUT/GET，不能称“已暖场复用” |
| 主线程identity/边界清理 | helper显式设置本新UUID actor并finally clear，外层也clear | 同左 | 同左 | 同左，避免原Source主线程无身份 |
| 最终有意清理 | 原IO已确认退出且截止基线检查完成后，才清stage、共享调用call+2并按原方式过期/clean本新tenant | 同左 | 同左 | 同左，只清该Source新tenant；不改历史UUID |

异常精确匹配使用IdentitySet遍历cause/suppressed，最多64节点，只比较固定码，不打印message。泛化SDK异常不是未确认许可。正常向量即便出现已报未确认而q已true，也不会改写PG码；其原q=true严格义务继续成立。

有限peer仍可能自然超时；before-peer-release只表示显式release前的位置，不能据它或release计数认定handler仍阻塞。更晚分项不反推首次q的原子分项。允许已接收原请求收尾，不把无新请求偷换成远端事务回滚；共享使用/最终清理有意请求与此前目标停点区间分开。
