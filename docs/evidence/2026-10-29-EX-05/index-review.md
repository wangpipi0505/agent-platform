# EX-05 AP 精确索引独立复核

日期：2026-10-29。复核者：`artifact_boundary_review`，未参与本卡实现或实际测试；实质审阅见同目录 `final-review.md`（`algorithm_interrupt_readonly`）。本次仅读取候选清单、工作区文件、Git index/blob、冻结记录与既有证据，并写入本报告；未运行测试、Node、Java/Maven、PG 或 HTTP，未读取凭据、修改 index 或提交。未使用 Superpower。

## 结论

**AP 本次 13 文件候选与 index 精确一致，独立索引复核通过，允许本地提交。** 主线程可仅追加本报告，再核最终载荷为下述 13 个不变 OID 加本报告后提交。此结论不授权加入 EX-15a Java、其它证据、输出目录或未登记文件，不授权推送。OR 的 generated 依赖修正及其最终审阅尚未收口，本报告不宣称 EX-05 整卡完成。

## 候选与索引身份

- 当前 HEAD 与 candidate.baseHead 均为 `67d1d41dc599a99ecad4654a4736446e076f84f7`。
- 候选：`output/ex05-staging/ap-candidate.json`，原始 SHA-256 为 `0b0dd8fa9d94f8af3e6d021fa2235e3fef424a7d672ae8747a5f1d22812f386c`。
- `git diff --cached --name-only` 恰为 13 条；与 candidate 路径集合双向差集为空。全部 stage=0，逐项 index OID 等于 candidate.filteredOid，全部工作区 SHA-256 等于 candidate.rawSha256。
- 载荷为 2 个源文件和本卡目录内 11 个文件（其中 1 个为 `.gitattributes`，10 个为证据）。没有 EX-15a Java、迁移、其它卡目录或 unrelated untracked 文件进入 index。

下表 evidence/ 表示 `docs/evidence/2026-10-29-EX-05/`；全部 OID 直接从 index 读取，并以 `git cat-file blob` 的原始字节另核。

| 路径 | index OID |
| --- | --- |
| `contracts/skill-invocation.md` | `61e0c41a662a764eeba87aa9073974a856f654b6` |
| `scripts/verify-contracts.mjs` | `3a62eff39fc74cb97c6e8ba95671d0b68aced915` |
| `evidence/.gitattributes` | `2c90b3b52ab09de7b8932bb3fc27da5117e2f387` |
| `evidence/README.md` | `c5f1bd0c6c1136e107e71fb0c275a93953d05522` |
| `evidence/check-consumption.mjs` | `d065a6082768d5b9c2a006554a289463c842a4c6` |
| `evidence/command-results.json` | `330c4f8fce9c89f3a2695dadf084e0ef2c268b7a` |
| `evidence/consumption-checks.json` | `f5310a7868b99f5e53dca99bd287ec6f7abf7b02` |
| `evidence/final-review.md` | `7753b584961d307d91c7ef67223f8d20566c0a94` |
| `evidence/green-ap-final.log` | `155f9cfaba9b6f4d207703358f7d69a8bef57b23` |
| `evidence/green-ap.log` | `155f9cfaba9b6f4d207703358f7d69a8bef57b23` |
| `evidence/red-ap.log` | `dc56e5ed0a6c340005a1c857bfc293871dfd5981` |
| `evidence/scope-before.json` | `954252d5a519f2defca130f917487819981e675d` |
| `evidence/whitespace-check.json` | `56b0539eac5271e6e88feed6aa0c3a3ee1da69f6` |

## 原始字节、冻结和属性

10 份证据的 index blob SHA-256 均逐项等于工作区原始 SHA 和 candidate.rawSha256；两个源文件也逐字节一致。只有 `.gitattributes` 自身按仓库既有 text=auto/eol=lf 归一：工作区 SHA 为 `3b5a2a9c33ea297fd2d1711940be4568c57997411b557b808005f943c687afeb`，index SHA 为 `19acad35aec0fc5edba101b9f8a8a693d30d4b2611392dcd276c71ceecde8227`。独立将原始内容仅作 CRLF→LF 后得到同一 index SHA，未发现文本差异。

独立从暂存契约 blob 取前 63,602 字节，SHA 为 `399f6248e3b24bf10c1489b670238b1a8b7cc67ee5d5ab0e1eb4c6d1a43d66a5`，与固定历史摘要一致；契约差异为追加 81 行。`git diff HEAD --name-only -- contracts` 仅列本契约，其余冻结文件不变，并复算以下真实字节摘要：

| 冻结文件 | SHA-256 |
| --- | --- |
| `contracts/freeze.json` | `4c915c50b9a53b4c110c48a6fa9144aa8a1ba7c874469b1ea2d94e43a53d95d4` |
| `contracts/snapshot/detailed-design-v1.2.md` | `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f` |
| `contracts/graph-definition.md` | `2053fb5e90369e3194a72cb199e40a51cc7eead327ee3620c323e43fe2c79c99` |
| `contracts/trace-model.md` | `7d0fe91476d6336877b00ece046d5e2daf1a239bf316a7d0b2f6c4507eaacb5f` |
| `contracts/authorization-confirmation.md` | `7283fb06f4f790b030f154d3546931e71907f48e498f2b869f622d28e392c995` |
| `contracts/capability-package.schema.json` | `0456799d04deb57dfb114d169d8a37c7b57a49cad3996fee917a93efa704a634` |

新增属性文件仅位于本卡证据目录，逐名列出本卡证据（含待加入的本报告），没有通配规则或生产源路径。`git check-attr --cached text whitespace` 确认：证据为 -text/-whitespace；契约仍为既有 -text、whitespace 未指定；验证器仍为既有 text=auto、whitespace 未指定。没有对两个源文件放宽空白检查。

独立执行只读检查 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --cached --check -- contracts/skill-invocation.md scripts/verify-contracts.mjs`，退出 0；同参数检查整个 index 也退出 0。该单命令保留真实尾空格、文件尾空行和空格后 tab 检查，仅按既有 CRLF 契约处理 CR，没有改写冻结换行或仓库配置。

本报告创建时尚未进入 index；最终提交由主线程追加此单文件并复核载荷。本次没有重跑或改写原红绿结果，AP 实质结论继续以 `final-review.md` 及其对应原件为准。
