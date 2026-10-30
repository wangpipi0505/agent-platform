# EX-07：J1/J2 绑定协议执行器（2026-10-30）

## 为什么做

手册 §3.2 给了一段 J1/J2 的内存探针，但它是"复制粘贴到控制台、参数靠 `Read-Host` 手输"的形态：
既不能重复执行，也无法区分"前置没满足"和"协议失败"——后者会让"没跑"看起来像"没通过"。
把它固化成脚本，外部输入到位时只剩一条命令；输入没到位时脚本自己说清缺什么。

## 做了什么

新增 `scripts/acceptance/joint-j1-j2.ps1`（PowerShell 5.1 与 7 均可）：

| 步骤 | 断言 |
| --- | --- |
| 前置检查 | 缺 `-SpaceId`/`-ReleaseId`（须 UUID）/`-AppCode`/两把 key 时输出 `[BLOCKED]` 并逐条列出，**不发任何请求**，退出码 3 |
| J1 负例 | 只读服务账号 POST 内部绑定面 → `403 NO_PERMISSION` |
| J1 无副作用 | 紧接着用只读凭据 GET 内部查询 → `404 BINDING_NOT_FOUND`（证明被拒的写没有留下绑定） |
| J2 首次写 | binder POST → `200`、`changed=true`、有 `bindingId` |
| J2 幂等 | binder 重复 POST → `200`、`changed=false`、`bindingId` 与首次相同 |
| J2 读回 | 只读凭据 GET → `200`、`bindingId`/`releaseId` 与首次一致 |

退出码：`0` 通过 / `1` 断言失败 / `3` 前置未满足。证据 JSON 只含脱敏输入与四段结果，
**不含 key、不含请求头**，并附两条边界说明（不等于 J9；J3～J8 未执行）。

## 已验证的两条路径（真实执行）

```
& joint-j1-j2.ps1                 → [BLOCKED] 列出 5 项缺失，EXIT=3
& joint-j1-j2.ps1 -SpaceId … -ReleaseId … -AppCode ex07-probe -OntologyBase http://127.0.0.1:1 -EvidenceDir .local/j1j2-evidence
                                  → J1/J2 FAIL（连接失败），EXIT=1，证据 JSON 落盘
```

另外用 `[System.Management.Automation.Language.Parser]::ParseFile` 校验语法 → `PARSE_OK`；
对证据 JSON 检查 `dummy-` 字面量 → 0 命中（未泄露 key）。

## 还缺什么（所以本卡不关闭）

1. 真实 `spaceId`/`releaseId`/`appCode` 与两把 `ontosa_` 服务账号 key（只读、binder 各一）；
2. 本体侧 18182 就绪且该 release 为 READY；
3. 真正的 J3～J8 与 J9（平台 ReleasePins 实际发送）仍未验——本脚本只覆盖绑定协议本身。
