# EX-06 首轮准备基础实际回执

2026-10-29。实际执行者root；docs_reader_check只读两份已存在安全JSON并逐字节归档，未执行候选、API/PG/服务。未使用Superpower。

- Windows PowerShell 5.1实际ExistingManifestCollision=4 checks/native0，All=32 checks/native0。两份回执均businessValidationPerformed=false、httpCalls=0、businessResourcesCreated=0；这是计划/静态manifest与ACL基础检查，不是资源、协议HTTP或业务实红。首轮直接绿如实记录，不故意改守卫造红。
- 当时Joint.Common.ps1实际SHA为`9b4ae0b61a18b7d6a4eb97405a60488baf5cb4aebff174281b350e94ba9eb627`；测试驱动SHA为`59a8aa9f9c6273a154c60cd848877a97a487d4c12e79ca3f4a8c62eaa0c1d1c6`。此处没有该旧Common完整字节快照，不按作者记忆补造；只引用root的实际指纹/回执。Common随后增加HTTP基础，新版须重新执行原32断言。
- root先前已读取这两个基础源；本归档不是EX-06整卡独立终审，三个入口/OR资源与AP发布/正式J/S1仍未完成。

实际命令（路径/Case取原运行，密码/令牌不存在）：

```powershell
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'G:\207\项目\算法重组工具\代码\agent-platform\docs\evidence\2026-10-29-EX-06\Test-JointPreparation.ps1' -Case ExistingManifestCollision -EvidenceDirectory 'G:\207\项目\算法重组工具\代码\agent-platform\.local\acceptance\ex06-collision-20261029-134801-941'
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'G:\207\项目\算法重组工具\代码\agent-platform\docs\evidence\2026-10-29-EX-06\Test-JointPreparation.ps1' -Case All -EvidenceDirectory 'G:\207\项目\算法重组工具\代码\agent-platform\.local\acceptance\ex06-plan-all-20261029-134821-394'
```

`manifest.json`记录原始文件路径、SHA和断言数；两份JSON按原始字节复制，没有改写阶段或数字。
