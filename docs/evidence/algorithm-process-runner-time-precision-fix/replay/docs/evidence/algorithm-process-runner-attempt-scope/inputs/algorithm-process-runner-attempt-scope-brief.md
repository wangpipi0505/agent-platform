# 算法真实进程验收：本地恢复与原外部身份的窄修复

本任务由root在独立memo完成后授权。唯一新工程遵守AGENTS；不得读取/关联/修改相邻旧工程。用户已授权持续开发和隔离端点验证，原v1.2/四契约、生产代码、962固定JAR、原4秒期限（原offload15秒）、health、环境白名单、异步PS上界/UTF8/精确进程归属和逐项收尾均保持。

先读 `algorithm-live-attempt-scope-review.md` 全文。其“最小runner修复口径”六条是本次要求；不是放宽为任意attempt数。详设1750/1754–1757明确本地认领尝试与tool_call登记分离。root已独立读原PG行及源码，采纳限定判断：v4的原外部attempt5未变，本地attempt6消费原终态结果；错误在runner总数断言，不改生产恢复路径。原memo、v4失败及前三轮历史保留。

## 唯一实施范围

只修改 `tests/smoke-algorithm-processes.mjs` 的PG只读capture/验收断言和必要有限纯Node测试；不要改变实际请求/重试/对账/初次受控调用/fixture行为。可新增纯Node断言组件或测试资料以便验证，但不造通用框架、不改旧31项测试语义。按memo补 before/after/重检后afterRecheck 的逐行集合、node提交字段、accepted终态note行和带时间事件；继续保留整数计数。重检后必须重新capture并重验，不能只看fixture POST数。

保留所有非恢复场景原总数1；仅ack-loss在精确UNKNOWN/WAITING前提下允许恰一条额外本地no2，严格校验原行、新行、同node key、node.committed_attempt_id、note/EXTERNAL_READY/NODE_STARTED/唯一提交的身份与顺序。节点attempt幂等键与外部call幂等键各自固定，二者不是同一字段，不能相互代替。

原call/fact/binding及各自snapshot的完整原身份、owner、args/contract/API/namespace/Job/identity摘要都固定且集合唯一；accepted终态note/result.owner仍为原外部attempt。UNKNOWN note不计终态；原UNKNOWN本地attempt保留。ACK前binding UID可NULL，首次采纳必须是fixtureBefore已记录的原非空UID，之后含重检后不变。终态terminalEvidence的实际字段是externalJobId/externalJobUid/namespace/apiSha256/identitySha256/condition，须与原绑定相符；Running UNKNOWN不要求存在终态证据。节点output与原structured结果的比较用于ack-loss成功恢复，不能将这一成功条件强加给Running/Failed/转存structured=null。

一次原call/fact/binding、readonly_retry_count=0、fixture POST1、终态note恰1（Running为0）、逻辑提交原计数继续严格。额外call/绑定/外部换代/第三本地attempt/错提交/重检后漂移均拒绝。每个场景整体仍必须走原重检/输出/UID/MinIO/清理检查，不能提前标passed。

## 真实红绿与交付

先以当前257e源码/真实v4只读行取得可运行的行为红（旧总数1确实错误拒绝合法本地恢复），缺导出或语法失败不计行为红。有限纯Node负例按memo分别注入第三attempt、错提交或事件顺序、任一外部attempt/owner/snapshot/完整身份漂移、错误首次UID/新call或绑定/POST2/重复终态note或逻辑提交，以及只在afterRecheck发生的漂移，必须拒绝。真实v4未执行重检；若测试构造afterRecheck数据，明确是合成样本，不能回写或冒充原v4观察。新的采集/验证不可通过SQLseed业务事实。

保存完整baseline/frozen/差异patch/源SHA/原始命令和结果、实际apply重放逐字比对；全部旧31项继续覆盖。原四轮实际日志、全部原patch/manifest/报告与独立memo保持原字节。另写 `algorithm-process-runner-attempt-scope.md`、对应patch/SHA目录，报告需求映射、真实行为红绿、已读设计/源码、限制。v4报告误记recoverExternalReady方法，实际为resumeExternalFacts；在新报告澄清，原已冻结报告不改。

完成即冻结移交，暂不执行第五轮、不package、不接真实服务、不启动JVM或新库、不提交、不派agent。root读报告及覆盖命令/结果后交原runner审查者核本次代码；真实新五场景与事后PG/MinIO审查另行授权。所有现有局部通过不得表述成17功能/34任务/30验收或生产环境签收。
