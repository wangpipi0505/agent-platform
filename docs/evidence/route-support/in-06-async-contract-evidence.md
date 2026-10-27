# IN-06：路线一异步真实响应字段证据（8080 真实实测）

> 日期：2026-10-27 ｜ 服务：space-ground-visible 8080（真实算法+真实库）
> 用途：AP-S03（异步合同）决策输入；skill-registry SUBMIT_QUERY 技能的字段配置依据
> 方法：`POST /api/v1/space/visibility/tasks`（A02 operation16 真实请求样例）→ 轮询 `GET /tasks/{taskId}`

## 实测响应合同（2026-10-27，全部为真实响应字节）

**受理** `POST /api/v1/space/visibility/tasks` → 单字段：
```json
{"taskId": "faf9efa9-2f33-4eff-9212-ca357a7b7d61"}
```
没有 jobId/status/progress 字段——SUBMIT_QUERY 技能的"任务ID提取路径"就是 `$.taskId`。

**轮询** `GET /api/v1/space/visibility/tasks/{taskId}` → 9 字段：
```json
{"taskId","status","totalPairs","completedPairs","totalTargets","completedTargets","errorMessage","createdAt","completedAt"}
```
- status 终态：SUCCEEDED / FAILED / CANCELLED（PENDING/RUNNING 为进行中）；
- 进度 = completedPairs/totalPairs、completedTargets/totalTargets；
- **失败是终态而非错误码**：HTTP 200 + status=FAILED + errorMessage 携带底层原因
  （实测样例："wrong checksum of TLE line 1, expected 3 but got 6 (1 00000U …)"）——
  技能契约的错误映射须按 status 字段而非 HTTP 状态码判定；
- GET result 对非 SUCCEEDED 返回 409（A02 已约定）。

## 阻塞（外部服务数据质量，owner 责任）

实测四次异步任务全部 FAILED：
1. 1 号星 TLE 校验和错误（数据行 1 00000U 26178B …）；
2. 2–5 号星"我方卫星缺少 SPACE 载荷参数"（当前库无 SPACE 载荷配置）。

因此 **result 信封形状本轮未能实测**，以 A02 接口文档（operation17/18）为准，
待 owner 修复演示数据后在 IN-07 联调补验。未修改该服务的任何数据/代码。

## 对 AP-S03 的结论（决策输入）

1. 受理响应仅 taskId 单字段 → SUBMIT_QUERY 契约的 jobIdPath=`$.taskId` 可提取任务；
   **但 2026-10-27 后续用真实响应复跑适配器时发现**：适配器默认要求受理与轮询响应回显
   `idempotencyKey`，而 8080 两个响应都没有该字段，原结论"不需要改框架代码"不成立。
   AP-S03 已增加显式契约开关 `reconciliation.idempotencyKeyEcho=false`：仅当服务合同
   明确只返回自身任务 ID 时才关闭回显校验；默认仍严格校验，见
   [ap-s03-async-task-envelope.md](ap-s03-async-task-envelope.md)。
2. 失败判定必须映射轮询体的 `status` 字段（FAILED→终态失败）而非 HTTP 状态码，
   errorMessage 透传给运行错误观测（AP-S07 的输入之一）；
3. 进度字段（completedPairs/totalPairs 等）可映射到技能执行事实的进度展示。

## 复现

```powershell
curl.exe -X POST -H "Content-Type: application/json" `
  -d @G:\5-YG\space-ground-visible\verification\A02\requests\operation16.json `
  http://127.0.0.1:8080/api/v1/space/visibility/tasks
curl.exe http://127.0.0.1:8080/api/v1/space/visibility/tasks/{taskId}
```
