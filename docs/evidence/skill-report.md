# 技能服务实施报告

> 本文为早期27项测试阶段的历史记录。其后MinIO、LAST_REAL当前scope、只读/写受控连接、OpenAPI导入和HTTP wire已继续实现；最新阶段以[progress.md](progress.md)、[skill-api.md](skill-api.md)、[skill-openapi-report.md](skill-openapi-report.md)、[artifact-report.md](artifact-report.md)及独立复核为准。下文“未实现/未部署”按记录时点理解，不覆盖后续追加。

## 实际范围

独立工程skill-registry工作包，未读取或关联旧工程。已读取AGENTS、实施brief、冻结详设第三部分与原20全文关键管道/DDL/API/状态机，以及engine-tool-calls-api。使用固定Java21/Boot3.5.0、networknt1.5.6、MCP SDK0.10.0、K8s Client23、Minio8.5.17。未阅读相关开源源码，不宣称完成源码机制对照。

源数据模型是skill主表+skill_version（DRAFT/PUBLISHED/ARCHIVED），未发现源要求另建skill_draft，故按源两表实现。V300核心业务；V301对账审计；V302修复草稿DELETE触发器并增加同tenant外键；V303按call/attempt保留执行事实代，当前统计行仍callId唯一。

## 已实现且可检查

- 技能CRUD、六维契约版本、真实bigint精确版本、不可变发布、旧版本归档可执行、被引用阻止停用。所有业务查询tenant，执行事实另限定principal。
- networknt Draft2020-12参数/输出Schema，禁止远程$ref；角色/属性默认拒绝，已验Actor交集缩小参数范围；query守卫声明和写确认角色发布门禁。
- 连接测试真实外呼仅readonly无副作用，写操作必须已有真实非Mock受控DEBUG调用；模型选型逐题真实请求model/chat，80%门禁，报告绑定版本id+sha。模型不可用不能伪造通过。
- 执行在claim前独立查询engine approval，必须精确技能版本和参数摘要批准；body.confirmed无效。未批准WAITING_APPROVAL且零claim/外呼。
- 执行核对engine登记code/version/argsDigest/node/run/idempotencyKey，只有claim成功才可submitted，submitted成功才可外部执行。Mock仅engine权威DEBUG。写超时UNKNOWN不重发。
- PG事实/outbox先于引擎事实回调；失败只投旧事实，旧lease迟到保留事实而不伪造逻辑成功。只读重试只能由引擎显式REGISTERED+新attempt授权；旧代归档与新代替换同事务，实际业务段时长累加，回调仅报当前attempt，重试层未复制到技能内部。
- 正常响应returnSpec分离摘要/structured/文件引用/安全JSON卡片。实际ontologyMeta完整时采集callId归属，绝不把预期版本填成实际观测。
- HTTP实际RestClient；MCP实际SDK初始化与工具调用；K8s实际SDK确定性Job、PG先绑、专用显式配置、允许镜像/非root/不挂载token/限资源、查询原Job和真实stdout。无外部环境时真实失败，未写适配成功桩。
- 大响应max+1字节后流MinIO multipart，回读原始对象验Schema；绝对64MiB、250000 token、深度100/字符串8MiB等原生解析约束。未配置/不可用不能返回假artifact。
- UNKNOWN对账仅查询契约端点或原Job，核对原幂等键、完成标识、输出Schema；同callId更新统计终态，对账耗时独立审计，不自动重发。过期engine lease仍需受控人工采纳。

## 验证

JDK `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`；仅新工程.env进程变量与`.tools/m2`独立缓存；真实PG `127.0.0.1:25432/agent_platform_v12`。没有H2/生产内存Map。HTTP外部测试端点仅test目录ServerSocket，engine权威客户端在skill测试中用Mockito；真实引擎权威安全由engine独立PG测试和总集成覆盖，不能把这批skill测试说成真实引擎端到端。

```powershell
mvn -B -ntp '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-pl' 'app-management,monitor,skill-registry' -am test '-Dtest=AppRulesTest,IntegrationRulesTest,EvaluationRulesTest,PublishTransactionTest,EvaluationPersistenceTest,SkillRulesTest,SkillExecutionTest,ArtifactBoundTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

技能关键失败测试先红：skill-red、skill-reconcile-red、skill-confirmation-red、skill-guard-red、skill-draft-delete-red、skill-return-spec-red、skill-readonly-retry-red日志。对应最终实现再回归。最终统计以skill-application-combined.log为准，不把开发中的失败/跳过当通过。

已覆盖真实PG+外部HTTP：只执行一次、事实先提交再回调、回调故障只重投、参数/守卫拒绝零外呼、无claim零外呼、相同call换参数拒绝、写超时UNKNOWN不重发、只读新代显式重试、旧验收不能批准改稿、UNKNOWN对账不增调用数、未批准写请求零claim/外呼、未发布删除/历史保护、returnSpec四份内容；另有规则与字节/token硬闸/对象存储未配置拒绝测试。

## 精确缺口与未验收条件

1. 未实现OpenAPI导入、签名异步CALLBACK与通用HTTP SUBMIT_QUERY。HTTP/MCP的未实现kind明确拒绝，不能按同步成功冒充异步完成。
2. 未实现Redis分布式技能并发配额/熔断、取消令牌端到端、Mock LAST_REAL来源授权；不得因保存了对应契约字段就标完成。
3. MCP SDK SSE当前先由SDK构造工具结果，再进入有界解析；传输层预缓冲的逐字节硬上限未完成。没有真实MCP服务器验收，不能据此认为MCP生产容量达标。
4. 无真实K8s专用集群、算法允许镜像/服务账户/结果协议验收。Job资源/权限设置仅代码实现，不替代集群测试。
5. 本机MinIO未部署（官方Windows下载不可用且容器未启），只验证未配置/不可用不能伪造成功；成功multipart上传/大输出Schema/故障清理待真实MinIO环境验证。结构树回读有明确byte/token上限，但并发堆容量仍待实测。
6. errorSemantics自定义匹配规则未完整解释，目前HTTP默认类别和结构错误语义已处理；静态配置字段不算完成所有错误规则验收。
7. 网关、模型真实提供方、本体/IAM/容量/外部部署由对应域和总集成验收；开发fixture不能算真实业务/模型验收。

因此本域可交接真实HTTP主链、版本治理和持久执行事实实现，不能据此宣告整体34任务或所有适配器已验收。

## 追加回归与交付边界
V304 自动 outbox 恢复测试模拟第一次回调失败、随后自动投递成功，确认外部HTTP仅执行一次。V305事实摘要绑定以及受控草稿正常执行/变更拒绝/普通DEBUG伪造拒绝已覆盖。CHAT/TASK真实权威值均归入FORMAL统计并保留原值。持久proof接口摘要测试先编译失败后实现，证据 skill-proof-red.log；最终结果参见 skill-application-combined.log。
引擎受控草稿流程已由engine独立真实PG fixture测试；本域engine边界使用mock，不能宣称全服务写草稿审批端到端已经验收。MinIO/MCP/K8s缺少真实正向服务验证，既有缺口仍有效。
最终 2026-10-20 03:03 回归：app-management 19、monitor 11、skill-registry 27，共57测试通过，零失败/错误/跳过。使用独立真实PG与本工程隔离Maven缓存。日志 skill-application-combined.log。
