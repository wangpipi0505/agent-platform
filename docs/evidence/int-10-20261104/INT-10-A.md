# INT-10（A 席）：三路线分级声明与交接（文档/配置层）

- 日期：2026-11-04
- 席位：A
- 基线 HEAD：`a3597509`（INT-09 `451031e1`、ONB-08 `94bc7d50` 之后；期间其他席位已合入 `ade5749c`/`a3597509`）
- 范围：`docs/integration/` 三路线分级声明文档、交付包索引与校验链
- 未触：`contracts/`、产品代码、测试断言、其他席位工作树文件（`docs/evidence/int-07-20261104/assistant-sdk-structure.json`）

## 一、原责任 → 新责任

| 原责任 | 现归属 | 说明 |
| --- | --- | --- |
| 三路线等级/宿主前提/责任边界散落在方案、证据与验收报告 | `docs/integration/three-route-tiering.md` | 逐路线声明 L0～L3 目标、当前可达性、宿主前提、允许能力、确认策略、故障责任、回退 |
| B04/B05/B08 只在启动准备记录中登记 | `three-route-tiering.md` §5.1 | 逐条列出路线、现状证据、可核验解锁条件、责任方；状态一律 BLOCKED |
| “文档层交付”与“验收完成”口径易混 | `three-route-tiering.md` §0/§7 | 明确两个等级体系、Non-claims、不得以 PUBLISHED/旧报告替代当前结论 |
| 交付包索引只覆盖 SDK/示例/模板 | `docs/integration/README.md`、`versions.md` | 增加三路线声明入口与版本行；保留原有交付物索引 |
| SDK 产物 checksum 固定为旧构建 | `checksums.txt`、`versions.md` | 按当前 HEAD 重建产物并刷新 digest/字节；`verify-delivery.mjs` 改为从 versions 行读取，不再硬编码前缀 |

## 二、改动文件

| 文件 | 行数/变化 | 内容 |
| --- | --- | --- |
| `docs/integration/three-route-tiering.md` | 新增 198 行 | 三路线分级声明、逐级可达性、交接清单、BLOCKED 登记、解锁顺序、Non-claims |
| `docs/integration/README.md` | +5 | 交付物清单增加声明文档；新增“三路线分级声明（INT-10）”入口段 |
| `docs/integration/versions.md` | 7 行改 | 构建提交 `a3597509`；产物 `252936` 字节 / gzip `81736` / sha `ae33f0…7bf7d`；增加分级声明行；校验命令改为新摘要 |
| `docs/integration/checksums.txt` | 4 行改 | SDK 行摘要更新为新产物；构建注释改为 `a3597509` |
| `docs/integration/verify-delivery.mjs` | +9/−4 | 从 `versions.md` 构建产物行解析 digest/字节，去掉 `f1a797` 硬编码前缀 |

## 三、不变量

1. **BLOCKED 优先**：三路线真实场景、全量/联合与独立验收均未执行，声明状态一律 BLOCKED；本文件不是验收完成证据。
2. **不替代**：不使用历史 `PUBLISHED` 标记、旧验收报告或示例页冒充当前等级；`PUBLISHED` 只解释为技能/应用发布状态。
3. **等级口径**：接入等级只用 `contracts/assistant-integration.md` §2 的 L0～L3；验证证据等级（验证方案 L0～L4）单独标注，二者不互换。
4. **不伪造引用**：未创建/未激活任何生产 IntegrationProfile 或 ConfigRelease；文中不写具体 `profileId/releaseId/digest`。
5. **真实用户**：三路线均要求 Authority portal session 或宿主 BFF 已验证会话；B08 未认证前不得使用演示账号、共享 operator 或旧用户权限。
6. **能力边界**：路线一默认只读（5 个已发布技能），文件/下传链未交付不开放；路线二 4 个本地算法技能未正式注册；路线三只读优先，写/审批由用户系统最终裁决。
7. **确认策略**：有副作用调用经审批中心（快照摘要）或用户系统审批流；SDK 只回传 `businessRecordRefs`，不自动提交业务。
8. **责任分层**：宿主负责登录态/BFF/origin/CSP/入口；平台负责短票、身份映射、事件与配置；业务系统负责状态机与最终裁决；算法 owner 负责算法/数据。
9. **回退**：停配置发布 → 停/撤接入档案与凭据 → 移除菜单/按钮 → 短票自然过期；不恢复已撤销权限、不清审计/会话。
10. **校验可复核**：`verify-delivery.mjs` 通过（8 项 PASS），`vue-tsc` 通过；未改契约与产品代码。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `node docs/integration/verify-delivery.mjs` | **失败 3 项（保留）**：SDK 产物 checksum 不符（期望 `f1a797…`，实际 `ae33f0…`）；`versions.md` 摘要/字节与产物不一致（`251955` vs `252936`）。原因：产物在 INT-07/08/09 后已变化，交付包摘要停留在旧构建 |
| 2 | `cd web && ./node_modules/.bin/vite build --config assistant.js/vite.config.ts` | 成功：`dist/assistant.js 252.94 kB │ gzip 82.19 kB`；`sha256=ae33f0eab09719c2a2ae575a1ddd0a7d5a2b158149a917819da3703103f7bf7d`，`252936` 字节 |
| 3 | `node docs/integration/verify-delivery.mjs` | **PASS 8 项，exit 0**：checksum×4、事件枚举 7 项、SDK 自检脚本对齐、示例接线、versions 与产物一致 |
| 4 | `cd web && npx vue-tsc --noEmit` | exit 0 |
| 5 | `git diff --stat` | 本卡 4 个文件修改 + 1 个新增；未含其他席位文件 |

## 五、未覆盖

1. 三路线真实场景、全量/联合、独立验收均未执行（无用户授权，B04/B05/B08 未解锁）。
2. 未创建/激活任何生产 IntegrationProfile 或 ConfigRelease；无真实 `profileId/releaseId` 可引用。
3. 未做 iframe 跨源/浏览器矩阵真机验证（P 窗口）。
4. 路线一 multipart 文件链、路线二正式 LOCAL 适配器、路线三 manifest 桥/身份映射均未交付，只登记。
5. `verify-delivery.mjs` 只覆盖文档/示例/枚举/checksum，不覆盖真实运行。

## 六、契约缺口（只登记，未私改）

1. 契约没有“路线级分级声明”的对象/状态机；当前只能用文档声明，无法落成配置对象。
2. B04/B05/B08 属启动准备记录，不在两份契约中，交接需双引用。
3. 路线三自审批/状态机拒绝无登记机器码（当前 HTTP 200 + 自然语言）；建议在 §6 增补后再实现。
4. 验证方案的“证据等级 L0～L4”与契约的“接入等级 L0～L3”同名不同义，易混；本文件已显式区分，建议在方案/契约统一命名。
5. `verify-delivery.mjs` 原先硬编码 SDK 摘要前缀 `f1a797`，交付物重建即误报；已改为按 `versions.md` 行解析（属校验脚本健壮性修复）。

## 七、回退

- `git revert <本卡提交>`；纯文档/校验脚本变化，无数据影响。
- 注意：回退会把 SDK 摘要恢复为旧构建值；若本地产物已按当前 HEAD 重建，需重新构建旧提交或接受校验失败，不得手工改摘要冒充。
- 不删除任何历史证据与审计；不改变三路线 BLOCKED 状态。
