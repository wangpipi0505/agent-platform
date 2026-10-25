# 算法适配器隔离协议验收规划

本项是 v1.2 T11/G2 算法适配器的后续落地准备。用户已明确没有专用 K8s 环境，要求继续实现并用本新工程的隔离端点验证。当前 skill 模块由 MCP 返修作者持有；本任务只读源码及固定 SDK，产出计划，不修改或测试生产模块。

## 绑定约束

- 仅访问 `G:/207/项目/算法重组工具/代码/agent-platform` 的源码/配置/工具；不得查看或使用相邻旧代码、配置、数据、进程。
- 固定 Java21、Boot3.5.0、Kubernetes Java Client23.0.0；跨服务 HTTP；PG 为业务权威。
- 凭据仅 secret_ref，不输出本地明文秘密；不读宿主 kubeconfig、Docker/已有集群。
- 原四份契约及原设计正文不修改。确定性 Job 名来自稳定幂等键；提交前登记/绑定；W2 对账不自动新建 Job；当前守卫仍须成立；技能只写执行事实，引擎提交逻辑结果与事件。
- 不把 isolated HTTP peer 说成真实 K8s 运行、算法容器成功或生产验收；不能据测试数量宣布 T11 完成。

## 阅读范围与产出

先读本工程 AGENTS.md、docs/implementation-brief.md；设计只需 contracts/snapshot/detailed-design-v1.2.md 的算法/三窗口相关段落（431、995–1071、1123–1145、1336–1338、1757 附近），不需整份计划。读取 AlgorithmAdapter、SkillAdapters、SkillExecution 对账 ALGORITHM 分支、PgStore/external_job_binding 迁移及已有相关测试。当前没有完整 Git HEAD，不能用空 git diff 推断变更或验证。

以本地固定 SDK API/源码与许可证核对 Job read/create/status、Pod list/log 相关调用；本地没有源码时可读取官方固定版本来源，只依赖一手资料。不要更换 SDK 或自行开发 HTTP Kubernetes 框架。

产出 `docs/evidence/algorithm-protocol-implementation-plan.md`：

1. 实际现状与确定的覆盖缺口，标明文件/入口及证据；区分静态推断与亲跑结果。
2. 最小实现范围、持久绑定/Job UID 与 owner 身份核验、确定性重用、queryOnly 无创建、时限和日志字节限额、错误/未知语义、真实发送/取消事实区分的接口影响。
3. 有限隔离 HTTP API fixture + 专用 PG 测试库的关键红绿矩阵、迁移兼容、新建/ACK未知/同名冲突/错误归属/完成失败/有界日志等；不创建真正容器或集群，不请求高负载或资源耗尽探针。
4. 与 MCP 当前修复、G5 调度、未来 expectedAttempt/单次许可、取消三态的依赖顺序；哪些必须一起改，哪些后续单独验收。
5. 精确拟修改文件与测试命令、固定 SDK 已读方法/许可证来源、验收标准和未覆盖外部条件。

只写上述计划和必要的独立读源记录；不运行 Maven、不改生产/测试/POM/原始报告，不启停服务，不派子 agent。最终只返回 DONE/待解决问题、计划路径及简短结论。
