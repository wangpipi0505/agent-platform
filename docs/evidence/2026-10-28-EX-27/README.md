# EX-27 测试基座子步骤证据

2026-10-29 增量：SSE 子步已由 `83cc21d0a490a50ae6e5ac3dbad57bd53e0381ef` 提交。SDK 中断前提/安全覆盖与 outbox 租户隔离分别审阅、分别提交；本卡整体仍进行中，不能把这三个子步算成三张完成卡。最新状态与提交串联见[统一台账§14](../../../../../文档/优化方案/优化台账-统一-20261028.md)和[EX-08索引](../2026-10-29-EX-08/README.md)。

outbox 的[归因](outbox-actor-preflight.md)、[静态准入](outbox-actor-static-review.md)及[本轮结果](runs/outbox-fix-20261029-085643-361/result.md)保留各阶段身份。原两个测试共用租户；产品从认领事实恢复 principal 的路径保持不变。本次仅测试逐例 UUID 隔离，原97断言保留并增加2条检查，未清理未知旧队列。默认28项定向已全绿；共享完整skill为70类674记录、558P/6F/106E/4旧SKIP，新非通过/缺失/源漂移0，原116非通过仍保留，不是全模块全绿。最终原因门与独审以对应新回执为准。

SDK 的[历史技术门](algorithm-history-technical-review.md)、[43EC静态准入](algorithm-fix-static-review.md)及[原五项通过但outbox新增F的Full](runs/algorithm-fix-20261029-032753-387/result.md)保持原件；新的共享Full不能倒写原失败现场，也不能把本轮handoff确认退出写成已重现历史未确认分支。一次Full同时包含SDK、outbox及EX-03共149源候选，分别提交后的最终串联树才恢复该源码上下文；任一孤立子步提交不声称独立复现674项。

命令原文直接保存在各次实际 `command.json` 的 `command` / `arguments` 字段，并同时保存该命令原生起止及 exit：

- [SDK默认整类5项命令原文](runs/algorithm-fix-20261029-032753-387/class/command.json)：默认43EC、5P/native0，无诊断flag。
- [outbox两类28项命令原文](runs/outbox-fix-20261029-085643-361/targeted/command.json)：默认607B、28P/native0，无failure.ignore。
- [同栈完整skill命令原文](runs/outbox-fix-20261029-085643-361/full/command.json)：70类674、558P/6F/106E/4旧S，原failure.ignore只采集。

以上各命令的参数数组是原文载体，不是手工概述；执行前置及环境恢复见各Run原字节 `preflight.ps1` / `run-verify.ps1`。历史红与诊断命令仍对应各自Run及当时测试SHA，不能将后续43EC/607B倒填到旧失败。

## SSE 子步原阶段快照（以下保留当时记录）

本轮只推进EX-27的网关回收夹具子步，Y-4其它部分未启动。仅测试及本证据目录在白名单内，所有src/main、SSE/HTTP/Budget、EX35 Proxy候选、Tomcat依赖与原期限不改；不使用superpower、不操作index/commit。root负责主入口、独立验收和提交。

当前冻结测试SHA=`6B144C0033CE22556996897824B2F0D864A862E39F6E12EF2C21B5212B5199C1`。**两准确方法2PASS、默认完整38方法38PASS，最终网关27类逐方法回归门通过；模块保留15个原非通过，Maven实际exit1，不是整模块全绿。** 实际窗口已明确归还root，当前没有在跑命令。独立验收及本子步提交仍待root完成，不关闭整张EX-27/Y-4或自行改写EX35主卡状态。

## 实际顺序门

| 阶段 | RunId | 实际结果 |
| --- | --- | --- |
| 两默认准确方法 | safety-targeted-20261028-233001-643 | 2PASS/0F/E/SKIP；Maven0、2 child真实exit0；安全事实独立归档。 |
| 默认完整类 | safety-class-20261028-233128-749 | 原37+新增1=38PASS/0F/E/SKIP；49 child真实exit0；原37及全部38缺失0、重复0。 |
| 网关全部27类 | safety-module-20261028-233458-012 | 27XML/288方法=273PASS/7FAIL/8ERROR/0SKIP；Maven1、49 SSE child真实exit0。Proxy33和SSE38均PASS。 |

各阶段均无诊断flag，保持同.env/argLine/flags、逐类fresh JVM `-DreuseForks=false`，没有清ServiceTokens。8项产品/测试/YAML/.env指纹及HEAD前后一致、采集错误0；先保存native Maven exit，再复制子JVM退出和XML，XML使用XmlDocument.Load绝对路径。独立PowerShell脚本进程退出，不污染root环境。raw/XML在忽略目录，安全事实分列，无敏感头或凭据输出。

最终[逐方法对照](runs/safety-module-20261028-233458-012/comparison.json)明确EX00原254（主29+补225）、恢复原Proxy30、新Proxy3、新SSE1，共288；缺失/重复/异常类0、newNonpass=0，15个保留非通过的top type及caused-by类型集合变化0。TLS旧FAIL本轮PASS单列，不声称本卡修复了TLS产品。原非通过没有因总数或单方法额外通过被抵消。

## 需求、安全与原件

[独立技术门](technical-review.md)批准原正例在真实socket锁内确认原processor关联且未closed后close，保留原5秒及全部旧断言。新增默认off-lock安全向量保留P1/P2/P2b真实恶劣交错，继续验证Owner、真实退出和动作安全，没有只把原红改成预期成功。当前[静态审阅](safety-static-review.md)核过E175及两类型delta；最终运行事实另见[current-stage-results](current-stage-results.md)。

目标两方法、完整38及最终模块均实际核实：原5秒recycled0/waiting2，Owner2/completion0；主动取消后两个实际reader退出，仍不虚退并拒第三真实SSE。旧closed连接各一次CLOSE_NOW且真实返回，另一真实keepalive连接两次普通REQUEST均无SSE token、均HTTP200/pong。新增向量没有原SSE processor复用；真实跨socket/keepalive复用由同次38里的两个原向量另证。完整类和模块的safety-facts-summary.json分别保存原关联、四阶段持费/worker、旧动作目标、普通请求及原真实复用事实，不能混算。

[447唯一实际诊断红](diagnostic-result.md)、EX35历史新增FAIL/单独PASS、FD34未运行的静态ping夹具错、E175两处long类型编译FAIL全部独立保留。没有认定历史唯一同因，也不把未执行后续断言写成已验证。详见[编译失败原件](compile-failure.md)、[ping静态修正](fixture-review-delta.md)、[类型delta](compile-type-delta.json)及[37方法范围](method-scope.md)。最终新增测试没有手工mint token或recycle，也未修改产品义务来凑绿。
