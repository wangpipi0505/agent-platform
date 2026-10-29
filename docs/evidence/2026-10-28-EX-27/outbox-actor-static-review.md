# Outbox测试租户隔离：独立静态准入复核

2026-10-29 08:54（Asia/Shanghai），审阅者`/root/ex00_collector_review`。独立只读核因果报告、原失败XML安全投影、产品选择/恢复路径、候选及字节清单；唯一新增本文件。未参与实现，未使用Superpower，未运行脚本、SQL、Java/Maven/HTTP或产品测试，未读真实环境文件/凭据，未改源/index/主入口。

**结论：607B单Test候选静态可准入一次默认28报告定向（SkillExecution25＋未改SkillTaskResult3，native0且无failure.ignore）；定向身份/断言完整通过、源和环境匹配后，可进入同栈默认完整skill674。实际窗口仍由root另行授予。未发现需在实际运行前回改的静态阻断。** 当前未编译，不能记为测试P、模块通过、回归提交签收或原Full/EX03/SDK子步骤关闭；单次定向P不能替代完整模块门。

## 1. 因果门与未证实部分

[outbox-actor-preflight.md](outbox-actor-preflight.md) SHA256 `30b3338c276696e9d3c4f4bcd369b98d440df2704b7995b3dff3b6fdb889dd39`的主要归因经源码/原XML核实：

- 原Full `algorithm-fix-20261029-032753-387/full/xml/TEST-cn.agentplatform.skill.application.SkillExecutionTest.xml` SHA256 `9284d75ed367c3a4bf9c9273d9034f9d852b2c323377abbc919263f35c4e63d9`，25报告24P1F。唯一失败为automaticOutboxRecoversTransientCallbackWithoutRepeatingBusiness，AssertionFailedError显示期望skill-tester、实际task-result-tester；栈经过SkillOutbox.drainTenant→SkillExecution.retry/deliver。
- 产品`SkillOutbox.java:40–69`是**给定tenant内所有到期事实**的原子认领，不按当前JWT的principal/当前测试call筛选；随后从认领行principal_id构造SERVICE Actor。`SkillExecution.java:1438–1448`用该Actor的tenant/call/principal重读同一事实，1473–1477行把同Actor传给三参post。实际另一个principal指向被认领事实，不能据此归咎BeforeEach缺身份或把产品改用当前JWT主体。
- 未改SkillTaskResultTest的UNKNOWN手插事实使用同一旧固定tenant skill-tests、principal task-result-tester，并省略投递列。V300默认callback_state=PENDING，V304默认due=now/lease空/attempts0，确有跨例留存且可被该租户outbox选择的源码路径。AssertionFailedError不是生产catch(Exception)会吞掉的错误，原栈与这条链相符。
- 原自身due已回拨一天，故不能把本轮刚写、默认due=now的那一行直接认作本次所选行。具体旧call/批次/当时due或外部并发仍未确定；本轮未查PG，也不追认唯一历史来源。已证明的隔离缺口及选择机制足以支持本次命名空间修复，不必先删除或识别旧队列。当前没有必须改变产品Actor恢复规则的证据，也未宣称产品所有恢复场景均已验收。

## 2. 候选与字节身份

上下文HEAD `83726d50a6cce116251c03c5c193080d8ed5aad2`。唯一源码为`skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java`：

| 对象 | SHA256 |
| --- | --- |
| 原真实字节快照 | `14AE4EEA65C05EBEEBDBDF16D3FC4733D4613FDEC20EE7644DF56DB83627194B` |
| 当前候选及候选快照 | `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8` |
| SDK AlgorithmOffloadInterruptionTest | `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D` |
| ArtifactStore | `9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D` |
| SkillTaskResultTest | `600F1A9DF2D5F0199A52F7F2152D339F2E6C6D78839382C30C5BA9C5E7FA164F` |
| SkillOutbox | `30BAE61B51DB4DF75462C5D92BADC5455FBEC1FC7437856CD1D9A9DA2DA32DB7` |
| SkillExecution | `8CD3D8EDAE7C0DEE54DD562C6F2F3F3664759DE85D122F70C1E00B9BC6D25218` |

准备包：[outbox-fix-prepare-20261029-084356-913](runs/outbox-fix-prepare-20261029-084356-913/candidate.md)。独立核file-manifest七项大小/摘要全部相符；实际diff19个hunk、42增加/24删除，内存按原上下文应用后精确生成候选。149份源码清单逐项核当前摘要，与失败Full的149路径集合一致，唯一变化是本次Test，其余148项相同。这个事实是范围冻结，不是模块测试通过。

## 3. 实际delta核查

| 核查项 | 实际判断 |
| --- | --- |
| 每例一次UUID | 实例字段tenant，63行在BeforeEach构造JWT前仅一次赋skill-execution-加UUID；字段非static，没有方法内重新生成tenant或手动重入prepare。该形状小于tenant列128字符限制。两个JWT（含scopeIdentity）均复用该例字段，不拆散同例权限收窄/来源绑定。 |
| 24处完整映射 | 独立对tenant-mapping的24条原/新行号和原文逐项核对，全部匹配；原固定skill-tests无残留。JWT、mock、版本/fact/history/artifact SQL、outbox和清理都一致使用同例tenant。没有只改出错方法或引入不同租户躲开同例业务状态。 |
| SQL真实绑定 | connection列tenant_id/skill_version_id/candidate_sha256/…/semantic_digest，对应4个参数tenant,id,sha,semanticDigest；acceptance列id/tenant_id/skill_version_id/candidate_sha256/…/semantic_digest，对应5个参数testId,tenant,id,sha,semanticDigest。没有拼接UUID进SQL，旧验收不能批准已改草稿的断言保持。 |
| 原业务/主体断言 | 原skill-tester、SERVICE、业务调用一次、自身事实DELIVERED及其余原97条JUnit断言全部保留；原principal不是改成实际串扰主体。独立按租户参数映射规范化提取97→99，原义务零缺，只有assertEquals从79增至81，其它断言类型数量不变。 |
| 新增两条加强 | 762–766行要求三参回调URL精确为本例call的/fact；775–782行按完整同tenant到期PENDING/lease可认领谓词查询，要求列表仅当前call。它没有添加principal/skill过滤，也没有排序或修改其它事实来制造唯一候选。即使存在意外同例事实，会先失败而非悄悄跳过。 |
| 产品行为不动 | drainTenant仍以1为上限，cleanTenant仍为8；原排序、租约、持久principal恢复、retry三元条件均不变。不是强行令产品用JWT主体，也不删除、认领或回拨未知旧队列。唯一due回拨仍限定本例tenant/call。 |
| 清理与测试身份 | 既有artifact清理改为新tenant，因此不会继续清旧skill-tests材料；未增加公共清库或清队列操作。29个方法/辅助签名及顺序、23普通Test＋一个CHAT/TASK参数方法的25报告形状保持；原PG/MinIO条件、等待、关闭资源和清上下文逻辑均未扩改。另一类3报告源码不变，合计28。 |

独立按上述授权增量反撤销：22处普通映射、两处SQL扩展/参数、实例字段/一次初始化和两条新增断言，**完整字节精确还原14AE**。这约束其它逻辑、断言、预算、排序、fork、skip和方法身份没有隐藏变化。30项作者静态回执与上述源事实相符；本轮未运行作者脚本，也不将文本/文件检查冒充Java编译。

## 4. 准入要求与保留门

可进入root另授的一次默认SkillExecutionTest＋SkillTaskResultTest定向：25＋3原身份齐全，native0且无failure.ignore，新唯一due/精确URL与原principal/SERVICE/DELIVERED/业务一次均须实际到达。不得改变排序/fork/skip或反复抽样取P；编译、前置或缺报告失败不算目标通过。

定向门通过后才进入同栈完整skill模块：预期70类674身份、原116非通过及4旧skip按原方法/类型/原因逐项比较，outbox自身方法恢复P，SDK五身份仍须全部P；558P/6F/106E/4skip仅是无其它变化时的预期，不能代替实际结果。保留failure.ignore native0与collector/方法门的区别，不用旧失败减少抵消新失败。

原Full新增outbox F、旧算法F及全部诊断/历史证据保持，不因静态隔离成立或单次28P而豁免。若出现新增非通过、丢身份、原因/环境/源漂移，停在准确技术门。完整模块、独立终审及提交回填齐前，EX03和SDK子步骤仍不关闭；本文件只给607B的静态准入，不签收未发生的运行或提交。
