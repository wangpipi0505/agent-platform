# EX-03 v2真红与最小产品候选（尚未跑绿）

经主代理明确授权，在 EX-01 实际阶段交还窗口后，先只运行补强 v2 的 `OntologyManifestImportsTest`；完成后即将 Java/Maven/PG/HTTP 实际窗交还 root，供 EX-36 engine 整模块使用。后续仅在已授 Skill 源码窗内修改本卡三个文件，未再执行编译或运行。

## v1/v2真红并列

| 轮次 | 测试SHA256 | 运行时间（北京时间） | 实际Maven退出码 | passed/failure/error/skipped |
| --- | --- | --- | ---: | --- |
| [v1红](red/20261028-222522-749/command.json) | `ACA2886F6AFDD833821B92BA27E602908C26408FB9D0C524D80FA98FAF556021` | 22:25:24–22:25:35 | 1 | 9/7/2/0 |
| [v2红](red/20261028-230240-211/command.json) | `4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF` | 23:02:42–23:02:51 | 1 | 9/7/2/0 |

两轮收集器进程均正常返回0，表中1是保存的真实 Maven native exit；不把收集器0误写成测试0。v2执行的是补强断言后的18方法，并保留 [当次方法身份/状态](red/20261028-230240-211/method-status.csv)、[源码指纹](red/20261028-230240-211/source-sha256.json)、[新鲜UTF-8 XML摘要](red/20261028-230240-211/xml-manifest.json)和本地原始raw/XML，不覆盖v1原件。

原9方法仍8P/1F（原成功映射方法新增API_KEY断言）；新9仍1P/6F/2E，失败身份没有替换。两个E仍为生产 `SKILL_AUTH_SCHEME_REQUIRED`，PG连接和Flyway已成功到达真实create内normalize，未提前宣称来源正向/发布/header链已执行。补强来源负例在旧产品中仍因整体ONTOLOGY不被接受而通过，最终必须由正向链及新产品完整负例共同证明新分支。

v2 [数据库前提](red/20261028-230240-211/database-precondition.json) 再次证明原本仓PG PID15204、数据目录、env同源指纹；专库已存在，owner与准确EX-03 comment均匹配，本轮未创建/清理库。测试本身也在Flyway前执行owner/comment的fail-closed准入，不再自动create。

## 产品候选

1. `OntologyManifestImports.java`：缺省显式API_KEY；调用者显式声明先复用共享枚举/组合校验，只接受API_KEY，不静默丢掉NONE/BEARER/未知值。整批写入前先ADMIN，再SecretVault.resolve；跨tenant的PlatformException保持403，格式/不可用引用收敛为不带引用或密钥的400。冻结契约只写opaque authRef与显式API_KEY。
2. `SkillCatalog.java`：仅在importSource归一化新增ONTOLOGY分支，精确六键、全为非空字符串、releaseId可解析UUID、contentDigest为64位小写hex。未知/缺失字段拒绝。version仅非空。旧OPENAPI五键分支、手工HTTP Bearer默认、其它目录/发布规则保持原语义。
3. `OntologyManifestImportsTest.java`：维持已跑v2 SHA与18个方法，没有在产品修复后变动测试。

当前候选指纹：

| 文件 | SHA256 |
| --- | --- |
| OntologyManifestImports.java | `41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5` |
| SkillCatalog.java | `7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03` |
| OntologyManifestImportsTest.java | `4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF` |

三文件 `git diff --check` 无输出、退出0。只有静态自检，不是编译、绿测或独立终审。源变更范围没有扩到AuthScheme、控制器/DTO、EX-04分页/非HTTP、迁移、依赖或其它服务。未 add/commit。

剩余：统一实际窗释放后先运行本卡三定向与真实PG/header正例；如新夹具路径暴露无关错误，准确归因并修测试前提，不放宽产品。Skill完整模块按EX-00精确方法集合对照，OPENAPI/手工Bearer兼容、零新增非通过/坏类/跳过/未执行、独立终审及最终提交收口仍待执行。
