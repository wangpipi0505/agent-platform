# R-37 评测服务间委派

有用户上下文的评测由门户用户发起，待核验候选绑定应用编码、草稿修订和候选摘要。网关负责建立用户委派，monitor 读取候选及提交评测时延用同一授权根，目标只能是目录登记的 app 候选读取及 engine 评测运行或轮次。服务之间各自核验调用目的和候选归属。

发布预检使用绑定应用编码的 `evaluation-publication` 委派。app 校验当前主体的发布能力，从服务端草稿取得修订和摘要，并把同一委派转发给 monitor 的 `evaluation.accepted.read`。monitor 核对应用绑定后按票据租户、候选修订和摘要读取报告。没有用户上下文的后台任务只使用已登记的 workload 能力，审计主体为服务身份，用户面继续拒绝机器票。

## 执行与恢复

- 已接通纯 FLOW 的受理、调度和重放。engine 从评测请求复核候选正文，用 `evaluation-candidate` 根入库，同时保存委托引用与材料范围。worker 通过现有 authority 的 `resume` 重取同一候选的 `run.advance`。用例以报告和用例 ID 幂等。同一请求号下改输入返回 409。
- `GET /admin/evaluations` 与单报告查询按当前门户主体读取。 `POST /admin/evaluations/{id}/resume` 携带 `appCode`、`draftRev`、`candidateSha256`。monitor 与原报告逐项比对，再让 app 按冻结候选的应用复核当前构建权限。恢复沿用已持久化的报告和候选。
- CHAT 多轮的会话固定租户、主体、候选、报告、用例和数据集。engine 按顺序读取已成功前轮的受保护输入及输出，保存两份材料范围摘要及信任标签，由冻结的模型输入清单送出。每轮只执行一个主模型调用。工具、数据源和未确认模型结果的重复发送保持拒绝。
- 原 grant 过期时，用户从门户恢复报告。monitor 先向 app 复核当前构建能力，engine 再匹配原主体、候选、数据集和输入，验证新委派覆盖原材料。V233 追加授权代次并原子切换运行使用的 grant，保留原授权。当前实现要求新委派的初始权限快照与原 run 一致。授权明确拒绝时保留拒绝状态。
- 本地联合验证使用真实 authority-service、用户目录、五个业务服务、PostgreSQL、Redis 和回环模型测试端。两轮均已成功，monitor 生成 `ACCEPTED` 报告，app 发布预检对相同证据摘要返回通过。

业务软件接入和 IAM 配置见 [统一授权通道](business-platform-access.md)。
