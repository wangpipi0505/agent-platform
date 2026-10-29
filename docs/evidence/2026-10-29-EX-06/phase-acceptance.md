# EX-06 本地阶段提交验收

2026-10-29，`ex04_pg_scope`。本次只做文件核验和提交范围准备，未运行测试、业务、网络或服务，未暂存、提交或修改 HEAD。**EX-06 仍进行中，`wholeCardComplete=false`；本阶段为 `ONTOLOGY_READY`。** 根线程实际执行的结果与独立审查分别保留，不以本清单代替运行证据。

| 范围 | 已有实际结果 | 验收边界与证据 |
| --- | --- | --- |
| Ontology 准备及旧会话 Resume | 49 个登记动作；最新 Resume native 0；actions/resources/context 深比较一致，凭据文件字节不变，pending=null、complete=false | [安全归档](operation-resume-20261029/README.md)、[比较回执](operation-resume-20261029/green-operation-resume-comparison.json)、[独审](empty-names-review.md)。49 是动作数，不是独立资源数；Resume 的读取、消费校验和审计不能称为零数据库副作用。 |
| 准备脚本检查 | 原 40 项保留，新增空对象属性名检查实红后，同一断言文件 41 项通过、native 0 | [红](empty-names-red-20261029-01/summary.json)、[绿](empty-names-green-20261029-01/summary.json)。HTTP 调用及业务资源创建均为 0。 |
| 协议及模型夹具 | 最新第 04 轮 63 项通过、native 0，源与上下文稳定，登记的夹具进程和监听均退出 | [结果](model-protocol-20261029-04/summary.json)、[非作者复核](model-protocol-final-review.md)。这是受控协议夹具检查，`businessValidationPerformed=false`。 |
| AP 阶段 | 仅静态方案、源码索引、历史运行环境盘点及其审查；未实施完整业务准备 | [执行候选](ap-stage-execution-candidate.md)、[方案审查](ap-stage-plan-review.md)。现有 Prepare 对 All 显式拒绝；不得登记 AP 三应用、技能导入/发布、真实模型调用、All、J1–J9 或 S1 已完成。 |

两次实际失败均保留：`model-protocol-20261029-01` 在原前 62 项通过后退出断言失败，native 1，原退出码未采得，恢复摘要不能补造该值；`operation-resume-20261029/failed-run.json` 保留 16:08 的旧会话 Resume native 1，后续 16:13 的成功归档独立保存。另保留 NullString 原红、operation 原 32 项后新增断言红、empty-names 原 40 项后新增断言红及各自绿，未重写旧原件或断言。

提交候选精确清单见 [commit-manifest.json](commit-manifest.json)：现有 **123 文件 = 3 个产品脚本 + 120 个证据文件**（含本说明与清单自身）。4 个 `driver.log` 精确列为排除项；不包含 `.local` 下 env、凭据、原始 journal、XML 或日志，也不包含其他执行卡、主入口、产品 Java 或运行目录。清单自身不伪造递归哈希，其最终 SHA 由交接消息单独提供。阶段独审身份见各原报告；本次是范围与公开性复核，不重新冒称所有历史实现均由本作者独审。

对候选文件扫描了 AP `.env` 与本次 Ontology 私密凭据文件中的 12 个去重已知秘密值（仅内存使用，不写值），真实值命中 0；JWT、PEM 私钥及机器凭据模式未发现真实秘密。原准备拒绝测试的 9 处合成字符串仍保留。JSON 中常见秘密字段的字符串值检查未发现候选；归档 manifest 中 32 个可定位副本记录全部匹配所记 SHA/长度。这是有界扫描，不是对所有未知秘密的绝对保证；提交前仍须按精确清单复核实际 index。

产品三脚本沿用仓库 `text=auto eol=lf`，PS5 BOM 保留，预期标准过滤后的 Git blob 已列入清单。89 个现有证据文件含 CRLF；为保留冻结证据字节，清单只提出本 EX-06 目录下 **120 条逐文件** `-text -whitespace` 属性候选，无通配符，不涉及产品脚本。本次未创建或应用属性文件；若根线程采用并加入该 `.gitattributes`，预期提交总数为 **124**。该属性文件的候选完整内容、长度及 SHA 在清单中；精确暂存后应核证据 raw SHA 与 index blob 字节相同，产品脚本则核标准过滤结果。

后续仍按已审 AP 候选逐步准入，先落实其 PG、Redis、运行 JAR/既有 V222 债务、夹具寿命和进程归属边界，再执行真实 AP 准备及联合验收。当前已完成的协议与 Ontology 阶段不解除这些验收要求。
