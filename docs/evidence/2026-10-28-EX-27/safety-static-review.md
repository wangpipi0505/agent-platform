# EX-27 安全向量候选静态复核

- 审阅者：`/root/ex35_independent_review`，未参与本候选实现。本轮仅只读源码及安全证据，并写本记录；未运行Java/Maven/HTTP/PG/浏览器，未修改测试、产品或主入口。
- 当前实际核对的测试SHA256：`E1758505D03E6997D257ECF6D52BAC1A97C6AFDBA782DBB15105E30549F37517`。此前`148…`、`FD34…`未运行，不是产品红阶段。所有结论仅指E175候选。
- 依据：[独立技术门](technical-review.md)、`safety-candidate.json`、`method-scope.md`、`diagnostic-result.md`，以及本次实际测试diff和SSE/Http/Budget必要调用方。

## 已发现问题与复核

| ID/级别 | 位置/场景 | 问题与影响 | 当前处置 |
| --- | --- | --- | --- |
| EX27-S1 / P2夹具阻断 | FD34测试约:798，首个GET /ping | 夹具要求普通ping具有非空SSE RequestSlot；ContextValve每个REQUEST先清旧attribute，仅真实SSE路径才铸新token，因此会在取消/退出/持费断言之前失败。不能改产品为ping授予token。 | E175实际复核关闭：明确REQUEST且attribute为null，读取真实处理bridge socket，分段计数两个实际HTTP请求/200 pong及同keepalive socket。不声称普通ping具有SSE token，也不声称原SSE processor已复用。 |

## 当前候选覆盖

1. 独立枚举为原37个方法全部保留，新增默认`offLockCloseDuringProcessingRetainsSafetyWithoutInventingRecycle`，合计38，无删除或重复。原显式诊断flag仍选择裸close及原entered断言；默认完整类不依赖该flag才能执行恶劣关闭向量。
2. 原回收正例以只读方式捕获原socket/processor/token/真实worker；在真实socket锁内确认未closed、仍关联该原processor，再调用真实socket.close。没有调用私有工厂、手调recycle、改token字段或造completion。原5秒entered等待及全部后续Owner/第三拒绝、无completion、真实回收/waiting/token清引用、重复close断言保留。
3. 新off-lock向量保留P1/P2/P2b：回调持原socket锁/关联为空，外部裸close正常返回，先解除人工暂停再见证处理退出。它在5秒观察之后继续安全断言，没有在原正例entered预期处结束；不把0recycle单独当安全通过。
4. 第三SSE拒绝在取消前及worker退出后分别通过额外真实Servlet REQUEST调用原start，由原工厂分配新Handle，再经过真实Owner.acquire。要求原固定`packet owner slots exhausted`异常、未发布Handle、Handle分配计数恰增1及Owner仍2，排除了重复使用旧Handle的claimSession假拒绝。额外两个Exchange只用于该向量，原正例/显式诊断仍为两个。
5. 退出证明使用捕获的真实Thread.join和isAlive、worker-body-exit及Session.active；持费检查与退出分开，要求没有completion、lease未释放、Owner未关闭、费用仍保留、awaitClosed不能冒成功，且退出后再次拒第三Owner。没有靠取消flag代替实际reader退出或合成完成。
6. 旧Handle重复close时，在实际before-close-now hook核原socket/origin/slot/token tuple且目标仍为原closed连接；动作次数至多1，与实际返回计数及安全事实对应。两个真实普通REQUEST在另一个keepalive socket上分段返回pong，旧关联保持且游标不推进。普通请求没有SSE token，不把请求次数包装成SSE token identity或原processor真实复用证明。
7. 原SSE的真实跨socket/keepalive复用仍由既有两个复用方法及原回收正例在默认完整38方法中验证；本新增向量没有宣称其未回收的两个processor已经被复用。src/main中SSE、Http、Budget没有本子步diff。`git diff --check`无错误，仅既有CRLF提示。

## 静态准入结论与边界

**E175候选可在root明确授予实际运行窗口后，进入原回收正例＋新增off-lock安全向量两个准确方法的编译/执行。** 未再发现需先回改的静态阻断。没有把FD34已知夹具错当产品失败，也没有将先前447静态/诊断结论直接套到E175。

这不是编译通过或实际安全已证：真实Tomcat回收、第三准入异常来源、两个HTTP响应、action目标、reader退出、Owner计数及子JVM退出均需实际结果核验。新增向量主动取消后的join也不能冒称未经主动取消的原idle/hard期限实测；相关既有期限方法仍须在完整38方法中保留。测试失败按实际编译/API/夹具/产品责任归因，不可放宽断言凑绿。

之后仍需同一冻结候选的默认完整38方法、gateway全部27类逐方法对照、独立验收及提交/回滚记录。原447诊断FAIL与EX-35历史新增FAIL保留，本记录没有关闭EX-27/Y-4整卡或EX-35模块gate。

## 编译类型delta复核：6B144候选

E175首个实际target `safety-targeted-20261028-232611-304` 编译失败，exit1、0XML、0方法、0child，没有执行目标断言；这是夹具编译问题，不是目标反例RED。首轮静态审查漏掉了`traceCount`返回long与两处int使用不一致，原失败记录保留。

本次独立读取当前源码，SHA256为`6B144C0033CE22556996897824B2F0D864A862E39F6E12EF2C21B5212B5199C1`。仅两处修正：约:828的`int actions`改为`long actions`，约:942的`mapToInt`改为`mapToLong`。在内存中反向替换这两处后，UTF8字节SHA精确恢复为已审E175，确认没有夹带其它Java变化；38方法身份及既有断言语义不变。`traceCount`原定义确实返回long，修正与该类型一致；diff检查无错误。

**6B144候选可在root授实际运行窗口后继续原定两个准确方法的编译/执行。** 本次仅delta静态准入，不重复整技术门、不外推编译已经通过或目标安全已证；没有运行任何Java/Maven/HTTP/PG命令，没有修改源文件、index或主入口。
