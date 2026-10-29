# EX-06 opCode命名与旧会话恢复独立复核

2026-10-29；复核者 `algorithm_interrupt_readonly`。只读两份候选、冻结测试、原件/安全journal、OR实际Controller/Service及已有运行回执，唯一新增为本报告。没有执行验收脚本、HTTP、PG、Java/Maven、Node或服务，没有读取凭据值，没有修改实现、测试、主入口、旧原件或index，未使用Superpower。PS只作AST/Parser静态读取。

**结论：未发现本次准确范围的静态阻断，接受EF5 Common/877 Prepare候选。** 准入root唯一实际窗口对已登记`joint-or-20261029-01`旧49动作会话执行一次Ontology Resume并留前后证据；不据40计划检查/63协议检查宣称内部在线分支已实跑，不自动扩为新session全套资源创建或All/J/S1完成。

## 冻结与已有实测

| 文件 | 原件SHA256 | 当前候选SHA256 |
| --- | --- | --- |
| `scripts/acceptance/Joint.Common.ps1` | `a008f692d89d18a416b657c9cb3b5f08f804b2bb44205e616253b90db48505cd` | `ef5cae1919812965592d4eb702b9b99ea9c7ca9a75191051c9ec2878febb84ce` |
| `scripts/acceptance/Prepare-Joint.ps1` | `fce9ce3a4e06d3b01ac9b34f80283de86740c0393a14788cbe8ef29d842fab98` | `87709f9fe5cc0c7bb387ed3a09433c713b7ce238f6b78e2bd64ae7c8b17c52ec` |
| `Test-JointPreparation.ps1` | `3eef58416df377e59776e993e4147038105e88647f0fb65f159dfb32824dd60d` | 同一字节，红绿均未改 |

旧Common仍在`protocol-ontology-20261029-1428/Joint.Common.final.ps1`，旧Prepare仍在`or-stage-source/Prepare-Joint.ps1`；本次核其SHA均精确等于上表，没有覆盖历史冻结。

独立重读root实际回执与每轮保存的三源字节：

- [operation-red-20261029-01](operation-red-20261029-01/summary.json)：native1，原32检查通过，新增`new session plan exposes bounded unique operation code`真实断言失败；HTTP0/业务资源0。它是既有GetPlan缺OpCode能力的断言失败，不是语法、缺函数或虚构业务红。
- [operation-green-20261029-01](operation-green-20261029-01/summary.json)：同3EEF测试40检查/native0，原32检查顺序及名称逐项原样保留，再有8项新增检查；EF5/877/3EEF before=after=current，HTTP0/业务资源0。固定向量、同session稳定、不同session区分、非法session拒绝及显式manifest沿用均已达。
- [model-protocol-20261029-03](model-protocol-20261029-03/summary.json)：Common变化后原63协议检查再次通过/native0，source/context稳定、两个owned child及端口退出，businessValidationPerformed=false。当前协议回执SHA `3d2c55a07ff35db373ccc4006e71c83e279561c9addb6843cd3bde846a79ab75`与summary相符，五份源before/after相同，其中Common/Prepare/TestPreparation匹配本候选。该轮不是旧A008绿的重复引用。

上述均为root执行，本审阅仅读原件，不声称自己运行或已创建/更新OR资源。

## 计划层及API语义

AST函数正文比对确认Common只改`Assert-JointManifestShape`和`Get-JointPreparationPlan`；HTTP、ACL、受限env、原子写及NullString修复函数保持原样。新计划在已有函数内用UTF8(SessionId)的SHA256前24小写hex形成30字符`joint_<24hex>`，不依赖临时nonce/磁盘结果；这是受控session命名后缀，不宣称数学上绝不碰撞。显式manifest复用直接保留manifest.opCode，主/隔离code增加≤64的ASCII字母开头形状门。原19字段、两个fixture组、context/URL/secret_ref门及不覆盖manifest的规则保留，计划仍无创建副作用。

OR实际`OperationController`为`/onto/operations`：GET按spaceId列出目录，POST/PUT返回Space；Service.list先查空间角色且SQL限定space_id，save校修订并返回spaces.write结果。生成code长度30和字符形状符合真实保存约束。本次因此没有把旧`A_operation.id`误认成operation ID：它原本就是保存操作返回的**space.id**。新的operationIds从真实GET目录获得，两个身份来源分开。

## Prepare恢复路径

Prepare新增`Get-JointOwnedSpace`（抽取原检查）、`Get-JointOperation`及`Initialize-JointOperationIdentity`，并让Ontology/endpoint/消费URL/resources使用同一plan.OpCode。原`Invoke-JointOrStep`、Publish-JointRelease、签发/追加binder角色、key读取与私密写函数正文未变；入口除初始安全字段和Initialize调用外没有加入资源重试或绕过原阶段规则。

1. **新session先固定。** 初始state就含plan.OpCode与空operationIds，第一次Save在任何OR空间/模型/grant业务写之前；随后code用于A/B操作创建、目录选择、端点选择、四次消费URL、主resources与isolationFixtures，不残留另一处固定joint_list。唯一字面joint_list仅在旧journal兼容候选分支。
2. **context/pending先拒绝。** Resume仍先核同session/tenant/environment/URL/模型上下文及nonce，pending为真即停止，早于登录/在线迁移；最终Reuse还要求原ONTOLOGY_READY/resources。新Initialize在真实OR owner已核之后、任何New-JointOntology业务写之前执行。
3. **code冲突即停。** 收集journal顶层、主/隔离resources和最终Reuse计划的code，逐个形状校验、大小写精确一致；未有确认operation时不得把其它code替换本session计划值。B确认而A未确认、未知label、无效/空UUID及异常ids容器均拒绝。既无code也无已确认旧operation的journal以UNCONFIRMED停止，没有猜一个新code继续创建。
4. **实际目录固定ID。** 对确认A/B逐一核其operation动作的space.id关系，再真实读取nonce/code/namespace匹配且仍OWNER/ACTIVE的已登记空间。目录须只有一个精确code且id为非空UUID，已有operationIds与实际id不符即CONFLICT；缺失/重复/HTTP失败不回退POST创建。新操作成功后保存实际ID，仍不增加额外业务action。
5. **旧49动作兼容。** 已保存的[旧安全journal](ontology-20261029-1438/after-resume-safe.json)本次静态复核为49动作，主/B opCode均joint_list，无顶层opCode/operationIds，A/B_operation.id各自等于A/B_space.id，pending=null。这条准确路径先取已有joint_list，再在线确认实际ID，才补顶层安全code/ids并更新内存plan；不把新派生joint_<digest>覆盖旧资源code。没有resources但存在已确认旧operation时，joint_list仅作为待GET核实候选，不能仅凭旧动作名称宣称在线身份已验证。
6. **不重签、不掩盖未知写。** `Invoke-JointOrStep`仍先返回已确认动作，pending REQUEST/SECRET_SAVE保存及未知结果拒绝规则未动。缺已存secret/ref、凭据已失效或权限不符仍失败，不自动创建替代key、撤销或恢复角色。已完成49动作的Resume不应产生新业务action；普通未完且身份明确的session可按原机制继续尚未完成步骤，这不等于承诺所有Resume数据库零副作用。

旧journal首次新增的operation ID是本次live目录确认值，不能恢复原先未存档的历史operation ID字节。恢复失败也不保证partial全文件SHA不变：原catch会保存lastError等安全元数据；重点是pending不被清除、旧动作/资源/凭据不被伪造成功。成功迁移则应只补相应安全身份字段，不改既有49动作和资源映射。

## 一次真实Resume的验收与限界

当前40检查只AST装载Common原七个函数，并没有装载/执行Prepare内部Initialize/GetJointOperation；冲突、缺操作及在线OWNER门目前属于本次源层检查，不能写作已取得在线负例绿。63也仅协议，不替代这一步。

因此准入范围仅root已有、归属已核的49动作会话：先保留原partial与credentials.env原文件SHA；同上下文只执行一次`Stage Ontology -Resume`，核源码仍EF5/877，native0、pending=null、complete=false；code仍joint_list，A/B operationIds为真实目录id，49 actions/resources/context深相等，原release/endpoint/6凭据与不同principal不变，credentials.env全字节SHA相同。只对env算SHA，不公开其值；本次登录、读审计和四次消费验证不能写成零副作用。

任何冲突、未知pending、失效key或读取失败都保留实况并停，不清pending、不重签、不修旧空间或猜测继续。当前报告不授权自动另建新session验证全套创建，也不授权AP三应用、All或J/S1；这些仍须独立准确范围/窗口及真实验收。实际Resume原件另留新RunId，不覆盖原49动作阶段或本次计划红绿。

本报告完成后停写；不修改三主入口状态，不操作index或提交，不把本候选准入当作EX-06整卡完成。
