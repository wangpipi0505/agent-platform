# R-25 执行记录：V901 快照缺冻结模型引用——两条路径的结构性边界实证

日期：2026-11-19。卡片：台账 §7.6b **R-25**（R-24 端到端登记的下一层缺口：demo 模型链 `MODEL_NOT_CONFIGURED`→NODE_FAILED）。脚本 `scripts/perf/verify-r25-model-chain.mjs`（发布链重放验证器）+ `start-stack-r24.sh` 增 `AGENT_PUBLISH_TARGETENVIRONMENT=NON_PRODUCTION`。

## 已交付增量（源码）

`AppService.templateConfig` **DIRECT 对齐**（app-management 侧）：`promptSource:"DIRECT"` 显式内联原样保存（此前 `PUT /admin/apps/{code}/config` 400"模板来源必须显式选择"——engine R-24 的 app 侧同源枚举缺口）。回归 PromptTemplateCreation 3+四类共 **19/19 绿**零新增失败。经网关实证：config 保存成功（draftRev 1→2，modelEntryId=1 落 draft）。

## 两条路径的执行与结构性边界（诚实登记）

### 路径 1：发布链重放播种（推荐方向）——结构性不可达
链路实测推进：注册 demo-provider#1/demo-model#1 → draft 补 modelEntryId=1（保存成功）→ `POST publish/precheck` → **409 `EVALUATION_ENVIRONMENT_BLOCKED`**。
根因：发布评测证据经 **monitor 服务** `/internal/evaluations/accepted` 取得——**monitor 域未入栈**（仓库无该模块，R-14 登记⑨一致），依赖故障在任何门禁模式（BLOCKING/ADVISORY/OFF）都拦截（AppPublicationService:289-291 注释明示"依赖故障不允许用豁免绕过"）；`environmentAllowed=evidenceValid&&(...)` 而 evidenceValid 要求 monitor 返回的 publicationEvidence 与候选指纹/目标逐项匹配。造评测报告行属 monitor 域数据面置备+跨域表结构，超出本卡量级。**归口 R-22 环境项**（真实环境含 monitor 与真实评测）。

### 路径 2：种子动态化（V902 跨域补冻结引用）——量级重估，需评审
冻结引用三件套（modelEntryId+bindingId+sha256+entry）的正当来源=发布链冻结解析；V902 跨域（app 迁移内 INSERT model 域条目+子查询取 entry id）与 Flyway 并行启动时序竞争（app/model 两服务各自迁移）不稳健，且违反"迁移按 schema 分域"纪律——**需评审裁量**，量级重估 **1–2 人日**（原登 0.5–1）。

## 结论

R-25 **改判阻塞（随 R-22 环境项）**：demo 模型链端到端出话依赖 monitor 评测证据链（本机缺位）或跨域种子重构（需评审）——两者均非 0.5–1 人日卡量级。本卡实际交付=app 侧 DIRECT 保存对齐（真实增量，端到端实证保存链路通）+两条路径边界实证+重放验证器脚本（环境齐备后单命令复跑）。

## 复现

```bash
bash scripts/perf/start-stack-r24.sh   # 189xx 段 + r24_check（V901 已落）
E2E_GATEWAY=http://127.0.0.1:18980 E2E_MODEL_STUB_PORT=18985 \
  node scripts/perf/verify-r25-model-chain.mjs
```
原始输出：`r25-model-chain-result.json`（本轮 precheck 409 实录）。
