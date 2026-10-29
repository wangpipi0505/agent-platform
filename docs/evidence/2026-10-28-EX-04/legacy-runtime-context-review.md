# EX-04 既有 V222 runtime 残留独立复核

- 评审者：`/root/ex04_pg_scope`，2026-10-29；未参与 EX-04 产品实现。
- 操作范围：只读本仓源码、Git 路径清单、当前构建文件及历史证据；本次未运行 Java/Maven/PG/HTTP，未修改代码、构建文件、迁移、collector 或 index。唯一新增本报告；不使用 superpower。
- 触发：ROOT 的严格 PG preflight 在 `agent_platform_engine_test` 识别历史 `V222__run_context_pack.sql` 为非当前源码迁移并停止；没有以该失败冒充产品红测，也没有继续 Maven。

## 已独立确认的事实

1. `engine-runtime/src/main/resources/db/migration` 当前有 V200～V221、V223，源码树与 Git 跟踪路径均没有 V222；EX-04 的 4 生产＋4 测试白名单没有任何迁移。
2. `engine-runtime/target/classes/db/migration/V222__run_context_pack.sql` 当前实际存在，**286 字节**，SHA-256 **`419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`**，文件系统 LastWriteTime 为 `2026-10-28 00:00:41`。mtime 只作文件观察，不作为不可伪造历史证明。
3. 文件内容为两行 PLAT-13 审计注释及 `alter table engine.run add column if not exists context_pack jsonb;`；本报告不执行、不复制成源码迁移、不据此回填列或改历史。
4. 早于本轮的 `docs/evidence/2026-10-28-PLAT-15-prepare-execute/README.md:138` 已明确记录：V223 先行、V221 夹具 outOfOrder 引导，以及 `target/classes/db/migration/V222__run_context_pack.sql` 是并行泳道残留、源码无 V222、当时未触碰。该 README 当前 SHA-256 为 **`b68c8ce24856054caf518d46fed7def58ca15f956105f66462d3b932fd3b7628`**。
5. ROOT 现有 `collector/inputs/full-engine-candidate/pg-before-error.json` 如实保留初次拒绝：固定库 `agent_platform_engine_test`、historyRows=25，唯一列出的 badHistory 为 rank22/version222/typeSQL/script `V222__run_context_pack.sql`/installedBy `agent_v12`/success=true。这是 ROOT 实际读取的归档；本评审者没有独立连接 PG，尚未自行确认该行 checksum。
6. 对原 EX-36 `after-module-20261028-230322-842` 的已归档 XML/log 检索未发现 V222 未解析或 checksum mismatch 文案。**这只证明原运行没有报告该错误，不能倒推原时点 target 字节与当前 SHA 已一致**；原 source manifest 不包含这个构建残留。不能事后补造历史 runtime 指纹。

## 对本轮限定准入的结论

可接受 ROOT 在保留原失败回执后，为本轮同环境回归追加**唯一已知 runtime 历史项**，但最终准入须同时满足下列实际证据，不把未知迁移批量放行：

- 精确 path、文件名、version `222`、type `SQL`、installedBy `agent_v12`、success=true；现有 target SHA 必须等于上述 64 位值。不能只按 `V222*`、任意 target 文件或任意成功历史准入。
- ROOT 只读核对实际 PG 历史 checksum 与当前 target 文件按当前 Flyway 算法得到的 checksum 一致，并归档算法/来源和计算结果。SHA 与 Flyway checksum 分别记录，两者不能互相替代。
- 其余 SQL 仍仅接纳当前项目源码迁移；SCHEMA 行继续执行原 rank0/versionNULL/精确 schema 引号名/owner/success 检查。未知 type、脚本、checksum 或 owner 继续拒绝。
- 仅复用现有构建上下文；不 `clean`、`repair`、补写源码 V222、删除 target、改历史行、改 schema 或预创建数据库。未提前执行 SQL，collector 保持只读。
- 在 Maven 前后分别冻结当前 runtime 文件 SHA，任何变化不再沿用该 admission。实际命令参数、原测试断言、fork/order/timeout/skip 及原失败原因对照保持不变。

这属于解释并固定原测试运行上下文，不是产品修复，也不是放宽 JUnit 断言；不能宣称“纯源码 clean 构建验证通过”。本轮全模块能否完成仍由实际回归、方法与原因差异、独立终审决定。本报告只签静态事实与上述条件，不预签实际 checksum/回归结果。

## 记录与后续归属

- EX-04 的 README/完整回归结果明确写出：当前环境复验复用已存在的历史 V222 runtime 残留；原 preflight 停止记录不覆盖；这是重现范围限制，不将其计为 EX-04 新迁移或新增产品缺陷修复。
- 三份主入口在 EX-04 回执链接本报告，并在 EX-27/Y-4 剩余项明确承接“源码/构建产物/测试库历史的一致性”。EX-27 仍进行中，不能以 EX-04 当前环境回归通过关闭该遗留项。
- 后续消除残留必须先登记准确白名单和历史库迁移策略，单独核 fresh build 与既有测试库的差异，再运行受影响回归；不能在 EX-04 临时删除构建产物、修改 Flyway 历史或补迁移来消除错误。
- 本报告补充 `engine-full-pg-scope.md` 中“当前源码 SQL 历史”的准入说明；该旧报告与原严格拒绝回执保持原件，不倒改为事先已知此异常。
