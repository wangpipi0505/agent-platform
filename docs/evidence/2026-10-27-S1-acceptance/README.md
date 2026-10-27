# S1 出口验收第 1 次执行（2026-10-27 23:25–00:10）

> 对应手册：`文档/优化方案/S1-验收运行手册-20261027.md`。
> 本次执行的是"装配后的真实行为"（六个服务 + 真实 PG），不是单模块测试。
> **只记录实际做过的项；未执行的项在 §5 明确列出，禁止读成"整体通过"。**

## 0. 环境与前置

| 项 | 实际值 |
| --- | --- |
| 代码基线 | `2ae55c9`（含 `879121f` PLAT-03/10、`2ae55c9` Y-6），工作树除既存未跟踪证据外干净 |
| 旧进程 | 先用 `Stop-Process` 停掉 18080–18085 六个旧 JVM（`.local/services.json` 里的登记已过期，`stop-backend.ps1` 认不出，逐个核对 jar 路径后停） |
| 构建 | `scripts/start-backend.ps1` 全量 package：7 模块 BUILD SUCCESS，38s |
| 启动 | 六个服务全部启动：gateway 5608/18080、app 12764/18081、engine 17268/18082、skill 65472/18083、model 10804/18084、monitor 66420/18085 |
| 健康 | 六个 `/actuator/health` 均 `{"status":"UP"}` |
| 迁移 | 启动日志确认：skill **v315**（本次新应用到 314/315）、app **v105**、engine **v220**、monitor **v504** |
| 令牌 | `POST /auth/dev-token` 返回本地开发身份（tenant `local-v12`、roles `EDITOR/ADMIN/APPROVER`），后续请求均带该 Bearer |

契约与门禁自检（启动前）：`verify-contracts.mjs` 通过（v1.2 快照、四契约哈希、七模块边界）；`verify-routes.mjs` 服务票据面 0 MISSING（门户面 26 条告警，均为已知 `/admin/**` 门户面）。

## 1. 验收项 D 的一部分：PLAT-11 鉴权声明（HTTP 层）

注册接口 `PUT /admin/skills/{code}`（skill-registry 18083，ADMIN Bearer）。脚本先用 `POST /admin/skills` 建壳，再用 PUT 提交完整契约。

| 用例 | 请求要点 | 实际响应 | 判定 |
| --- | --- | --- | --- |
| `authScheme=NONE` + `authRef` | 完整 HTTP 契约 | `400 SKILL_AUTH_SCHEME_CONFLICT`，message「authScheme=NONE 表示对端不需要鉴权，不能同时配置 authRef；请删除 authRef 或改用 BEARER/API_KEY」 | ✅ 拒绝，且给出可执行的下一步 |
| `authScheme=BEARERX` | 同上，无 authRef | `400 SKILL_AUTH_SCHEME_INVALID`，message「authScheme 只能是 BEARER/API_KEY/NONE，收到：BEARERX」 | ✅ 未知值拒绝并回显 |
| `authScheme=API_KEY`（合法） | `authRef=vault:probe` | `200 OK`，响应 `authScheme=API_KEY`、`digestVersion=1`、`semanticDigest` 与 `sha256` 同时返回 | ✅ 归一化结果显式落库（执行侧不再回读 legacy 开关） |
| 不声明 `authScheme` | 无 authRef | `200 OK`，响应 `authScheme=BEARER` | ✅ 存量技能兼容（`legacy-bearer` 默认 true） |

说明：本次只验了**注册期**语义。真实出站头（`X-API-Key` 且无 `Authorization`、vault 缺键 401 且对端零请求）需要可达的对端，属 D 项剩余部分，见 §5。

### 1.1 顺带发现的契约约束（非缺陷，记录备查）

建技能时 `execution` 必须显式给 `kind/readonly/sideEffect/idempotent/cancellable` 且 `readonly`/`sideEffect` 必须是布尔（`"NONE"` 会被拒）；`httpMapping` 的键集必须恰好是 `path/query/body`，`body` 的值只能是保留名 `body`，`inputSchema.properties` 必须与映射一致。这些约束在 `SkillCatalog.normalize` 里，报错都带字段名，可用。

## 2. 验收项 B：门禁不再固定 BLOCKED（HTTP 层）

| 用例 | 请求 | 实际 | 判定 |
| --- | --- | --- | --- |
| 未知应用预检 | `POST /admin/apps/g10-noapp/publish/precheck` | `400 APP_NOT_FOUND`（「应用不存在」） | ✅ 不是 500、不是静默放行 |
| fixture 应用预检（无验收报告） | `POST /admin/apps/space-visibility-agent/publish/precheck` | **HTTP 409**，body `{"code":"EVAL_GATE_BLOCKED","message":"缺少满足安全100%、通过率及无新增回归的候选报告","traceId":"4f060366-…"}` | ✅ 三件套齐全、状态码 409 |
| 豁免创建（ADMIN，绑定错误 sha） | `POST /admin/apps/space-visibility-agent/publish-waivers`，`candidateSha256=000…0`、`draftRev=1`、`reason` 36 字、`expiresAt` 30 分钟内 | `200 OK`，返回 `id=pw_2832295d-…`、`approvedBy=developer-v1…` | ✅ 允许登记，但见下一行 |
| **豁免绑定校验** | 同上之后再次预检 | 仍 **409 EVAL_GATE_BLOCKED** | ✅ 未匹配当前候选的豁免不放行（绑定 sha/draftRev 生效） |
| 豁免撤销 | `DELETE /admin/apps/space-visibility-agent/publish-waivers/pw_2832295d-…` | `200 OK` | ✅ 撤销可用 |

**未验**（需要一条真正通过评测的候选报告，见 §5）：预检 6 项全 PASS、`gate_mode='BLOCKING'` 落库、`WAIVED` 分支、`ADVISORY`/`OFF` 分支、`agent.publish.waiver-enabled=false` 的 403 与强制降级。

## 3. 验收项 A / C：未执行

- A（断言 + 评测）需要一条可复现的输出与 `monitor.evaluation_report` 落库；本机没有可用模型端点，按手册 §8.1 应退化为"固定输出的桩技能"，但仍需先建数据集与应用草稿。本次未做。
- C（异步任务态）需要 `SUBMIT_QUERY` 桩服务（`scenario-2*.json`）。本次未做。

## 4. 数据与清理

- 新建了三个探针技能：`g10-authcheck`（空契约壳 + 多次失败保存）、`g10-apikey`（API_KEY）、`g10-default`（缺省 BEARER）。它们留在 `skill.skill` 里，`status=DRAFT`、未发布，不影响门禁；如需清理走 `DELETE /admin/skills/{code}`。
- 测试豁免已撤销（`revoked_at` 非空），`app.publish_waiver` 留一行审计。

## 5. 结论

| 项 | 状态 |
| --- | --- |
| 装配与迁移（六服务、V315/V105/V220/V504） | ✅ 通过 |
| PLAT-11 注册期鉴权声明（4 条） | ✅ 通过（真实出站头未验） |
| PLAT-02 门禁分层（拦截 409、豁免绑定、撤销） | ✅ 通过（放行/降级分支未验） |
| PLAT-01 断言、PLAT-04 异步任务态 | ⏸ 未执行 |
| 路线三去桥直连（真实 `X-API-Key`） | ⏸ 未执行（需 ontorepo release 与 `ontopa_` 凭据） |

因此 **S1 出口尚未达成**：台账里 PLAT-01/02/04/11 仍保持 `待验收`，不因本次部分通过而改 `完成`。下一步按手册 §3–§6 补齐 A/C/D，或在有模型端点时先做 A。
