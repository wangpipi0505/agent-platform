# EX-27 缓存登记 UTF-8 修复复核

日期：2026-10-29。范围：`scripts/verify-cache.ps1` 唯一登记 JSON 读取修复及它的前置失败、诊断、实际读回证据。结论：此单行修复及对应 Full-v2 指纹更新可接受；本结论不代表 engine 933 方法回归通过，也不关闭 EX-27。

审阅身份：修复由主线程编写，本报告作者未修改该产品脚本，独立复核其行为差异和证据。报告作者编写过 Full 采集器，因此下文 Full→Full-v2 的字节检查是版本一致性自查，不冒称对自己采集器的独立审查；主线程已另行独审 Full-v2。本轮没有运行 Java、Maven、WSL、PG、HTTP、Node、缓存验证脚本或服务，仅读取文件、Git 差异并计算指纹。未修改产品、主入口、索引或历史原件。

## 1. 原失败准确落点

原运行：`.local/ex27-v222-recovery/v222-full-933-20261029-01/`。

- `collector-error.json`：`phase=STORAGE_BEFORE`、`line=7`、`nativeExit=null`。SHA256 `13AD31F34D3806447E9276DAC10BCC0F2FA7969E5F2D0FB55F8A045629EB5401`。
- `collector-exit.json`：`collectorExit=2`、`nativeExit=null`、`testInvoked=false`、没有自动进程或数据库清理。SHA256 `608CE5CF8CBA6C9A97EDF45AD6659E0C4F05203068CA9B84FA38E4E717FDD4A8`。
- 私密 `collector-error.raw.txt` 指向 `scripts/verify-cache.ps1:7` 的“缓存登记不属于本工程”异常。此处在 TCP 客户端建立、Redis AUTH 和 WSL readlink 之前。

这是 Full 的前置工具失败，Maven/test 尚未启动，不能写成“933 实红”、新增产品测试失败或 933 方法的任何通过数量。此前 PG 只读前置可能已经执行，不能把“未启动测试”写成“没有任何前置读取”。原运行原件均保留，不用新版本覆盖或倒写为成功。

## 2. 根因是 JSON 解码，不是路径别名

原 `.local/redis-process.json` 是无 BOM 的 UTF-8 JSON，`distribution` 为 `AgentPlatformV12`，`root` 为本仓 G 盘路径下 `.local/wsl`。登记本身正确，没有改成 V 盘，也没有放宽所有者判断。

私密纯文件诊断脚本 `.local/ex27-v222-recovery/cache-record-encoding-check-20261029-01.ps1`，SHA256 `A24D2BF70076CECB4D090E4F2BC2B53ECE8F28EA5B0C51C891D2EB1FDDC8766B`，只比较旧 Get-Content 解码与严格 UTF-8 解码后的登记路径，不连接缓存。主线程记录其 PS5 输出为 `legacyRootMatches=false`、`utf8RootMatches=true`、`recordRootBytesEqual=false`、`recordHasBom=false`；PS7 两种读取均一致。

本报告作者此前也以真实 Windows PowerShell `5.1.26100.9711` 做了独立纯文件复现（工具输出 `696fed`）：默认编码为 `gb2312`；旧 Get-Content 将中文路径读坏，旧路径比较 false，`[IO.File]::ReadAllText` UTF-8 读取后同一完整路径比较 true，distribution 始终准确。这里的 `recordRootBytesEqual` 是诊断字段名，实际比较的是两次解码后的字符串，不表示磁盘原件字节被修改。

原登记指纹仍与已冻结清单一致：

| 文件 | SHA256 |
| --- | --- |
| `.local/redis-process.json` | `8E7851C732E2F17281617D4EA5BB9638CDF1CE4B22BE047DC13A97A58EF9232B` |
| `.local/minio-process.json` | `C0C5AC331ECE9A76DD4977A4B4F51FE4823ECD79AC3264BFD12B818620C16184` |

MinIO 登记同样包含 UTF-8 中文路径，但 Full 采集器已经使用 `Read-Json`→`[IO.File]::ReadAllText`，没有采用错误的 PS5 默认 Get-Content 读取。因此没有依据修改 MinIO 登记、所有者检查或路径别名。

## 3. 修复严格只改一行

Git 实际差异为 `1+ / 1−`，第 6 行登记读取只增加 `-Encoding UTF8`：

```powershell
$agentRecord=Get-Content -LiteralPath (Join-Path $agentRoot '.local/redis-process.json') -Raw -Encoding UTF8|ConvertFrom-Json
```

原脚本 SHA256：`CDA0B714C34A5DD596D5A7633B041DCD16B945A8C36DB8AA8486A04357FA11BB`。

修复脚本 SHA256：`0A2A9402D6B9D34116874ED644E8FDB956FA326642CF431910305B8AB690D920`。

纯字节反向检查：从新原始字节中仅撤销此唯一 `-Encoding UTF8`，重新计算 SHA，精确得到上述旧 SHA。故不是依赖 Git 换行显示掩盖其他改动。

第 7 行 distribution 和 GetFullPath 的完整归属比较、登记专用 WSL 发行版/二进制校验、Redis AUTH/INFO/CONFIG GET、5 秒读写超时及 finally 关闭 socket 全部未变。`-Stop` 参数及其 SHUTDOWN 分支没有改动，本次实际调用没有传 `-Stop`。没有新增 start-cache、FLUSH、CONFIG SET、队列清理、未知进程停止或路径放行。

## 4. 真实读回及补录凭据的来源

实际私密日志 `.local/ex27-v222-recovery/cache-verify-utf8-green-20261029-01.log`（275 B），SHA256 `546EBD2B1CA61D836CFBFEF3FCD1B1F57C71D74BAA6E74FD4FE2AF494918936D`。日志保留 WSL localhost 代理提示，随后实际返回：

- Redis `7.2.16`，端口 `26379`，`authenticated=True`；
- 数据目录 `/var/lib/agent-platform-redis7`；
- 发行版 `AgentPlatformV12`。

对应凭据 `.local/ex27-v222-recovery/cache-verify-utf8-green-20261029-01.json`（756 B），SHA256 `88D29F2831BF5DC3BD910434633E0DA80C5E6791B358119AFDC2EB4B4B2F7FFB`。其 `scriptSha256`、`rawLogSha256` 与实际文件吻合，结果字段与原日志一致。

该 JSON 明确标记 `recordedBy=root after actual exec tool completion`、`toolChunk=37250f`、`notAutomaticallyEmittedByVerifier=true`。`nativeExit=0` 是主线程依据真实 exec 完成结果补录，不能说成 verifier 自动生成；原日志本身没有 exit-code 行，不能仅从成功结果字段推导进程退出码。主线程向审阅者提供了该实际工具结果来源，本报告保留此来源差异，不把补录凭据重新包装为独立自动采集输出。凭据明确 `stopInvoked=false`、没有进程启停或队列清理。

这份实际读回证明单行解码修复跨过原缓存归属前置，且维持真实认证和原归属检查。它不代替 Full 的后续方法、原因或存储后置回归。

## 5. Full-v2 只使用新指纹，原版本与失败原件保留

| 版本 | runner SHA256 | manifest SHA256 |
| --- | --- | --- |
| 原 Full/run01 | `56CE06FF054ECEEAE4B87704FB7E6285352EF62D1B5CCD21906D731ECC69FC33` | `2C5ACF11ED38E5C610450406A1699F5F309A3CD4A49F1B324017468BBDB209CA` |
| Full-v2 | `9F653CD7019FCCF83BD2EA1F5FC4FFDAFD8B9BF45F384B9BDD5911E898B29E80` | `7D6D93308E366A6BC8DB14C7F5CEE358EF97C4D259959F11A974DE1E1CBABD9D` |

实际文本检查证明 runner 唯一差异是读取 `full-regression-manifest-v2.json`；所有函数逐字不变，原 PS5 parser 检查为 0 错误。新清单更新自身 runner pin、唯一 verify-cache pin，并增加旧 Full/run01 不可变原件指纹与失败归因说明。

306 个模块源来自 RED305 加唯一 V222 SQL；本次脚本修复作为此范围之外明确白名单的一行脚本变化单独固定指纹。317 个既有应用子进程 classpath 文件、Green 六项安装依赖、原 14 固定库/仅 catalog 的 dev 连接、Redis/MinIO 边界、933 条身份及 115 条严格原非通过原因、2 个类事件、14 个原未执行方法、1 GiB/900 秒预算均未放宽。没有把旧运行记为新脚本版本，也没有将新前置通过计作 Full 全绿。

报告形成时主线程已启动 Full-v2 `v222-full-933-20261029-02`，本报告不读取或提前判定其最终回归结果。最终收口必须依据该运行实际 XML、原因比较、源/依赖稳定及存储后置结果另行判断。
