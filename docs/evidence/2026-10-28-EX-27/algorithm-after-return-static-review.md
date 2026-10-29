# Algorithm SDK事后诊断模式：独立静态准入复核

2026-10-29 01:28（Asia/Shanghai），审阅者`/root/ex00_collector_review`。本轮未参与候选编写，只读核测试源码和本次静态证据，唯一新增本文件；未使用Superpower，未运行验证脚本、Java/Maven/PG/HTTP或任何产品测试，未读取真实环境文件/凭据，未改index。

**结论：SHA `155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1`静态可准入一次独立RunId的原Full上下文取证。无本次delta阻断。** 这是受限诊断候选的技术准入，不是编译/运行通过、原GET_BODY失败关闭、EX03完成或产品提交签收；实际窗口仍由root串行安排。

## 对象与独立核查

- AP上下文HEAD为`ae2d1860071c9e49d07e80ecace331e41f898c9d`；候选仅`skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java`的诊断增量。本次读取实际文件SHA与上述155D完全一致。
- 本轮静态证据位于[runs/algorithm-diagnostic-20261029-after-return-prepare-012418-100](runs/algorithm-diagnostic-20261029-after-return-prepare-012418-100/candidate.md)：`candidate.md`、`d64-delta.json`、`candidate-static-check.json`、`script-parse.json`及`verify-candidate.ps1`。没有新Full运行或编译结果。
- 独立在内存中逐一撤销下列三处唯一匹配的增量，完整字节SHA精确还原`D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A`。未改写源码或使用作者的反向结论替代独立计算；因此其余D64源码、相位、判断及等待均保持。

| 实际delta | 静态判断 |
| --- | --- |
| 288–289行新增`afterReturnOnly = enabled && Boolean.getBoolean("ex27.algorithmInterruptAfterReturnOnly")` | 新开关在worker启动前确定，且依赖原诊断开关。诊断关闭时短路为false，不单独启动观察/PG/输出；原诊断开启而新开关缺省时保留D64事前采样模式。 |
| 92行原位增加`if (!diagnostic.afterReturnOnly)` | 新模式确实不调用该次peer-entered capture，故不执行其取栈、反射、生命周期monitor等事前观察；没有换成新的等待/取样或挪动interrupt、计时起点。 |
| 527–530行emit增加模式、省略标志及`clientStageAtInterrupt=UNKNOWN` | 仅最终输出元数据，不改变判定。省略标志描述所选模式，不证明旧模式一定到达采样点；实际样本仍以samples为准。UNKNOWN避免把服务端GET_BODY闩锁或更晚样本冒充中断时客户端阶段。 |

独立计数仍为25处原断言，D64反向完整身份相同。作者静态回执对应当前155D，记载13项检查全过、25断言/15等待表达式、两个测试方法及四报告身份、PUT→GET→GET_BODY顺序、完整未确认向量及有限peer不变。复核未重跑该验证器。其当前脚本SHA `5E1F2F689E88E329F954659DA37016592333BC8D48DDAA68A2828D178B47F94D`与PS5.1 ParseFile零错误回执一致；这是静态证据，不是Java已编译证明。

## 与前一技术建议的对应

此前建议是移除已知较重的事前观察点，同时保持原失败判定和所有200ms/2s预算、join/await/sleep不变；不引入客户端阶段同步钩子。实际候选符合该范围。原capture在interrupt及计时起点前取栈/反射/monitor，存在改变交接时序且其耗时不计入returnMs的可能，移除它有技术依据；既有两次不同栈观察没有证明观察动作就是原失败原因。

首次quiescent仍在102行先取布尔，103行只记录，104–106行按该固定值断言；未到此处时仍为null。normal body和finally里的既有释放前/后采样未移动，既有异常图、PG与生命周期信息没有被删改。模式名称不证明worker确实已经返回：若更早断言失败，finally可能看到worker仍活着或首次检查未到达，必须保留这些事实。

后续采样也不是首次false时各分项的原子快照；它可能已恢复静止。`before-peer-release`只表示显式release前的代码位置，peer可因原有限等待自然超时；release剩余1不能证明服务端仍阻塞，须分别看peerFinishedRemaining及时间信息。客户端中断时阶段在新模式中明确未知，不从事后异常、服务端闩锁或状态补猜。

## 一次实际取证的边界

若root发放实际窗口，应显式同时启用`ex27.algorithmInterruptDiagnostic=true`和`ex27.algorithmInterruptAfterReturnOnly=true`，保留原Full模块/选择范围、fork及环境来源、产品/SDK依赖、预算、全部断言和原报告身份，另建RunId记录模式与源码摘要。只允许该次原Full取证，不追加定向、循环同源抽样或为取得PASS重复执行；采集/编译/前置失败必须原样记录，不能记作目标已运行。

若首次false出现，按固定原判定、异常图、`ALGORITHM_IO_STOP_NOT_CONFIRMED`/其它错误、PG持久态及更晚生命周期进入下一次技术判断，不能直接把200ms未确认分支豁免或放宽原断言。若未命中，只能报告“本次未捕获原失败”；之前D64单类/Full PASS及本次可能的PASS均不覆盖原EX03 Full673的GET_BODY first-quiescent失败。原失败、缺失的原现场分项和EX03硬门继续保留。

本结论仅适用于155D这三处诊断增量。后续改变相位、等待、断言、产品或采集范围，须重新审受影响部分；本轮没有审阅或签收无关产品改动。
