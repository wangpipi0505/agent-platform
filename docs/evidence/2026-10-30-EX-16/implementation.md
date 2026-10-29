# EX-16 取消生产接线实现

已实现 run PG 取消提交后的有界技能出站通知、精确租户/主体/run/call/attempt/version 权威核验、排队至启动栅栏、同代进程树取消和收据。迟到结果继续使用原提交栅栏，不回滚 CANCELLED。输出交接后取消仅补偿本批新对象。沿原服务目录、身份、工具事实和租约，不增表/新调度器。

最多两轮：

- 首轮 skill `ProcessCancellationChainTest` 4 FAIL，engine `EngineProcessCancellationTest` 2 FAIL；均 nativeExit=1、新 XML、源稳定。目录 `.local/ex16-skill-cancel-run/cancel-first-20261030-02/` 与 `.local/ex16-engine-cancel-run/engine-cancel-first-20261030-01/`。前一空目录01仅收集器路径准入失败，未启动 Maven，不作为执行轮次或业务红。
- 第二轮先编译安装本仓 admin-common 新目录资源（skipTests、exit0），再 `mvn -B -ntp -fae -pl engine-runtime,skill-registry test -Dtest=cn.agentplatform.engine.infra.EngineProcessCancellationTest,cn.agentplatform.skill.application.ProcessCancellationChainTest,cn.agentplatform.skill.infra.ProcessAdapterTest`：17 PASS（2+4+11），nativeExit=0、3份新XML、10个选定源稳定。原11控制覆盖超时、中文空格目录、旧入口等。完整参数 `.local/ex16-cancel-second/cancel-second-20261030-01/command.json`；同轮契约门禁 exit0。不追加第三轮/全仓回归。

ROOT 完成一次非作者轻量检查，并在第二轮前补输出交接后取消的精确批次补偿。引擎用真实 PG 和签名接收 peer，接收方独立连接确认 CANCELLED 已提交；技能用真实 PG、签名权威 peer 和真实双层 JVM。两组局部链路不能冒称同一套正式服务的端到端取消。

范围8生产/目录、2新测试、原 PROCESS 工程契约与本记录。回滚本地提交可撤出站/入口/句柄改动，PG历史终态与事实保留；退化为仅取消平台状态要显式告知。

仍待验/接续：正式服务联合、真实算法worker、跨实例句柄路由、IAM delegated 出站（当前明确拒绝 legacy transport）、外层事务尚未提交时的后续通知、输出交接取消补偿专项场景。收据始终 remoteTerminationConfirmed=false，不夸大远端结束。EX-16实现已交，整卡保持进行中；不阻塞后续实现。
