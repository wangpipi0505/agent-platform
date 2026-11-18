## dev 05 回退可用性（文档化，未执行） @ 2026-11-18T07:19:59Z

- 回退唯一受支持路径 = 备份恢复（Flyway 社区版无 downgrade）。
- 回退锚点：本目录 `agent_platform_dev_v12-pre-upgrade-20261118-071945.dump`（含同名 manifest.json sha256）。
- 命令（真要回退时逐字执行）：
  `bash deploy/upgrade-20261118/rollback.sh --database agent_platform_dev_v12 --backup-file <本目录dump绝对路径> --force`
  （PowerShell：`rollback.ps1 -Database agent_platform_dev_v12 -BackupFile <dump> -Force`）
- 回退后验证：history-summary 应回到升级前（engine V200–V231 且无 app/skill schema）；
  row-counts 与 `01-pre-rowcounts.sorted` 一致。
- 本次演练**未执行**该回退：dev 库保持链头态（三模块 history 全 success + V901 种子在案）。
