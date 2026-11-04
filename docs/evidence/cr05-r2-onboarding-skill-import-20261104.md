# CR05 R2：技能侧受控幂等 OpenAPI 导入

状态：代码已实现、仅编译通过；新增行为未复核。CR05 既有两轮功能预算已用满，本次没有启动第三轮功能测试，没有执行数据库迁移，没有修改原断言。本证据不表示整组 CR05 已关闭。

## 本席准确文件白名单

生产文件均为新增：

- `skill-registry/src/main/java/cn/agentplatform/skill/api/OnboardingSkillImportsController.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/application/OnboardingSkillImports.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/infra/OnboardingImportReceipts.java`
- `skill-registry/src/main/resources/db/migration/V320__onboarding_skill_import_receipt.sql`

本项证据：本文件及 `docs/evidence/cr05-r2-onboarding-skill-import-20261104-compile.log`。

未修改 `OpenApiImports`、`SkillCatalog`、`SkillGovernance`、`SkillController`、`RunService`、任何 Onboarding session/前端或公共 authority 文件；共享树内这些文件的其他变更由各自席负责。没有 stage/commit。

## 接口与信任边界

- `OnboardingSkillImportsController.create(HttpServletRequest)`：`POST /internal/onboarding-skill-imports`。
- `OnboardingSkillImportsController.get(String,String)`：`GET /internal/onboarding-skill-imports/{sessionId}?planSha256=...`。
- 只接受已验当前授权 `caller=app`、`target=skill`，action 分别为 `skill.onboarding-import.create/read`；显式要求 SERVICE，并沿用 `Actor.requireRole("EDITOR")` 的 ADMIN 上位语义。
- 当前授权在事务前通过 `AuthorityContext.requireFor` 刷新，沿原 root resource；不从 body 创造授权资源，不接受 gateway/普通用户直接调用，没有 legacy 无 caller 票据回退。
- app 负责从自身 PG 权威 freeze 生成 source、文档、人工合同字段和批准连接引用。技能服务复算文档、完整请求及 source 摘要，并把实际已验 resource/operation/actor 存入不可变 receipt 审计。body 不含、也不接受 tenant/principal/root 自报字段。

POST 的精确顶层字段为 `source/document/includeOps/defaults/overrides/bindingRefs`。

`source` 精确七字段：`sessionId/planSha256/templateCode/templateRev/packageVersionId/packageSha256/documentSha256`。sessionId 使用当前会话 `os_` 加32位小写十六进制格式。

`defaults` 只接受 `baseUrl`。`overrides[operationId]` 必须显式含 `name/description/execution/guard/returnSpec/errorSemantics`；可选原导入器支持的 `authRef/timeoutMs/constraints`。不能提供 code、Schema、httpMapping 或 importSource；这些仍由原 OpenAPI parser/导入器确定。非空 authRef 的 ADMIN 与真实 secret_ref 校验保持原样，不能以空凭据降级受保护 operation。

每个操作必须有唯一精确 bindingRef：`profileId/profileRev/bindingId/bindingRev/operation/environment`，仅允许额外可选 `resolutionDigest`。它们仅旁存 receipt，不进入原技能合同归一化。当前 grant 复核与受控测试消费由 R3 接线承担。

## 数据与恢复不变量

- 复用 `OpenApiImports.create` 和 `SkillCatalog`，保持 OPENAPI 来源原五字段不变。
- 编码固定为 `onb_ + sha256(canonical([sessionId,planSha256,operationId])).substring(0,48)`；客户端不可覆盖同码技能。
- 同一 skill 本地事务覆盖整个批次导入及 receipt 写入；PG 事务 advisory lock 串行同 tenant/session/plan 的首次并发创建。
- 数据库自然唯一键为 `tenant/sessionId/planSha256/operationId`，另以 tenant/skillVersionId 唯一及同租户外键锁定真实技能。receipt 触发器禁止 UPDATE/DELETE。
- 同键重放核对完整请求摘要和来源；异摘要冲突，不另造草稿。返回前锁定真实 skill/version，核对原 ID、版本、合同 sha 及实际 importSource；人工改字节、停用或归档时报冲突。
- 允许同一字节的原版从 DRAFT 正常成为 PUBLISHED；状态取实时数据库事实，回执不冒充发布许可。
- 响应 `drafts` 含真实 `operationId/code/version/skillVersionId/sha256/status/definition`；`sourceReceipt` 含 `source/sourceSha256/requestSha256/bindingRefs/authority/imports`，imports 含每操作的 receiptId 及精确版本元组。每条数据库 receipt 仅存对应操作的单一 bindingRef。
- R3 可调用 `findBySkillVersion(Actor,long)`，以 tenant+principal+精确版本读取；`hasSkillVersion(Actor,long)` 只返回同租户存在性，避免他人导入技能因看不到其回执而落回无 refs 的 legacy 路径。

## 实际编译记录

使用 `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`，工作树根目录执行：

```powershell
mvn -pl skill-registry -am '-Dmaven.test.skip=true' compile
```

最终结果：2026-11-04 18:35:22 +08:00，exit 0，BUILD SUCCESS；admin-common 成功、skill-registry 以 release 21 编译81个生产源文件。完整最终输出见同目录 compile.log。

首次未加引号的 `-Dmaven.test.skip=true` 被 PowerShell 拆参，Maven 在编译前因未知 lifecycle phase `.test.skip=true` 退出1；加引号后18:34:18编译成功。随后仅修正 ADMIN 上位角色语义，最终18:35:22重新编译成功。这些是编译命令，没有运行功能测试。

编译警告来自原 `SkillAdapters` 的 ExtendedOpenOption 与过时 API、原 `ApprovedConnectionResolver` 的 unchecked 使用。本次没有新增依赖，没有 clean，没有运行测试、服务、数据库或迁移。

未覆盖：真实 PG migration/事务回滚/并发与丢响应恢复、正式 IAM forward 与当前授权撤销、Bearer 实际导入、R1冻结输入→R2→R3受控测试→R4来源映射→应用发布完整链。以上均保留“未复核”，不能标 PASS。

## 最终生产文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| OnboardingSkillImportsController.java | e7f13ed6b584896842a607bea1275d9f7ae32de2a504214c4068fc29c97eb798 |
| OnboardingSkillImports.java | 6a04454ca833c22ccafa5a1167b39cd6673b7c26670281a9699c1e5ddd1cf5e6 |
| OnboardingImportReceipts.java | 938b844a56aaf6492314a30904aceb93b31ffe90cf202e5dd2251038d6a7f95d |
| V320__onboarding_skill_import_receipt.sql | ba454fb74767ffd96c07d1b8a5e6f51d81b6324ed5be52a0c0ad88254b48614a |
