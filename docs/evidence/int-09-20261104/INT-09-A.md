# INT-09（A 席）：平台标准接入包与兼容验证

- 日期：2026-11-04
- 席位：A（平台接入/SDK）
- 基线 HEAD：`7005d25a`（`feat(onb-04): 草稿组装与验证发布前端…`）
- 范围：`docs/integration/` 新增交付包、SDK 产物 checksum 与自检脚本；不修改 SDK/后端源码
- 未触：`web/assistant.js/src/**`、`web/shared/**`、后端任何模块、`deploy/integration/README.md`（仅引用）、他席未提交文件

## 一、原责任 → 新责任

| 原责任 | 新责任 |
| --- | --- |
| L0～L3 分级散落在契约 §2，宿主无单页选择方法 | `l0-l3.md`：分级、选择判据、L1/L2/L3 最小步骤与安全边界 |
| 宿主不知道要准备什么、CSP/代理怎么配 | `host-checklist.md`：A～E 五组自检项 + origin/CSP/反向代理清单 + 上线回归 |
| 事件/配置 Schema 只存在于源码与契约 | `versions.md` + `checksums.txt`：SDK 1.0.0、依赖、Schema/契约摘要、公开 API 与校验命令 |
| 故障码只在契约 §6 表格 | `error-codes.md`：派生表（不新增码）+ 宿主处理 + 排查顺序 + 非登记码边界 |
| 接入示例缺一个可照抄的宿主页 | `examples/blank-host.html`（按钮→对话→只读调用→结果回传）、`vanilla-host.html`、`vue-host/`、`iframe-host.html`、`bff/ticket-server.mjs` |
| 能力/提示词配置无模板 | `templates/experience-template.json`、`templates/prompt-template.md` |
| 升级/回退只有部署 README | `upgrade-rollback.md`：组合版本、升级步骤、回退步骤、兼容事实 |
| 交付物无法复核 | `verify-delivery.mjs`：checksum、事件枚举 TS/Schema/契约、示例接线、`versions.md` 与产物一致性 |

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `docs/integration/README.md` | 85 | 交付包索引、快速开始、校验、边界 |
| `docs/integration/l0-l3.md` | 45 | 分级与最小步骤 |
| `docs/integration/host-checklist.md` | 85 | 宿主自检与 CSP/代理清单 |
| `docs/integration/versions.md` | 82 | 固定组合、产物 checksum、公开 API |
| `docs/integration/error-codes.md` | 43 | §6 派生故障码表 |
| `docs/integration/upgrade-rollback.md` | 46 | 升级/回退与兼容事实 |
| `docs/integration/checksums.txt` | 8 | 可复核摘要（含构建命令与提交） |
| `docs/integration/verify-delivery.mjs` | 193 | 交付包自检脚本 |
| `docs/integration/examples/blank-host.html` | 208 | 空白宿主页完整接线 |
| `docs/integration/examples/vanilla-host.html` | 59 | 原生 JS 最小接线 |
| `docs/integration/examples/iframe-host.html` | 123 | iframe hello + 精确 origin/nonce/实例 |
| `docs/integration/examples/vue-host/{index.html,main.ts,App.vue,assistant.d.ts,README.md}` | 13/4/136/52/21 | Vue 3 接入示例 |
| `docs/integration/examples/bff/ticket-server.mjs` | 164 | 可信后端短票 + 只读记录示例 |
| `docs/integration/templates/experience-template.json` | 53 | 体验/能力/推荐问法模板 |
| `docs/integration/templates/prompt-template.md` | 48 | 提示词引用与变量模板 |

合计 1468 行（含 JSON/HTML）。

## 三、不变量

1. **固定组合**：SDK `@platform/assistant.js@1.0.0`、vite `6.4.4`、TypeScript `5.8.3`、marked `15.0.12`、DOMPurify `3.4.17`、宿主事件 Schema `1.0`；换任一项即新交付包版本。
2. **产物可复核**：`web/assistant.js/dist/assistant.js` sha256 = `f1a797c02a7247de3900ffa0502069afa9f77ff33938bab05587a57944bc0ed5`，251955 bytes；重建后一致（本轮重建两次一致）。
3. **无密钥**：所有示例不含 API Key/模型密钥/vault/Authority 凭据；短票只经同源 BFF，票据不写 URL/localStorage，不打印 Authorization。
4. **事件白名单**：示例只处理 `ready/opened/closed/run-started/approval-required/result/error`；未知事件忽略；iframe 不使用 `"*"` 作为 `targetOrigin`。
5. **身份边界**：pageContext 只放显式业务字段；示例不发送 tenant/user/roles；`businessRecordRefs` 只作为宿主后端重新鉴权的查询线索。
6. **业务效果 UNKNOWN**：示例把 `structured.answer` 当展示文本，不当作业务已提交；只读查询独立于模型输出。
7. **契约派生**：故障码表逐条对应 §6；非 §6 的运行时码单列并标注「不属登记集」。
8. **不重写部署文档**：引用 `deploy/integration/README.md`，只补充入口层与自检。
9. **IAM 限制如实**：`/embed/ticket` 在 IAM 入站边界不放行；L3 与 BFF 示例注明该模式用 portal session。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `cd web && ./node_modules/.bin/vite build --config assistant.js/vite.config.ts` | `dist/assistant.js 251.96 kB │ gzip: 81.90 kB`；sha256 `f1a797…0ed5`，251955 bytes |
| 2 | `node docs/integration/verify-delivery.mjs` | 8 项 PASS：4 个 checksum、事件枚举 TS/Schema 一致（7 项）、SDK 自检脚本对齐、示例接线要素、产物与 `versions.md` 一致；exit 0 |
| 3 | `cd web && ./node_modules/.bin/vue-tsc --noEmit && vite build admin && vite build webapp && vite build assistant.js` | 全 exit 0；admin 6.83s、webapp 4.35s、assistant 0.51s（仅 chunk>500 kB 警告） |
| 4 | `sha256sum web/assistant.js/dist/assistant.js`（重建后复核） | 仍为 `f1a797c02a7247de3900ffa0502069afa9f77ff33938bab05587a57944bc0ed5` |
| 5 | `cd web && node tests/assistant-sdk-split-check.mjs` | `ok:true`，17 项 checks 全 true，`pageErrors: []`，dialog 仅 1 次（审批确认）；含 `hostResultEventDelivery` |
| 6 | `node docs/integration/verify-delivery.mjs`（构建后复跑） | exit 0，同上 |

## 五、未覆盖

1. 示例页**未接真实后端运行**：按指令，接线示范为代码级，真实浏览器/跨源/iframe 验证留在 P 窗口。
2. iframe 示例未在真实跨源环境跑通 `hello → ready/opened`；只做静态契约对齐。
3. `bff/ticket-server.mjs` 未对真实网关执行（会话校验为占位，默认拒绝，避免误当生产实现）。
4. Vue 示例未在独立 Vue 工程编译（`docs/` 不参与 `vue-tsc`；组件语法按现有 Vue 3.5 写法）。
5. CSP 下 Shadow DOM inline style 的浏览器差异未实测；文档给出约束与退让方案。
6. checksum 对 `contracts/` 的钉住会随契约评审变化；变更时需重生成交付包（已在 `versions.md`/`upgrade-rollback.md` 说明）。

## 六、契约/实现缺口（只登记，未私改）

1. `integrationId` 的生成/登记来源未在契约定义；示例用配置发布页提供的值。
2. iframe `hello` 帧格式（`agent-platform:assistant:hello` + `nonce`/`instanceId`）未写入契约，仅实现约定；本包按实现记录。
3. `businessEffect` 取值未入枚举（当前固定 `UNKNOWN`）；`businessRecordRefs.source` 的取值域未定义。
4. `INTEGRATION_RATE_LIMITED` 等运行时码不在 §6 最小表；本包在 `error-codes.md` 单列并标注。
5. IAM 模式 `/embed/ticket` 不可达属已知入站边界限制；文档只引用，不在本卡修改。

## 七、回退

- 回退：`git revert <本提交>`，删除 `docs/integration/` 即回到无交付包状态；不涉及运行时数据/迁移。
- checksum 仅约束交付文档引用的产物与契约摘要，不回写 SDK；撤销交付包不影响已接入宿主。

## 八、白名单外改动

无。全部新增均在 `docs/integration/` 与本报告；未触碰他席未提交文件（`docs/evidence/int-07-20261104/assistant-sdk-structure.json` 仍为其工作树改动）。
