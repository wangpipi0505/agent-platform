# EX-09 能力包声明门禁接线证据

- 执行日：2026-10-29；作者：`/root/ex09_package_gate`；基线/候选 HEAD：`28aa99ea12cb96bbbf781859c017acebd30647a3`，候选为本目录及两个脚本的未提交改动。
- 前置：主执行者已确认 EX-00 完成。本卡仅使用获准的纯本地 Node CLI 窗口，没有执行 Maven、Java、PG、HTTP、前端、浏览器或服务操作；没有载入环境文件或读取密钥。
- 状态：实现与作者定向自检已齐，等待主执行者安排独立只读评审、提交及台账回填；本记录不宣布整卡关闭。

## 范围与调用方

已读本仓 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`，统一台账 v2.8、执行卡片 v1.8 的共同规则/EX-09、联合验收与回归手册 v1.8。AGENTS 中旧设计短路径不用于修改冻结快照；冻结原文、schema、默认声明均未修改。

调用方核实：`scripts/test.ps1:5` 以 `verify-contracts.mjs` 为本仓测试前置；修改前该脚本校验冻结原文、七模块依赖边界及路由，却没有运行 `verify-capability-package.mjs`。后者已有零依赖 schema/语义校验、默认目录全集和可重复 `--file` CLI，无需新建校验器。相关脚本与 `verify-routes.mjs` 只读本地文件，没有联网或启动服务逻辑。

仅修改：

1. `scripts/verify-contracts.mjs`：用当前 `process.execPath` 同步运行现有能力包 CLI，继承诊断输出，向它转交 `--file` 参数，传播失败原生退出码；启动失败或信号中断也不能落到末尾成功消息。
2. `scripts/verify-capability-package.mjs`：更新接入状态说明；必填字段缺失时将诊断路径从父对象精确到缺失字段。校验条件与 schema 不变。
3. 本卡证据目录：采集脚本、三种合成负例、修改前后命令与输出、哈希/候选清单。

同仓另有 EX-03 的 `OntologyManifestImports.java`、`SkillCatalog.java`、`OntologyManifestImportsTest.java` 和 EX-27 的 `AlgorithmOffloadInterruptionTest.java` 等并行改动。本卡不修改、暂存或提交它们。两阶段 `snapshot.json` 保留当时 HEAD、完整脏路径清单与相关源文件哈希，路径清单不代表本卡所有权。

## 原场景与验收结果

三份 fixture 均从未改动的 `contracts/capability-packages/route1-visibility.json` 深复制，只做下列单点变异；不修改冻结默认目录：

| 负例 | 唯一变异 | 要求的精确字段路径 |
| --- | --- | --- |
| missing-tool-binding | 删除首个工具 `binding` | `能力包.tools[0].binding` |
| identity-plaintext | 将空 `identity.secrets` 换成含合成明文 `ref` 的一项 | `能力包.identity.secrets[0].ref` |
| unknown-kind | 首个入口 `kind` 换成合成未知值 | `能力包.interfaces[0].kind` |

所有凭据负例仅使用 `EX09_SYNTHETIC_NOT_A_CREDENTIAL`。fixture 不是可执行业务包，不接入运行链路。

| 入口/场景 | 原 native exit | 原精确路径 | 修后 native exit | 修后精确路径 |
| --- | --- | --- | --- | --- |
| 独立 CLI：默认真实声明全集 | 0 | 不适用 | 0 | 不适用 |
| 独立 CLI：缺工具绑定 | 1 | 仅父路径及字段名 | 1 | 有 |
| 独立 CLI：明文身份 | 1 | 有 | 1 | 有 |
| 独立 CLI：未知 kind | 1 | 有 | 1 | 有 |
| 总门禁：默认真实声明全集 | 0 | 不适用 | 0 | 不适用 |
| 总门禁：缺工具绑定 | 0（漏校验） | 无 | 1 | 有 |
| 总门禁：明文身份 | 0（漏校验） | 无 | 1 | 有 |
| 总门禁：未知 kind | 0（漏校验） | 无 | 1 | 有 |
| 两脚本 `node --check` | 未执行 | 不适用 | 分别 0 | 不适用 |

没有为了制造红阶段改变原校验器、schema 或默认样例。原独立 CLI 三负例已能拒绝，如实保留；真实缺口是总门禁对同一 `--file` 负例返回 0，并没有能力包输出。修后默认总门禁有能力包执行明细；三负例的错误输出与独立 CLI 一致，原生退出码均传播为 1。缺绑定原来的拒绝能力保持，仅改进精确字段定位。

实际运行环境是 Windows、本仓工作目录、Node `v24.16.0`。以下两个采集命令退出码均为 0，分别保存 8 与 10 条实际子命令结果；修前采集器仅记录差距，不把预期失败的基线伪装为验收通过。修后 10 条的退出码与字段路径全部符合预期。

```powershell
node docs/evidence/2026-10-29-EX-09/capture.mjs before
# 完成上述两脚本最小修改后：
node docs/evidence/2026-10-29-EX-09/capture.mjs after
```

`before/commands.json`、`after/commands.json` 逐条记录精确命令、native exit、signal、期望退出码、精确字段路径命中，以及 stdout/stderr SHA256；同名 `.stdout.txt`/`.stderr.txt` 是实际原始输出，空 stderr 文件也保留。采集器保留用于审阅；不要在现候选上重跑 `before` 覆盖原证据。后续复验应直接运行 `commands.json` 中命令并保存到新的独立目录。

## 冻结一致性与边界

两阶段实际 SHA256 完全一致：

| 文件 | 修改前后相同 SHA256 |
| --- | --- |
| `contracts/capability-package.schema.json` | `0456799d04deb57dfb114d169d8a37c7b57a49cad3996fee917a93efa704a634` |
| `contracts/freeze.json` | `4c915c50b9a53b4c110c48a6fa9144aa8a1ba7c874469b1ea2d94e43a53d95d4` |
| `contracts/snapshot/detailed-design-v1.2.md` | `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f` |
| 默认真实声明 `route1-visibility.json` | `d51f6cfc256f9723a8d458f5cafbe5cb53ab4982fe4103f32a5110347c60d6d9` |

四份冻结契约正文的修改前后哈希也逐项一致，详见两份 `snapshot.json`。`git diff --check -- scripts/verify-contracts.mjs scripts/verify-capability-package.mjs docs/evidence/2026-10-29-EX-09` 退出 0。

此处为脚本层 18 条实际命令记录，不是 Java 测试方法数或整项目测试通过结论。路由前置仍提示原有门户面 27 条、STALE 5 条非阻断告警；未修改其规则。默认声明的 evaluation/concurrency 仍按原豁免留痕，本卡不交付能力包运行层、软件端点连通性、真实身份映射或其它 EX 卡。未知 kind 的既有“未注册”告警仍存在，冻结 schema 的枚举错误会明确阻断。

## 交还与回滚

实际 Node 窗口已交还主执行者；两个 `.mjs` 候选冻结交还，后续不再自行改动。公开可暂存文件限 [candidate-files.json](candidate-files.json) 列出的两个脚本、本目录及该清单自身；清单对每个文件记录当前 SHA256，不包含其它卡源码或环境配置。

回滚仅撤销本卡对两个脚本的接线、注释和诊断路径改动，保留本次证据。冻结 schema/正文/默认声明无变更，无需回滚。独立评审 [review.md](review.md) 由主执行者安排独立审阅者写入；作者自检不能替代它。
