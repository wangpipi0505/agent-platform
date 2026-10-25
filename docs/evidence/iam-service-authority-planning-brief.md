# G6 可信IAM与服务权限：只读实施规划

仅独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform`；遵守AGENTS、docs/implementation-brief、contracts/README。不得读取相邻旧业务代码/配置/数据/缓存/进程。此任务**只读源码/设计并写一份计划**，不是同时实施第二套功能；不改任何业务/测试/POM/契约/环境/部署文件，不运行Maven、不启停服务、不派子agent。

用户已授权持续按v1.2实施，当前没有专用模型/本体/IAM/K8s环境，继续隔离端点验证。不要以此请求环境或停止内部实施。基线 `contracts/snapshot/detailed-design-v1.2.md` SHA3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f，原四契约不改。模块2本体平台范围外。当前后端完整846/独立开发制品；expectedAttempt后续skill297独立115批准但未部署。root下一实现为算法协议，你不改skill。

任务目的：根据真实当前安全代码和设计，细化G6剩余可实施增量，明确生产可信JWT/IAM声明上限、服务调用caller/target/action权限和当前主体scope来源；逐阶段可独立评审，最终本地隔离issuer/JWKS/token endpoint和真实跨服务HTTP失败路径可测。不要只写“配置IAM后验收”，也不将通用SERVICE角色或本地共享HS256密钥当作生产caller可信证明。生产门禁BLOCKED_IAM_SCOPE_ACCEPTANCE保持，有限夹具不替代真实IAM签收。

优先实际读admin-common SecurityConfiguration/Actor/ServiceTokens/ServiceClients及所有服务的internal/public校验、网关双认证/集成票据、恢复/background任务如何得到Actor、现有部署门禁及相关测试。搜索实际路径，不假定包位置；只读.env.example，不读或打印.env/vault/masterkey/真实凭据。测试库和工具仅新工程。记录当前具体缺口、路径/行号和调用图。

必须解决或明确分期的接口事实：设计要求用户令牌不透传下游，不能简单把原portal JWT当内部服务令牌；来源可信不等于caller可任意自报用户角色/属性。当前ServiceTokens.issue(actor)在后台恢复可能只拿到PG旧actor，无当前IAM证据。请核对后选择最小可成立的生产授权/委托方式（如可信token exchange/当前权限查询），说明配置和信任边界，不能凭旧scope重新签票假装当前身份。ApiKey出票/SDK原路径不可破坏；门户JWT、内部服务票据、外部集成短票据须明确不同受众和用途。生产HS256开发fallback/未知kid、算法混淆、错误issuer/aud/purpose、scope收窄/撤销/过期、服务target/caller不符、网络不可用/恢复等均默认拒绝，且测试有限。

不要求自建一个生产IAM平台，不增加与详设不符的完整认证产品。可将可信输入映射、JWT验证、内部委托调用、恢复授权分成能独立验证的软件阶段；若某阶段不能单独提供安全可用行为，必须指出依赖，不能先开放半套生产入口。新增内部配置不回写原四契约；对外接口改变需明确标为后续审查决策，不默默改变冻结签名。

使用Writing Plans，必要官方技术资料只读 primary 文档和固定依赖源码/API/许可证，并标明实际读取内容。避免堆耗尽、慢模式、攻击性探针、真实第三方凭据或外部端点操作；验证仅有限签名JWT、小JWKS、固定权限矩阵、短时失效/轮换和loopback服务。

输出唯一文件 `docs/evidence/iam-service-authority-implementation-plan.md`（用户/项目证据位置约定覆盖技能默认计划目录），包括：现状核对；阶段与依赖/顺序；每任务具体文件/接口/配置/失败红绿矩阵/命令/验收；实际开源读源；保持的门禁和未验边界。按Writing Plans标准头和checkbox任务，精确数值来源区分设计、现有实现、建议值；不写虚假已实现/已测结果。不改root总进度或外部三份执行文档，不提交发布。完成后简短返回计划路径和待root裁决的实质问题（能合理决定的常规细节自行决定，不追问用户）。
