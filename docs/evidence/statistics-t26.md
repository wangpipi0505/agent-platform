# T26 / G3 统计后端实现与验证

日期：2026-10-20。范围仅新工程 `G:/207/项目/算法重组工具/代码/agent-platform`。设计输入为冻结v1.2第三部分§5.6/§1.4、管理F8与授权开发任务拆分A23/T26；已读AGENTS、implementation-brief与contracts/README。此报告不把整个G3（还含T25历史检索）或17项联合验收记为完成。

## 实现

1. `SkillStatistics` 以 `skill.execution_fact` 的 `(tenant_id,call_id)` 主键逻辑调用事实聚合，补时间/版本/用途/Mock分离、正式真实日成功率、错误语义与有界样本。UNKNOWN在原事实行转终态后重新归类，不新增逻辑调用；`duration_ms` 已由执行器累加业务实际段，对账只更新结果、不累加等待。一次技能报告在PG REPEATABLE_READ只读事务生成，事务超时15秒。
2. `RunStatistics` 从engine运行/工具调用权威表独立聚合运行、技能、确认量；确认事件在运行维度单独计数，不与工具多行做放大连接。技能 `duration_ms` 来源已核对 `ToolCalls.acceptFact`：按本attempt已接受时长差额累加，attempt_note对重复事实去重；不拿run/attempt墙钟冒充业务执行时间。
3. `ModelStatistics` 只读模型发送事实并按runId先聚合。usage缺失时总token保持null，保留已知部分及缺失条数。既有ModelStore统计去除“缺usage=0”的错误兜底并加ADMIN闸，未改计量、配额或业务调用。
4. monitor `StatisticsService/Controller` 通过内部短票据HTTP取得两个权威投影，以engine返回的runRefs关联模型统计；不读其他schema，不引用其他业务jar。ADMIN平台报表与普通owner技能统计边界独立。报告明确PER_SOURCE_SNAPSHOT；业务系统从冻结Actor属性投影，缺失不造值。
5. `V501__statistics_snapshots.sql` 提供实际持久重建：重读来源完成后，PG单事务插入完整快照与active指针切换；请求号与筛选摘要绑定、幂等，不接受客户端汇总。来源失败时保留旧快照。该机制不同于“再查询一次即假称重建”。不维护易失统计队列，数据库事实本身即重建来源。
6. ADMIN错误下钻只返回本租户最小运行/失败技能元数据，不开放其他owner I/O。普通技能样本仍使用owner运行记录入口。所有时间跨度、响应、分组、运行关联与样本均有明确硬闸，见[API交接](statistics-api.md)。

## TDD与新证据

所有新增PG fixture使用专用 `agent_platform_statistics_test`，不清理或写入开发库。仅使用本根.env的独立数据库/Redis凭据、Java21、`.tools/m2`与 `V:/.local/tmp`。已阅读scripts/test.ps1，定向命令沿用其环境加载方式与Maven设置；未运行会把既有测试fixture写到开发库的历史全模块脚本。

| 证据 | 结果 | 验证点 |
| --- | --- | --- |
| `.local/statistics-skill-red.log` | 4 tests / 4 failures / 0 errors | 旧数组不满足冻结对象契约；非法分组/跨度原未拒绝 |
| `.local/statistics-skill-green.log` | 4 / 0 / 0 / 0 skipped | 时间半开边界、正式/调试/评测、Mock独立、UNKNOWN更新、版本/owner过滤 |
| `.local/statistics-skill-drill-red.log` | 6 / 2 failures / 0 errors | ADMIN错误样本缺专用下钻、内部全租户统计缺ADMIN闸 |
| `.local/statistics-skill-internal-red.log` | 7 / 1 failure / 0 errors | 旧内部无界dump仍可访问 |
| `.local/statistics-skill-final.log` | 7 / 0 / 0 / 0 skipped，04:49:31 | ADMIN/owner链接分别可用；内部统计要求code并复用有界接口 |
| `.local/statistics-model-red.log` | 2 / 2 failures / 0 errors | 内部按可信run集合统计接口原缺失404 |
| `.local/statistics-model-green.log` | 2 / 0 / 0 / 0 skipped | 缺usage保留null、重复runIds不重复累计、ADMIN/大小闸 |
| `.local/statistics-monitor-red.log` | 3 / 3 failures / 0 errors | 原平台缺ADMIN和token合并，重建404 |
| `.local/statistics-monitor-green.log` | 3 / 0 / 0 / 0 skipped | 真HTTP票据/投影、缺usage、幂等持久重建、来源503保旧指针、外tenant404 |
| `.local/statistics-engine-drill-red.log` | 5 / 1 failure / 0 errors | ADMIN统计下钻原404 |
| `.local/statistics-engine-final.log` | 5 / 0 / 0 / 0 skipped | 04:46:35独占engine窗口；app/biz/mode、确认不放大、10001run拒422、实际技能段/Mock/UNKNOWN、ADMIN跨owner可读及跨tenant404 |
| `.local/statistics-final.log` | skill6 + model新3/既有quota5 + monitor4，均0 failure/error/skipped | 04:47:56非engine稳定回归；增加model跨tenant不贡献token、重建拒客户端total与请求号换筛选409；既有计量恢复回归仍过 |

选测命令（在仓库根，先按scripts/test.ps1方式加载.env并设置指定Java21）：

```powershell
mvn.cmd -B -ntp test -pl skill-registry,model-gateway,monitor -am '-Dtest=SkillStatisticsPostgresTest,ModelStatisticsPostgresTest,StatisticsPersistenceTest,ModelQuotaPostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
mvn.cmd -B -ntp test -pl engine-runtime -am '-Dtest=RunStatisticsPostgresTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
node scripts/verify-contracts.mjs
```

初次engine红绿遇到共享target并发写入，未把那次无完整surefire日志的结果计为稳定最终。最终engine5项在主任务明确分配独占窗口完成；之后已释放窗口给G1修复者，不重跑其整个模块。

## 自审与边界

- 已核对SQL参数绑定、固定day分组、Actor租户过滤、owner/ADMIN分离、逻辑调用主键、来源粒度、usage null、分页与硬闸、幂等快照唯一键及复合tenant外键。使用仓库已有google-java-format对新增文件和统计局部格式化。未声称读码对照任何外部开源实现。
- 新测试合计19项（skill7、engine5、model3、monitor4）及既有quota5项，共24项分批验证。还需由主任务完成独立代码评审、前端实际页面联调与统一整轮验证。无新截图或生产服务重启/打包证据。
- 04:49:31 `node scripts/verify-contracts.mjs` 实际通过冻结快照/四契约原文哈希与七模块依赖边界检查。
- 直接PG聚合替代冗余日报表/易失队列，当前明确拒绝超过10000运行或2000分组的范围；不把该有界实现当成大规模容量验收或后台定时预聚合已经落地。
- 两源各自REPEATABLE_READ不能构成全局原子快照，已在API与持久报告中如实注明；统计中途新到事实只能在后续查询/新重建中看到。
- 故障用例覆盖来源HTTP失败与事务不切换、重复请求恢复；没有执行进程kill/数据库中断的真实崩溃窗口注入，不把数据库事务语义本身冒称该类实测。
- 未改web/、未修改冻结契约、未改其他引擎运行逻辑或V213、未停进程/外发布/git提交。T25历史检索、生产IAM、完整业务数据与前端验收仍归主任务跟踪，不以本报告关闭整个G3。
