# EX-20 显式模板、冻结正文与评测留痕

已实现服务端精确模板sha固定、创建/编辑选择、显式主来源、候选正文双hash核验、评测PG留痕和恢复复用。旧内联及附加顺序保持；IAM初始CHAT仅增受核单引用材料，不接受自报正文，不扩工具权限。复用原模板/候选/评测持久结构，无迁移或新模板框架。

仅两轮：

- 首轮app/monitor五项=1 PASS /4 FAIL，engine两项=2 FAIL；nativeExit=1，全部新XML/源稳定、0 ERROR/SKIP。`.local/ex20-template-run/template-first-20261030-01/`及`template-engine-first-20261030-01/`。保留旧兼容控制，不制造全红。
- 第二轮 `mvn -B -ntp -fae -pl app-management,engine-runtime,monitor test -Dtest=cn.agentplatform.app.application.PromptTemplateCreationTest,cn.agentplatform.app.application.TemplateDigestTest,cn.agentplatform.engine.application.PromptTemplateSourceTest,cn.agentplatform.engine.application.PromptFreezeDigestTest,cn.agentplatform.monitor.application.PromptTemplateEvaluationRefTest,cn.agentplatform.monitor.application.EvaluationSemanticDigestTest`：22 PASS（新7+原15）、nativeExit=0、6份新XML、选定源稳定。同轮原契约门禁、vue-tsc及admin vite build均0。完整参数/结果`.local/ex20-template-run/template-second-20261030-01/`，不第三轮/全仓回归。

app/monitor使用真实专用PG及HTTP协议peer，报告在实际PG落库后恢复；engine新例验证主段/旧顺序、有限材料及摘要拒绝。协议peer不冒称真实外部模型/IAM。ROOT一次非作者轻量检查，核引用与正文实际TemplateService输出字段、可信来源、共享RunService保留及UI实际提交字段，无阻断问题。新注释遵循中文不变量说明。

范围10生产、3新测试、源码约定与本记录，共15文件。RunService仅模板小hunk，已有快照/取消/观测/trace保持。`VITE_PROMPT_TEMPLATE_SELECTION=false`为已实现构建时UI回滚键；历史引用/快照不删除。

仍待真实浏览器、服务重建后的live文档及外部模型/IAM联合，不把22P外推成整个真实业务验收。源码实现已交，后续按台账继续。
