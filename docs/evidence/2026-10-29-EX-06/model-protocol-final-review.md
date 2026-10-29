# EX-06 模型纯协议实跑终审

审查日期：2026-10-29。独立复核者：artifact_boundary_review；不是 fixture、driver 或两行修复作者。本次只读源码、归档与既有实际回执，仅新增本文；未执行 driver、Node、Java、Maven、HTTP、PG 或进程操作，未修改产品、脚本、主入口或 Git index。未使用 Superpower。

**结论：接受模型纯协议子步。** `model-protocol-20261029-02` 在 Windows PowerShell 5.1 下实际 native exit 0、63 条断言通过，两个本次登记 PID 与监听端口均已退出，source/context 稳定。`01` 的 62 条通过与最后退出断言失败完整保留，不能追认为通过。这个结论不代表 AP 资源准备、真实模型服务集成、三应用评估发布、J/S 联合业务验收或 EX-06 全卡完成；两轮均明确 `businessValidationPerformed=false`。

## 1. 精确源码与断言未弱化

| 对象 | SHA-256 |
| --- | --- |
| 01 归档 `source/Test-JointProtocol.ps1` | `26107BC6E05F1DB85FDA28F6C3F02CFF34C3F60C0057450619E8A82C2CBFF4C0` |
| 02/current `Test-JointProtocol.ps1` | `FBEB113D01506B6A7429B51C3F02EF9F253B14B6F90348544658C31FB5213298` |
| 两轮 fixture `Joint-ProtocolFixture.mjs` | `BCFA907779DD98CB69A26A8DE9D20D4E048ACEBC6CBDF2E38255F2ECCA5FF401` |

01 的 `source/` 五文件均重新计算哈希，与其 `source-before.json` 一致。02 `source-before.json` 与 `source-after.json` 全文相同。五个冻结对象为 Common、Prepare-Joint、fixture、Test-JointProtocol、Test-JointPreparation。

从当前 driver 去掉下列**两整行**后，其余全文与 01 的归档 driver 逐字相同；这是直接比较结果，而非仅依据差异摘要：

```powershell
$childHandle=$child.Handle # Cache the live process handle for reliable PS5 exit-code retrieval.
$modelChildHandle=$modelChild.Handle # Keep the process object handle available after exit.
```

两行分别紧跟对应 `Start-Process -PassThru`。读取 `.Handle` 的作用是在子进程存活时让同一 Process 对象取得并缓存句柄，供退出后查询退出码；不是把未知退出码转换为成功。没有按 PID 重取另一进程，也没有引入 null→0、仅 HasExited 放行或取消退出码检查。原 `$modelStopped=$modelChild.WaitForExit(3000)` 和 `$modelStopped -and $modelChild.ExitCode -eq 0` 断言逐字保留，原 25 条与新 38 条均未弱化。

## 2. 01 的真实失败及采集边界

实读 `model-protocol-20261029-01/protocol-checks.json` 与私有 `.local/acceptance/model-protocol-20261029-01/protocol-checks.json`，二者字节哈希均为：

`33165BED834C0AE60D45FE8A43724FC3DE454702F20DF224294E7FBA40B93C02`

原件为 `PROTOCOL_ASSERTION_FAILED`、`passed=false`、62 条通过，最后已通过项为 `authorized model stop acknowledged`；`modelStopped=true`，失败为 `ASSERTION_FAILED:model process exits within finite stop budget`。两个子进程 stderr 文件均为 0 字节。62 条成功与正常停止路径不足以单独证明原模型进程退出码为 0。

**01 original ExitCode 没有记录。** 原 protocol-checks 不含该值，`recovered-summary.json` 也明确 `exitCodeValueNotCaptured=true`。此前“ExitCode=null”的诊断没有可由该原件独立复原的数值证据；本报告不据此追认原退出码，也不把后续 02 的成功回填到 01。

原 collector 的 `$ErrorActionPreference='Stop'` 在接收 PS5 native stderr 时中断外层采集，01 的 `driver.log` 为空且没有常规完整 summary。`recovered-summary.json` 明确标识 `collectionRecovered=true`、nativeExit 1、62/FAILED，并说明中断原因；后来观察的 PID 5088、25632 均不存在，字段为 `absentNow`。这是恢复回执，不冒充未经中断的原 collector 完整输出，也不据此声称已经保存 01 的端口退出检查或原始 ExitCode。原 private/public protocol facts 未改写。

另有更早一次 **collector 预执行路径错误**：主线程当时将 `scripts/acceptance/Prepare-Joint.ps1` 误写为 `scripts/acceptance/Prepare-JointAcceptance.ps1`，在 Sources/Get-FileHash/Resolve-Path 报路径不存在、创建 run 目录之前停止。该事实来自主线程本会话工具输出说明；没有本地落盘原件，本审查不声称读到了该原件、不编造日志或 SHA。路径修正后才有上述 01 实际协议执行，不能将预执行停止算作第三次业务或协议运行。

## 3. 02 同断言完整通过

实读 `model-protocol-20261029-02/{command,source-before,source-after,protocol-checks,summary}.json` 及 `driver.log`，并与私有原件比对：

| 事实 | 实读结果 |
| --- | --- |
| runtime | Windows PowerShell `5.1.26100.9711` |
| driver | `PROTOCOL_CHECKS_PASSED count=63; businessValidationPerformed=false` |
| native / result | nativeExit 0；passed true；63 条 |
| 第 63 条 | `model process exits within finite stop budget` |
| 原前 62 条 | 与 01 的 62 项按顺序逐字一致 |
| model stop | `modelStopped=true`；保留原有限等待与 ExitCode 0 条件 |
| Protocol child | PID 57500，端口 54457；采集器记录 PID 不存在且无监听 |
| Model child | PID 50072，端口 54472；采集器记录 PID 不存在且无监听 |
| stderr | 两子进程均 0 字节 |
| source / context | sourceStable=true，contextStable=true |
| scope flags | protocolFixtureOnly=true，modelFixtureOnly=true，businessValidationPerformed=false |

02 两个端口/PID 与各自私有 ready registration 逐项一致；本审查未再次查询当前进程或网络，将退出事实准确归于实际 collector 的当时采集。02 public/private protocol-checks 哈希均为 `95398EE62B235310322367F1FA86731357DB6BF48975D304678724DD8FDDB86A`，并匹配 summary 中的 protocolChecksSha256。

两轮 command 均记录 HEAD `1cc453388950ec90eba51a1484994208c78d67fd`、同一环境文件哈希、`environmentImported=false`。02 collector 在 native driver 调用期间使用 ErrorAction Continue，紧接着读取 `$LASTEXITCODE`，再恢复 Stop；其 gate 同时要求 native 0、passed、63、modelStopped、两个 PID/端口退场及 source/context 稳定。这个采集修复允许保存失败回执，没有放宽驱动断言。02 command 的 collector SHA 为 `1700944D1AB59D1F22A34D501B1917B881A25B3D26D89BD2C7EBACC6CB0C2A87`；01 为 `AD39EC9631C2A3EAA1BB5B2978DABBC522853A7645530195FE162DBF9F385303`，两者不混同。

## 4. 接受范围

源码仅两行句柄获取、全部原断言保留、01 失败保留、02 实际全部通过，足以接受此次 PS5 退出码采集修复和模型纯协议子步。01 的具体未采集退出码及当时内部句柄状态不能事后恢复，本报告不作更强根因断言。

仍沿用 [静态独审](model-protocol-review.md) 的边界：显式 Model 角色仅提供固定非流式、空参数工具调用、health_probe、双轮终答、安全拒绝、未知输入拒绝与有界 nonce 停机协议；63 条通过不证明 Spring AI 实际适配器已经接通，也不证明 AP provider/skill/app、真实 OR 调用、评估发布及 J1–J9/S1 完成。AP 执行阶段仍须按其已登记环境与业务门另行验收。
