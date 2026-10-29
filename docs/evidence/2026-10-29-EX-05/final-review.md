# EX-05 AP 消费映射独立实质审阅

日期：2026-10-29。审阅者：`algorithm_interrupt_readonly`；AP 实现及实际执行者为主线程 `root`。本次独立读取源码、Git 差异、已存命令回执和日志，并复算静态摘要；**未运行 Node、Java/Maven、PG、HTTP、前端或仓库测试，没有读取凭据，也未操作 Git index/提交。** 唯一获准写入为本报告。未使用 Superpower。

## 结论与范围

**AP 两个授权源文件的实质审阅通过，无阻断。** 本结论覆盖契约消费映射和总门禁接线，不代表 EX-06/07 联合业务验收完成；OR 最终契约、生成类型和前端回归由 OR 同目录 `review.md` 独立核定。整卡状态及精确索引、提交仍由主线程收口，不把并行 EX-15a Java 差异纳入本卡。

| 复核文件 | 当前真实字节 SHA-256 |
|---|---|
| `contracts/skill-invocation.md` | `a9dd35cab57e30dd03beaa5ab9aed09f4882f720db78e5ffb35dac35bb9eb4da` |
| `scripts/verify-contracts.mjs` | `0ff00338bc670aa7d0b2e985ea3d12d26eb23ccb2095e2e47afcc3d78456a802` |
| `check-consumption.mjs`（本目录） | `efd1b718efb99e620ef5bb9155b92e1ca82e30ca19be10afe31b5860c831402d` |
| `consumption-checks.json`（本目录） | `1a62052e0fa83aed89565793d7c12ec92787f64407855edc0b605176b5cfd4ae` |

## 冻结和实现语义

- 独立按字节取契约前 63,602 字节，SHA 为 `399f6248e3b24bf10c1489b670238b1a8b7cc67ee5d5ab0e1eb4c6d1a43d66a5`，与旧 freeze 及 `scope-before.json` 一致。契约差异只有追加 81 行，原历史内容未重写。`contracts/freeze.json` 仍为 `4c915c50b9a53b4c110c48a6fa9144aa8a1ba7c874469b1ea2d94e43a53d95d4`，v1.2 原文快照仍为 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。
- §16 七个顶层键与真实 `OntologyManifestImports`、`SkillCatalog`、`SkillRules`、`SecretVault` 源码逐项核对：正式 HTTP/SYNC/POST、API_KEY/X-API-Key、六字段 ONTOLOGY 来源、QUERY/MUTATION 执行旗标与风险、OFFSET 四字段、编辑省略保留与显式 null 拒绝均准确。未登记的非 HTTP 名义绑定仍拒绝，没有把适配器类型注册当作 binding_ref 目录。
- OFFSET 没有改成 PAGE；没有猜 response 路径，没有新增 `maxRows >= maxPageSize` 规则，也未宣称自动翻页或已强制累计行数。先整批纯语义预检再逐项 create 的边界准确保留；后续数据库创建失败仍可能部分成功，没有虚称跨批原子事务。
- OR 人 Bearer、服务 `ontosa_` 和消费 `ontopa_` 的说明按各自用途区分；密钥引用沿用 ADMIN、SecretVault 和租户校验，不从 manifest 接纳明文凭据或自报身份。
- 总门禁对固定历史前缀作摘要校验，再检查增补的唯一 marker、精确起点、JSON/结束边界、七键 deep-equal 及未知顶层键拒绝；其它三契约、原快照、模块依赖和原 G-9 行为保留。`--skill-contract` 仅选择临时变体输入并拒绝重复/缺值，原 EX-09 `--file` 透传和原生失败退出码保留。
- 现有契约 `-text` 属性保护原始 CRLF。Git 默认空白策略会将新增 CR 误报为 trailing whitespace；复核以单命令 `git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check -- contracts/skill-invocation.md scripts/verify-contracts.mjs` 检查实际尾空格/tab，退出 0。未改冻结换行、源文件属性或功能断言。

## 读取的实际执行证据

以下结果均来自主线程已执行原件；审阅者没有重跑这些命令：

| 证据 | 记录及独立核对 |
|---|---|
| `command-results.json`、`red-ap.log` | 旧 63,602 字节契约在新门禁下 native=1，停于消费映射增补缺失；并非编译或夹具失败。 |
| `command-results.json`、`green-ap.log` | 仅追加 §16 后总门禁 native=0；原 G-9 门户面 27/STALE 5 告警保留，没有降低门槛。 |
| `check-consumption.mjs`、`consumption-checks.json` | 独立数得 20 个唯一检查、20 PASS；1 个 native=0、19 个 native=1。测试实际调用总门禁，并同时核退出码及对应诊断。 |

20 项为：有效例、原文漂移、增补缺失/重复/边界偏移/游离尾文、七键分别缺失、四项语义替换，以及原 EX-09 缺绑定/明文身份/未知 kind 三负例。测试没有复制生产门禁判据来制造通过，也没有条件跳过。README 记录的初次检查器 JSON 提取范围准备错误与后续修正不冒充产品实红；最终检查器摘要和实际 20 项原件匹配。

## 收口边界

AP 本范围无须再改产品。OR/生成类型的最终审阅、精确候选清单、index 复核和本地提交仍须完成后，才由主线程更新整卡状态。后续公开 API 变更需重跑本卡门禁；本次结果不能永久沿用，也不替代真实 OR 操作、发布 pin、凭据权限或业务副作用的联合验收。回滚只反向本卡契约增补与门禁差异，并保留原红及评审证据。
