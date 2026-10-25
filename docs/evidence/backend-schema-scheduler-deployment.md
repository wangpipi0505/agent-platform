# 846阶段独立开发制品与运行更新

root实际完成08:29:57单轮七模块846项，0失败/错误/跳过，262份后端src/POM前后SHA相同；见backend-schema-scheduler-reviewed-all.md。08:33:46离线package BUILD SUCCESS，强制重建普通JAR以嵌入新common；package的skipTests只用于制品，846来自此前真实测试，不混淆。首个PowerShell未引用带点-D参数被Maven当生命周期拒绝，修正引用后成功，原参数错误日志保留。

停止前核验六个旧服务的端口/PID/新工程JAR，备份旧675制品与日志；仅停止这些进程。生成六个新不可变备份JAR和SHA，均嵌同一新common、RE2/J1.8及networknt1.5.6，skill无迁移前旧SchemaWorkBudget类。逐个启动后六端口UP并再次校验PID/JAR归属。当前新主开发PID：gateway46040/app46860/engine10768/skill42116/model47796/monitor29744。制品清单、完整SHA、日志摘要及检查结果见同名json。Git仍无HEAD，不伪造提交范围。

随后真实MCP完整页面/协议、双实际engine JVM调度、异步评测页面均通过，见三个live报告。本阶段已部署于独立开发进程，不是生产发布；IAM/scope发布门禁仍BLOCKED_IAM_SCOPE_ACCEPTANCE，未做镜像/集群/容量验收。后续expectedAttempt增量源码与本846产物分开记录，未经新统一回归不称已纳入846。
