# 一次实际窗口：准入停止，测试未执行

2026-10-29。独立Windows PowerShell5.1进程执行本目录run-once.ps1；脚本先经5.1.26100.9711 ParseFile，0错误，SHA256 `45384BE7C85008DADEC692F232B7184209E6CEFCB8C4E936BFFF14994A9BC489`。未使用superpower。

本次止于准入，**没有启动Maven/Java/测试HTTP**，不是候选编译红、四项测试红/绿或一次额外PASS。外层工具命令exit=1；collector-error.json的nativeExit=null、nativeExitPersisted=false、state=ADMISSION_FAILED_NO_MAVEN。没有native-exit.json、Maven raw.log、XML或diagnostic-facts，不能补造。

实际窗口已在独立进程退出后立即明确归还root。没有重试PG/测试，没有修改源码、原断言、预算或数据库；未reset/drop/truncate、创建库、补归属标记或操作index。归还后只读已有落盘记录与文件/Git指纹并写本报告，没有继续PG/Java/HTTP命令。

## 已保存事实及缺口

- source-before.json成功写出，故HEAD=`28aa99ea12cb96bbbf781859c017acebd30647a3`、.env的AF038F68…准入比对及D64/9DEB两源比对已经走过；完整原值见脚本及指纹文件。
- pg-preflight.raw.log存在但长度0，说明至少进入过只读psql准入命令；没有pg-precondition.json，不能宣称全部PG角色、owner、schema或Flyway准入已经通过。
- 采集catch只保存异常类别`System.Management.Automation.RuntimeException`，未保存抛出行/固定准入阶段码。本次进程变量已退出，**现有落盘不足以准确区分停止在PG目录/角色、专库owner、schema或Flyway哪一门**；不能猜成数据库不属于本仓或Flyway确有问题。此为采集可定位性缺口，不是产品根因证据。
- post-stop-file-fingerprints.json是归还后的纯文件/Git元数据核查：HEAD仍28aa99e；测试仍`D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A`，ArtifactStore仍`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`，.env仍`AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A`。未输出任何配置值、密码或key。

如需继续，先由root处理准入可定位性，只能保存固定phase/安全异常类别/抛出行，不能输出任意异常消息或秘密。该准备不自动获得第二实际窗；重新准入/一次原类4项诊断需root重新交接，且另建实际日/runid保存这次停止原件。

原EX-03 full新增GET_BODY FAIL继续保留；本次没有命中原quiescent检查，不能判断产品/夹具责任，也不能覆盖或关闭EX-03回归门、EX-27子步或整卡。
