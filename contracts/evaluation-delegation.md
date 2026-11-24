# R-37 评测服务间委派

有用户上下文的评测由门户用户发起，待核验候选绑定应用编码、草稿修订和候选摘要。网关负责建立用户委派，monitor 读取候选及提交评测时延用同一授权根，目标只能是目录登记的 app 候选读取及 engine 评测运行或轮次。服务之间各自核验调用目的和候选归属。

发布预检使用绑定应用编码的 `evaluation-publication` 委派。app 校验当前主体的发布能力，从服务端草稿取得修订和摘要，并把同一委派转发给 monitor 的 `evaluation.accepted.read`。monitor 核对应用绑定后按票据租户、候选修订和摘要读取报告。没有用户上下文的后台任务只使用已登记的 workload 能力，审计主体为服务身份，用户面继续拒绝机器票。

## 执行与恢复

- 已接通纯 FLOW 的受理、调度和重放。engine 从评测请求复核候选正文，用 `evaluation-candidate` 根入库，同时保存委托引用与材料范围。worker 通过现有 authority 的 `resume` 重取同一候选的 `run.advance`。用例以报告和用例 ID 幂等。同一请求号下改输入返回 409。
- `GET /admin/evaluations` 与单报告查询按当前门户主体读取。 `POST /admin/evaluations/{id}/resume` 携带 `appCode`、`draftRev`、`candidateSha256`。monitor 与原报告逐项比对，再让 app 按冻结候选的应用复核当前构建权限。恢复沿用已持久化的报告和候选。
- CHAT 多轮要在同一评测根上保存会话、报告、用例、轮次和前轮结果范围。engine 从已提交且同归属的前轮生成历史材料，固定输入摘要和模型引用后再执行。授权换代以相同租户、原主体、候选和当前平台权限为前提，并保留旧代记录。该材料链需要联验后启用。

业务软件接入和 IAM 配置见 [统一授权通道](business-platform-access.md)。
