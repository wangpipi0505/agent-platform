# G2 前置增量：原尝试身份贯穿适配器

先读本文件，作为完整需求；只在新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 内工作并遵守 AGENTS.md。用户授权按 v1.2 详设持续实现，当前无模型/本体/IAM/K8s环境，继续使用隔离测试端点。不得读取或复用相邻旧工程。原四契约、工期、相邻设计正文不修改。

## 位置与前置状态

G5及共享Schema已独立关闭，root刚完成单轮后端846项（common10/gateway16/app35/engine427/skill270/model66/monitor22），262份src/POM固定；旧846制品已存 `.local/artifacts/schema-scheduler-20261020-0830` 并独立运行。真实MCP完整浏览器与协议、两个实际engine JVM调度和异步评测页面均通过。上述是历史固定阶段；你的新变更不能声称已经纳入846或已经部署。

本增量是 `engine-not-applied-retry-plan.md` 的显式 expectedAttempt 前置，先完成身份传播和原HTTP异步发送CAS。随后才做算法完整协议，再做引擎证明重试。**本次不开放UNKNOWN重置、新代许可、自动重发或重试API；不做迁移。** 全部技能路径仍维持 retryBusinessAllowed=false。保留合法只读RETRYABLE_FAILURE既有路径、计量、审批、Mock、CALLBACK、proof、MCP预算语义。

## 验收结果

1. SkillExecution首次业务执行沿原已核实请求/引擎authority的attempt，reconcile沿recorded的attempt，传入SkillAdapters，再传给HTTP同步/HTTP异步/MCP/ALGORITHM。参数进入后不可通过读取“当前binding.attempt_id”替换。不存在缺attempt、默认0、使用当前数据库代次的业务调用重载；测试调用也应显式提供有效原代。纯列工具不算业务发送，沿原独立发现入口不混入假call。
2. HttpAsyncAdapter.bind对唯一冲突完整核实原逻辑归属、attempt、版本、合同/参数/guarded参数与快照，错代不能复用绑定。invoke进入先匹配原expectedAttempt，发送CAS绑定原attempt及原principal/run/version/key/request_snapshot/合同身份；CAS失败不得发送，不以新binding补身份。ACK、查询、DONE继续用原代及快照裁决。读取之后、发送CAS之前发生换代的迟到线程必须零POST。旧submitted ACK线程在新REGISTERED行出现后恢复必须零POST。测试用显式受控测试DB事务模拟未来换代；不新增生产UNKNOWN reset能力。
3. HTTP同步/MCP/ALGO此次确保原代显式传播且无无身份业务重载，按现有真实归属与流程校验。若它们尚缺持久发送栅栏，报告必须明确“参数传播不是跨代原子发送保证”，不得为使声明漂亮偷加无审查的整套许可机制。算法全面PREPARING/UID/Pod归属/有界I/O是下一增量，不在本次冒称完成。
4. 关键失败路径先写并实际得到行为红（不能用编译失败充当行为红），再最小实现。至少：旧sender在新REGISTERED行出现、读后CAS前换代、错误主体/run/版本/key/快照/合同/args绑定、同原代并发发送仅一次、queryOnly无POST且错代无查询/写回。明确测试隔离新工程专用DB；可用测试专用独立库或既有 `agent_platform_v12` 集成测试库（它不是运行库 `agent_platform_dev_v12`），不要误称。禁止大内存/慢正则/耗尽探针。
5. 跑相关定向、完整skill模块及受影响其他模块编译/测试（仅确有调用方时）。不启停root服务、不package、不改live JAR、不改root三个smoke脚本。不要与别人并发使用skill target；目前已独占分配给你。

## 文件与证据

只允许skill-registry生产/测试源及新增自己的证据，必要实际官方/已缓存开源参考。不要改admin-common、engine、前端、root进展/覆盖/执行文档。当前 SkillAdapters 及 AlgorithmAdapter 存在多级旧invoke重载，必须搜索全部新工程调用点，兼容调整受影响测试；若超边界先报告具体原因，不静默留绕行。

完整基线源+SHA先保存；因无HEAD必须提供真实完整上下文可应用patch及冻结副本、add/modified/deleted清单SHA，不能把git diff空结果当成没改。报告 `docs/evidence/skill-expected-attempt.md`，包含红绿命令、数目、实际PG及有限HTTP端点计数、保护文件、限制和后续顺序。写完冻结交还skill target；root会独立复核、统一回归并决定制品更新。不得提交/发布或派子agent。返回简短状态、测试摘要和报告路径。
