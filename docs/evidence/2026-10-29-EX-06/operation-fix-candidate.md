# EX-06 opCode最小补丁候选（停写，待root复验）

2026-10-29，docs_reader_check。root已先新增OperationIdentity行为断言并实际执行`operation-red-20261029-01`：native1、前32检查通过、新opCode形状断言失败、http0/resources0。本报告不把缺文件/语法失败当该实红。该红的测试SHA为`3eef58416df377e59776e993e4147038105e88647f0fb65f159dfb32824dd60d`，本次保持原字节；未改新旧断言、AST装载名单或其它脚本。

本轮只改`Joint.Common.ps1`、`Prepare-Joint.ps1`并新增本报告，没有运行driver/Node/HTTP/PG/Java/服务，没有修改主入口、产品或Git index。两PS均UTF-8 BOM，显式WindowsPowerShell5.1.26100.9711 `Parser.ParseFile`均0错误；这只证明语法，不声明实际绿。

## 1. 精确候选SHA

| 文件（AP相对路径） | 修改前 | 当前候选SHA256 |
|---|---|---|
| `scripts/acceptance/Joint.Common.ps1` | a008f692d89d18a416b657c9cb3b5f08f804b2bb44205e616253b90db48505cd | ef5cae1919812965592d4eb702b9b99ea9c7ca9a75191051c9ec2878febb84ce |
| `scripts/acceptance/Prepare-Joint.ps1` | fce9ce3a4e06d3b01ac9b34f80283de86740c0393a14788cbe8ef29d842fab98 | 87709f9fe5cc0c7bb387ed3a09433c713b7ce238f6b78e2bd64ae7c8b17c52ec |
| `docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1`（只读） | 3eef58416df377e59776e993e4147038105e88647f0fb65f159dfb32824dd60d | 同左，未编辑 |

修改前Common A008原字节保留在`protocol-ontology-20261029-1428/Joint.Common.final.ps1`；Prepare FCE9保留在`or-stage-source/Prepare-Joint.ps1`。不重写历史冻结。

## 2. 计划层：实现已达失败断言

- 直接在既有`Get-JointPreparationPlan`计算UTF8(SessionId)的SHA256前24小写hex，返回`OpCode=joint_<24hex>`；没有新增Common函数，因此**现测试AST七函数导入名单无需追加**，不请求改断言。固定向量joint-unit-01→joint_7cd41b76f38d0ca927b26060已按标准库静态复算，与冻结断言一致；未执行PS函数。
- 保持SessionId原ASCII/长度校验，非法Unicode仍`JOINT_CONTEXT_INVALID`；同Session不依赖nonce或磁盘结果。显式最终manifest复用时直接沿用manifest.opCode，不重算派生值。主/隔离opCode增加同一ASCII字母开头、字母数字_-、≤64形状校验；实际两空间code一致性留Prepare的身份复核拒冲突。
- 该函数仍只产准备计划，不创建资源、私密目录或manifest。原manifest冲突保护、HTTP、私密写入、A008 NullString修复均保留。

## 3. Prepare：先持久固定，再允许业务写

- 新session初始state含`opCode=$plan.OpCode`与空`operationIds`；第一次Save-JointStage在任何OR业务写之前完成。后续A/B操作创建、目录查询、发布端点筛选、消费URL、主resources和isolationFixtures均使用同一已固化code；只剩旧journal兼容分支保留一次字面joint_list。
- 新增Prepare内部`Initialize-JointOperationIdentity`，在真实OR owner已验、任何space/model/grant等业务写前执行。journal顶层opCode、已有主/隔离resources、显式复用manifest的code逐个验证，大小写精确比较；不一致即`JOINT_OPERATION_IDENTITY_CONFLICT`，非法形状拒绝。未创建操作的新state还须与本session派生值一致。
- `Get-JointOwnedSpace`沿用原nonce/code/namespace/OWNER/ACTIVE检查；`Get-JointOperation`只GET该已登记空间的operation目录，精确code必须唯一、id须非空UUID，已有operationIds须完全一致。新操作成功后只额外保存其实际id到顶层operationIds，不新增业务journal action。
- pending仍在最前拒绝Resume；未知身份无回退创建，无重试/重签/撤旧。B操作先于A的矛盾状态拒绝；未知旧journal既没有code资源也没有已确认operation action时以`JOINT_OPERATION_IDENTITY_UNCONFIRMED`停止，不能按猜测迁移。

## 4. 现有49动作旧会话兼容边界

对本卡已有`ontology-20261029-1438/after-resume-safe.json`只读静态核过：49 actions、主/隔离code均joint_list、A/B_operation的返回id均为各自已登记space.id；未读/改私密env或在线资源。

旧journal缺顶层opCode/operationIds时：先从其已有resources取joint_list；若是早期已确认operation但尚无resources的旧journal，仅把原joint_list作为待核候选，再对已确认A/B_operation逐个读取真实已登记空间，确认实际唯一joint_list及id，全部核准后才存顶层安全code/id。失败不进入New-JointOntology业务写。

因此本会话预计仍复用原A/B空间、3 READY release、所有49 actions、6 credential/principal与key refs；只新增顶层opCode/operationIds安全字段。**这不是已实跑的“资源/字节不变”结论**，必须root下一次实际Resume前后深比较actions/resources并只对credentials.env算SHA。Resume照旧会产生读审计和消费验证事实，不能称数据库零副作用。

未改Stage All拒绝、complete=false或final manifest规则；没有接入AP三应用、模型供应商、数据库/Redis准备链。

## 5. root后续验证顺序（本报告未执行）

1. 独立通读两份候选与冻结测试，确认本报告SHA；不得在实际运行中并写。
2. WindowsPS5执行原测试`-Case All`的新RunId，预计原32+OperationIdentity8=40检查；以真实结果为准，不把静态向量当绿。原`operation-red-20261029-01`和3EEF测试源原件保留。

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `
  "$ApRoot/docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1" `
  -Case All -EvidenceDirectory "$ApRoot/.local/acceptance/operation-green-$RunId"
```

3. Common已换源，按root已封版的Test-JointProtocol（含两个Handle修复、原63断言）重新执行63并保存原生/采集状态，不能沿用A008历史63作为新源结果。测试源码不由作者改。
4. 40只覆盖计划层，未装载/执行Prepare内部在线身份逻辑；独审后再由root唯一实际窗口对现session作Ontology `-Resume`，保留原partial与credentials.env SHA基线，核49 actions/resources一致、key SHA一致、旧code保持、操作id精确、无新增业务动作/重复资源，complete=false。
5. 新session完整OR创建须root另给实际窗口/范围，不在计划层绿后自动创建新空间或签key。本次不以原会话改code来证明新session派生行为；若需要追加Prepare内部纯分支/冲突测试，由root冻结并改测试装载名单，作者不扩白名单。

候选已停写，等待root复核及实际结果；本报告不是独审或实跑完成证明。
