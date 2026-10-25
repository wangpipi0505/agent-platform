# O2b F2c1：完整材料验证与内部借用窗口

本文是预备任务；仅在 F2b2 独立复审/有限审计/限定证书和八索引闭合、root确认其实际私有Session接口后，显式派发才获得写权。不得读取活动B2源码/target/owned。所有shell cwd固定 `G:/207/项目/算法重组工具/代码/agent-platform`；不得读取、关联或影响相邻旧工程的代码、配置、依赖、数据、进程。

用户已授权按计划持续实现，没有专用联调环境，继续新工程隔离端点。v1.2/四契约不动、时间安排不在范围；遵守AGENTS、implementation-brief、contracts/README，固定Java21/Boot3.5/现有Jackson与common/API分层，不升级依赖、不引其他业务JAR、不提交或部署。

## 完整要求与本任务边界

以下三个冻结文件是本任务不可省略的具体要求，须完整读而非按摘要实现：

1. `.local/iam-o2b-f2c-material-task-draft.md`，18724B / SHA256 c0483e13a7ef8f49e66a7c45f9ce1e78e097f8e766fce5bb564cdac5302688f2：严格nested schema、原Q七事实、HELLO receipt/child关联、全部scope/直接父集合/floor/initial、state/event业务SHA、fresh G current与借用生命周期。
2. `.local/iam-o2b-f2c1-material-budget-notes.md`，20551B / cb09c66313d4924f0d9b47637f27f815520ee9fdacdc5413f07f80947271c378：具体80MiB分配表、3遍扫描/最多2次P3重验、primitive索引和局部容量profile。正式具体实现仍需逐分配对照，数值不是全JVM/TLS/common/native容量保证。
3. `.local/iam-o2b-f2c1-fixture-gate-notes.md`，7957B / 94c989956d20a7106b89d53c9cf44e4dd153182493c5dc5161ac0ca6d582622f：干净checkout测试fixture、公开receiver/真实receipt链、冻结E WINDOW资产与独立业务SHA oracle。该提案只读、尚无编译/执行证明；实际接口仍以B2Accepted补充为准。

目标是从本Session实际G/原Q事实/Owner/UntrustedPacket验证出仅供内部同步借用的VerifiedWindow。receipt签名、代际、材料关系与业务canonical均真实验证；不增加浏览器字节、SSE/Servlet、公开cursor推进、controller/assembly或外部通用permit工厂。三份稿中的建议API若与已Accepted B2接口不同，root在实际API补充中裁定，不能靠反射或猜测调用。

## 最小源文件写权（待实际输入清单确认）

B2候选source623；若实际Accepted保持623，本轮1已有修改+4新source→627，其他622不变：

- 修改 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java`：仅同Session绑定验证器、内部同步借用窗口/进度及失败退出接缝，不改变原Q或现有私有输入默认语义。
- 新增同模块infra/PortalEventsMaterialVerifier.java，包内final，私有构造VerifiedWindow/有限描述符/业务canonical helper；不建立通用授权框架。
- 新增同包测试 PortalEventsMaterialVerifierTest.java、PortalEventsMaterialAuthorityTest.java。
- 新增 `platform-gateway/src/test/resources/iam-o2b-f2c1/e-event-window.fixture.json`，唯一冻结WINDOW资产，资源计source。

不改production common/TLS/F1/engine/pom/main/前端/设计契约/八索引。无需额外common helper；新增fixture helper嵌入上述AuthorityTest。若实际B2 API或必要回归要求改变范围，先给root具名原因，不自行扩权。

## Root冻结的实现决定

Session内部以私有构造的绑定context携带自己的真实G、原Q七事实、Owner及具体packet。验证器不能接收caller随意拼接的G/facts/Owner/child，不新增public verify(...)→permit；VerifiedWindow仅在同Session/同包/同generation借用内有效，关闭后所有访问拒绝。包原字节/可变数组/DOM不得通过公共接口逃逸。

HELLO只消费F1实际完整包：receipt ASCII≤4096，调用同G.acceptChildReceipt原串恰一次；随后严格解析完全相同已验串的关联claims交叉比parent/child ID，不用解析结果替代签名验证。receipt/HELLO不产运行状态或公开成功。CAPACITY/EOF不产生事实/终态/游标。

每个WINDOW严格14键及全部嵌套字段，owner/root/version/两SHA逐项对原Q私有事实及G subject/resource。历史execution initial/provenance不强行等于此次observer来源。scope集合是三个root、state、event及其直接父的精确并集；被包含scope的完整parent数组也用于其本身hash，不能递归扩大闭包，也不能裁掉超过64的父项。

每次权限材料验证绑定真实最新Capture，对全部scope及floor/initial执行同代校验；结束核未superseded。只在任何公开字节前允许至多2次完整P3重验；不能给旧验证结果换generation标签，不重新exchange Q/G/child、不延长原55秒。C2对每次真实write/flush再检查，本轮句柄不授权任意未来发布。

业务SHA必须等E的语义：递归Java String key排序、array保序、精确BigInteger/BigDecimal stripTrailingZeros、zero归整0、JSON字符串转义；不能替为wire SHA/RFC8785/double。先算数字展开代价再展开；canonical流式digest，不能生成无界/8MiB整串。

采用P1紧凑索引、P2单scope、P3 fresh capture与scope/floor/business payload，常规3遍，最多2次额外P3；primitive8192 slots/bitsets、受限探测、定期deadline，避免全metadata DOM或按scope重复扫描整份metadata。profile：metadata65536 tokens/name occurrences524288/单name256/单scalar65536/number1000；oneScope8192 tokens/字符串occurrence131072 UTF16/canonical≤1MiB/parents≤4096；persistent facts16384 occurrence/1048576 UTF16；payload≤262144B/depth64/tokens32768/scalar262144/number1000；business canonical≤8MiB、单数字输出≤65536。严格拒绝unknown/duplicate/trailing/UTF8/surrogate，容量失败与权限失败分类。

reserve80MiB=83886080B（另128token）须先于相应图分配；按预算表A parser16M、B index/persistent9、C scope4、D common helper24、E payloadDOM12、F canonical8、G receipt/margin7对照。B2 lazy1MiB只计一次；C1消费峰值候选96805860B/Owner，后续C2候选98715562B/Owner、双197431124<201326592。F1 64MiB scan已退出才进入材验；c1/parser/frame与借用必须释放后才能下一次F1扫描，不能把scan和80MiB同存。排除全进程/provider/native/common全图，不声称RSS硬界。

## 可复现fixture与验证

使用真实LoopbackAuthority、同Session G/child和实际TLS两连接；E peer从实际收到的Bearer走公开receiver链：TrustedJwtDecoder→真实SecurityContext/ServletRequestAttributes→ServiceAuthorization.preHandle（精确 `/admin/runs/{id}/events` 路由）→AuthorityContext.requireInitialFor→engine.openPortalEventsChild→portalEventsChildReceipt；finally afterCompletion并恢复上下文。不得反射bindRetained、不用public bind(currentOnly)假装initial、不得自签receipt或预先acceptChildReceipt。

唯一新asset来源为 fixture gate所列冻结E event向量198285B/d24b82c6f28ac27d0958c4c2a2a8dd2401571282fff14d0301e88f4150faf60d。获权后从该已核不可变向量仅提取WINDOW原metadata195843B与payload58B，保留69scopes/maxParents68/全部父数组；用base64原字节、offset/length/段SHA及来源身份包装于新JSON，禁止历史HELLO/JWT/私钥。输入旧vector身份继承原root冻结cache，提取后只hash新asset/段并比旧header已验SHA，不无故重hash旧binary。root审计新asset真实来源/段身份后冻结，测试classpath读取并核自身常量身份，运行不读ignored.local或engine JAR。

Q七事实/观察者配置要匹配冻结WINDOW，但不重写历史initial/provenance。用新E receipt/child ID构造新的HELLO/header，机械F1封包helper可以复用；默认hello/window只是未授权骨架。新的负例应修好wire SHA/COMMIT和必要scope引用，才能证明实际拒在receipt关联/nested/floor/business层，不把早期坏包失败冒称深层覆盖。

首正例业务SHA expected来自冻结E material payloadSha256。数值/Unicode/null小向量采用独立手写canonical字节+JDK digest，不用待测Verifier生成oracle；实际E采样兼容与按其语义推导的向量分别报告。至少65父兼容正例必须实跑。

profile各个最大值未必可同时达到：4096个64字符父hash先超oneScope字符串profile，name/depth等固定schema也可能不可达；不放宽schema造最高值正例，不以unknown/其他限制先拒冒称后续guard覆盖。记录可达正好/+1及真实拒点。只在新epoch执行两新test及root在B2实际API补充选定的必要回归；不重跑已成功旧F1/E/PG/provider矩阵。关键失败先真实RED，配置/编译/缺API/不同层的拒绝诚实分类，不预报测试总数。

existing F1 acceptedTwelve采样依赖ignored.local的历史范围保留到最终whole-branch portability评审；本轮不调用或扩大改写它。新C1测试必须独立classpath可运行。

## 证据与交还

ownedRoot仅 `.local/iam-o2b-f2c1-material/`、`docs/evidence/iam-stage-4-o2b-f2c1-material/`、具名`.tools/iam-o2b-f2c1-*`、报告和实际wrapper日志。所有成功phase立即journal/cache，元数据错误只续未完成部分、不重复成功测试/编译/PG/provider/Git apply。每阶段完整manifest、五个新增/变更source副本和其他622继承关系，source resource与实际Maven copy同绑定。

顺序完成主privateGit精确before623、replay before623 bytes/names留档、新raw binarypatch（1已有4新）、首次plain check/apply各1次与完整after627名字/字节，attrs在add前、隔离config，无第二辅助index/无apply后补copy。finite target仅actual compiler/resources/common testJAR/Running，从B2历史logical继承；diagnostics另列。child完整(run,PID)、实际exit/collector join，未退出/borrow未归还不退Owner费用，不以cancel当退出。

报告 `docs/evidence/iam-stage-4-o2b-f2c1-material-report.md` 与handoff/inventory冻结，交还source/target/owned，短报DONE类状态/实际验证/顾虑。root独立复审及审计后更新八索引。本文不签收完整IAM/公开EVENTS/SSE/Servlet、真实enginePG全链、生产launcher或全部容量。
