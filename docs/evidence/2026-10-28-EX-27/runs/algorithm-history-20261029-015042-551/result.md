# 原Full历史PG只读核查：恢复未确认收尾分支事实

2026-10-29。仅一次有界只读业务查询，未运行Java/Maven/HTTP、修改源码/状态/主入口/index或准备新模式。PG只读进程结束后已立即明确归还窗口；后续仅读保存的匿名JSON和源码原件分析，没有追加PG查询。未使用Superpower。

**找到原精确Full窗内唯一符合GET_BODY失败边界的两行组：目标记录仍为CLEANUP_PENDING，保留ALGORITHM_IO_STOP_NOT_CONFIRMED，updated_at仍是原失败时段、cleanup_attempts=0、deleted_at=NULL。** 结合实际当次9DEB源码唯一码写入路径，可恢复“当次offload catch已观察stopNotConfirmed=true并持久化清理态”的事实；不是当时内存/网络现场，也不直接判完整保留义务已满足或产品缺陷。

## 原时间、来源和清理边界

- 原EX03 `full-20261028-234917-584/native-exit.json`：2026-10-28 **23:49:19.2381126～23:52:48.9248179+08:00**，本次created_at范围严格沿此窗，余量0秒。
- 原类XML SHA `4C2EC4084E8918C27FDA2A20A0BF06721764E5AE7D7E7C5F7C3504E76E491F36`没有testsuite timestamp属性，不能补造；user.timezone=Asia/Shanghai、suite time=2.35秒，原manifest记录mtime=23:51:29.0757423+08:00。四case顺序为独立未确认向量P(0.543s)、PUT P(0.637s)、GET P(0.638s)、GET_BODY F(0.455s)。mtime只作一致性旁证，不反推精确case绝对起止。
- 原Full源清单保存Test B06F…、ArtifactStore **9DEB5156…**；现行Store完整SHA与原相同。已读原HEAD测试块及实际Store，不用新诊断P覆盖原红。
- 原参数化方法唯一使用`algorithm_interrupt_`前缀：先暖场call+1，再目标call；仅全部后续断言走过后才共享复用call+2、令本tenant产物过期并cleanTenant。GET_BODY原F停在首次quiescent断言，之前实际peer.entered/worker退出/failure非空已过，后续复用/PG清理未到；finally只释放peer/interrupt/join及关闭store，不清PG行。
- Store `offload:425–432`只有algorithmIo非空且stopNotConfirmed=true时将last_error_code写为ALGORITHM_IO_STOP_NOT_CONFIRMED，同时CLEANUP_PENDING、updated_at=now()、cleanup_after=now()+1h。产品其它writer不产生此码；unconfirmed异常消息本身不是PG写入。
- `deleteObject:601–605`只标DELETED/deleted_at/updated_at，不清旧码；`cleanTenant:669–670`认领时增attempts并coalesce保留旧码，`:685–688`失败会覆盖OBJECT_DELETE_FAILED_*。global cleanupPulse可枚举到期tenant。因此updated_at若在窗外并不自动使旧码无效；本次目标实际未出现这种后续修改。

## 实际读取范围与匿名结果

preflight先验证本仓PID15204、25432、V:/.local/pgdata及**既有**agent_platform_skill_algorithm_test库/skill schema/history的agent_v12归属和17行严格SCHEMA/SQL分类；未创建、reset、DDL或接管不明库，未读OR。

独立PS5.1加载AP.env并finally恢复；preflight和read-history均先ParseFile零错误。唯一业务SELECT只读该库skill.artifact，条件是上述created_at原窗＋精确固定前缀（left=20且LIKE显式转义），SQL最多13行检测上限，statement_timeout=5000ms、lock_timeout=1000ms、read-only、connect timeout5秒。不以updated_at过滤，不扫其它业务表或租户范围。未选择/输出真实tenant、call、object_key、ref、run或凭据；组名为md5匿名值，call仅输出0/1/2相对偏移。

实际01:55:04.369～01:55:04.443+08，native exit0，**8行/3组、无截断**；原SQL与安全输出分别保存在history.sql/history-facts.json，完整scope/source在window-and-source.json。

| 按创建先后匿名组 | 行数/相邻call | 当前状态与码 | 与原源码/报告的对应推断 |
| --- | --- | --- | --- |
| 83cc5be4… | 3条，创建顺序偏移1→0→2，call跨度2 | 全DELETED、各attempts1；目标保留OFFLOAD_FAILED | 完整暖场/目标/共享复用与清理，匹配原PUT P |
| 04289415… | 3条，同上 | 同上 | 匹配原GET P |
| **4a1f36f638529f7aeb7b45ad8816c37f** | **2条，创建顺序1→0，call跨度1** | 暖场READY/null码；目标CLEANUP_PENDING/**ALGORITHM_IO_STOP_NOT_CONFIRMED**；两行attempts0/deleted_at NULL | 唯一缺共享call+2且未清理组，匹配唯一GET_BODY F |

最后一组暖场created=23:51:28.637353、updated=23:51:28.773666；目标created=23:51:28.830032、**updated=23:51:29.065114+08:00**，cleanup_after=2026-10-29 00:51:29.065114+08:00（精确+3600秒）。所有目标/暖场更新均仍在原窗，目标未被清理/覆码。组级READY1属于暖场，**不是目标曾READY**。

“这是GET_BODY原失败组”是基于精确时间窗、此方法独有前缀、三个调用组的3/3/2唯一形状、相邻call/创建角色、原参数与case顺序、失败停点及XML mtime的相互匹配推断，PG本身没有stage列。没有仅凭一条错误码跳过身份核实；未发现另一可匹配的两行组或截断歧义。

## 可恢复和不可恢复的结论

该唯一组与原失败路径可靠对应，且updated_at原窗内、attempts0、未删标、原码未覆盖，故不只是“某时曾留下此码”的弱事实：在本仓当次相同Store唯一writer路径下，恢复出原目标offload catch的**stopNotConfirmed=true/CLEANUP_PENDING/+1h延后清理**分支事实。原Full首次false不能再按“缺码、完全无法区分是否未确认”处理。

这没有恢复interrupt/返回时间、客户端阶段、当时stage/Call/executor的原子分项、异常图、SDK原future持续所有权/实际有限退出、后续网络/DELETE计数，也不能把之后未置READY或PG未变代替这些内存/IO义务。caller中断/输入close等原断言因首次F未到，也不因这次PG查询补计PASS。**显式未确认分支被证实，不等于完整合法保留/无产品缺口已经证实，更不能将原无条件正例断言直接改成两种都通过。**

原Full FAIL及后续各P原件继续分别保留。本次仅提供可供root/独立技术门使用的原分支事实，不关闭EX03、EX27或产品卡；本代理到此停止，没有再测、再查或改源。
