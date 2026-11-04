# CR-05 新技能导入、固定测试绑定与会话来源接线

日期：2026-11-04。后续开发在阶段1两轮检查之后完成，**只编译，没有第三轮功能检查**。本记录不改变此前阶段1真实PG5/5及纯规则12/12的覆盖边界，也不把新子链写成实际场景已通过。

## 已补实现

- plan扩展sourceDocument、routePlan、importOverrides；V121追加IMPORT_SKILLS步骤，不回改已应用的V118/V119。
- 导入之前固定原规格、完整实际预检、包声明、显式人工合同与批准绑定；EDITOR只取模板真实发布快照和旧精确技能当前read授权的元数据提议，ADMIN可选择批准提议。新技能grant仍须显式授予。
- 原导入receipt GET恢复；只有确切NOT_FOUND才按原payload幂等create。真实草稿经原技能工作台的连接测试、选型验收、发布后，同步读取精确版本并交由不可变session_source固化；不改包全局mapping或旧来源断言。
- session_source装配复用原prepare/validate/save，服务器真实关系选择新来源分支，不能靠客户端字段自称来源。相同会话新计划可切到新不可变来源，人工修改仍由draftRev保护。
- SkillController现有internalVersion窄接点从同主体receipt核code/version/id/sha、来源摘要和refs，向engine提供executionBindingRefs/onboardingReceiptRef；Governance只传核对提示，engine自己读元数据冻结，不信body连接覆盖。真实connection evidence新增同一request_snapshot.bindingRefs匹配条件。
- ApprovedConnectionExecution根据不可变OPENAPI importSource.path推导原服务根，避免native baseUrl已有路径时重复附加；附属入口仍限原服务根，实际地址仍限批准根/策略。该CR02后续兼容hunk没有功能复核。
- 新会话路径metadata使用正式授权client；RuntimeClient仅IAM分支使用AppAuthorityTransport，缺装配拒绝，未创造Actor/caller或扩大原engine IAM受理域。

## 检查与边界

最终统一源码在JDK21执行`mvn -q -pl app-management,skill-registry,engine-runtime -am -DskipTests test-compile`，2026-11-04 19:07:48至19:07:56（+08:00），exit0。输入包含AppAuthorityTransport最终SHA256 `f6f913de11b3a6e9cd6f4077794cc3849b19072e39ffe42cd3d31f87e2b72dff`（含响应解析后的deadline复核）；日志本地ignored `cr05-r3-compile-final.log`。无新增功能轮次、无V120/V121/V320实际DB迁移执行、无业务资源调用。

仍需后续有真实输入的独立功能复核：显式DEV专用app证明配置、原规格/批准NONE或ADMIN凭据、当前新技能grant、真实模型选型用例和应用评测用例。IAM的DEBUG/controlledDraft仍按原有范围拒绝；不能将这种已知范围限制称为仅缺验收数据，也不能用DEV证明回退绕过IAM。

当前首版导入仍受OnboardingImportRules的明确子集约束（包括批准origin与冻结包环境声明一致等）；缺资料或不支持组合返回可恢复missing。实际技能/应用发布门没有自动PASS、豁免、假受控callId或代授grant。
