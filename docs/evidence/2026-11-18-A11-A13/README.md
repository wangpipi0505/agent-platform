# A11+A13 验收证据：机制级验收（阶段四收口）

日期：2026-11-18。卡片：台账 §7.5 **A11 计划导入/调试/发布/外部取结果** + **A13 多角色走查**。基线 tip `66b7dc79`。

## 验收执行口径

按测试分级减负口径（§5.3）：M4 门标准 A11/A13 对应机制级定向回归；部署栈正式页面走查（F3-6 真实页面两角色、SUS、基准任务步数浏览器证据）随部署环境承接——机制级已由 L4 交付的前端静态断言锁定（workspace-shell/manifest-panel/session-drill/plan-detail 等）。

## M4 门标准逐项对照（台账 §2.2）

| 标准 | 机制级证据 | 结果 |
| --- | --- | --- |
| A11 计划导入 | ConfirmationCenterTest（plan-import 画布桥：受限映射+不可表达降级 stepId+不支持字段——B4-1） | ✓ |
| A11 发布 | A3-1 预检（manifest 引用核验+冲突阻断）+ A3-8 resource-manifest（发布版本资源装载定位） | ✓ |
| A11 外部取结果 | ExternalIntegrationTest 5/5（SERVICE 四端点白名单/mode 强制 AGENT/他人任务 404/跨应用 403/白名单外 403 审计） | ✓ |
| A11 调试 | AgentEngineShadowTest（turn 信封/影子验证）+ AgentD2RequestPersistTest（D2 先落库后出流） | ✓ |
| A13 多角色走查 | ConfirmationCenterTest（四类待办处理角色口径：ADMIN/APPROVER/提交人/来源用户+越权 403 审计）+ PlanModeTest（双轨解耦）+ MemoryApiTest（A09 权限链）+ PlanMode/Plan API 角色投影 | ✓ |
| 页面静态锁定 | workspace-shell（锚点/任务 b ≤4）/manifest-panel（任务 a ≤6）/skill-precheck（任务 c ≤8）/session-drill（双视角）/f4-04（断言 4-7）——L4 已交付 | ✓ |
| 越权记审计 | ConfirmationCenterTest 403 审计 + SessionEventsEndpointTest 诊断视图 403 审计 + ExternalIntegrationTest 白名单外 403 审计 | ✓ |

## 回归统计

7 类 **47/47 全绿**（ConfirmationCenter 8+ExternalIntegration 5+ResourceManifest 6+SubagentPlanning 8+TasksApi 6+MemoryApi 9+PlanMode 5）。

## 部署依赖（诚实登记）

F3-6 两角色真实页面走查、A13 六角色浏览器操作流、SUS≥70、A11 外部宿主出票全链——需部署栈（七服务+模型+外部宿主），随 G5-0/阶段5 承接。
