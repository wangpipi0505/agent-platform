# 持久接入会话（CR-05）

2026-11-04。此契约承接已授权的审核补改；实现及检查状态见`docs/evidence/cr05-onboarding-20261104/`和项目升级补改台账，不据本文件声称整体验收通过。

## 身份、入口及状态

- 仅已验Actor的EDITOR操作；tenant与principal不从JSON/请求头取值。会话仅创建者可读写。ADMIN仍通过原接口维护模板，不扩大给业务用户。
- `GET /admin/onboarding-sessions/templates`仅投影可使用的PUBLISHED QUERY模板，不返回底层secret或物理地址。
- 会话创建`POST /admin/onboarding-sessions`，字段`templateCode/templateRev/appCode/appName/requestId`；模板编码与目标应用编码独立。服务端生成`os_`＋32位十六进制会话标识，初始化计划来自固定模板及实际资源。
- `GET`根列表、`GET /{sessionId}`恢复；`PUT /{sessionId}`携带`expectedRev/requestId/plan`；`POST /{sessionId}/preview`携带`expectedRev/requestId`。
- `POST /{sessionId}/steps/{ASSEMBLE|VERIFY|PUBLISH}/execute`携带`expectedRev/requestId/planSha256`；`POST /{sessionId}/cancel`携带`expectedRev/requestId/reason`。取消保留已建资源和历史步骤，不做自动删除。
- 状态为DRAFT、ASSEMBLED、VERIFIED、PUBLISHED、CANCELLED；后两项终态只读。步骤RUNNING/SUCCEEDED/FAILED单独持久保存，不能只靠页面进度或内存标记。

## 计划及真实执行

`plan`包含`fieldsBundle:{fields,mappings,returns}`、`values`、`selectedOperations`以及可用时的批准`connectionRef`。字段来自当前支持的Schema子集；direct/constant映射保留JSON类型，拒绝未知字段/操作/表达式。false、0、null不作空值丢弃。端点、凭据值和服务端来源关系不可由plan写入。

最低QUERY路径以包内已经解析的精确只读技能组装FLOW，业务字段经标准节点args进入实际调用，返回呈现使用原始结构化回执与END映射。不能仅把字段塞进prompt或计划元数据冒充接线。文件引用受原不可变技能白名单约束，不新增动态文件抓取。

会话自己的计划与应用创建回执不占用包版本的全局application_plan_sha256；相同模板可供多个目标应用使用。仍复用原prepare、精确技能复核、来源关联和AppService保存门，禁止复制假包或替换skillRefs绕过来源校验。未解析包/新技能导入等未覆盖路径应返回明确缺项，不能作为该组全部完成的证明。

## 幂等、恢复与发布

- 同一requestId的规范化载荷不同则冲突；相同载荷复用原收据。步骤绑定plan摘要，改计划不能复用旧验证成功。
- app草稿、来源关系与本地装配收据在本地事务内提交；远端验证/评测不持有本地行锁。部分成功或响应丢失须恢复原资源和runId，不能重新创建资源或自动覆盖人工改动。
- VERIFY使用真实受控debug-run并持久化运行标识，未结束/失败不得写成功。评测仅复用现有monitor门，预期结果须有明确输入，不构造假PASS。
- PUBLISH复用原AppService预检/提交及接受评测/权限/环境确认门。缺项给实际应用工作台入口，模板PUBLISHED不代表应用发布成功。
- `handover`仅由真实应用发布产生，含`templateCode/appCode/appVersion/launchPath`；前端核对应用身份和正整数版本后交接。独立助手链接默认打开当前发布版本，本次发布回执版本不能被说成链接已固定历史版本。

IAM部署中缺少正式下游授权通路须明确失败，不透传用户token、不退回旧HMAC票据。白名单/目录登记不代表下游签发、撤权和真实HTTP已通过。
