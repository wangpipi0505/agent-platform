# EX-06 第二轮基础：真实loopback HTTP与原32断言

2026-10-29，root实际执行，docs_reader_check仅逐字节归档，不运行候选/Node/API/PG/服务。实际命令显式使用Windows PowerShell 5.1.26100.9711；protocol-execution.json中的psVersion=7.6.5描述调用父shell，不是测试解释器，原始字段不改，另有protocol-invocation.json准确说明。

- `Test-JointProtocol.ps1 -EvidenceDirectory .local/acceptance/ex06-protocol-20261029-140147-590`：19 checks/native0，真实loopback HTTP封包/UTF8、403、坏JSON/业务错误、重定向不跟随、字节上限、有限超时、鉴权头隔离。协议桩只响应协议向量，不模拟OR发布或数据库；businessValidationPerformed=false。首轮绿如实记录，不伪称业务红→绿。
- `Test-JointPreparation.ps1 -Case All -EvidenceDirectory .local/acceptance/ex06-plan-final-20261029-140202-046`：原32 checks/native0，公共HTTP增补后原准备断言不放宽。
- 两命令均由`C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ...`运行。原JSON、actual command/shell/source指纹及实际保留的4源字节见本目录；manifest逐文件SHA可复算。
- 本次4源before/after相同。新资源阶段以后修改Common时仍须复跑19+32；本证据只绑定本目录4DD33262/E080D47F/B5B2DCCA/59A8AA快照，不外推新的实现。

未创建OR资源、签key、准备AP三应用或发布；最终manifest、EX-06/07整卡均未完成。未使用Superpower。
