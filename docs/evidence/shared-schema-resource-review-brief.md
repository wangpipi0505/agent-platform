# 共享 Schema 与 MCP 生命周期防护独立复核

本任务审查新工程的防护性修复及共享迁移。唯一根目录 `G:/207/项目/算法重组工具/代码/agent-platform`。不访问旧相邻工程、外部供应商/集群，不启停或package服务，不改生产/作者测试/证据，不派子agent。

## 当前依赖

先读 AGENTS、shared-schema-budget-brief.md、mcp-resource-budget.md、mcp-resource-budget-source-sha256.json；随后读共享迁移报告 shared-schema-budget.md 及其新候选清单/补丁。共享作者仍在完整回归，当前只做源代码与证据阅读，**根任务明确“target释放”后才允许定向Maven**。不要把旧位置/旧包清单在迁移后失配误报为源码漂移；应核对新候选摘要、真实前全文/补丁及保护文件。

历史skill270、engine412/common旧5和前端127不是新的完整整轮。G5原3项已独立关闭，不重审无关业务。原MCP审查曾被自动安全审核中断，无最终APPROVE；本任务仅审查修复后的防护代码与有限正常测试，不能复用或重放原检查。

## 安全方法与范围

- **只做源代码审查、普通合法小输入以及可注入低预算的拒绝测试。禁止读取/执行旧资源耗尽探针、生成复杂攻击Schema、长CPU正则、资源压力或大量请求。** 不通过改写旧检查绕过自动安全审核；如无法用安全方法核验，就明确写未验证，不跑同类危险试验。
- 验证 native 构建前的 Schema 图/原文规模预算、共享定义和引用位置、封闭外取、有限预加载、正则编译前预算及RE2无fallback、实例工作预算和deadline；以正常本地defs/anchor/literals/typed输入和短语法拒绝核对兼容性，不构造过量负载。
- 原递归Schema成功用例已完整保留在基线，作者按brief将循环明确拒绝、保留非循环anchor正反断言。评估该兼容性收窄是否被准确记录、错误清晰，不能误写全部旧断言未变；如发现设计或行为问题照实报告。
- 引擎输入在深拷贝/转换/原生构建前的有界检查；输出预算失败保持既有TERMINATE/RETRY/PASS及预算/事实语义。检查低预算只能收紧，固定硬闸不能被放宽；默认18MiB旧产物路径仍有完整回归记录。
- MCP会话许可须等实际reader/send/HttpClient退出才归还；API等待有界，慢清理不开放更多会话。以现有有限reader gate/普通连接用例检查，不把远端心跳传播延迟当线程泄漏。
- MCP写isError/输出失败/ACK未知保持UNKNOWN不重发；queryOnly不调用业务；元数据权限/精确冻结toolName/原始Schema保留。共享迁移不得改变HTTP异步/回调/证明CAS，也不得改G5四生产文件/FlowDriver或原四契约。

作者的全模块日志带前后SHA，不要求重复整轮。target移交后，只针对疑点运行有限的新定向测试或独立小边界检查；不同时跑同模块 Maven。报告区分亲跑、仅阅作者日志、静态推断、未验证。

唯一产出 `docs/evidence/review-shared-schema-resource.md`，给出本增量APPROVE/REQUEST_CHANGES与紧凑定位/影响/修复验收，不代签生产或整体功能。亲跑结果、清单匹配、必要截图/资源证据路径及最终target释放均记录。需要当前新增报告/清单时向根任务询问路径，不扩大修改范围。
