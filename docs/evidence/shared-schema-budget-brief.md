# 引擎与技能共享 Schema 资源防护

本增量是已确认 MCP 资源边界问题的同类入口修复。G5 调度返修已独立 APPROVE，原 engine412/gateway16/25文件冻结仍作为历史。MCP 作者完成资源防护模块回归并释放 skill 后，移交本任务，避免两个服务维护不同安全算法。当前不要改任何文件，须以根任务明确移交为准。

移交：MCP资源修复270/270、08:04:44、零失败/错误/跳过，`mcp-resource-budget-source-sha256.json`固定12个源配置文件。根任务已于该冻结后明确移交common/engine/skill源码和Maven target。先读完整修复报告和清单，保留270及更早历史，不以其代替共享迁移后的回归。

兼容性决定：原 OutputPolicyTest 中存在递归本地 Schema 成功用例。本次按明确资源子集拒绝循环引用，不实现第二套递归求解器；这是一项必须独立记录的兼容性收窄，并非原例本身已被证明耗尽资源。保存原完整正例与SHA，将其改为精确拒绝断言，同时使用非循环命名anchor保留有效/无效实例两断言。不得跳过测试或把它描述为“所有旧断言不变”；后续若需要递归，须另行给出可证明有界的支持方案。原v1.2/四契约不修改，运行中旧制品尚未更新。

## 实现范围

- 只在独立新工程；不碰相邻旧代码/配置/进程/数据，不修改原设计或四契约。
- 复用 MCP 最终冻结的 SchemaWorkBudget、BoundedRegexFactory、SchemaDeadline 三个小组件，将无业务状态的实现迁至 `admin-common/src/main/java/cn/agentplatform/common/schema/`。保持 networknt1.5.6、固定 RE2/J1.8；通过现有 networknt 扩展点使用，不新增框架、线程池、子进程校验服务或自写 JSON Schema 引擎。
- admin-common 当前已有 Jackson；如共享正则适配器需要，添加固定 networknt/re2j 直接依赖。skill/engine 保留其直接使用的 networknt，去掉重复实现，调整明确 imports；不写技能↔引擎业务 JAR 依赖。MCP 传输/许可清理/UNKNOWN 不重发/HTTP异步回调证明等行为不改。
- 对 engine InputDeclarations、OutputPolicy 的 native Schema 构建和实例校验统一接入前置图预算、已审线性正则工厂、实例工作预算及有限本地 monotonic deadline。**预算须早于递归 walker、深拷贝/原生 validator 构建等潜在展开步骤；绝不执行资源耗尽探针或先初始化再捕获 OOME。** 有明确输入转换需要时，先进行有限原文结构规模检查，再转换与 Schema 图检查。
- 保留 InputDeclarations 对声明数组/对象、两个并存配置的完整校验和现有 typed/null/false/0/空容器/默认值不暗改语义；保留 OutputPolicy 的严格JSON、unused defs、onInvalid三策略/重试次数/原生判分。正常本地共享defs/anchor、properties中业务名称以及const/default/enum/examples实例literal保持原测试语义。循环引用、嵌套$id、不支持方言/RE2语法、超预算需清晰拒绝，不能静默忽略或退回Java regex/外取。
- Schema配置不合法应在收费模型调用前拒绝；输入实例超工作预算返回清晰400；模型输出超校验预算按原输出策略给出有界诊断，不崩溃、不卡住工作线程。引擎参数/业务权威、PG租约、调度/API/确认/恢复不改；G5的4生产文件和FlowDriver保持冻结SHA。
- 共享方法的错误文案适度去掉“技能”限定词，但保持调用层既有错误码；不顺手重构其他 JSON/模型/应用 Schema 入口，只报告是否有其他具体同类静态风险。

## 安全验证与证据

1. 保存本轮改前完整文件及SHA，使用根任务提供的 MCP 最新冻结清单，确认 author release；原23/396、25/412、MCP242及资源修复清单均不覆盖。
2. 先写有限安全断言红：调用纯预检/预算入口，证明输入与输出入口在过低测试预算时提前拒绝；小型共享defs/anchor/literals正常；不支持正则只测试短语法拒绝，不运行慢模式；实例预算用注入小限额/少量数据；过期deadline立即拒绝。不得构造/重放耗尽堆或长CPU载荷。编译缺入口不算行为红。
3. 补原生构建不发生/零收费调用的边界证据，若不能安全在原版执行某个风险，不声称其已行为复现；以安全拒绝断言和源码顺序核对记录。
4. 验证原 engine412（含183 Schema位置矩阵与35输入）和 skill资源修复整轮，以及 admin-common 回归；Maven共享target顺序运行。新共用依赖先 reactor compile/install 的选择由根任务和MCP作者确认，不与当前技能回归争用。
5. 完整模块前后源码SHA一致；格式/契约检查；报告 `docs/evidence/shared-schema-budget.md`，精确文件清单和可应用补丁，逐项区分亲跑/继承/静态结论。禁止用无HEAD空diff证明范围，不启停或package运行服务，不派子agent，不写全局进展/原评审报告。

冻结并释放 common/engine/skill target 后，由根任务安排安全的独立源代码、有限预算边界复核及单轮全后端回归，再固定制品做两JVM/真实MCP/前端异步纵切。本增量不授生产发布许可、不关闭完整G5/G2/T11。
