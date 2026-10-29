# 五报告Test-only修正：独立静态准入复核

2026-10-29 03:13（Asia/Shanghai），审阅者`/root/ex00_collector_review`。只读核候选、155D快照、实际diff、静态回执与相关调用/清理路径；唯一新增本文件。未编写该候选，未使用Superpower，未运行验证脚本、Java/Maven/PG/HTTP或产品测试，未读真实环境文件/凭据，未改源/index/三主入口。

**结论：43EC候选静态可准入一次默认五报告定向；五身份完整通过且证据/环境/源身份匹配后，可进入一次原完整skill上下文（预期674记录）。实际窗口仍由root另行安排。未发现需在实际取证前回改的静态阻断。** 当前未编译，本文不是测试PASS、原Full失败关闭、EX03完成或产品提交签收；若定向失败/缺报告，应保留事实返回技术门，不自动继续Full或循环重跑。

## 1. 对象与身份

- 上下文HEAD `83726d50a6cce116251c03c5c193080d8ed5aad2`。
- 唯一测试源`skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java` SHA256 `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D`。
- 原155D真实字节快照SHA `155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1`；候选快照与当前源逐字节相同。
- 产品`ArtifactStore.java`仍为`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`，未修改其200ms规则、所有权或SDK。
- 证据：[candidate](runs/algorithm-fix-prepare-20261029-022836-663/candidate.md)、[覆盖矩阵](runs/algorithm-fix-prepare-20261029-022836-663/coverage-matrix.md)、实际`candidate-vs-155D.diff`、双源码快照、`static-check.json`及`static-script-parse.json`。评审依据为[历史技术门](algorithm-history-technical-review.md)，SHA `e6adc415cbdefa2729a8e405cc0f618a73c20e3e4e3d55078e52e373f2775f58`。

独立核实际diff为20个hunk、255增加/30删除；在内存按hunk核原上下文并应用，结果精确等于候选行。独立提取JUnit断言：原25处、现47处，只按已明确的首次q别名及两个原共享截止等待映射后，原25处义务无缺失。没有把相对HEAD还包含旧诊断的diff当成本次差异。

## 2. 技术门逐项核查

| 义务 | 实际源码与判断 |
| --- | --- |
| 默认五身份 | 30–40、221–222行：原PUT/GET/GET_BODY参数顺序与原有限Source身份保留，新增一个默认handoff；1个ParameterizedTest＋2个Test产生3＋1＋1报告。没有Disabled/Assumption或诊断flag才执行的新向量。现70个源Test文件；674是原673加新1的预期，不是实际完成数。 |
| 正常正文前提 | 106–121、349–402行：peer与客户端见证共原总1s截止。单次worker栈需同时含readJson、Exchange响应体read、Okio InputStreamSource.read及socket read帧；不是仅解析入口。随后核原stages0、原Calls恰一个GET、原dispatcher running/queued0及原ThreadPoolExecutor active/queue0；没有先等Calls空或quiescent。正证据/截止不满足即FAIL，满足后没有第二次诊断夹入interrupt。此为有意建立正常前提，不是无观察扰动或原场景未变的复现。 |
| 原始handoff | 112–121行以`!originalHandoff`包住事前capture及readiness；新向量从原peer闩锁即interrupt，即使旧debug模式开启也没有事前栈/反射/PG采样。constructor的开关/字段初始化在worker前，未引入交接时的同步钩子或替代客户端。 |
| 首次q与严格分类 | 129–155行先固定q，再进行PG/诊断。原三个正常例仍严格q=true；handoff只有在精确异常图未报告未确认时要求q=true。405–417行按精确IllegalStateException类和全等固定码遍历cause/suppressed（去环、上限64），与目标PG码双向核；q=false且无码、两端不一致均失败。已报未确认但q已恢复true仍保留该码，没有按布尔简单二分。 |
| PG及延后清理 | 420–440行按本新tenant/call查询唯一目标、pending/no READY、精确码、cleanup_after恰updated＋1h且未到期、attempts0/deleted NULL。163–166、311–316行实际调用原shared.cleanTenant并要求0，再核PG和路由；不是只根据字段推测不会早删。这些是指定时点断言，不是完整PG状态审计。 |
| 固定路由基线 | 798–805、831–864行在目标停点用该已接收序号CAS一次，再countDown；后来的请求不能更新它。140–142行在PG之前读取该固定基线，PG/早清理后、释放后、40ms后、诊断后及共享意图入口均核同一序号/routes/DELETE；没有用晚计数吸收迟到请求。Source固定基线0。 |
| 原资源真实退出 | 168–176、449–456行让peer.finished和原operation的quiescence共原1s收尾截止，原40ms保留。实际调用未改的algorithmIoQuiescent，包含原stages/Calls结束和原owned executor terminated；没有替代future、清计数或新增强关执行器来造退出。network分支不强求retained=true，兼容仅executor尾段未确认。 |
| 确定性Source完整义务 | 230–345行保留原有限输入/1500ms等待、800ms join、retained=true/q=false、原500ms实际退出循环、retained=false及两次routes0；新增输入关闭、精确异常码、pending/no READY/延后清理及cleanTenant0、实际退出后同样完整检查。无HTTP暖场；原shared直到原资源确认退出之后才首次实际使用，不把它称为已暖场客户端复用。 |
| 共享使用与有意清理边界 | 459–490行入口先核固定基线，再明确开始共享意图、清peer.stage；真实PUT/GET计数各精确＋1且返回引用非空，之后才将本新UUID tenant的对象置为可清理并清理。网络向量是同一已暖场shared再次使用，Source是原未使用shared首次使用。父线程显式建立正确新tenant actor并finally clear，外层也clear；没有借用子线程遗留身份。 |

## 3. 调用、清理与静态证据边界

- 主线程的JUnit断言没有被catch转成PASS。worker的Throwable捕获仅保存原offload/close结果供主线程判定；readiness反射/查询/断言异常会向JUnit传播。可选诊断仍只吞观察性Runtime/反射错误并记录，未吞主断言；新handoff元数据明确RAW_HANDOFF_POST_RETURN、事前省略、客户端阶段UNKNOWN。
- `targetState`、早清理和最终过期SQL均使用当前方法新UUID tenant及目标call，未使用历史groupHash/旧tenant。最终按tenant更新位于确认原资源退出及共享意图之后。实际读取现有AlgorithmTestDatabase调用为默认reset=false，只有另一个专用migration库允许reset；本候选没有修改DBhelper或增加清库路径。执行时仍须原归属/库存在/迁移准入，不能靠测试helper补造前提。
- 原2s算法期限、产品200ms、Source500/800/1500及原40ms未放大。新准备/收尾循环消费原共享截止；不能把它们或PG调用视为整个测试无条件硬时限证明。网络栈是否在真实JDK21/固定SDK的原预算内出现，必须由实际运行确认，缺见证会失败而非降级通过。
- 静态回执有29项true、当前43EC/原155D、25→47断言、原Source12处保留、5/674预期；独立核关键身份/调用/条件及上述断言差异后，与回执相符。本轮没有运行该验证器。脚本SHA `907C355F2758888DAE14FF8BFB0D34087B874CCC2870717874CC880E2267F2CF`与PS5.1 ParseFile零错误回执匹配。词法平衡/文本匹配不等于Java编译。
- 固定SDK窄读清单围绕OkHttp Exchange真实body Source、Dispatcher移队列/执行尾段及Okio InputStreamSource，和实际正见证判据相符。没有更换真实SDK/transport、生产hook或断言用mock future；不把读源或静态判据存在当作运行到达证明。

## 4. 准入与保留事实

未发现本次候选需要先回改的静态问题。可在root发放的独占窗口执行一次默认五报告定向，记录当前Test43EC/产品9DEB、环境/依赖/模式及阶段事实；只有报告齐全、必要前提与安全断言均满足，才按登记进入原完整skill上下文674。不得以编译失败/缺XML计目标已通过，不自动重试、循环抽样或删断言取绿。

原Full GET_BODY首次q失败、后来恢复的未确认PG分支及其缺失实时分项仍完整保留；两分支的PASS只表明本次对应安全义务满足，不补造旧现场。候选五报告/完整模块及后续独立终审齐前，不能关闭原F或EX03。若实际暴露新的安全失败，保留准确事实另走技术门，不能在本卡顺手改9DEB、SDK或预算。

本文件仅审43EC限定Test-only候选；未审阅或签收无关模块、产品改动及提交。写后继续核该源和两个快照身份，root后续改动须再审受影响部分。
