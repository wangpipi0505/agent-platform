# EX-03 失败测试源码候选（未运行）

起点 HEAD：`0cc967278007343bd5ab09faf9953d86cd573bf8`。EX-01 子代理明确释放 `OntologyManifestImportsTest.java` 唯一测试源码写窗；其后续四服务打包使用 `-Dmaven.test.skip=true`，不读取此测试源。产品 Importer/Catalog 仍保持原始内容。

实际仅修改 `skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java`：原 9 个方法保留，新增 9 个方法，总 18。结构失败测试改用合法临时 vault 引用，避免未来鉴权前置掩盖原断言；原映射成功方法增加 API_KEY 与不泄漏明文断言。测试临时密钥与主密钥均为源码明确的无权限测试字面值，非环境秘密。

新增方法覆盖缺凭据、声明冲突/未知、显式 API_KEY、格式/不可用凭据、跨 tenant、无 ADMIN、legacy-bearer=false、ONTOLOGY 严格来源，以及真实 PG 导入/读回/治理/发布/执行链。PG 链使用环回 HttpServer 接收真实头；DEBUG 调用成功由 SkillExecution 写入真实非 mock 事实，connection 读取该事实；acceptance 使用确定性模型协议替身，由 SkillGovernance 计算并写入通过结果。没有手工写假通过报告、改发布状态或在出站前补 authScheme/ref。

PG 方法拟使用精确专用库 `agent_platform_ontology_import_test`，只有经主代理核准且测试窗释放后才运行；若不存在才创建，仅追加迁移及随机 tenant 数据，不 drop/truncate/删已发布记录。此时尚未连接 PG，未创建该库。

静态检查：`git diff --check -- skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java` 无输出，退出 0。不是编译/红测结果。未执行 Maven/Java/PG/HTTP，产品仍未修改；不得把候选称为“已红”。

当前 SHA256：

| 文件 | SHA256 |
| --- | --- |
| OntologyManifestImportsTest.java | `ACA2886F6AFDD833821B92BA27E602908C26408FB9D0C524D80FA98FAF556021` |
| OntologyManifestImports.java（原产品） | `E0C4A725533CBE5C84962D2DDA2A8CB408344960DB389BE2542809F85942BF5D` |
| SkillCatalog.java（原产品） | `CC1DEBCBC26EF5DE1B48DE74E3C627A671B605B8BDC5F1593D7DBD5C151B4EC1` |

`git diff --name-only` 限定两个产品路径无输出，证明当前未改。后续拿到主代理运行窗口后，先运行并立即归档此候选在原产品上的真实失败，再进行最小产品修改。模块回归仍按 [前置设计](preflight-design.md) 引用的 EX-00 逐方法基线比较。
