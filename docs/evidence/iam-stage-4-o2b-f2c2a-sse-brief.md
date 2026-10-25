# O2b F2c2a：未装配的SSE编码与真实Servlet生命周期组件

本任务仅预备；**C1完整材料校验独立Accepted/有限审计/八索引闭合，root核实际API并冻结输入、显式派发后才有写权**。当前不得读取C1活动source/owned/target。所有shell cwd固定 G:/207/项目/算法重组工具/代码/agent-platform，不读/关联/影响相邻旧工程。用户已授权按计划持续实现及隔离测试，无专用环境不构成停止理由。v1.2/四契约与时间安排不动，无commit/部署/历史主服务重启。

## 完整输入与具体范围

实施前完整读下列冻结输入，并以root最终bootstrap裁定覆盖旧候选中尚未解决的方案：

- .local/iam-o2b-f2c2-sse-task-draft.md，7989B / de615ee4a663bd2df58044a6e773b3bdeab199410e8d735b03172807cb80f06b。
- .local/iam-o2b-f2c2-abort-seam-notes.md，5974B / 3f6cd1b764f9ae0fc68de4d89c75c780c3bb6a378fc1ac0cc00ab688a762a83b。
- .local/iam-o2b-f2c2-write-thread-gate-notes.md，22454B / 472cb764dda291553e229a0ccc2c21a65ec99ae9b2663200ffff00a347a5017d。
- .local/iam-o2b-f2c2-listener-bootstrap-notes.md，13308B / 699c408733ba62ef687d65eb1cc5ad91211f38ba5b0b768b97046831dcf2d1b9。
- .local/iam-o2b-f2c2-bootstrap-root-decision.md（身份随正式inputs冻结）、C1的正式brief/预算及Accepted后actual API补充（待root）。

固定Java21/Boot3.5.0/Spring6.2.7/Tomcat10.1.41/Servlet6.0，HTTP/1.1 NIO；不升级依赖或引其他业务服务JAR。C2a只将真实同Session的C1 VerifiedWindow转为SSE，并证明有限应用资源生命周期；公开IAM EVENTS路由暂不接线。C2b再做controller/assembly/common ingress和真实gateway-engine-issuer-PG-TLS首链，不能以C2a测试peer替代C2b生产E/PG证据。

候选最小四路径（待C1实际清单确认）：新增gateway infra/PortalRunEventsSse.java、同包test PortalRunEventsSseTest.java/PortalRunEventsSseLifecycleTest.java；只修改已有Http的必要私有收费/终止接缝。候选source627→630（3新1改）；其他626不变。无扫描注解/新公开路由；production common/controller/assembly/engine/TLS/F1/pom/main/frontend/原设计/八索引不改。若实际C1接口使范围不成立，向root具名说明，不能自行扩权。

## 不可省略的输出与授权规则

SSE只接本真实Session已验证窗口，不接caller拼接G/facts/Owner/packet，不造通用permit。实际原Q/Opening→G→同child→receipt→C1完整材料/capture链不变，t0/5秒Opening/55秒hard及实际15秒idle不重置；不退回legacy/公开上游SSE，不从HELLO或EOF推导成功/终态。

业务event的data是原payload直接JSON值，不新增wrapper。仅在C1严格校验后移除string外SP/TAB/CR/LF格式空白，保留数值lexeme/字符串空白/转义/Unicode；不得canonical重编码或用double。event type继承PG varchar32 Unicode、最多128 UTF8B，保留前导空格，实际CR/LF/NUL/空type有限拒绝。完整固定frame backing270374B，UTF8 event行保留一个分隔空格，id规范十进制，无第二背板/增长数组/队列。null与高精度数值须实际验证。

reset优先于首status，status最多一次；terminal在backlog完整消费后，EOF不冒终态。CAPACITY/局部capacity/坏包/拒权不产run事实/伪cursor；失去可用fresh许可时直接受控终止，不伪造最后reset。输出前可返回现有有界ApiResponse错误；body已有字节后不拼JSON错误或新代尾部。公开cursor仅全event write、requiredflush/drain、最终fresh generation检查全部成功后推进，私有COMMIT不推进。

所有实际Servlet getter/ready/write/flush均在明确生命周期与fresh guard范围；每次chunk≤8192，partial attempt先标记，不以isCommitted推断零字节。capture过期/代际替换时至多2次完整P3重验仅允许在任何frame-byte attempt之前；可以有限失败关闭，但不能给旧结果换代标签。任何attempt后代际变化必须终止，不能重新验证后续写旧frame尾部。

## 采用的实际Servlet顺序

原真实请求线程消费受控Session，begin/收费、startAsync/安装owned AsyncListener、arm精确request token，再启动唯一private reader worker。原线程仅有限等待首真实VerifiedWindow/首帧slot、首错或自有原绝对deadline，释放所有应用gate；worker同步持有C1借用，不自行get/register。

首窗先发布后，原请求线程在输出/terminal gate内fresh guard→getOutputStream→fresh guard→发布output→setWriteListener，然后立即归还Servlet，绝不等callback。固定Tomcat requestThread分支只DISPATCH_WRITE；worker setter会DISPATCH_EXECUTE并同步sc.run/形成gate→socket死锁和生产者自等，禁止采用；AsyncContext.start也不修复request-thread身份。

真实WriteListener执行全部actual write/flush。ready=false返回等已armed容器通知；ready=true无frame时轻外壳在唯一条件上有限等待新frame/EOF/stop，条件释放锁、不持输出gate/token锁、无旧material/frame局部。最多2个初始请求线程/2个callback占用，均绑定2Owner，不能借容器线程池容纳无界等候。容器timeout/RST可被socket锁延迟，已有独立55秒/实际15秒必须实际唤醒退出，不依赖容器回调解锁。虚假唤醒不重置期限。

per-window heavy helper实际返回、slot清空/借用真正结束、原同步consumer退出之后，才能退C1/parser/frame/packet费用并下一F1 scan；轻callback可保持inert等待，不能把自身finally信号称整个callback已返回。最终Owner还需private transport/worker/IO/borrow退出、completion观察、heavy引用detach、timer不能再触达重图；任一失败持费、两个Owner未退时第三拒绝。明确轻容器尾部、全channel/内核关闭无同步ack的边界，不能声称全container join。

精确一次CLOSE_NOW用既定Tomcat公开action：停止新输出/notify→终止私有HTTP→受控action；与完整isReady+write/flush按同gate排序，防清listener造成blocking fallthrough。无wait/join持gate/token锁，不递归onError abort。onComplete在回收前同monitor disarm精确token，迟到abort不能伤keepalive下个请求。不用output.close/flush冒作abort。

## 费用与真实验证

继承固定候选：frame270534含token、UTF8/counters65664、Servlet1MiB+token、callback/worker/exit512KiB+token、可能第二borrow384；B2 1MiB只计一次，候选98715562B/Owner、双197431124<201326592。逐分配实际核wrapper/buffer/复制图，reserve先于分配；原C1/F1不重叠。排除全JVM/TLS/native/global container/GC余留，不把账本算术等同RSS上界。若新增对象图不在profile，先root具名gate，不默用余量。

两新test须真实嵌入Tomcat HTTP/1.1 NIO、同真实LoopbackAuthority/公开receiver/私有TLS peer与C1完整材料；可复用本工程既有classpath资源和机械test helpers，无ignored.local运行依赖/业务JAR。原生产路由未接线须如实标注。先关键失败路径，编译/API/fixture早拒不冒业务RED。

覆盖：Unicode>64B/前导空格/null/高精度/转义/最大frame与可达+1真实拒点；first-window bootstrap/首错/迟到首窗/独立期限，真实callback是否set返回后；ready-idle新frame进展/false通知/虚假唤醒；实际停止读产生pendingflush/ready=false，撤权和generation在各write边界、partial-unknown/cursor不早进；pause heavy/helper和轻shell分测，原scan/两Owner费用不早退，第三拒绝；外部abort夹ready/write与同gate排序、late keepalive token；action/close/callback卡住有限耗尽且持费。实际peer EOF不替代服务端出口/费用证据。

只选择受影响的C1/Http必要回归，不重跑作者已成功旧E/F1/provider/PG套件；最终新epoch两新test及必要旧方法，不拼各epoch作同场。root在actual API补充中定实际必要旧回归。

## 证据、交还及后续

ownedRoot .local/iam-o2b-f2c2a-sse/ 与 docs/evidence/iam-stage-4-o2b-f2c2a-sse/、具名.tools/iam-o2b-f2c2a-*、报告/实际wrapper日志。成功phase/hash立即cache，metadata只续未完成且保留失败原件；stage raw/XML/compile/epoch/resource/childwaitFor+collector立即保存，其他unchanged source继承明确。

唯一主privateGit精确before、attrs先add；replay pre-save before bytes/names后首次plain check/apply各一次，完整after精确字节/名字，不设辅助index/after补copy。finite target只actual compiler/resources/common fixtureJAR/Running，从C1历史logical继承，diagnostics另列；无wholecurrent/classpath/部署声明。

报告 docs/evidence/iam-stage-4-o2b-f2c2a-sse-report.md，handoff/inventory冻结并交还所有writer。root fresh完整代码review及有限artifact审计后只签收C2a组件、更新八索引；随后C2b实际ingress生产接线＋gateway/engine/issuer/新PG/TLS≥65父首链。不得提前把公开EVENTS/完整IAM/生产/容量/17-34-30报通过。
