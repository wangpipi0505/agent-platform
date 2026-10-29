# EX-15a skill 回归采集器静态复核

2026-10-29，审阅者 `/root`，作者 `/root/ex04_pg_scope`。仅审采集器三文件；本报告不是运行结果，也不关闭整卡。

- `collector/run-regression.ps1` SHA256：`25681F23901F3ED2290A4F3AE772C761EE1FB13DCC020FD70803B57418C850A2`。
- `collector/manifest.json` SHA256：`E2041FFD36FF25A74790DD6888B2A37DFDB920E07F7F48766B48FB647D5086BC`。
- 已逐段阅读脚本，另行核全部 manifest pins 无差异，语法解析零错误；作者另有实际 PowerShell 5.1 静态解析记录。
- 基线为 EX-04 最终 689 个身份，新增 9 项合计 698；相邻固定 5 类 32 项。状态、类集合、异常类型/cause/首项目帧以及原文摘要均比较，四旧 skip 保留。唯一已知迁移动态消息仅限准确身份及两 UUID/两 timestamp，原件和 raw 差异保留，不泛化处理其它原因。
- 150 源仅准五文件差异；定向 16P/11P 的同源结果及环境、installed common JAR、当前运行前后 HEAD 均核对。Full 的 failure.ignore 只用于收齐原失败，不能声称全绿；Adjacent 不忽略失败。
- PG 五个已登记库的归属/历史和本仓实例前后只读检查；原两个 JUnit 迁移夹具重放边界沿用，不由采集器发 DDL、建库或清队列。MinIO 只做健康检查。
- 原日志/XML逐份归档、无自动忽略/stage/清理，错误阶段及原生退出码单列。读取旧 reader，不执行旧 674 入口。

结论：允许主线程串行执行一轮 Full，归还窗口后执行 Adjacent。若任何门不满足，保留本轮原件并定位；本静审不豁免实际新红、真正 JVM 重启或网关上传验证。运行结果以各 run 的 summary/native/XML 为准。
