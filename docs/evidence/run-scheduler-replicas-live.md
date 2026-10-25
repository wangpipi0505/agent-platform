# 两个实际引擎JVM的调度验证

2026-10-20，root执行 tests/smoke-run-scheduler-replicas.mjs .local/artifacts/schema-scheduler-20261020-0830/engine-runtime.jar，退出0。实际新建专用PG库agent_platform_scheduler_live_4410c11fe3；只启动自己创建的18086/18087子Java进程，产物SHA 4fb6f8e3856f73098bac0b8ca6c743f8bbf9ceaf692c6a8c14fce319e6e010e7，端口/PID/JAR归属验证后才请求。摘要、三个进程记录、日志SHA见同名summary.json。

- 节点未领取前持私有PG行锁，A取得dispatch代1后强制停止，仅在真实dispatch租约到期后由B取得代2并成功；RUN_STARTED1、START/END尝试2/提交2。
- 101个有有效节点租约的头部RUNNING条目与200个就绪尾部任务并存：尾部200/200成功、starts200/attempts400/commits400、实际两个worker owner、头部attempts0。
- 两子服务各经真实HTTP DEBUG受理并SUCCEEDED，只向私有28419应用快照peer读取2次，无模型或技能业务调用。没有依赖repository伪造双进程。
- finally仅停止本轮记录的子进程并关闭peer，保留专用库与日志，不改变主开发六服务。

本验证证明调度机会租约接管与有限队列公平，不证明在途node/外部副作用接管、正常OS优雅停止、分布式资源许可或规定500/5000/200容量。
