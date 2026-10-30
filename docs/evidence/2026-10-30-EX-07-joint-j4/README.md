# EX-07 J4 执行器自测（本体清单 → 平台导入，两段式）

## 1. 做了什么

J4 = "本体侧 manifest 被平台真正导入成技能草稿"。它跨两仓，且第二段必须人身份（ADMIN JWT），所以把手册里的手输探针固化成两段式执行器：

- `scripts/acceptance/joint-j4.ps1`（PowerShell 5.1 兼容，零第三方依赖）：
  - **第一段（本体侧）**：只读服务账号 `GET /onto/releases/{id}/manifest`，断言 200、`data.manifests` 是数组、每项有 `toolCode`/`spaceCode`/`releaseId` 且 `releaseId` 与入参一致、清单非空；
  - **第二段（平台侧）**：带 ADMIN Bearer `POST /admin/skills/import-ontology-manifests`，断言 200/201、`failed=0`、`created>0`。
- `scripts/acceptance/stub-context-pack.py` 增 `/manifest` 桩与 `--mode mismatch`（返回别的 releaseId），用于验证"导错版本"会被拒绝。

设计要点：缺 `-BearerToken` 时**仍然跑第一段**（那是真实验证），然后整体判 `BLOCKED` 并说明缺什么——不把"本体侧通了"说成 J4 通过。

## 2. 五条路径的实测结果（本机，2026-10-30 13:5x）

| 场景 | 条件 | 结果 |
| --- | --- | --- |
| A 只有只读 key | stub 正常，无 `-BearerToken` | 第一段 3 项全 PASS → 整体 **BLOCKED**，退出码 **3** |
| B 凭据不对 | 传 `wrong-key` | **FAIL**，退出 1，`J4a-取清单 期望 200 实际 401 SERVICE_CREDENTIAL_REQUIRED` |
| C release 不存在 | 传一个不存在的 releaseId | **FAIL**，退出 1，`实际 404 NOT_FOUND` |
| D 清单与入参 releaseId 不一致 | stub `--mode mismatch` | **FAIL**，退出 1，`J4a-逐项字段 … bad:queryCampaign,querySegment`，原因写明"可能导致平台导错版本" |
| E 缺 `-ReleaseId` | 不传参数 | **BLOCKED**，退出 3，**未发出任何请求** |

第二段的 PASS 路径本机无法自测：`/admin/skills/import-ontology-manifests` 要真实 AP 的 ADMIN JWT 与真实技能库，用桩伪造"导入成功"会变成假绿，所以留空并在报告里标明。

## 3. 为什么第二段不做桩

第一段答的是"本体有没有这个 release 的清单、版本对不对"，第二段答的是"平台能不能把它变成可发布的技能草稿"。后者是产品行为（写 `skill.skill`/`skill.skill_version`、走 DRAFT、后续还要连接测试与验收），桩只能伪造 HTTP 200，伪造不了"草稿真的落到库里、`contentDigest` 与本体一致"。所以第二段只留真实路径。

## 4. 边界

- 第一段通过 ≠ J4 通过；本脚本用退出码 3 明确区分"缺输入"与"协议失败"。
- 清单非空是硬条件：空清单意味着该 release 没有可导入工具，导入只会产生 empty 结果，不能算通过。
- `releaseId` 逐项比对是防"导入到别的版本"的关键一行——本体清单里每个操作都带 release 归属，平台据此写 `importSource.releaseId`，错版本会让运行期取到错的 context-pack。

## 5. 复跑

```powershell
# 真实环境：只读 key + ADMIN JWT
$env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly key>'
./scripts/acceptance/joint-j4.ps1 -OntologyBase 'http://127.0.0.1:18182' -AppBase 'http://127.0.0.1:18081' `
    -ReleaseId '<uuid>' -BearerToken '<admin jwt>' -EvidenceDir docs/evidence

# 本机自测第一段
python scripts/acceptance/stub-context-pack.py --port 18199 --mode normal
./scripts/acceptance/joint-j4.ps1 -OntologyBase 'http://127.0.0.1:18199' -ReleaseId 7dc4a1e2-5b3c-4f8d-9a11-2c3d4e5f6a7b
```

## 6. 已知坑

桩的 `--mode` 需要显式登记在 `argparse` 的 `choices` 里；第一次加 `mismatch` 只改了分支没改 choices，进程直接退出（日志里是 `invalid choice: 'mismatch'`），表现为"curl 无输出"，容易误判成脚本挂了。
