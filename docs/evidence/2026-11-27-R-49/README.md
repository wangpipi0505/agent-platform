# R-49 冻结代码在 E2 的 JUnit 回归
测试类由远端冻结提交 44d9f906 在空目录编译，E2 以 PostgreSQL 16 临时容器和同网络空间的 JDK 21 运行，测试使用独立数据库与回环模型。设置 ENGINE_PG_TEST=true，JUnit Platform Launcher 执行 cn.agentplatform.engine.infra.ModelFailureTaskPostgresTest。确认 503 与发送中断恢复两项均通过，零跳过。[JUnit 汇总](junit.txt) · [之前的红绿对照](../2026-11-26-R-49/README.md) · [本轮部署端点的不同结果](../2026-11-27-R-48/README.md)。
