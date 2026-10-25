# 运行时安全修复复核

日期：2026-10-20。本轮结论：**R1、R2、R4 可关闭；R3 的成功事实恢复已补齐，但失败事实恢复仍有 P1，暂不能关闭。** 新增工件限定审查未发现新的 P1 越权或物理位置泄露路径。

## 范围与证据

沿用上一轮 code-review 方法，仅查看本新工程业务源码、迁移、定向测试和证据；仅写本报告。没有修改业务代码，没有重跑完整测试套件，没有读取相邻旧工程。

复核对象为原报告 R1—R4 及新增技能 ArtifactStore/工件登记表/下载 Controller 与必要的网关路由授权。仓库当前把工件登记逻辑包含于 `ArtifactStore`，自动事实投递类实际名为 `SkillOutbox`。模型/引擎仍在施工的上下文、配额、模型新配置等不属本轮审查对象；旧报告的其他功能缺口也不因本次复核自动关闭。

已阅读 `docs/evidence/integration-report.md`、engine/skill 报告的对应修复记录，以及现有 Surefire XML 的结果摘要。技能当前 XML 为42项，0失败、0错误、0跳过；引擎报告的45项结果及对应两条取消闩锁、两条受控草稿、两条持久恢复测试均已有绿色记录。target 还包含后续施工模块的测试结果，不能将目录里混合的结果累计认定为本轮全部新源码的全量验证。本轮结论依据实际代码和定向测试内容，不仅依据测试计数。

集成报告和脚本说明确实覆盖：无SQL预改状态的DRAFT写技能→确认前零外调→确认后一次→connection/选型管道→上架→候选评测确认后同report/case恢复→发布→正式TASK执行且伪Mock不生效。外部模型和写服务为明确标记fixture，证据证明协议/控制流程，不代表真实业务模型、IAM、MCP/K8s或容量验收。

## 原发现关闭状态

| 发现 | 状态 | 实际代码核对 |
|---|---|---|
| R1：正式trigger类型不兼容 | **CLOSE** | `SkillExecution.java:78`—`:79`从引擎权威用途把CHAT/TASK映射为FORMAL，并于`:100`保留authorityTriggerType；DEBUG独立分支才允许Mock，body.triggerType不决定用途。对应正式管道、FORMAL统计和伪Mock反例已有定向证据。 |
| R2：写技能首次发布前置条件环 | **CLOSE** | `RunService.createDraftTest():275`先要求EDITOR/SERVICE，按版本读取并校验完整合同摘要，服务端构造单技能DEBUG图，冻结合同和版本；`:468`—`:482`只对该受控绑定允许DRAFT。`SkillExecution.java:34`—`:41`再次核对引擎权威DEBUG/版本/当前合同摘要，草稿修改会拒绝旧测试。`SkillGovernance.java:29`—`:35`发起受控测试并按version+合同摘要+非Mock成功DEBUG事实验收。没有把一般execute入口放开成任意草稿执行。 |
| R3：回调失败无自动恢复 | **REMAINING / P1** | `SkillOutbox.java:16`—`:35`已实现PG租约、短期重投与原tenant/principal的SERVICE-only身份；`SkillExecution.java:210`、`:213`给状态写入增加attempt限制。`ExecutionRecovery.java:21`—`:54`可主动取持久proof并持久化失败探测计划，`:88`—`:114`校验run/node/call/版本/参数/合同/原attempt/用途/Mock及终态，`:118`以后只重排节点，不直接提交逻辑成功。成功分支可关闭原“永久无消费者”问题；下述FAILED分支仍不闭合。 |
| R4：取消与提交权竞态 | **CLOSE** | `ToolCalls.locked():121`—`:151`在validateLease前按run→node→call锁定；register同样先锁run和node，submitted复用该锁路径。`RunService.cancel():1056`先更新/锁run，再改node、call，未提交外部者ABANDONED，已SUBMITTED写CANCEL_IN_FLIGHT且externalEffectReverted=false。两个方向的PG闩锁测试覆盖取消先赢和提交先赢。取消在已取得提交权之后不承诺撤回外部效果，符合这一互斥边界。 |

附带关闭旧报告的 ADMIN 通配审批问题：`ApprovalService.java:54`—`:56`现在只匹配声明角色；已有ADMIN-only主体被拒绝的定向测试。内部服务caller/target受众隔离等其他旧边界不在本轮关闭清单中。

## F1 / P1：采纳 FAILED 持久事实后再次交付旧代，节点重新进入 UNKNOWN

位置：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/ExecutionRecovery.java:134`—`:145`；`engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java:195`—`:202`；`skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:42`—`:54`、`:205`—`:211`。

触发场景：技能实际执行明确FAILED，事实已提交PG，但首次引擎回调未被接受；原租约随后过期。恢复器读到FAILED proof并通过校验，把引擎tool_call更新为FAILED，attempt_id改成新恢复attempt，再把节点置READY。

后续普通驱动尚未闭合：

1. AgentDriver.skill只对登记返回SUCCEEDED直接消费；登记为FAILED仍会POST技能execute。
2. 技能发现旧FAILED execution_fact，不满足引擎显式REGISTERED的只读新代重试条件，进入deliver(existing)。旧fact保留原attempt和execution_owner。
3. deliver用旧attempt回调。原回调未被接受，故不存在accepted=true的同摘要去重证据；引擎call已换代、旧租约也已过期，回调返回late。
4. 技能将结果包装为UNKNOWN，AgentDriver抛RESULT_UNKNOWN，节点再次WAITING_EXTERNAL。引擎现有FAILED调用又可被resumeExternalFacts排回READY，重复上述过程；原明确失败无法按错误策略收口，也不能按原错误语义重试。

另外，ExecutionRecovery的采纳UPDATE未写error_semantic：即使后续直接消费FAILED，仍可能拿到之前的RESULT_UNKNOWN/null，而不是proof.result.error.semantic。现有 `ExecutionRecoveryTest` 两项仅覆盖成功proof到READY及proof不一致；没有把FAILED proof采纳后继续推进驱动至终态。

修复建议：让普通驱动统一从引擎权威终态消费SUCCEEDED/FAILED；FAILED应保留并传播proof中的明确错误语义，交给现有参数修正/失败策略/只读重试层，禁止为消费终态再外调技能execute。恢复采纳时同步冻结/校验并写入error_semantic；不要把原事实的旧执行身份当作新代交付许可。需要重试时仍由引擎显式将原逻辑调用置REGISTERED并授权新attempt。

建议定向回归：

- 明确FAILED（NO_PERMISSION或PARAM_ERROR）+首次fact回调失败+原租约过期→自动proof采纳→继续真实RunService.drive，要求最终按原错误策略失败/处理，无第二次业务execute、无UNKNOWN循环。
- readonly RETRYABLE_FAILURE同故障窗口→按配置恰好一次新代重试，callId保持稳定；新代执行时长和旧代错误证据均保留。
- 取消先于proof采纳时不重开run、不写逻辑成功事件；现有成功proof恢复保持一次提交。

这项关键发现已实时通知root。本轮只读审查不修改实现；修复后须针对上述后续驱动路径复核，不能仅以tool_call变FAILED或节点变READY计作恢复完成。

## 新增工件限定审查

本轮未发现新的P1工件越权或bucket/object key公开路径，依据如下：

- 上传位置由服务配置bucket、tenant摘要和随机artifactId构造，用户/外部响应不能提供物理对象位置。上传前先写PG的tenant/principal/run/call固定归属；V306触发器禁止改写归属和bucket/object_key。
- 生产SkillExecution只在引擎claim归属、参数摘要、幂等键核对后把权威runId传入适配器；无run归属的旧offload方法直接拒绝。不能把ArtifactStore测试允许构造的fixture runId误当作生产API的外部授权入口。
- `ArtifactStore.owned():86`—`:91`按已验Actor的tenant+principal限定READY且未过期对象；外部下载再核对路径runId。不存在ADMIN通配下载。引用只接受单字段artifactId，重新按本次run归属解析；URL、s3/pg/file协议、额外uri键不会成为受信任工件能力。
- `reference():94`—`:96`只返回opaque ID、受控路径、摘要、字节数、类型；公开信封不带bucket/key或预签名URL。下载为后端流代理，异常消息也不包含物理定位。`ArtifactController`设置attachment、private/no-store与nosniff；网关已转发Content-Disposition，其公共安全过滤器承担默认安全头。
- 网关Routes把精确工件路径转技能服务；IntegrationPolicy允许受控run工件GET，IntegrationAccess仍先核对引擎run的tenant/principal所有权和票据绑定app，再调用技能存储的owner/run检查。
- 超限/Schema失败/连接中断不返回公开引用，按登记的固定键清理；持久清理任务有租约、批量上限、后退时间，精确查询并撤销该对象未完成的multipart上传，不扫描或删除任意其他前缀。64MiB绝对上限及JSON结构上限明确存在。

已有真实MinIO测试包含5MiB实际上传/下载、内容摘要、同租户不同主体、不同租户、错误run、伪造外部引用、Schema/超限/中断清理、multipart残留、清理失败重试与DB归属不可变；这些证据支持本限定判断。仍需真实六服务网关下载在吊销/策略变化/对象过期时的纵切验收，以及生产对象存储权限和保留策略验收。本轮不将这些未做的部署验收计为通过。

本报告不对仍在施工的上下文、模型配额及其新迁移作成功或失败判定。
