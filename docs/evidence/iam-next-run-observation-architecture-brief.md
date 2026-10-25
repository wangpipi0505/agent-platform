# D4c之后：独立运行查询与观察的只读架构准备

本任务只读准备下一实施切片，不编写产品源码、不运行测试或服务、不重新验证历史全量证据。唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，每次shell显式workdir；遵守AGENTS.md，不访问相邻旧代码、数据、配置、依赖和进程。后端D4a/I1已独立通过，source380/target1933冻结；前端D4b另有唯一作者，禁止读取它的半成品、改前端或占用其测试端口。

要求来源为既有v1.2、`docs/evidence/iam-service-authority-implementation-plan.md` §2.4/§2.4.1/§2.4.2及Task4–6，以及`docs/evidence/iam-next-published-chain-seam-proposal.md` §3.3/§6。原设计、四契约、该计划和裁决不修改；没有真实外部环境，后续继续使用隔离端点。目的不是重新设计全IAM，而是确定可审查的下一切片和真实接线点。

请在现有冻结后端中核清：

1. 独立GET运行status的gateway/engine真实路径、handler模板及现有purpose/action/catalog/metadata/owner/root解析和材料读取。已经授权创建POST内部status并不授权独立GET；不能用旧main/local proof、Actor或runId自报代替。给出最小首切片的精确文件/接口/资源摘要/两次query-main关系及legacy兼容方式；明确复用哪些已实现类型，哪里需新增私有不可伪造能力。保持固定跨服务HTTP和真实PG，防独立metadata先读业务payload。
2. 以真实RunController.events循环和gateway流代理为对象，列出随后PORTAL SSE所需open/check、当前材料校验、提交前后错误、授权reset及idle watchdog/阻塞读取消的缺口。查清现有common ResponseAuthority/RR1类型是否只是骨架或已具有实际接线。区分普通PORTAL硬停止、DELEGATED后台新动作及INTEGRATION原响应drain；不能只打开旧SSE路径或用已过期VerifiedAuthority代替current。
3. 提议依赖顺序：先有限独立status，后PORTAL SSE，再RR1/RR2/剩余内部消费者，或提出有源码依据的必要调整；每一切片的正负验收、实际counter与未覆盖边界明确。避免把有限FLOW成功外推所有CHAT/模型/技能/确认/附件/集成动作。

报告必须给出实际读取的路径/行号及SHA、准确行为和最小变更面；引用开源机制仅对实际已读固定版本窄范围负责，不需为规划新下载库。允许写你独占的 `docs/evidence/iam-next-run-observation-seam-proposal.md` 和 `.local/iam-next-run-observation-architecture/`；root负责正式brief与审批，不修改八份执行索引/旧证据/源码/target/dist。向root返回文件与SHA、关键待裁决点及精确建议，不粘贴整份报告。报告交还后冻结所有权。
