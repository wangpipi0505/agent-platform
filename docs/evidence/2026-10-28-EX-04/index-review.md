# EX-04 精确提交候选索引独立复核

- 审阅者：`/root/ex04_pg_scope`，2026-10-29；未参与本卡产品/测试实现。此前参与的只读 PG 范围报告不作为产品作者自审的替代；功能与回归终审仍以 `final-review.md` 为准。
- 本次只读 Git index/blob、磁盘候选、最终回归 source manifests 与 OR 状态；没有 stage/commit、修改 index、运行产品或读取真实凭据值。唯一新增本报告，不使用 superpower。
- 候选 base HEAD：`d7880a66b345f15aecf37133a702df769e591fa5`。审阅时索引 **300 文件 = 8 代码 + 292 本卡证据**，跟踪文件工作区相对 index 无差异；`git diff --cached --check` 退出0。
- 精确候选索引指纹：`0e454bcd2130137bbe420416a2ce3f53cb2d98afe646c6cdeb8d6c18a6b261de`。算法为按路径排序，逐项 UTF-8 `path + NUL + mode + NUL + blobOid + LF` 后 SHA-256；只含本次 staged changed paths，不把此值称为 Git tree ID。

## 源码范围与冻结

非证据变更恰下表四生产＋四测试，没有其它产品路径、POM、依赖、迁移、RunService、SkillDigest、OR 代码或生成资源。新 Projection 测试已实际纳入 index，不再遗漏在 untracked。

| AP repo 相对路径 | index blob OID（Git 标准过滤后） |
|---|---|
| engine-runtime/src/main/java/cn/agentplatform/engine/application/ToolOutputPolicy.java | c25d8a342d9ba0dd1664464b15f76df36efdf7ee |
| engine-runtime/src/test/java/cn/agentplatform/engine/application/RunToolProfileTest.java | 702b19a84b0c0307652bb10250aa7ebd20b7ce86 |
| engine-runtime/src/test/java/cn/agentplatform/engine/application/ToolOutputPolicyTest.java | 952d6310bd087e7c1295569e92fd5533a81e5e66 |
| skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java | dbc3d104321c52b99c8b54177347e5a6f6c2935b |
| skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java | 47e3295ce7a2f9bb8e52536907f641cdac2965d3 |
| skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java | 7f0229f0ba6010032a7726b90c085fdf2e06eff2 |
| skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java | dfbe80fd5c3a82a95cd77658035ffab362ae1c77 |
| skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyProjectionImportTest.java | 507adf1684d6003791b2b76d5be66b514faadb96 |

独立重算最终 skill `full-skill-legacy-save-20261029-1112` 的150源及 engine `full-engine-20261029-1124` 的202源：**352 个文件均 before raw SHA = after raw SHA = 当前磁盘 raw SHA**。八代码 raw SHA 也与 `commit-scope.json` 完全一致，Catalog 是完成旧手工保存补修的 `c9c3a0b6…` 版本。

对这352路径一次批量运行只读 `git hash-object --stdin-paths`（无 `-w`），使 Git 按实际 `.gitattributes` 执行标准过滤；所有输出 OID 均等于当前 index 相应 blob。此检查明确区分磁盘 raw SHA 与 Git 标准 LF/blob，不声称二者逐字节相同。

根 `.gitattributes` 未改，仍 `* text=auto eol=lf` 等原规则，raw SHA `08bb8d13896f619a5794638b2c0b553a1d3beedb4fcaa484719c78fba93f667b`。本卡证据规则没有覆盖到任何 src 路径。

## 证据集合与字节

- 其余292文件全部位于精确 `docs/evidence/2026-10-28-EX-04/` 下；使用单次 `git cat-file --batch` 读取 index blob 并逐文件比较：**292/292 raw 工作文件与 index blob 字节相同**，总 5,864,456 字节。
- 目录内 `.gitattributes` 恰292条一对一 `/具体路径 -text -whitespace` 规则（含自身），无 glob/生产范围豁免，规则集合与292证据路径集合精确相等。归档代码快照 `.java/.txt` 是证据，不是第三方产品改动。
- 已读取最终 `final-review.md`；它确实在此次300文件集合内，SHA `af3ea4ba02684c61738af91fb18c18cc962b850f83378addb928dc6b9e29a948`，并非稍后新增第301个文件。运行、原因有界证明、原失败与最终回归资料均位于该证据集合。
- 集合中没有 raw XML/log、`.env`、业务 JAR、证书/密钥容器、target V222 文件、EX-17、EX-00 database-names、其它 user audit/output 文件。原 XML/log 继续本地保留，`xml-manifest.json` 等安全元数据不等同原 XML 本文。
- ROOT 已报告对本环境凭据值做过零命中扫描；本审阅者没有重新读取真实 `.env`/密钥，因此这里只签文件集合和字节核验，不冒称独立复做了秘密值扫描。

OR HEAD 为 `da3345a123752c3fda06c5a7a8a1dc9d614b8918`，跟踪文件工作区与 index 均无变化，原 user untracked 保留。本次不创建 OR 提交。

## 结论与最后补入边界

接受上述300文件作为 EX-04 本地代码/证据提交候选，未发现范围或过滤漂移。本报告本身不操作 index，当前不在已审300文件中；ROOT 后续只需精确补入本报告，并在本卡 `.gitattributes` 增加其唯一准确规则，然后再次只读确认最终 **301 文件 = 8 代码 + 293 证据**、上述八个 blob 不变、原291个非 attributes 证据字节不变、只新增本报告及 attributes 对应行。该最后确认通过会以协作回执给出，不另造递归报告层。

本报告没有授权扩大提交范围、push/tag 或外部发布。三份仓外主入口及 EX-08 增量/快照不在这次产品候选内，后续检查点必须单独审阅，不把 AP Git 提交误称直接包含仓外原文件。
