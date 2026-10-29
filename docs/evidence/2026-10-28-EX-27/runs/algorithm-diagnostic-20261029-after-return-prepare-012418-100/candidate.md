# SDK事后诊断模式：最小静态候选

2026-10-29。先前Full的116原因对照与结果已落盘，本轮仅改AlgorithmOffloadInterruptionTest并新增本目录静态证据；没有Java编译、Maven、PG、HTTP或实际运行脚本准备，没有产品/预算/断言/主入口/index修改。未使用superpower。候选须交独立静态审，当前没有新实际窗口。

新测试SHA256：`155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1`；基准D64：`D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A`。仅三处delta：

1. 新增`afterReturnOnly = enabled && Boolean.getBoolean("ex27.algorithmInterruptAfterReturnOnly")`，只有原`ex27.algorithmInterruptDiagnostic=true`已开时生效，默认false。
2. 原`capture("peer-entered", false)`原位置仅加`if (!diagnostic.afterReturnOnly)`，新模式跳过这一处interrupt之前的取栈/反射观察，未改其余控制流。
3. 原emit输出新增三键：diagnosticMode=AFTER_RETURN_ONLY或WITH_PEER_ENTERED_SAMPLE、peerEnteredCaptureOmitted=模式布尔、clientStageAtInterrupt=UNKNOWN。

| 原diag | 新after-return-only | 行为 |
| --- | --- | --- |
| 关闭 | 任意 | 原诊断关闭，无采样/PG/输出；新开关不单独开启诊断 |
| 开启 | 关闭（默认） | 保留D64原事前peer-entered观察与原所有后续相位 |
| 开启 | 开启 | 仅省略事前peer-entered观察；其余原相位完全原样 |

本轮没有新增phase、等待、线程、反射项、生产hook或首次检查采样。normal body里的beforeRelease、after-original-peer-wait以及finally的beforeRelease/after-finally-peer-release都没有移动；首次quiescent布尔仍在原位先固定再原assert，未到则null，joinReturnMs与firstCheck时间仍分开。所有异常/PG/生命周期字段只来自这些D64既有释放前后观察位置；后续已静止或分项不能反推首次false时的原子分项。

mode表示所选模式；peerEnteredCaptureOmitted只说明被新模式省略，不承诺旧模式一定执行到该调用，实际样本以samples为准。新模式客户端中断时阶段明确UNKNOWN，不以服务端闩锁或后续样本补猜。before-peer-release是**显式release之前**的原位置；peer有限等待可能已自然超时，不能将该phase名称或release剩余1等同于“服务端还阻塞”，仍须分别读peerFinishedRemaining/其它事实。

静态证据：原verify-candidate.ps1原字节复制并先经PS5.1 ParseFile（0错误）后运行；13项检查全过，25处原assert、15处原等待表达式、两个测试方法/四报告身份、PUT→GET→GET_BODY顺序、完整未确认向量和整个有限peer均保持，git diff --check通过。当前HEAD AE2D，仅静态读源/Git并写本目录JSON，没有读取.env。

额外内存反向校验：仅撤销以上三处delta，完整SHA精确还原D64，见d64-delta.json；这证明其余源码（包含原相位/字段/等待/判断顺序）没有变化。新候选未编译，不能计红/绿；先前D64单类与Full的P仍只作各自观察，原Full GET_BODY F不覆盖、不关闭问题。本轮到静态交审即停止，不准备或启动重跑。
