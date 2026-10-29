# EX-15a live 采集器集合转换修正：独立复核

日期：2026-10-29。复核人：独立子智能体 `/root/ex04_pg_scope`。本次只读核对主线程的三处改动，并执行 PowerShell 5.1 纯语言复现和 AST 解析；只新增本报告，没有修改 live 三源、产品、旧 run、index，没有运行 Maven/Java/PG/HTTP/服务。

结论：**三处改动精确且足以修正本次已实测的 List<object> 转数组障碍，可以进入新的唯一 run 重跑。** 该结论只覆盖采集器语言错误，不代表任何正式上传、鉴权、JVM 重启或物理清理断言已经通过；EX-15a 仍不能据此完成。

## 原失败事实与归因

原 run 为 [`ex15a-live-20261029134252-dfe888aa`](live/runs/ex15a-live-20261029134252-dfe888aa/collector-diagnosis.json)。保留的执行脚本 SHA256 为 `827899a5c12903ed85bfafdd51014e3cd8520132ccaa2d06cd8dd2732792f49f`。原诊断记载 collector native1、package native0；只读核对私有 package 日志包含 `BUILD SUCCESS`，原 failure 日志同时包含 `Argument types do not match` 和 `return @($result)`。其 SHA 与原 run 的私有原件指纹一致：

| 原件 | SHA256 |
|---|---|
| `collector-diagnosis.json` | `26b59ff8073794c16e3b088827e3e413d6184a4e0b206fed47c031baff65bbad` |
| 私有 `failure.raw.log` | `4cae3f502f624ffab8736f1da1f81f22f1dcb98928f398f704792ff89820edde` |
| 私有 `package.out.raw.log` | `7ded52d821fdcd2fbda7c85ba4c81c9894e4e17b41b2d9582723fe6a4fd37011` |
| `inputs-before.json` 与 `inputs-after.json`（字节相同） | `df6fe15fc82927a3af0d69398e528f4ba3aabddc8b40d1a2a9bfed5a233f9b06` |

原失败位于 PackagePins 返回值（原脚本第222行），控制流在建库和启动服务之前。私有目录不存在 `createdb.raw.log` 或 `initial-start.raw.log`，与诊断中的未到达建库、服务启动、产品断言相符。本复核没有连接数据库或检查进程，因此不将文件观察冒称额外运行态数据库审计。原 finally 的 `facts=@($facts)` 同类转换错误导致原 run 缺失 run.json；旧 run 保持原貌，以新增诊断说明缺失原因，没有补造旧成功记录。

## 差异核验

对原执行脚本只做下列三个字面替换，所得全文与当前脚本逐字符完全相同；两份脚本都保留 UTF-8 BOM `EF BB BF`。因此当前修正没有夹带其它控制流、准入、资源或断言变动。

| 位置 | 原表达式 | 当前表达式 |
|---|---|---|
| 第222行，PackagePins | `return @($result)` | `return $result.ToArray()` |
| 第304行，包内 class 记录 | `Save ... @($classPins)` | `Save ... $classPins.ToArray()` |
| 第363行，run.json facts | `facts=@($facts)` | `facts=$facts.ToArray()` |

当前 `run-live.ps1` SHA256：`a70b4df9f8ee3f72aab8e9c00a8dcbdf74702d42435b80643dde56b3477f0fdc`。

Java 探针与原执行副本 SHA256 同为 `f5e5954cf26304058862e0a74567a49746962636ec14d8841525e36f7caed91c`。包括无 Bearer / 坏签名严格401与零新行、合法上传的原身份断言、64 MiB 长度/SHA、同 id 重启后下载及精确 NoSuchKey 在内的原验收断言均未修改。

## PowerShell 5.1 实际最小验证

显式调用本机 `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -NonInteractive`，实际版本 `5.1.26100.9711`。仅执行内存中的集合构造、转换、JSON 序列化及对当前脚本的 AST 解析；没有执行采集器主体。

核心复现为：

```powershell
$sample = New-Object 'System.Collections.Generic.List[object]'
$sample.Add([pscustomobject]@{ordinal=0;value='item-0'})
try { $old = @($sample) } catch { $_.Exception.Message }
$new = $sample.ToArray()
```

实际以 0、1、3 个元素分别验证：

| 元素数 | `@($sample)` | `.ToArray()` |
|---:|---|---|
| 0 | `Argument types do not match` | `System.Object[]`，Length=0 |
| 1 | 同一错误 | `System.Object[]`，Length=1，值 `item-0` |
| 3 | 同一错误 | `System.Object[]`，Length=3，值 `item-0,item-1,item-2` |

同时按三处真实调用形状验证：

- 函数 `return $result.ToArray()` 后调用者收到3个对象，顺序及字段值完整。
- `SaveStyle $classPins.ToArray()` 得到包含3个对象的 JSON 数组，ordinal 与 value 完整。
- `@{facts=$facts.ToArray()}` 得到 `{"facts":[{"ordinal":0,"value":"item-0"},{"ordinal":1,"value":"item-1"},{"ordinal":2,"value":"item-2"}]}`；空 facts 精确得到 `{"facts":[]}`，可覆盖构建前失败的 finally 情形。
- 当前脚本 PowerShell 5.1 AST 解析错误数 **0**。

前次仅 AST 通过不能证明运行时泛型集合绑定成功；本次增加的最小实际语言验证补上这一点。重跑须保留本次 A70B 脚本版本，使用新 RunId，不复用或覆盖失败 run；是否完成仍以正式三阶段原断言及后置指纹全部通过为准。
