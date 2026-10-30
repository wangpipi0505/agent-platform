# EX-07 J3 执行器自测（context-pack 拉取与摘要核对）

## 1. 这次做了什么

EX-07 的 J1–J9 需要真实跨仓运行，暂时不改代码也无法跑。J3 里唯一能在本机证明的是**本体侧能不能给出真实、结构完整、摘要可核对的知识包**，所以把手册里手输的探针固化成执行器：

- `scripts/acceptance/joint-j3.ps1`（PowerShell 5.1 兼容，零第三方依赖）：只读服务账号 `GET /onto/releases/{id}/context-pack`，断言 200、`data` 存在、四段（`conceptCards`/`termDictionary`/`relationSummaries`/`exampleQa`）都是数组、`candidateSha256` 出现时为 64 位小写十六进制；对**收到的原始字节**复算 SHA-256，给 `-ExpectSha256` 时作为通过条件。
- `scripts/acceptance/stub-context-pack.py`（仅本机测试桩）：实现同一个端点与 `X-API-Key` 校验，`--mode normal|empty|badsha` 用于验证判定逻辑。

## 2. 为什么"按原始字节"复算

手册要求"完整包摘要可复算"。如果先 `ConvertFrom-Json` 再把对象序列化回去，键序、空格、数字表示都会变，摘要必然对不上，测出来的是"重新编码一致性"而不是"包内容一致"。所以执行器拿 `response.Content.ReadAsStringAsync()` 的原文做 SHA-256，解析只用于结构断言。

## 3. 六条路径的实测结果（本机，2026-10-30 13:42–13:44）

| 场景 | 命令要点 | 结果 |
| --- | --- | --- |
| A 正常包，只观测摘要 | stub `--mode normal`，无 `-ExpectSha256` | PASS，退出 0，`observedSha256=50a0d81c…6141802d` |
| B 期望摘要与实收一致 | 同上 + `-ExpectSha256 50a0d81c…6141802d` | PASS，退出 0 |
| C 期望摘要不一致 | 同 A + `-ExpectSha256 000…0` | **FAIL**，退出 1，原因"按原始字节复算的摘要与 -ExpectSha256 不一致" |
| D 凭据不对 | stub 要求 `good-key`，传 `wrong-key` | **FAIL**，退出 1，`J3-取包 期望 200 实际 401 SERVICE_CREDENTIAL_REQUIRED` |
| E 前置缺失 | 不传 `-ReleaseId` | **BLOCKED**，退出 3，列出缺失项，且**未发出任何请求** |
| F 空包 | stub `--mode empty` | PASS，退出 0，`空段（需人工确认是否真的为空）：conceptCards,termDictionary,relationSummaries,exampleQa` |
| G `candidateSha256` 非法 | stub `--mode badsha` | **FAIL**，退出 1，原因"candidateSha256 不是 64 位小写十六进制" |

空包场景特意做成"PASS 但列出空段"而不是失败：空知识包是合法发布结果，脚本不该替业务判断"必须有内容"；但必须让人看见是哪几段为空，避免把空包当成"内容正确"。

## 4. 边界（不夸大）

- J3 **只覆盖本体侧**：证明包能取到、四段结构正确、按字节可复算。
- **不覆盖** AP 运行期的 `snapshot.contextPack`（`space`/`releaseId`/`sha256`/`fetchedAt`/`truncated`）：那需要一次声明了 `ontology` 的真实运行，链路依赖 `_运行即冻结` 与真实评测/模型，属后续步骤。
- 桩**不冒充**真实 ONTO：真实包由本体侧 `FrozenKnowledge` 产出，段内容、`candidateSha256` 语义都以真实端点为准；桩只验证脚本判定逻辑。
- `observedSha256` 在未给 `-ExpectSha256` 时只记录，不作为通过条件——否则会变成"自己算的摘要等于自己"的自证。

## 5. 复跑方式

```powershell
# 真实环境（只读 key 已由 EX-06 签发）
$env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly key>'
./scripts/acceptance/joint-j3.ps1 -OntologyBase 'http://127.0.0.1:18182' -ReleaseId '<uuid>' -ExpectSha256 '<64hex>' -EvidenceDir docs/evidence
```

```powershell
# 本机自测（桩）
python scripts/acceptance/stub-context-pack.py --port 18199 --mode normal
./scripts/acceptance/joint-j3.ps1 -OntologyBase 'http://127.0.0.1:18199' -ReleaseId 7dc4a1e2-5b3c-4f8d-9a11-2c3d4e5f6a7b
```

## 6. 踩到的坑

- 桩进程用 `pkill -f stub-context-pack.py` 在 Git Bash 里**杀不掉**原生 Windows Python；两个进程能同时 `LISTENING` 同一端口（Windows 的 `SO_REUSEADDR` 语义），于是"切到 empty 模式"实际仍由旧进程应答，测出来还是 normal 包。正确做法是按 `netstat -ano` 的 PID 用 `taskkill //PID … //F` 杀，或换端口。这一条记在证据里，避免下次再误判成"脚本判错"。
