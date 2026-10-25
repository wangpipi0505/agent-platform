# D3a0 deadline 测试补强（root授权）

唯一工程根 G:/207/项目/算法重组工具/代码/agent-platform，显式 workdir，读 AGENTS.md，不访问相邻旧树/配置/数据/进程。用户授权持续实现和隔离测试。原D3a0作者已归还 source/target；独立 reviewer 正完成原快照审查，已指出一项 Minor 测试判别力缺口，root选择本次窄修。不能改变 reviewer 的独立原结论。

原要求 docs/evidence/iam-stage-4-d3a0-brief.md。当前原报告 iam-stage-4-d3a0-report.md 15850字节、SHA 2d1c7d3cd6ab7bb0822be0eeee1da91640e92357a3ab7709079b709185325c00；原344 source/POM、patch dc290dff...、冻结与真实回放保留，root audit .local/iam-stage-4-d3a0-root-handoff-check.json。请先确认实际报告原字节/摘要，若不同给出原因，不覆盖。

## 唯一代码修改

platform-gateway/src/test/java/cn/agentplatform/gateway/application/PublishedMetadataClientTest.java，尤其 fullBodyDeadlineCancelsARealStalledHttpResponse 与其本类有限HTTP夹具。当前夹具只发 '{'、sleep3s后EOF，测试仅要求抛PlatformException且<4s；没有2s全body截止也可能3s后因无效JSON抛异常，故该测试不能单独证明deadline。

以受控闩锁或等价确定性有限夹具改为：确实已发送headers/部分**合法响应**，完整响应尚未释放时，真实客户端在既定body期限抛异常并结束；不能由夹具自行关闭/畸形JSON造成假阳性。若无deadline，夹具最终只能返回完整合法DTO或明确触发测试失败。正常清理与失败均释放等待，server线程有有限兜底、测试失败不得留后台线程。继续核实际服务票/hits和原subscription cancel直接测试；有界等候，不做大量/耗尽/长时间探针。优先最小修改现fixture和该测试，不新增通用harness、生产timeout或配置。避免仅收紧一个不可靠墙钟上限替代响应未完成的状态断言。

不得修改生产源码、common测试fixture、POM、业务app/engine/skill/model/monitor、运行主服务、数据库、模式/原契约、旧D3证据。只跑覆盖新增断言的具名方法，命令：
node .tools/run-iam-module-tests.mjs iam-stage-4-d3a0-deadline-fix platform-gateway PublishedMetadataClientTest#fullBodyDeadlineCancelsARealStalledHttpResponse

原生产客户端已经有future.get(2000)与cancel，本次是测试判别力补强，不能伪造生产bug红。除非新增变化/失败需要，不重跑同码common155/app9/client其余9项或smoke。每轮raw/XML/源SHA即刻归档，不用旧XML冒充；首次失败若有保留。

## 交付

报告仅追加 docs/evidence/iam-stage-4-d3a0-report.md 的集中补强节，原15850字节前缀原样保留。新证据目录 docs/evidence/iam-stage-4-d3a0-deadline-fix/；baseline前344源必须匹配原D3a0 source-after，after344/只有本测试一项变化；完整fix-only patch真实apply逐字核对；记录本次gateway target manifest/冻结和其他模块target不变，原报告/原证据保护，实际test方法、命令、输出/XML、有限夹具释放与线程退出方式，未跑范围/警告。可在新证据内写最小采集辅助，不改旧helpers/runner或root索引。所有secret沿既有受控runner，不读打印.env/vault/密钥。

无HEAD/remote，不伪造commit或git diff；正确parent Git ceiling，保留失败/whitespace警告。完成source/target归还后，同一独立reviewer针对fix-only fullcontext包复审；不在本段实施E1a或新业务能力。
