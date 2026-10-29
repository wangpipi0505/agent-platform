# EX-03 预审测试补强（候选 v2，未运行）

依据独立 [pre-review.md](pre-review.md) 的 PRE-03-01/02/03，经主代理明确仅授测试源码与本卡证据写窗完成。没有运行 Java/Maven/PG/HTTP，没有修改产品、主入口或 index。

| 预审项 | 当前测试变化 | 验证边界 |
| --- | --- | --- |
| PRE-03-01 严格来源反例不足 | 在原 `ontologySourceRejectsUnknownFieldsAndMalformedCoordinates` 内遍历六字段缺失/空串；追加额外键、UNKNOWN/错误 OPENAPI kind、非空非法 UUID、64位大写/非法字符摘要及63/65位摘要。合法来源仍由严格注册与PG正例覆盖。 | 摘要只接受64位小写hex，releaseId按UUID使用；version仍仅要求非空，不新增版本语法。未宣称这些反例已执行。 |
| PRE-03-02 ADMIN校验先后未证 | 在原 `credentialImportNeedsAdminBeforeAnyDraftIsCreated` 保留合法自有引用，再加入不可解析的 `vault:not-a-uuid`；均要求403且精确 `SYS-APP-4031`，catalog零交互。 | 能在未来执行时区分先角色拒绝与先解析返回400；跨tenant ADMIN 的 SECRET_SCOPE_DENIED原用例保留。 |
| PRE-03-03 测试直跑可绕过专库归属 | 删除PG测试内按名字自动create；在构造目标库连接/Flyway前从postgres查询固定库owner/comment，要求恰一行、owner=agent_v12、comment为本卡准确标记。缺库/不符以断言失败，不自动建库、接管或skip。 | 已授权 runner继续负责前置创建/归属记录；本次未访问PG，未变更数据库。 |

18个 `@Test` 方法身份保持：原9＋新9。只是扩展已有三个方法中的断言，没有以删除原有效结构断言减小覆盖。`git diff --check` 无输出、退出0，只是静态空白检查，不是编译或测试通过。

当前 `OntologyManifestImportsTest.java` SHA256：`4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF`。

已归档红轮 [red/20261028-222522-749](red/20261028-222522-749/command.json) 对应旧测试SHA `ACA2886F6AFDD833821B92BA27E602908C26408FB9D0C524D80FA98FAF556021`。该轮18项=9P/7F/2E的事实与原始文件保持不动；不能把旧轮结果当作本次新增断言已跑。拿到统一运行窗后应记录当前候选的新鲜结果，再按主代理授权进入产品修复。

产品仍保持原SHA且两路径 `git diff --name-only` 为空：Importer `E0C4A725533CBE5C84962D2DDA2A8CB408344960DB389BE2542809F85942BF5D`；Catalog `CC1DEBCBC26EF5DE1B48DE74E3C627A671B605B8BDC5F1593D7DBD5C151B4EC1`。当前HEAD仍为 `0cc967278007343bd5ab09faf9953d86cd573bf8`。
