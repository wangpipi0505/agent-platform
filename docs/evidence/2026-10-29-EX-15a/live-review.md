# EX-15a 正式重启采集器静态独立复核

2026-10-29，审阅者 `/root`，作者 `/root/ex04_pg_scope`。逐段读取PS与Java探针，并对照真实ArtifactController、ArtifactStore、DevAuthentication、启动/停止脚本及两服务POM；不以静态检查预签live通过。

准入版本：`live/run-live.ps1` SHA `827899A5C12903ED85BFAFDD51014E3CD8520132CCAA2D06CD8DD2732792F49F`；`ArtifactLiveProbe.java` SHA `F5E5954CF26304058862E0A74567A49746962636EC14D8841525E36F7CAED91C`；README SHA `60EE9252B3CB3C83B174E453797559CD19A56DC8D1F03E51213248882088F130`。作者实际PS5.1 parser为0错误，原脚本UTF-8 BOM保持。

审查修正已复核：既有日志ACL先去除全部原访问规则，再仅当前用户/SYSTEM；gateway无JDBC/Flyway依赖，不能因YAML出现配置便要求不存在的history。新库只读取真实skill迁移历史，gateway仅记录精确to_regclass存在性。绿测准入已验证实际11P summary、native、XML和对应前后GatewayController SHA。

明确边界：本轮唯一空库，先核AP实例/数据目录/owner、两旧基础库已存在，限定两服务正式package且不clean/install；所有source/JAR/class/installed依赖/HEAD前后固定。仅本次PID+CreationDate+JDK+JAR登记进程可停止；旧日志覆盖前私有备份，未知监听拒绝接管。无Bearer/坏签名必须401且零行，再合法64MiB流上传/下载，伪造身份头不得改变PG身份；真正退出skill并启动新PID，gateway保持后同artifact读回，长度/摘要一致。精确行到期后等待正式scheduler，严格NoSuchKey链；不cleanTenant/直接删对象/删除数据库。

root已独立确认当前gateway定向从7P4F到同11断言11P，源码75EF9A91…；另有`algorithm_interrupt_readonly`对最小产品差异及原始XML的独立准入。可在唯一实际窗口运行本采集器；实际构建/启动/重启/清理任一失败均保留原件并定位，不放宽断言。完整gateway模块相对回归仍须另跑，live通过不能代替该门或关闭EX-15a。
