# G2 算法协议落地任务

此任务在原attempt前置独立批准并由root明确移交skill target后才能启动。唯一工作区 `G:/207/项目/算法重组工具/代码/agent-platform`；遵守AGENTS、contracts/README和v1.2快照。不得访问相邻旧业务实现/配置/存储/进程。用户已授权持续开发、无K8s等外部环境时使用新工程隔离端点验证，不再索要环境。

## 唯一需求与既定决策

读取同目录 `algorithm-protocol-implementation-plan.md`，这是本任务完整功能/失败矩阵/开源机制需求（只读计划，不是已实现证据）。按其Task A–C一起落地并交付Task D审查材料；所有关键矩阵必须有有限真实协议/PG断言，不能只做几条happy path就交付。该文件的原“未实现”、旧源码计数与迁移预留属历史，以root移交的实际源SHA为基线。原四契约和v1.2正文不动。

root已解决的交叉依赖：

- 846阶段共享Schema/MCP防护独立APPROVE、完整回归、固定制品和真实纵切已闭。不要覆盖这些代码或重新运行曾中断的资源耗尽/慢正则探针。测试资源边界只用计划中的小预算和有限短延迟。
- 原attempt前置在本任务之前独立审查；所有业务invoke已必填expectedAttempt。**不恢复任何无身份/默认0/临时读当前attempt的业务重载。** 计划3.1/4节所说“保留旧invoke重载用于HTTP/MCP测试”被此前置决策替代，测试应显式给原代；列工具仍为独立协议探测。
- 本任务技能迁移使用 **V311__algorithm_job_identity.sql**（启动时确认实际未占用）。后续引擎许可预留engineV216/skillV312，不在本轮开放UNKNOWN reset、重试许可、随机新Job或DELETE。
- 新模拟K8s服务和实际纵切runner代码放 `skill-registry/src/test` 或根 `tests/` 下，遵守本工程“模拟端点只位于测试目录”的约定。计划中 `scripts/algorithm-protocol-live.mjs` 改为 `tests/smoke-algorithm-protocol.mjs`，不能在生产入口引入模拟服务。脚本不接管root主开发服务。
- 允许写skill-registry生产/测试/迁移、新增自己的测试运行脚本与证据；不改common/engine/gateway/app/monitor生产代码、前端、root总进展/三份执行文档、历史证据或他人V文件。当前HTTP异步proof/callback语义必须回归保持。

## 实施与验收

重点是完整PREPARING登记+绑定、submitted之后单次发送CAS、完整合同/参数/tenant/principal/run/attempt/key/API/namespace身份、Job UID只写一次、可信Failed/Complete与Pod controller owner/容器核验、SDK扩展点有界元数据/日志与单调总deadline、原fact→binding短事务同代终态采纳和W1–W3。网络不跨PG事务；queryOnly无新绑定/业务POST；旧算法缺身份默认安全未知且不猜回填，HTTP旧行/proof兼容。SDK依赖本身不算协议通过。

Job安全上下文/资源/专用SA/镜像允许清单保留；实际读取固定SDK23源码/API/许可后在注释和自己的报告列明使用机制。没有真实K8s时仅证明有限HTTP协议，不能声称容器执行/RBAC/集群停止或外部效果撤销。身份摘要不是签名，其可信命名空间/RBAC前提写清。

每组先取得真实行为红，不能将编译失败当行为红；新增构造测试接缝可单独准备，再跑原行为。使用两个明确专用算法库，绝不清空运行库。Finite JDK HttpServer仅127.0.0.1随机端口/白名单路径/有限响应，检查真实POST接收计数、PG登记顺序/回滚/并发唯一、超时关闭与无迟到发送。完整skill回归必须零失败/错误/跳过，前后全部src/POM SHA一致；不要与别的agent共用target。

不package、不停root六主服务、不改或覆盖root已有smoke/报告。真实子进程纵切由root后续安排；如提供runner，明确目标、PID/端口/JAR归属、只清理自己创建的子进程、最终日志与限制，未知外部业务不能重试发送。根测试脚本可先syntax check，不冒称实际进程已通过。

## 交付

### 结果转存的总期限补充裁决

root于本轮收尾确认：算法的同一个单调时钟绝对deadline必须覆盖日志接收、MinIO上传及回读/结果验收尾段。允许为此扩展ArtifactStore算法专用入口，并纳入本轮完整差异和红绿测试；保留原HTTP/MCP入口及原有超时行为。不得关闭共享客户端、dispatcher或连接池，不把每次HTTP重设remaining timeout误当作跨分片/上传/回读的统一期限。应依据实际SDK源码选择窄实现，期限到达能取消实际I/O，确认实际资源退出后才释放占用；超时返回后不允许遗留后台转存。使用小体积、有限慢响应覆盖上传和回读，不能做资源耗尽探针。已写外部对象的清理/状态按实际事实记录，不承诺与PG形成跨系统原子回滚。

完整源基线+SHA先存，交付真实完整上下文patch、变更清单含before/after SHA及add/delete、冻结副本和实际apply验证。无HEAD，不用空git diff证明未改。写 `docs/evidence/algorithm-protocol-report.md`，逐项矩阵映射源码/测试，写红绿命令与实际计数、迁移兼容、完整回归、开源读源、保护项、限制；不在root总表代签T11。冻结后归还skill target，root独立复核、统一回归/制品/真实纵切。不得提交、发布或派子agent。
