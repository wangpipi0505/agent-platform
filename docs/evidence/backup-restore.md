# 独立数据库备份与恢复验证

2026-10-20 03:48，实际执行新工程 `scripts/backup-database.ps1` 与 `scripts/restore-database.ps1`。源库仅 `127.0.0.1:25432/agent_platform_dev_v12`，恢复到新建 `agent_platform_restore_20261020_034851`，pg_restore 单事务、遇错停止，退出0，恢复后发现44张业务表。运行库连接未改变。原始日志为被忽略的 `.local/backup-proof.log`、`.local/restore-proof.log`，备份清单含SHA256、字节数与UTC时间。

脚本核对专用PG的data_directory；只接受本工程备份目录、固定源库和restore前缀新库。目标已存在时拒绝，没有覆盖、清空或删除分支；恢复前核验备份摘要。备份与恢复脚本不接管其他实例，不将SQL或明文凭据打印到日志。

本次证明数据库快照可独立恢复，不代表完整灾备演练通过。PG中的secret_ref仍依赖加密vault及独立master key，对象附件还依赖MinIO；vault密文、master key与对象数据需按部署运维方案分别受保护地备份与演练，不能仅凭此dump恢复全部外部服务能力。未启动恢复库上的业务服务，避免其outbox、定时器或恢复任务意外外发。RPO/RTO、跨机器恢复与生产备份保留期仍待专用环境验收。

## V501统计快照迁移后的追加恢复

05:25:46生成新的运行库一致性备份，目录`.local/backups/20261020-052546-417`；05:25:53实际恢复至全新`agent_platform_restore_20261020_052547`，pg_restore退出0，业务表**47张**，覆盖新增的统计持久快照表。备份SHA256 `feb5e2f8f53e5071329cf3bc048b35b7451632b121f65533c8269d248eed78b2`，恢复结果JSON留在该备份目录。未切换运行库配置，未启动恢复库上的后台任务。该追加仍仅证明PG恢复；对象/vault/master key、生产RPO/RTO和跨机演练边界均保持。

## 输入集与对账证明迁移后的追加恢复

06:51:11生成新一致性备份 `.local/backups/20261020-065111-218`，349933字节，SHA256 `de61c6568662397d8cd2cd9342aedb5da11b2f91a6d9fa21bb37ea0df614eba6`。实际恢复至新建 `agent_platform_restore_20261020_065112`，单事务退出0，5个业务schema内51张表。该计数包括各schema的Flyway元数据表，不应解释为51张纯业务实体表；前文44/47也按相同schema计数方式保留为历史记录。

本次覆盖V214命名输入、V309回调与V310不可变证明、V502数据集来源；恢复日志 `.local/input-proof-backup-restore.log`，清单与恢复JSON在该备份目录。随后只读检查恢复库中的证明SHA、命名输入行及engine/skill/monitor迁移版本，结果保留 `.local/input-proof-restored-data.json`。运行库配置未切换，恢复库没有启动业务进程或定时任务。此追加仍只证明PG快照恢复，不将其升级为对象/vault/master key一致灾备或生产RPO/RTO验收。

## 共享Schema与V215后的追加恢复

08:43:42一致性备份 .local/backups/20261020-084342-272，370092字节，SHA256 b5382edb4c1141354860065dfd8e5017cc73f304f4a53b76575ebc14e012ab81。实际单事务恢复至全新agent_platform_restore_20261020_084404，退出0；只读核对51表（含Flyway）、engine215/skill310/monitor502、命名输入3行、原不可变证明SHA及触发器1，见schema-scheduler-restored-data.json。运行配置未改变，恢复库不启动业务服务。仍仅PG，不替代对象/vault/master key一致灾备或RPO/RTO。

## 算法V311后的追加恢复

10:51:03实际备份 `.local/backups/20261020-105103-166`，373385字节，SHA256 `d9f4bec3e0ea2f5a93dc86cc81c52fb41fe066852baff0fe9701e9a647b3f54c`。单事务恢复至全新 `agent_platform_restore_20261020_105107`，退出0；随后以显式只读事务核对52表（含Flyway）、engine215/skill311/monitor502、原证明SHA、命名输入3行、原proof不可变触发器，以及V311新增两唯一索引/算法历史不可变触发器。结果见 `algorithm-restored-data.json`。没有切换运行连接或在恢复库启动服务；仍仅证明PG快照恢复，不包含MinIO/vault/master key一致灾备或生产RPO/RTO。
