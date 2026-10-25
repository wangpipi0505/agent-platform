# 实际阅读范围

- Spring Framework 6.2.7 `DataSourceTransactionManager.doBegin/doCommit/doCleanupAfterCompletion` 与内部 `DataSourceTransactionObject.setRollbackOnly/isRollbackOnly`：事务连接绑定同 DataSource、手动 commit、真实 JDBC commit 调用以及 holder 回滚标志。N1 新写点验证同 TM/DS，使用专用顶层 RR；初始化异常标记同 holder 回滚，不会因调用方 catch 提交半份。
- `AbstractPlatformTransactionManager.handleExistingTransaction/processCommit` 与 `TransactionTemplate.execute`：默认 REQUIRED 会加入已有事务，隔离校验不是默认开启；joined execute 返回不能代表外层真实 commit。按 root 裁决拒绝 ambient worker 写与 status；没有 REQUIRES_NEW。终检位于真正顶层 execute 返回后。
- `DataSourceUtils.prepareConnectionForTransaction/resetConnectionAfterTransaction`：真实 Connection isolation/readOnly 与自动恢复。status 测试对实际 payload SELECT 记录物理连接 identity、isolation=4、readOnly=true、autoCommit=false，14 份读取同一条记录。
- `ResourceHolderSupport.setRollbackOnly/isRollbackOnly`、`JdbcTransactionObjectSupport` 的保存点检查，以及 DataSourceTransactionManager 的 holder 代理：初始化 caller catch 后仍被真实 UnexpectedRollbackException 拒绝提交。没有新增全局事务设置或生产测试 hook。
- Jackson Databind 2.19.0 `JsonNodeDeserializer._fromFloat/_fromBigDecimal`：显式 BigDecimal/BigInteger 处理；N1 复用原私有 EXACT/normalize，不经 binary double。SQL NULL 形成 JSON-null 摘要；独立保留实际 raw SQL-null flag 才能保持原 status 的 SQL NULL→{}、显式 JSON-null→null 展示。
- PostgreSQL 16.15 `mvcc.sgml` Repeatable Read 节（489–548 附近）：首个非事务控制语句建立一个快照；等待被锁行，若并发 updater 提交实际更新，RR 报 serialization failure。N1 不无界重试、不把未知数据库错误转成授权 503。真实 run→authority→node 锁让 recordCheck 的阻断 CAS 排在正在授权的动作之后；所有网络仍在事务外。

源文件、固定版本 URL、SHA 和许可见 manifest.json。Spring/Jackson 原件从已冻结的 E1b upstream 复制后逐字 SHA 复核；TransactionTemplate 与 PostgreSQL 源文档本次从对应固定 tag 下载并实际读取上述段落。这里只声称所列方法/段落，不声称阅读完整上游项目或重新审计工具 SDK 树。没有修改共享来源索引；待 root 双审后登记。
