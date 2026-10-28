# EX-27 回收诊断与测试前提独立技术复核

- 审阅者：`/root/ex35_independent_review`；未参与本次诊断实现。本轮只读源码、冻结需求与安全事实，仅新写本记录；未运行Java/Maven/HTTP/PG/浏览器，未改测试、产品或依赖。
- 诊断对象：`runs/in-flight-diagnostic-once-20261028-223443-168/`；测试SHA256=`4470434C459F900B9CC85AD96D4F7D8953A17D7BCC7B37C3EAE64FBBD9D5C53D`。
- 结论范围：**允许在root更新准确测试白名单、交还测试源码写窗后，修正真实processor回收正例的前提，并新增一个默认执行的off-lock安全向量；不授权任何src/main变更。** 不是EX-27/EX-35验收、模块gate通过或任意容器关闭的生产保证。

## 实际事实与不能推出的结论

本轮命令、Maven及子进程均以1退出，唯一准确方法为FAIL。安全JSON记录两条不同socket：ready-false暂停时，回调各自持真实ReentrantLock、socket未closed、currentProcessor为空；两个外部closer均不持该锁，真实close正常返回并置closed。人工暂停随后解除，主线程取得两个processing-exit-fence，观察到closed=true且processor已重新关联。此后原5秒等待实际约5005ms，hook=0、recycled=0、waiting=2；Tomcat.stop之前计数仍相同。

这固定了本次“处理期间关联暂空、close已返回、处理退出后重新关联、原窗口未进入实际回收”的交错。原5秒内已没有诊断故意占住回调锁，不能把红归为人工暂停贯穿等待。getter数值是依次读取的事实，不是跨字段原子快照。

本次没有证明EX-35历史新增失败唯一同因：历史运行没有相应processor指针与closer观察。也没有证明“实际recycle发生但token失效失败”。当前诊断在原第一个entered断言处失败，后面的Owner、token、迟到action断言没有执行；不能把那些安全目标记作本次已通过。不到7秒的观测也不能外推原15秒idle/55秒hard期限后的worker是否退出。

## 原权威要求的边界

已核对原[SSE brief](../iam-stage-4-o2b-f2c2a-sse-brief.md)、[fixes brief](../iam-stage-4-o2b-f2c2a-fixes-brief.md):42、59、正式`.local/iam-o2b-f2c2a-abort-profile-amendment.md`及`.local/iam-o2b-f2c2a-wrapper-retention-amendment.md`，并核对[open-source-references](../../open-source-references.md):280、284的限定签收记录。

明确义务是每个新REQUEST、正常completion、相关异常以及实际delegate.recycle之前失效原token；action捕获原socket/origin后在该socket最外锁下重新校验关联，不能命中复用后的请求。强制回收没有completion时保留两个Owner直到实际JVM退出，不伪造完成或退款；失败、卡住继续持费并拒第三Owner。固定人口/历史引用由后续1536名额及收费模型约束，不能恢复被正式补充否定的旧1218推算。

没有找到“任何socket.close返回都必须在5秒内调用processor.recycle”的承诺。真实Poller存在off-lock close入口，故本次裸close不能判作非法；但裸close也不能直接当作已实际发生processor回收。测试名称中的forced release必须由实际生命周期事实支撑。

## token、迟到动作与持费

`PortalRunEventsSse.RequestSlot.abort`先捕获原socket/origin，再取得原socket锁并复核armed、used、State、response及两侧关联（约:412–438）。实际`WrappedProcessor.recycle`在delegate回收前清token；ContextValve在每个新REQUEST之前失效旧epoch。动作不能把token转向别的socket或新请求。

当前abort没有以socket.isClosed单独拒绝。因此未发生回收/新REQUEST、原关联尚存时，旧Handle有可能在旧已closed连接上领取一次CLOSE_NOW；used保证不重复领取。它与“回收/新REQUEST之后旧token碰到另一个socket或epoch”是不同断言，不能额外发明前者必须为0的产品义务。本轮没有执行这些动作，仍须真实验证，不能只凭源码写0跨代action。

`State.finish`等待completed、action/heavy退出及真实worker退出，之后才detach重图、释放SSE lease；Http的Owner退款还受真实transport/operation/lease门约束。没有completion时不退款符合正式要求。closed processor仍在waiting集合是实际保留现象，但其重图应继续由原Owner收费、processor bundle继续占固定名额，不能仅凭recycled=0断言无费泄漏，也不能仅凭“有预算”宣布所有安全条件满足。原期限后的实际reader/transport退出、计费不早退与第三准入拒绝应由新增向量分别观测。

## 允许的最小测试调整

采用**37个原JUnit方法＋1个新JUnit方法**；默认完整类必须执行两种语义。两个场景各用fresh child，遵守单JVM终身一个protocol。显式诊断flag可继续保留供历史红复核，但默认类不能只靠手动flag隐藏恶劣关闭覆盖。

| 向量 | 最小前提/动作 | 必须保留或新增的断言 |
| --- | --- | --- |
| 原真实processor回收正例 | 在每条真实原socket处理锁内，确认该socket尚未closed且currentProcessor仍为该请求的原processor，再调用真实socket.close；不手调recycle、不伪造token/processor或completion。准备前提使用有界同步，不能把回收gate或close包装成假实现。 | 原entered.await(5s)、hook暂停时recycles=0、两个Owner/第三拒、awaitClosed=false、无CLOSE_NOW/无completion、释放gate后真实回收、waiting=0、token五引用清空、重复close无迟到动作，原断言全部保留。锁内close仅服务这个正例，不能代替下面的off-lock向量。 |
| 新增默认off-lock处理中close安全向量 | 保留已验证P1/P2/P2b：两回调持原socket锁且关联为空，外部裸close正常返回；先resume人工暂停，再取得处理退出见证。直接记录实际是否recycle，不能停在原正例的entered预期处。 | 核实真实closed/关联/回收/waiting事实；没有实际completion时completion计数仍0，Owner仍2、第三SSE准入确实拒绝、Session/Handle不能虚称awaitClosed成功或退款。真实停止/取消及原有期限应让实际reader退出，计费仍遵守completion门；不得延长或重置产品期限。对旧Handle重复close记录原旧socket上CLOSE_NOW至多一次，并验证另一个socket/请求响应及epoch未受影响；不能把旧closed连接的合法单次动作冒充跨代动作。 |

实际recycle或新REQUEST后的旧token五引用清空、0跨socket/epoch动作，仍必须由真实回收正例及既有`sameProcessorOnAnotherSocketCannotReceiveOldAbort`、`lateAbortCannotTouchReusedKeepaliveRequest`在修改后的默认完整38方法中覆盖。若off-lock子场景的原processor因为未回收而根本没有复用，须如实写未发生复用，不能人工改指针、new token或手调回收后冒称真实容器复用。该子场景至少应证实旧关联未被转向，以及另一个真实请求未被旧动作命中；跨代真实复用事实另列，不能混算。

各原窗口、主动清理前、清理后及Tomcat.stop前的计数分开保存。安全观测即使发生在预期失败后的收尾，也不能倒写原窗口通过。原95秒父子进程上界与所有finally释放保持；新增同步前提未命中、实际reader未退出或缺facts均是失败/证据不足，不能变为skip或预期成功。

## 需求与范围决策

目前事实支持修正“裸close等于实际processor回收”的测试前提，不足以确认SSE产品违约；也不足以直接关闭新增回归。上述测试修改须经root正式登记后实施，源码仅限既有`PortalRunEventsSseLifecycleTest.java`及EX-27证据，所有src/main、Http、Budget、鉴权、POM与依赖仍只读。

若新向量发现跨socket/epoch动作、实际回收前未失效、无真实completion却退款、原期限后自有worker仍不退出，或收费图不再受原Owner/固定名额约束，则另立准确产品修复卡。最小候选白名单应为`PortalRunEventsSse.java`、该LifecycleTest及新卡证据；`PortalRunEventsHttp.java`和Budget先只读，只有独立失败证据证明其职责缺口时再核准扩围。新卡先保留该具体旧实现反例，再验证真实回收/复用、Owner/真实退出及完整模块逐方法零新增；不能以改Oracle替代已发现的产品安全失败。

本记录仅通过测试前提与新增向量的技术门。EX-35原模块新增FAIL、EX-27本次诊断FAIL及原历史记录继续保留；最终38方法、27类模块、独立验收、提交/回滚与台账闭环均未由本记录完成。
