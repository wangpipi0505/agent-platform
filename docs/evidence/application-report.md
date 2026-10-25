# 应用与评测服务交付报告

## 范围与设计依据

仅操作当前独立新工程app-management、monitor及本application-*证据；未读写、引用或复用相邻旧工程。已读取AGENTS、implementation-brief、contracts/README及冻结snapshot第二部分模板、第七部分、发布原文§3~6与第九部分。使用Java21、Spring Boot3.5.0、JdbcTemplate和真实PostgreSQL，未引入H2或生产内存存储。未阅读开源源码，不声称完成源码机制对照。

## 实际实现

- 应用CRUD、元数据、领域/业务分类/标签字典，CHAT/FLOW类型固定，草稿修订乐观锁，业务修改生成新修订，纯布局不变修订与摘要。
- 模板严格插值、类型/必填校验、新草稿版本与不可变发布版本；token数量明确标记估计而非伪造精确计数。内部精确模板版本接口供引擎冻结上下文。
- 图回读，结构、参数、类型/无效引用、外部引用、版本守卫、评测六组预检；结构进一步HTTP调用引擎同源图验证器。技能参数基于精确已发布版本inputSchema检查必填/字面量类型，表达式运行时仍由引擎验证。
- 预检不写版本。commit锁草稿，复核rev/sha/通过报告/15分钟有效期，原子写不可变app_version、current指针、skill_reference、publish_commit及生命周期日志。相同请求、不同请求同候选均复用版本；改稿412、幂等号换候选409；事务内无HTTP。
- 生命周期停用、重启、归档、回滚历史指针，历史版本DB触发器禁止改删；从未发布DRAFT才可删除。
- 集成凭据ADMIN签发/策略修改/撤销、精确域白名单和声明上限。随机32字节secret仅签发响应一次显示，PG只存SecretVault引用及SHA256；网关通过内部接口读当前撤销状态和策略。密钥库为公共SecretVault的独立本地AES-GCM开发实现，不把生产KMS验收计入此报告。
- 评测先冻结候选与数据集，再逐用例HTTP执行引擎，只有SUCCEEDED且expected与output精确JSON相等才通过。安全100%、可配置通过率阈值、最近同数据集通过报告无新增回归才ACCEPTED。数据集同版本修改409，报告绑定candidateSha与datasetVersion。
- 评测RUNNING/ERROR可手动resume，使用原evalReportId/caseId/frozenCandidate调用引擎持久幂等接口复用原run；等待确认等未终态保持RUNNING，绝不常量PASS。没有后台自动恢复调度。
- 确认中心、统计与审计真实HTTP投影引擎，审批写经引擎API，不写引擎表。依赖不可用返回502，不以空数组伪造。

## 验证与证据

命令均JDK `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，独立Maven缓存 `.tools/m2`，数据库 `127.0.0.1:25432/agent_platform_v12`，仅从新工程.env设置进程环境，不输出秘密。

```powershell
mvn -B -ntp '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-pl' 'app-management,monitor' -am test '-Dtest=AppRulesTest,IntegrationRulesTest,EvaluationRulesTest,PublishTransactionTest,EvaluationPersistenceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 初始reactor/common尚未存在，构建真实失败，未计作测试通过。
- ERROR_EDGE缺失边测试先失败，再实现校验通过：application-rules-red.log/green.log。
- Schema必填与数组到标量测试先编译失败（方法未存在），实现后通过：application-schema-red.log/green.log。
- 模板变量值包含花括号应按字面量输出，测试先失败，修复避免对插入值二次解析：application-template-red.log。
- 真实PG测试捕获Windows JDK HttpServer NIO UnixDomainSocket故障；改用测试目录的阻塞ServerSocket HTTP端点，不更换数据库或跳过断言。生产HTTP客户端显式SimpleClientHttpRequestFactory并设置连接3秒/读取60秒超时。
- 最终application-postgres.log：应用9项事务集成+7项规则+2项集成策略；monitor4项PG+HTTP集成+6项判分规则，共28项，无失败/错误/跳过。
- 事务覆盖：改稿拒绝发布、相同/不同请求复用、租户拒绝、布局摘要不变、引用写失败回滚、并发单版本、密钥仅一次与撤销。
- 评测集成覆盖：所有case确实HTTP运行/绑定数据集摘要、安全失败拒绝、数据集不可重写、等待中的评测恢复且无新业务run。

## 自审与限制

详细API见application-api.md。事务和查询均限定tenant；principal来自已验JWT；内部HTTP新签SERVICE票据，不透传用户token；凭据只存secret_ref。修正过图边from/to及节点枚举与冻结Schema对齐、模型ENABLED状态、技能守卫按精确版本读取、DATASET基线不可由调用方任意挑选弱报告、评测等待状态不可误判失败等问题。

未完成：LLM-as-judge/人工标注/复杂rubric、完整JSON Schema组合关键字标准一致性、自动恢复调度、生产KMS及密钥孤儿清理、集成密钥轮换专门接口、归档恢复与应用复制便捷接口。真实模型/本体/IAM/对象存储与容量需外部环境验收。四件套路径已返回，WebApp/assistant和网关出票的端到端可用性由总集成验证，不能只因后端返回路径就声称外部系统已接通。

补充：通过率阈值仅校验(0,1]，0.85是开发默认，正式业务阈值D7尚待定标。V102已将主实体application与真实app_draft分表，保留既有数据，主实体/草稿均有bigint id；报告与版本通过真实数字FK关联，commit锁FOR UPDATE OF d；草稿业务字段不再留在主表。


认证专用例外：GET /internal/integration-credentials/{id}要求principalId=_gateway且显式SERVICE/GATEWAY_AUTH，以全局唯一凭据id解析tenantId；不采信body租户。签发返回apiKeyOnce=id:secret，明文仍只在签发响应显示。常规服务读取继续要求已验tenant。


## 最终安全修订（2026-10-20）
- V102 实际拆分 application 主实体与 app_draft 草稿，主实体及草稿均有 BIGINT id；发布锁真实草稿行，版本及提交记录关联真实主实体。
- 内部指定历史版本的新建运行入口检查应用未停用/归档；管理历史读取继续可读。
- 评测报告保存 createdBy，resume 仅允许同租户原 principal，避免引擎 principal 维度幂等键变化导致安全用例重复执行。
- 集成来源认证查询只接受 _gateway + SERVICE + GATEWAY_AUTH，按 ACTIVE 凭据 exact origin 返回 allowed，不返回跨租户数据。
- 发布产物 webAppUrl 改为独立 WebApp 的 /w/{code}；assistant.js 全局 AIAssistant.init 使用宿主后端 ticket 回调，ticketUrl 为必要宿主配置。长期 ApiKey 只保留宿主后端。
最终 2026-10-20 03:03 回归：app-management 19、monitor 11全部通过；技能27亦通过，共57。零失败/错误/跳过。证据 docs/evidence/skill-application-combined.log。
