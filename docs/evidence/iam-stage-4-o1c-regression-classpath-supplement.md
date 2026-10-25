# O1c 原 POST 回归 classpath 复核补充

本补充记录 root 对已批准具名 POST 回归的常规执行裁定，不改变 8 个生产文件、4 个新测试文件的范围，不修改已封存 O1c 正式 brief 或此前证据。只有本次实际验证成立，才可运行原测试；它不是已执行或已通过报告。

原 `GatewayPublishedChainPostgresTest.Child` 精确读取 `.local/iam-n4b2-gateway-published-chain/app-classpath.txt` 与 `engine-classpath.txt`。允许只读这两个原 locator：先在 O1c 自有目录独立生成本次 app/engine dependency classpath，加上当前各自 module 与 admin-common 的 classes/test-classes，保留顺序规范化核对，并验证实际第一解析路径及对应当前 class 字节。不能仅比较集合，不能让旧 common/JAR 抢先解析。原 locator 只有在与 fresh 本次产物等价时才能消费；报告应明确“消费原 locator，已以本次 fresh CP 独立核实”，不得覆盖、重标旧文件或将旧清单说成本次生成。

不采用 bootstrap、字节码拦截或文件系统重定向。若规范 CP 或实际 class 首次解析无法证明等价，保留具体差异，向 root 提出最小现有测试系统属性路径接缝；未获该具体 scope 裁定前不编辑原测试，也不强行使用旧清单。

Root 对此具名风险窄读的是已 Accepted 冻结原件：原 POST 测试 start/seed/stop、Child 的 CP/工作目录/生命周期，以及已批准 selected positive；两 receiver fixture 的 DB、迁移与 seed/stats 位置。原测试仅从上述 locator 读取；运行使用本工程 ROOT，receiver 使用 app/engine 独立 module，无跨服务业务 JAR。旧 dedicated admission app/engine 数据库按本次新 UUID tenant 追加该回归事实，允许本轮复用；旧 tenant/行不得更新、清空或重建，迁移版本和本轮 tenant 隔离应记录。保持主 PG 服务器及原服务进程。这里的“原”均指本独立 agent-platform 工程之前的已验测试，不涉及相邻既有实现。

该裁定不替代实际命令前 source 清单、当前编译归因、即时 Running-class XML、自有资源关闭与最后独立审计。SESSION 回归按原具名方法执行；POST 回归通过与否以本次实际日志为准，不拿历史通过数填充。
