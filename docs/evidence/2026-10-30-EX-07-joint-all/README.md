# EX-07 联合验收统一入口（J1/J2 + J3 + J4 一次跑）

## 1. 为什么要有这个入口

J1–J4 各自是独立探针，但真实执行时的第一个问题是"**到底缺哪个输入**"：缺 `spaceId`、缺两把 key、缺 ADMIN JWT，都会让某一段跑不了。分开跑时这些信息散在四个终端里，容易被误读成"验收失败"或"验收通过"。

`scripts/acceptance/joint-all.ps1` 只做编排，不改任何单卡判定：

- 每个 suite 先检查必需输入，**缺就不调用该脚本**，记 `SKIPPED-BLOCKED` 并写明缺什么；
- 调用后原样保留各卡自己的证据目录（`<EvidenceDir>/j1`、`/j3`、`/j4`）；
- 汇总退出码：任一 `FAIL` → 1；否则任一 `BLOCKED` → 3；全 `PASS` → 0（**FAIL 优先**，因为它比"缺输入"更需要处理）；
- 落 `joint-summary.json`（含每个 suite 的 exit、缺失项、输入快照，不含 token）。

配置可以走参数，也可以 `-ConfigFile`（JSON：`ontologyBase`/`appBase`/`spaceId`/`releaseId`/`appCode`/`platformTenant`/`environment`/`expectSha256`/`evidenceDir`/`bearerTokenEnv`/`suites`）。**token 不写配置文件**：只给环境变量名，值从进程环境读。

## 2. 本机实测（2026-10-30 13:46，stub 提供本体侧）

| 场景 | 结果 |
| --- | --- |
| A `-Suites J3,J4`，只有只读 key | J3 `PASS`(0)、J4 `SKIPPED-BLOCKED`(3) 缺 `AGENT_ACCEPTANCE_BEARER`；**总体 BLOCKED，退出码 3** |
| B `-Suites J3` 且 `-ExpectSha256` 与实收一致 | J3 `PASS`(0)；**总体 PASS，退出码 0** |
| C `-Suites J1` 缺 `-SpaceId`/`-AppCode` | J1 `SKIPPED-BLOCKED`(3)，缺失项两条都列出，**未调用、未发请求**；总体 3 |

另外确认了编排不会把"没跑"写成"通过"：C 场景里 `joint-summary.json` 的 `results[0].status` 是 `SKIPPED-BLOCKED` 且 `missing` 非空，而不是 `PASS`。

## 3. 边界

- 这是**编排器**：真实通过与否仍由 J1/J2、J3、J4 各自判定，本入口不重算断言。
- `SKIPPED-BLOCKED` ≠ 跑过并失败；它表示前置不足，补齐后重跑同一命令即可。
- J2 与 J1 共用执行器（`joint-j1-j2.ps1`），编排里只跑一次并在汇总里标注，避免重复的写操作（J1 会写真绑定）。

## 4. 已知坑（PowerShell 5.1 专属）

1. **`.ps1` 必须带 UTF-8 BOM**：`edit` 工具回写会丢 BOM，5.1 随即按 ANSI 解码，中文字符串变成乱码，报出"表达式或语句中包含意外的标记"这类看不懂的解析错误。本次就是这么坏过一次，用 `utf-8-sig` 重写并 `Parser::ParseFile` 复核后才通过。
2. **动态环境变量名不能写 `$env:$name`**：`$env:` 是驱动器语法，冒号后必须是字面量名。改用 `Get-Item -Path ('env:' + $name)`。
3. 三处验证都要跑：`Parser::ParseFile` 的 `PARSE_ERRORS=0`、三条聚合路径（0/3/3）、以及"缺输入时不发请求"。

## 5. 复跑

```powershell
$env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly key>'
$env:AGENT_ACCEPTANCE_BEARER = '<admin jwt>'
./scripts/acceptance/joint-all.ps1 -ConfigFile .local/joint-config.json -EvidenceDir docs/evidence/joint
```
