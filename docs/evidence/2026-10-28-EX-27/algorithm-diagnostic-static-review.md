# 算法SDK中断诊断候选独立静态评审

- 评审日：2026-10-29（Asia/Shanghai）。作者：`/root/algorithm_interrupt_readonly`；审阅者：独立代理 `/root/ex02_independent_review`，未参与实现。
- 对象：EX-27 已登记的 AlgorithmOffloadInterruptionTest 测试内观察；不是产品修复、正例前提修正、原断言豁免或本卡验收。
- 最终静态候选：`skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java` SHA256 `D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A`；核对时 HEAD `28aa99ea12cb96bbbf781859c017acebd30647a3`。ArtifactStore 仍为 `9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`。
- 已读：[只读准备](algorithm-interruption-preflight.md)、[候选说明](runs/algorithm-diagnostic-20261029-prepare-002200-707/candidate.md)、verify-candidate.ps1 源码及其已有PS5.1解析/13项静态记录、唯一Java完整diff与相关原测试块、ArtifactStore实际生命周期。未使用superpower。
- 执行边界：本审阅者仅静态读文件/Git与内存文本比对，未运行候选PS脚本、Java、Maven、PG、HTTP或操作进程，未读取真实配置/凭据；仅新增本评审文件，未改源、其它证据、index或主入口。没有重跑已完成的ParseFile。

**静态准入：可由 root 在既有PG归属/健康与外层有界窗口核准后，进入一次原类4报告项的显式诊断运行。** 必须使用上列新SHA；D32与D64均尚未编译或运行，不能称为红/绿结果。无需扩大产品、SDK、数据库辅助类或时间预算白名单。

独立原块比对结果为：2个原测试方法/4报告身份、PUT→GET→GET_BODY参数顺序保留；25处原断言（仅首次quiescent改用已固定别名）及15处原await/join/sleep/2秒deadline表达式，数量、内容和顺序一致；完整独立未确认向量与整个LatchedS3尾段逐文本相同。未增Disabled/assumption、后台观察线程或生产hook。生产200ms与原2秒预算、原finally的peer.release/worker.interrupt/join(1000)及其异常传播均未改。

原 `algorithmIoQuiescent()` 首次结果在额外PG、后续采样或peer释放前固定，原assert仍使用该值。稍后样本即使变为true，也不会重写原false或吞掉原断言。最终样本与共享store复用/原清理的关系有明确 `originalBodyCompleted` 和phase；末段累计routes/deletes/PG不能倒推成原停止前已无迟到动作。

诊断默认由 `Boolean.getBoolean("ex27.algorithmInterruptDiagnostic")` 关闭；关闭时不采栈、不读PG、不打印诊断。每参数最多4个固定phase/3条附加PG聚合。原AlgorithmIo monitor内只读stage计数、Call集合大小、stop/interrupted/stopNotConfirmed与原cancelUntil，不遍历Call、不改字段、不关闭/回收。退出monitor后才取executor状态/active/queue；peer闩锁未到时省略SDK executor getter，闩锁到达只作为原enqueue已发生的见证，不冒充客户端正文读取见证，不懒建新执行器。各对象状态明确不是同一原子快照。

安全JSON只包含计数、布尔、相对时间、固定phase/状态码、有限白名单类/方法/行号。异常树最多16节点，栈扫描最多64/输出16帧；message仅用于匹配3个固定码，不输出消息、Throwable/Call对象、URL、路径、请求头/体、tenant/call值或凭据。PG单独template只读当前随机tenant/call的一行聚合，state/error按固定允许值输出；queryTimeout=1与maxRows=1不修改原template。采样异常记安全类别，未替换原判断。

| 问题 | 事实与处置 | 复核状态 |
| --- | --- | --- |
| DIAG-01：失败时缺少原join返回耗时 | D32仅在原成功末尾打印returnMs，失败JSON只有随后首次检查时刻。作者最小增加3行：复制已有returnMs至Long字段，最终JSON输出joinReturnMs；没有新增时钟读取、等待或判断 | 独立在内存移除这3行后，完整SHA精确还原 `D32B1DF8293CA0435C8996DFDCAC0E5E3D695F8841D03C81A08D8160D2C314C3`；D64差异仅此3行。关闭 |

`joinReturnMs`是原join结束及原存活断言后已有计算的毫秒耗时；`firstCheckSinceInterruptNanos`是随后首次quiescent检查时刻。两者不能互换，也不能把它们解释成worker线程内部精确退出时刻。未到相应语句时为null/缺失，不能补猜数据。

实际运行准入仍须 root 先核本仓25432当前进程/数据目录、**既有**算法专库owner与迁移归属/健康；原AlgorithmTestDatabase自动建库分支不是归属证明，本评审不授权建库、reset/drop/truncate。PG的1秒语句超时不保证DataSource连接获取也有1秒硬界，必须保留外层测试有界窗口。第一次运行只编译/执行原类四项，显式开启诊断、保存实际native退出码/新鲜XML/源码指纹与原始安全输出；不顺带跑完整模块或其它服务。

静态准入只允许取得事实：正常确认返回前原stage/Call/executor退出，与200ms确实无法确认时固定码/PG状态/持续所有权及后续真实退出，是后续技术门的不同分支。server GET_BODY闩锁不能证明client已读正文，单个quiescent/retained布尔或executor bookkeeping也不能独立判定根因。原full新增GET_BODY FAIL和全部旧XML必须保留；一次额外PASS仅为观察，不能关闭EX-03回归门、EX-27子步或整卡。若实际采样不足以区分分支，只能记证据不足；若证实产品义务受损，另立准确产品卡，不在本诊断中修改生产/SDK/原预算或把两种结果改成都算PASS。
