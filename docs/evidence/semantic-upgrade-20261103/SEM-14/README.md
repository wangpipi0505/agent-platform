# SEM-14 证据（AP 侧）：标准交付文档与脱敏 manifest

日期：2026-11-04　席位：O　提交：见本轮回执

## 1. 交付物

| 文件 | 说明 |
| --- | --- |
| `docs/integration/semantic-runtime.md` | 语义运行时标准接入包：四类 profile、12 类能力端点、两类资源同标准复用、配置/权限/错误码、一致性核对表、回退、BLOCKED 登记 |
| `docs/integration/semantic-runtime-delivery.json` | 脱敏 manifest：版本、25 个交付提交、能力清单、两类资源、一致性、BLOCKED |
| `verify-sem14.py` | 自检脚本：manifest 结构、文件与摘要、提交存在性、文档交叉引用、脱敏 |
| `self-check.txt` | 自检输出（89 项通过、0 失败） |
| `verify-delivery.txt` | SDK 交付包自检输出（既有产物摘要不一致，见 §4） |

## 2. 自检结果

```
PYTHONIOENCODING=utf-8 python docs/evidence/semantic-upgrade-20261103/SEM-14/verify-sem14.py
-> 通过 89 项，失败 0 项
```

覆盖：4 份契约/摘要一致；25 个提交在两仓实际存在；文档列出全部能力端点；5 条 BLOCKED 均有登记；无密码/密钥/连接串/内部账号/回环地址/端口形态。

## 3. 交叉核对

| 项 | 结果 |
| --- | --- |
| 文档 §2 profile ↔ 契约 §7 | read-only/condition/decision 一致；goal 独立 Schema 待 AP 收口（已登记） |
| 文档 §8 错误码 ↔ 契约 §3 | 一致；补充码为领域码，未改写契约码 |
| 文档 §3 端点 ↔ OR openapi | **部分缺登记**：facts/decisions/conditions/identity/changes；已登记为 `SEM14-OPENAPI` |
| manifest 提交号 ↔ 两仓 git | 全部可解析（`git cat-file -e`） |
| 配置项 ↔ 代码 | 键名与环境注入形态一致；无内联值 |

## 4. `verify-delivery.mjs`

```
node docs/integration/verify-delivery.mjs -> exit 1
FAIL checksum 不符: web/assistant.js/dist/assistant.js
  期望 f1a797c02a7247de3900ffa0502069afa9f77ff33938bab05587a57944bc0ed5
  实际 ae33f0eab09719c2a2ae575a1ddd0a7d5a2b158149a917819da3703103f7bf7d
FAIL versions.md 的 SDK sha256 与产物不一致（252936 bytes）
```

判定：该自检属于 SDK 交付包（INT/ONB 线），`dist/` 被 `.gitignore` 忽略、由构建生成；当前产物是其他席窗口重建的结果，与 `versions.md` 固定摘要不一致。**本次未改 SDK 源码、未改 `versions.md`、未重建产物**，也没有把该失败计入本卡通过条件；如需统一，由 P 在构建窗口重建并更新 `versions.md`/`checksums.txt`。

## 5. BLOCKED 与解锁

| ID | 解锁条件 |
| --- | --- |
| `SEM14-RESOURCE-HANDOVER` | B02 运行栈就绪＋用户授权窗口；两类源按文档 §4 配置并留存真实正负记录 |
| `SEM14-M1A-JOINT` | 同上；联合验收单独授权 |
| `SEM14-OPENAPI` | P 窗口统一导出 openapi 并复核 |
| `SEM14-GOAL-SCHEMA` | AP 新增 goal Schema 并回契约 §7 登记 |
| `SEM14-EMBED` | INT-05/07 与 F/P 席集成窗口 |

## 6. 回退

删除本文档与 manifest、撤销证据目录即可回到上一交付状态；无运行时行为、无迁移、无 API 变更。
