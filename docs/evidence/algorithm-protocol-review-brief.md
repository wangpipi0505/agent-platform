# 算法协议独立复核需求

此任务仅在作者最终冻结并由root移交skill target后启动。只在独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 遵守AGENTS；不得读取或关联相邻旧实现、配置、存储、进程。用户无真实K8s等环境，已授权继续隔离端点实现与验证。

需求输入依次：`algorithm-protocol-implementation-brief.md`、其单项原计划 `algorithm-protocol-implementation-plan.md`、root补充 `algorithm-readonly-retry-decisions.md`。最后一份裁决细化原计划：可信原Job Failed确定终态用现有NON_RETRYABLE_FAILURE，不重建原Job；可靠未开始创建的只读暂时故障可经完整NOT_SENT fence、已核原引擎新attempt、当前guard及原子归档/准备转为新尝试。不是UNKNOWN写许可，不任意把新节点attempt映射旧Job。该裁决可被技术审查指出矛盾，不能因root接受而忽略真实缺陷。

作者报告 `algorithm-protocol-report.md` 应索引实际完整patch、baseline/frozen、变化清单、源码前后SHA、红绿和最终全模块日志。无HEAD，必须用真实差异和字节核验，不能空git diff。原expectedAttempt前置skill297/独立115是历史基线；新的完整回归数从实际日志读取，不估计拼接。

重点核实真实链路：

- persistentBinding包括ALGO但不改变原HTTP asynchronous语义；完整PREPARING/fact与binding同事务、网络在事务外、submitted先于业务发送；并发一次POST、旧ACK迟到不借新代；queryOnly无绑定插入/新业务、ACK未知按原Job恢复。
- full tenant/principal/run/call/attempt/version/contract/raw+guarded args/key/API/namespace/image/SA身份和UID只写一次；每次原Job/409复读校验，Pod controller owner UID/name/apiVersion/容器与完整输出核验，不仅标签。
- NOT_SENT与已知FAILED只读新代的锁序/原子历史/准备/完成CAS；UNKNOWN/sideEffect/缺新权威/已有Job禁止此移交，原HTTP/MCP合法只读路径不被禁。取消/租约/审批/Mock原语义不弱化。
- total monotonic deadline从首次外部读前覆盖read/create/409/poll/Pod/log以及MinIO上传/回读验收尾段；算法专用ArtifactStore扩展不得改变原HTTP/MCP入口，不以每个HTTP单次timeout替代统一绝对期限、不关闭共享客户端资源，期限到达后的实际I/O退出及清理事实需有证据。SDK实际请求禁重定向/隐式重试；元数据在SDK物化前计数，日志完整流式接收/转存/Schema验收，超限/截断不伪成功，本地资源确实关闭。cap+1是应用/SDK可见的解码字节边界，需明确固定底层Okio预取而非TCP读取承诺。只用小预算/有限延迟测试，不做耗尽探针。
- 可信Complete/Failed/矛盾状态分别正确采纳；fact→binding同代短事务，旧查询不改新UID/DONE/事实，W1/W2/W3和取消后迟到不复活逻辑运行。
- V311空库与V310升级、旧算法缺身份不猜回填、不对HTTP旧行全局加NOT NULL；原HTTP回调/proof记录及不可变摘要保持。

独立验证：核源/patch/SHA，选择真实专用PG+有限K8s HTTP的关键套件亲跑，检查实际POST计数与事实；按需覆盖边界/原路径保护。target移交后仅你运行skill Maven。不要跑完整七模块、package或启停root主服务；不派子agent，不读.env以外任何旧凭据（只用root明确可用的新工程测试环境，装入变量不打印）。不重放MCP原曾中断的内存/慢模式探针。真实engine+skill子进程纵切由root后续执行，不冒称协议夹具是真K8s/容器/RBAC。

输出 `docs/evidence/review-algorithm-protocol.md`，分别给需求符合性和代码质量结论、具体发现行号/触发/影响、实际执行命令/计数/限制。遇实质问题先报告，不自行改作者源码或原证据。报告仅本增量，不代签全T11/G2或容量/生产验收。完成后交还target，简短返回结论。
