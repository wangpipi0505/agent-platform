# 优化台账执行报告：详设问题第二批（P1-1/2/8/9、P2-14/15/17）

> 执行日期：2026-10-25。七项均为"核查后落稿"：四项核查发现实现/契约已正确（指控来自旧设计稿口径），三项以现实现为准落稿为规范。本轮**无代码变更**（核查结论均为已实现或契约已正确），落稿位置在工程外详设分册，本文件仅入库存证。

## 核查不成立（实现/契约已正确）

| 项 | 核查结论 | 证据 |
|---|---|---|
| P1-1 幂等键缺调用身份 | 幂等键已含 callSeq（`sha256(runId:nodeInstanceId:callSeq:skillVersionId)`，AgentDriver.skill），恢复沿用原 callSeq 即复用原身份 | `AgentDriver.skill` 幂等键构造 |
| P1-2 "查无记录便执行" | `tool_call.idempotency_key` UNIQUE 约束的原子插入（CAS 登记）即权威并发去重；冲突方读到既有记录按状态幂等返回（SUCCEEDED→回放结果） | `ToolCalls.register` + AgentDriver 对 REGISTERED→SUCCEEDED 的处理 |
| P2-15 数组截断静默丢数据 | 《图定义契约》L213 已是"默认 ERROR+显式 truncate 才放行"；当前引擎 `Values` 仅 `[*]` 展平取值，无任何静默截断代码 | contracts/graph-definition.md L213 |
| P2-17 不可信内容渲染 | `safeMarkdown` 已实现 DOMPurify（FORBID_TAGS：script/iframe/img/form/style）+ **URL 协议白名单 `^(?:https?:|#|/)`** | web/shared/content.ts L15-20 |

## 落稿为规范（以现实现为准）

- **P1-1/P1-2** → 前置契约册追加"C3 幂等与并发去重落稿"：callSeq 即调用身份；UNIQUE 原子插入为权威去重，无"查无记录便执行"窗口。
- **P1-8** → 32 册：requestId（RESUMED 事件载荷唯一索引去重）+ pauseSeq 乐观校验已实现；跨副本孤儿判定由 R-A5 `RunOrphanConvergence` 承接。
- **P1-9** → 32 册：确认人按 confirmRoles 解析、Mock 绑定调试快照（Authority.mock_snapshot 唯一许可）、LAST_REAL 技能侧二次核验——三项均为现实现，落稿为规范。
- **P2-14** → 10 册："×1.3 普适系数"作废；展示档 `(bytes+2)/3` 仅巡检，准入走 tokenizer 精确计量 + `model.metering_calibration` 校准 + 四档降级。
- **P2-15** → 30 册：以契约条款为准；长度对齐校验随数组映射特性建设落地。
- **P2-17** → 60 册：渲染规范固化为"必须过 safeMarkdown"；卡片动作白名单随卡片特性建设时定义（当前无该渲染面）。

## 台账影响

详设问题 18 项：完成 14（P1-1/2/6/7/8/9/10、P2-12/13/14/15/16/17/18）、待启动 4（P1-3/4/5/11，均为设计修订类）；合计 36 项完成 15、待验收 5。
