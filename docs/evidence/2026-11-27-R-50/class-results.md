## 历史失败逐类核查

旧轮次来自 [R-49 联测报告](../2026-11-26-R-49/engine-suite-follow-up.md)。表中另外列出首轮已恢复的统计用例及 R-50 排查中暴露的夹具故障。最后一列采用全套与独立联测逐例合并口径。

| 测试类 | R-49 失败 / 错误 | R-50 基线红 | 归因 | 处理及验证 | 本轮覆盖 / 红 |
| --- | ---: | ---: | --- | --- | ---: |
| `ActionPlanPostgresTest` | 0 / 2 | 2 | 断言及夹具过期 | 冻结声明补 risk。已验的两个主体才能审批，审批后执行仍要求当前在线授权。 | 8 / 0 |
| `ChatSessionConfigPinTest` | 0 / 5 | 5 | 夹具过期 | 为 SessionStore 补 TraceStore 与 JDBC 依赖。 | 5 / 0 |
| `ChatTurnEventLinkageTest` | 0 / 3 | 3 | 夹具过期 | 仅清本类会话、运行和事件，遵循外键顺序。 | 3 / 0 |
| `EngineContextPostgresTest` | 0 / 2 | 2 | 缺环境输入 | 启动真实 Spring 上下文，测试进程注入专用 JWT 配置。 | 2 / 0 |
| `EvaluationConversationSessionTest` | 1 / 0 | 1 | 夹具过期 | 保留真实 IAM 准入逻辑，仅替换可控的下游交互。 | 1 / 0 |
| `EvaluationRunPostgresTest` | 0 / 1 | 0 | 缺库或连接不稳 | 专用评测库复跑通过，覆盖上一轮回答进入下一轮规范模型输入。 | 3 / 0 |
| `FlowMaterialDatabasePreflightTest` | 0 / 1 | 1 | 断言过期 | 临时库真实执行 V216 → V217，并验证二次迁移不重复执行。 | 1 / 0 |
| `FlowMaterialScopePostgresTest` | — | 0 | 缺库 | 准备专用材料库；继续验证作用域、拒绝路径和事务隔离。 | 11 / 0 |
| `FlowMaterialStatusPostgresTest` | 1 / 1 | 2 | 断言及夹具过期 | 与当前 errorRaw 和 durationMs 字段对齐；错误资源先通过合法的路径绑定建样本。 | 11 / 0 |
| `FlowMaterialTransactionsPostgresTest` | — | 0 | 缺库 | 统一使用隔离材料库，执行真实事务验证。 | 10 / 0 |
| `FlywayLegacyUpgradeEvidenceTest` | 1 / 0 | 1 | 断言过期 | 检查 V231 已成功执行，接受后续有效迁移。 | 1 / 0 |
| `InitialChatAdmissionHttpPostgresTest` | 0 / 10 | 10 | 缺进程、夹具及断言过期 | 独立 PG、Redis、真实 app/model/engine，配置应用范围、统一测试时钟；验证并发重放与持久化终态。 | 11 / 0 |
| `InitialChatObservationPostgresTest` | 0 / 3 | 3 | 缺进程、夹具过期 | 同上，验证独立观察者、重连游标、窄权限和不可变材料。 | 3 / 0 |
| `InitialMaterialScopePostgresTest` | 0 / 1 | 1 | 夹具过期 | HTTP 应用代码由已签票根取得，测试才可到达目标拒绝边界。 | 12 / 0 |
| `InitialSenderFactsPostgresTest` | 0 / 48 | 48 | 缺进程 | 每例独立 PG、Redis、真实 engine/model 与受控模型提供端。 | 48 / 0 |
| `InitialSenderIntegrationTest` | 0 / 2 | 2 | 缺进程 | 发送端两条实际调用与持久化回执均在独立进程复跑。 | 2 / 0 |
| `ModelFailureTaskPostgresTest` | — | 0 | 本地连接资源争用 | 复验发现 Windows 回环连接已被占用。用小连接池复用物理连接，R-49/R-48 三例断言保留。 | 3 / 0 |
| `ModelInputMaterialHttpTest` | 1 / 0 | 1 | 缺 TLS 及夹具过期 | 传入完整证书链。证书 SAN 仅 localhost，保留 IP 访问和不信任证书的拒绝用例。 | 12 / 0 |
| `ModelInputMaterialPostgresTest` | 21 / 0 | 1 | 缺夹具输入及旧路径 | 隔离登记文件和证据目录，移除个人路径，子进程 JSON 与诊断日志分流。 | 21 / 0 |
| `OwnedFlowMaterialsPostgresTest` | 1 / 0 | 1 | 缺库及断言过期 | 独立 FLOW 库。恢复后路由、扫描、聚合各取一组当前授权，节点尝试和终态事件仍唯一。 | 15 / 0 |
| `OwnedFlowSchedulerPostgresTest` | — | 1 | 缺库 | 独立 FLOW 库供 worker 分辨已受理 FLOW 与其他状态。 | 10 / 0 |
| `PortalChildEventsHttpTest` | 0 / 1 | 0 | 夹具时序 | 在受控点开具新探针票，保留真实线程出入口、容量占用和槽位释放断言。 | 10 / 0 |
| `PortalChildEventsPostgresTest` | 1 / 0 | 1 | 夹具时序 | 并发可见性用最小合法 FLOW，原容量用大样本。 | 7 / 0 |
| `PortalRunEventWindowPostgresTest` | 1 / 0 | 1 | 夹具时序 | 并发写入用最小合法 FLOW，保留 RR 前后可见性与原大样本预算验证。 | 6 / 0 |
| `PortalRunEventsCapacityTest` | 0 / 1 | 0 | 夹具时序 | 隔离环境全套复跑通过，继续核对容量失败的 HTTP 413。 | 5 / 0 |
| `PublishedAdmissionHttpPostgresTest` | 1 / 0 | 1 | 夹具过期 | 当前 app 子进程引入平台授权服务，应用全迁移并登记仅该应用可读的真实测试授权。 | 20 / 0 |
| `RunAdmissionBoundaryPostgresTest` | 1 / 0 | 1 | 夹具过期 | 隔离实际调度队列和 SQL 统计，避免历史 READY 任务先占用槽位。 | 2 / 0 |
| `RunAdmissionHttpTest` | 0 / 5 | 5 | 夹具过期 | 专用数据库和 test profile。保留容量、重放、会话恢复与 drain 的真实 HTTP 判据。 | 5 / 0 |
| `RunEventReaderPostgresTest` | 1 / 1 | 2 | 夹具时序及断言过期 | 并发使用最小合法 FLOW，保留大样本预算，允许仅用于预算的长度投影。 | 21 / 0 |
| `RunEventReaderTimeoutBudgetPostgresTest` | 0 / 1 | 0 | 缺证据目录 | 每轮指定证据目录，执行实际连接及超时预算用例。 | 3 / 0 |
| `RunInputValidationPostgresTest` | 0 / 7 | 7 | 夹具过期 | 按本类已登记会话回收子行，清理触发器在事务内恢复。 | 37 / 0 |
| `RunObservationMetadataHttpPostgresTest` | 6 / 1 | 7 | 实现缺陷及夹具过期 | 元数据查询限制 authority 列，不读取原始评测候选；负向后按原 DDL 恢复约束。 | 16 / 0 |
| `RunObservationStatusHttpPostgresTest` | 1 / 0 | 1 | 实现缺陷及断言过期 | 复用收窄的投影，继续断言当前观察流程不续开执行委托。 | 19 / 0 |
| `RunQuotaAdmissionPostgresTest` | 2 / 1 | 3 | 缺环境输入 | 启用真实 PG 和专用 JWT 测试配置后，配额边界三例通过。 | 3 / 0 |
| `RunSnapshotAssemblyTest` | 0 / 1 | 1 | 夹具过期 | 装配 ModelInputMaterials 与 ChunkBus，保留 Spring 注入验证。 | 1 / 0 |
| `RunStatisticsPostgresTest` | 0 / 0 | 0 | 缺库 | 建专用统计库后通过，已从历史失败集恢复。 | 5 / 0 |
| `RuntimeClientsPublishedHttpTest` | 0 / 1 | 1 | 夹具过期 | 移除个人盘符，测试子 JVM 统一 UTF-8，保留发布读取和历史路径断言。 | 20 / 0 |
| `SessionEventsEndpointTest` | 1 / 0 | 1 | 夹具过期 | 日志捕获明确开启 WARN 并恢复原级别。 | 11 / 0 |
| `SessionSseBackpressureTest` | 3 / 0 | 3 | 夹具过期 | 可关闭的审计捕获与线程安全日志列表，保留连接恢复、限额拒绝及审计断言。 | 4 / 0 |
| `SubagentPlanningTest` | 1 / 0 | 1 | 夹具过期 | 明确捕获 WARN 审计并在测试后恢复。 | 9 / 0 |
| `TestInputHttpTest` | 0 / 9 | 9 | 夹具过期 | 启用 test profile 和专用 PG，保留真实 HTTP 及元数据最小投影断言。 | 9 / 0 |
