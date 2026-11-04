# AP算法共享夹具的DEV端口准备

2026-11-04，属于PREP-FIX的AP补充批，仅测试夹具，不修改产品Java或预算。

AlgorithmTestDatabase仍固定25432/agent_v12并自动访问维护库。为避免DEV回归碰旧实例，新增显式AGENT_PG_PORT与既有AGENT_DB_USER消费：只连接已预建的两种白名单算法专库，不访问维护库、不自动建库、不回退。未配置显式端口时保留原独立窗口行为。环回、精确库名和端口合法性守卫保持。

实际新准备资源：dev-ap 35432、dev_ap_test所有者、agent_platform_skill_algorithm_test及agent_platform_skill_algorithm_migration_test。创建前确认不存在，记录专库用途并拒绝PUBLIC连接；迁移重置仅限本批新建专库，不碰场景或旧25432。

## 必要检查与边界

- 没有用原夹具在旧25432发起红测；该固定访问本身就是本次禁止触及的资源。新的URL守卫3例真实通过，覆盖默认保持、显式35432、错库和非法端口拒绝。
- 第一轮同时执行原迁移类2例：1通过、1失败，0跳过；真实连接和迁移已进入断言。失败是旧HTTP快照未认识V314合法新增5个NULL列。保留全部旧字段逐字相等断言，并补5列精确键集及全部NULL检查；新列不被忽略。
- 第二轮只复跑失败方法：前述字段、证据摘要、跨租户、唯一性和Flyway.validate均已到达后，末尾旧“最高V312”断言遇到实际V320失败。将精确预期同步为当前源码最高V320，未改为大于等于或删除断言。
- 两轮已用完，最后常量同步仅编译成功，不追加第三轮；**不声明迁移类修后整类全绿**，该方法留正式回归确认。第二轮原件明确保存实际320，可核对最终预期的来源。端口/库白名单3例和首轮另一真实PG方法的通过记录保留。

原始日志：`D:/CodexPrep/algorithm-reorg/dual-20261103/logs/dev-prepare-ap-algorithm-fixture-20261104.log`、`dev-prepare-ap-algorithm-fixture-r2-20261104.log`、`dev-prepare-ap-fixture-final-compile-20261104.log`。没有重复已通过测试、调整业务断言范围或启动全量验收。
