# R-17 OPERATOR 授权目录统一 + publishReview 计数口径——交付证据（2026-11-19）

分支 `close-wt-r16`（R-16 之后顺延提交），worktree wt-r16。

## 一、授权目录统一（T2-12 对账端点）

### 现状核查

全仓检索（admin-common/engine/app-management main 源码）无既有授权目录常量类
（无 RoleMatrix/RoleCatalog/ROLE_* 常量），各服务授权判定均为内联角色字符串
（`actor.roles().contains("OPERATOR")` 等散写，engine 内 8 个文件、目录外还有散布）。
按卡面"若无统一常量则建一个最小常量类"，在 admin-common 新建
`cn.agentplatform.common.Roles`（符合 AGENTS.md"公共服务约定在 admin-common"）。

### 改动文件

- `admin-common/src/main/java/cn/agentplatform/common/Roles.java`（新增，最小常量类）：
  8 角色常量 USER/BUILDER/EDITOR（BUILDER 兼容别名）/APPROVER/OPERATOR/ADMIN/SERVICE/
  GATEWAY_AUTH + 判定助手 `has`/`hasAny` + `catalog()` 目录全集视图；ADMIN 通配语义
  不在此实现（各端点显式书写判定组合，注释已登记缘由）。
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/TaskReconciliation.java`
  :47-48（授权链 ADMIN/OPERATOR 判定改 `Roles.has`；javadoc 登记常量出处）。
- 同类授权判定端点共用（首批接线，均为 OPERATOR/ADMIN 或入口门判定）：
  - `Tasks.java` :1007-1009 `privileged()`（恢复投影特权门）→ `Roles.hasAny`；
  - `ChatSessions.java` :610-611 `requireDiagnosticRole()`（诊断视图门）→ `Roles.hasAny`；
  - `ConfirmationCenterService.java` :62 `admin`、:78 `requireEntry()`、:103 `tenantWide`
    → `Roles.has`/`Roles.hasAny`。
- `engine-runtime/src/test/java/cn/agentplatform/engine/application/TaskReconciliationTest.java`
  （新增 2 用例 :219 `authorizationChainUsesRolesCatalogConstants`、:262
  `rolesCatalogConstantsAreSingleSource`；既有夹具角色改用常量）。

### 用例（TaskReconciliationTest 5→7）

1. `authorizationChainUsesRolesCatalogConstants`——同租户非提交人：目录中无对账处置授权的
   APPROVER 与 SERVICE 均 403 `RECONCILIATION_FORBIDDEN`（角色断言引 `Roles` 常量，无字面量
   散写）；OPERATOR 放行（RETRY REGISTERED 成功）。
2. `rolesCatalogConstantsAreSingleSource`——目录 8 角色全集核对（防拼写漂移）+ has/hasAny
   助手与目录常量同源断言。

### 诚实登记项（授权目录）

1. **OPERATOR 工作区授权仍是租户级占位**：`TaskReconciliation.authorizedWorkspace` 现状
   `tenantId != null` 恒真（T2-12 台账待确认口径"授权目录随工作区授权模型统一"）——R-17
   统一的是**角色常量出处**，不改授权语义；"同租户 OPERATOR 未授权工作区 403"场景在工作区
   授权模型落地前无法由角色判定触发（403 用例以 APPROVER/SERVICE 非授权角色承载）。
2. **常量接线为首批最小集**（对账端点+三个同类判定门）；engine 其余内联散写
   （Memories/RunSnapshotService/ResourceManifestService/SubagentPlanning 等 4 文件）未在本卡
   改动（避免无关回归面），后续可机械替换。

## 二、publishReview 计数口径（B4-1）

### 现状核实（读 SQL/查询+库稿）

`ConfirmationCenterService.countPublishReview` 原为 `select count(*) from
app.integration_config_release where tenant_id=? and status in ('VALIDATED','APPROVED')`。
库稿 V113：该表 **主键 (tenant_id, release_id)** 一行业务号一行；create 幂等重放
（writes.replay/remember）与 validate/approve（`ConfigReleaseRepository.transition` 同行 CAS
状态迁移）均**不新增行**——"同业务号多次提交重复计数"在现行 schema 下结构上不成立（无既有
缺陷）。按卡面口径显式化：计数改 `select count(distinct r.release_id)`（防未来 schema 演进
破坏一行业务号不变量，语义 today 不变）。

### 改动文件

- `engine-runtime/src/main/java/cn/agentplatform/engine/application/ConfirmationCenterService.java`
  :106-122（`count(distinct r.release_id)` :122 + 口径注释）、:26-35（类 javadoc publishReview
  条目补去重口径）。
- `engine-runtime/src/test/java/cn/agentplatform/engine/application/ConfirmationCenterTest.java`
  :434-492（新增 1 用例）。

### 用例（ConfirmationCenterTest 9→10）

`publishReviewCountsDistinctReleaseBusinessIdsAndMatchesManualReviewList`：
1. 同一 config_release（业务号 h41-rel-dup）首次提交后：裸 INSERT 同号被主键拒绝
   （DuplicateKeyException——一行业务号一行）；幂等重放路径（ON CONFLICT DO NOTHING）连投
   3 次仍 1 行；已 VALIDATED 业务号同行 CAS 再迁移仍 1 行；
2. **重复提交不重复计数**：ADMIN 计数=3（dup/dup2/dup3 三个业务号），重复提交未放大；
3. **计数与人工可复核清单逐条一致**：ADMIN（租户范围）与 BUILDER alice（created_by 本人
   范围）两口径分别断言 `counts.publishReview == 同表同范围 distinct release_id 清单行数`
   且重复提交业务号在清单恰一行。

## DoD 命令与结果

```bash
# R-17 定向用例
mvn -B -ntp -pl engine-runtime -am test "-Dtest=TaskReconciliationTest,ConfirmationCenterTest" \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=.tools/m2 "-DargLine=-Xmx1024m"
# → TaskReconciliationTest 7/7 绿 + ConfirmationCenterTest 10/10 绿

# 触达改动文件的定向回归（10 类）
mvn -B -ntp -pl engine-runtime -am test "-Dtest=TasksApiTest,PlansApiTest,SessionEventsEndpointTest,\
SessionListEndpointTest,SessionSseBackpressureTest,TaskEventsSseTest,TaskEventsSseRealPortTest,\
TaskDispatchTest,TaskReconciliationTest,ConfirmationCenterTest" -Dsurefire.failIfNoSpecifiedTests=false
# → 65/65 绿，BUILD SUCCESS

# admin-common（Roles 新增所在模块）
mvn -B -ntp -pl admin-common -am test -Dmaven.repo.local=.tools/m2
# → 387 tests，11F+1E —— 基线（5bde1c30，无 Roles.java）复跑同值 11F+1E（本卡零新增失败；
#   失败均为 authority/IAM 部署模式门的环境依赖用例，超出本卡范围不修，见诚实登记项）
```

### 全量套件对比（零新增失败的实证）

三次 `mvn -B -ntp -pl engine-runtime -am test "-Dtest=cn.agentplatform.engine.**"
-Dsurefire.failIfNoSpecifiedTests=false"` 全量对比（同一 .env、同一命令、同一机器；
工作量 11 分钟/次）：

| 运行 | 代码状态 | 用例数 | F+E | 失败类数 |
|---|---|---|---|---|
| B | 基线 5bde1c30 | 1127 | 33F+117E | 27 |
| A | HEAD=0276541b（R-16，R-17 已 stash） | 1127 | 35F+114E | 27 |
| 当前 | HEAD + R-17 未提交 diff | 1130（+3 新用例全绿） | 32F+114E | 27 |

三次失败**类集合逐类相同**（`comm` 双向 diff 均空）：27 个失败类全部为既有环境依赖失败
（@SpringBootTest 缺 AGENT_SECURITY_MODE、缺 `.local/iam-n3-*` 工件、缺
agent_platform_statistics_test 专用库、Redis 缓存未起、共享库 FK 清理顺序敏感、既有
ChatSessionConfigPinTest 构造 NPE——该类已单独在基线代码态复跑证实同因 5E）。F/E 个位数
抖动为 logback ListAppender 审计捕获类在全量顺序下的顺序敏感表现（SubagentPlanningTest/
SessionEventsEndpointTest 等既有审计断言类同样抖动），类级无新增。R-17 净增 3 用例全部绿。

## 测试统计（本卡净增）

- TaskReconciliationTest +2（403 常量断言用例+目录全集核对用例），7/7 绿。
- ConfirmationCenterTest +1（publishReview 去重与清单一致性用例），10/10 绿。
- admin-common/engine 既有回归：零新增失败（admin-common 11F+1E 与基线同值，经 stash
  复跑核实；engine 触达面定向 65/65 绿）。

## 诚实登记项（计数口径）

1. **现状无缺陷**：一行业务号一行由主键保证，原 `count(*)` 语义与业务号去重等价——本卡
   `count(distinct)` 为口径显式化与防御性收口，非修数。
2. **人工可复核清单口径**：publishReview 无独立"待办清单端点"（计数与 app 侧
   `GET config-releases?appCode=` 全状态列表不同源）；"逐条一致"断言在测试内以同表同范围
   `distinct release_id` 清单承载（卡面允许"测试内断言 counts 与 detail 清单行数/去重口径
   一致"口径），并断言清单成员（含重复提交业务号恰一行）。
3. **引擎测试库最小兼容表**：app.integration_config_release 测试内建最小列集（无
   updated_at 列，见 ConfirmationCenterTest 类 javadoc 既有登记）——用例 SQL 未引用
   updated_at。
4. admin-common 基线 11F+1E（AuthorityConfigurationTest 4F/AuthorityPolicyTest 1F/
   LocalRunAdvanceAuthorityTest 1E/IamInboundGateTest 1F/ServiceTicketProviderTest 1F/
   ServiceTokensDeploymentModeTest 4F）经基线复跑证实与本卡无关（涉及 IAM/部署模式门
   环境依赖），本卡不动。
