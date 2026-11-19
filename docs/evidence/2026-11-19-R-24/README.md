# R-24 验收证据：V901 种子 promptSource 与 engine 装配口径对齐

日期：2026-11-19。卡片：台账 §7.6b **R-24**。修复提交 `168c4cc1`（口径二选一**择 (b)**），端到端脚本 `scripts/perf/{start-stack-r24.sh,verify-r24-agent-terminal.mjs}`。

## 二选一口径裁决（写入 §7.9b）

**择 (b)：engine 接受 `"DIRECT"` 显式映射内联**。理由：(a) 改种子需 V902 修正迁移（V901 已落 dev/验证库，改文件致 checksum 失配），(b) 仅 engine 一处分支+契约条目，已落库环境零迁移；且 "DIRECT=内联快照提示词" 与 "TEMPLATE=冻结模板集" 是清晰的来源类型枚举。

## 交付物

1. `ContextAssembler.promptText`：promptSource 二值枚举——`TEMPLATE`（既有冻结模板主段）/`DIRECT`（内联 systemPrompt，空正文 409"直连来源缺少内联正文"）/缺省（旧内联兼容不变）/未知值 409（口径收窄为"仅 TEMPLATE/DIRECT"）。
2. `contracts/prompt-template-source.md` 补 **DIRECT 条目**（语义/空正文 409/附加模板顺序/缺省兼容）。
3. `DemoSeedPromptSourcePostgresTest` **5/5 绿**：V901 字面形状装配=内联原文不抛 409；缺省内联兼容；DIRECT 空正文 409；TEMPLATE 缺冻结正文仍 409；未知值仍 409。
4. 邻接回归 Plans 6/Tasks 7 全绿。

## 真实网关端到端（r24_check 专用库，五服务 189xx 段直启——R-9① 后无 shim）

- 栈：gateway 18980/app 18981/engine 18982/skill 18983/model 18984 + 模型桩 18985；r24_check 全链 Flyway 自举（engine V200→V901、app V100→V104，日志在案）。
- **装配缺陷消除实证**：3 条会话轮次 run 全链装配通过——`PROMPT_TEMPLATE_SNAPSHOT_MISMATCH` **DB run_event=0 且 engine 日志=0 命中**（修复前该 409 使 R-14 全部 AGENT/轮次 NODE_FAILED）。
- 集成测试+端到端原始数据：`r24-agent-terminal-result.json`（会话轮次/AGENT 受理快照）、`r24-next-gap.json`（run 终态清单）。

## 诚实登记与下一层缺口

1. **完整"出话 SUCCEEDED"被下一层缺口阻挡（已登记 R-25）**：3 条 run `FAILED|NODE_FAILED`，run_event payload=`MODEL_NOT_CONFIGURED`——V901 快照缺 `dependencyVersions.modelRefs[]` 冻结模型引用（demo-provider/demo-model→entry 的冻结解析属发布链动态产物，静态种子无法预置 entry id）。该缺口与 promptSource 口径无关（装配已通过、错误点在模型引用层），另卡承接（种子动态化或发布链重放）。
2. AGENT 任务受理成功后停 ACCEPTED：**T2-5 派发接续未交付**（M5-3/R-14 已登记口径），非本卡范围——如实留证于报告 `agentTask` 节。
3. DoD 载体修正依据：卡面"AGENT 任务跑终态"在派发接续缺口下不可达；promptSource 缺陷的实际载体=**会话轮次链**（r14_perf 实证 run 全 CHAT/FAILED）——本端到端以 CHAT 会话轮次复验缺陷闭环，AGENT 受理照发留证。
4. 会话事件投影过滤诊断帧（USER 白名单），NODE_FAILED 断言以 DB run 表为准（脚本内 psql 实证）。
5. 端到端票据为本地铸造 HS256（M5-2 先例，服务端同密钥真实验签）；模型出话为本地桩。
