# G6 实施计划只读独立复核

仅新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 内遵守AGENTS，不访问相邻旧工程/配置/进程。当前唯一实现者正在skill算法协议，不与其争用源/target。本任务只读方案、指定实际安全代码和必要primary来源，输出一份审查报告；不实现业务/测试、不跑Maven、不启停服务、不派子agent。

先读规划需求 `iam-service-authority-planning-brief.md`、方案 `iam-service-authority-implementation-plan.md` 和 root裁决 `iam-service-authority-root-decisions.md`。原四契约与v1.2不改；用户已授权没有真实环境时继续隔离端点，不能要求先提供IAM才能规划/实施。root接受首版有限客户端profile，未授权自建生产IAM；profile不是RFC8693天然提供的功能，不以SERVICE/共享HS256/旧Actor冒充生产当前权限，生产门禁保持blocked。

独立判断方案是否具体、可安全分期落地且符合原设计；核对真实代码缺口和建议语义，不能因root裁决而忽略技术矛盾。重点：caller/target/action/resource/subject绑定；secret_ref机器启动不伪装用户；token exchange与持久委托的信任循环；ApiKey/SDK上限与撤销；current scope/历史required scope和原批准者confirmRoles；long SSE/模型stream的授权到期/撤销及恢复；事实outbox/恢复的委托缺失/权限收窄；未知handler/defaulttarget/跨主体；每个阶段实际测试条件与生产阻断；候选迁移顺序避免高号先用后补低号。合法声明EDITOR的确认正例必须保留，不可全局改APPROVER。

请找真正影响实现或验收的缺口，不堆无关假设。所有事实区分当前代码、方案建议和未验证外部条件。可只读核固定源码/JAR/API或官方文档，不读.env/vault/masterkey或真实凭据，不执行资源耗尽/慢模式等探针。已有算法/Schema实现不在审查范围，不重放被中断的资源审查。

输出 `docs/evidence/review-iam-service-authority-plan.md`，分别给需求符合性和计划可实施性/质量结论；问题给方案具体行/段、触发条件、实现影响和所需补充。只写自己的报告，不改方案/源码/总进度/原契约/三份外部执行文档。返回简短结论；无发现也如实记录读了什么、没有验证什么，不当作功能已实现或真实IAM签收。
