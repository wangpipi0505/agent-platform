# CR-10 语义与目标产品挂载

2026-11-04。仅本项必要功能检查，最多两轮；未启动PG、外部模型/业务服务、全量测试或浏览器视觉验收。未使用superpowers流程。主席唯一提交/台账回填；本证据不更新项目完成口径。

## 实现

- AP菜单“语义与目标”挂载原SemanticBindingsPanel，复用新SemanticConfigurationStep。该组件自读实际应用、受控正式catalog与技能版本，通过Schema参数表单、对象/动作/扩展选项保存真正的应用config.goals和semanticBindings引用，携带原draftRev并确认返回修订递增，才发saved事件。已有配置保留；版本冲突不自动覆盖。当前页技能目录读取预算64项，正式版本候选预算16项，定义投影截断显式显示。
- 公共新读口GET /admin/skills/semantic-catalog仅从当前Actor部署绑定列候选，再逐一用原OntologyManifestClient/SemanticDefinitionClient复核当前OR授权/正式版本四元组。失败分项可见，不返回secret_ref，不接受浏览器自定身份/目标URL/release集合。来源选择不是授权票据。
- SemanticBindingStep将已发布只读技能版本作为选择；组合父组件从正式扩展取extensionDigest，只发后端允许的引用字段，避免原objectType/factFields/ruleRefs被目录白名单拒绝。目录与应用草稿是两次提交；应用写失败时重试先复用相同ACTIVE不可变修订。
- 新RunAuthorityPanel在原Chat和RunDetail挂载SemanticEvidence/GoalOutcomePanel；assistant.js以独立authority helper使用同一server-authority解码。只读服务端顶层goals与独立semantic-results，模型output伪造同名数据不起作用。安全数字callId规范化，UNKNOWN/部分/失败保持，读取被拒即清旧投影；按原时效显示。CR-07体验/配置版本逻辑保留。
- OR原发布与服务工作台增加“语义与身份”页签。READY版本下拉、不可变CLASS/MAPPING/PROPERTY/RELATION/RULE与批准端点生成扩展选项，原IdentityLinksPanel改用后端要求的spaceCode路径。原OWNER+功能权限、expectedDraftRev保留，切空间/版本/主体清空编辑/解析/列表并隔离迟到响应。

向导挂载协议：`SemanticConfigurationStep` props `{appCode:string,disabled?:boolean}`；emit `saved({code,draftRev,config})`。父页只在已建真实appCode时挂载，saved后刷新持有的应用/修订，不再次PUT。主席负责OnboardingSessionWorkspace父页挂载，本席没有修改该文件。

## 必要检查

第1轮：

- AP `node node_modules/vitest/vitest.mjs run tests/semantic-runtime.test.ts tests/semantic-product-mounts.test.ts tests/chat-session-restore.test.ts tests/run-detail-navigation.test.ts`：4文件41/41通过。覆盖真实SFC setup保存实际配置API路径/原draftRev、保留原配置、回执后emit、退出清理、服务端UNKNOWN和模型伪造隔离、绑定白名单，以及原会话恢复/运行导航断言。既有测试只补新增只读API/认证上下文夹具，没有放宽断言。
- AP全项目vue-tsc：失败2项，均新SemanticConfigurationStep重复读取unknown字段未收窄；改为局部rawRefs/configuredRefs明确收窄。
- OR新增测试collect失败：未直接安装compiler-sfc；测试置src还触发2个类型错误。移到本工程web/tests，使用本工程已装Vue真实包目录解析其compiler-sfc依赖，未增依赖、未使用邻仓依赖目录。
- Java生产编译通过，testCompile被连接席在途跨app/engine导入的测试阻断。连接席随后将该跨模块探针移出skill/src/test/java到自己的证据目录，不修改其断言、不新增跨业务jar依赖。

第2轮（最终）：

```powershell
# AP根目录，JAVA_HOME=JDK21
mvn -o -pl skill-registry -am '-Dtest=SemanticCatalogTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# AP/web
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
# OR/web
node node_modules/vitest/vitest.mjs run --config apps/onto-admin/vitest.config.ts tests/semantic-product-mounts.test.ts
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p apps/onto-admin/tsconfig.json
```

结果：Java 3/3通过、BUILD SUCCESS；OR实际扩展/身份面板使用生产API客户端的提交路径/固定版本/当前draftRev及切空间清理检查2/2；两端全项目vue-tsc exit 0。遵照主席要求未重复首轮已通过的41例。最终只作格式/声明分行整理与不变量注释，没有新增业务行为，不开展第三轮。

限制：组件HTTP/JDBC/远端目录替身仅验证具体请求形状、分项拒绝与界面逻辑，不代表PG落库、真实发布/激活成功或跨平台真实用户权限已验收。浏览器菜单点击、视觉、真实服务写读/撤权链、三路线场景仍需用户后续授权；保存目录/草稿不声称业务完成或正式上线。CR10依赖transport席的releaseIds/正式扩展descriptor支持三处，提交清单须一并纳入这些窄支持hunk（其CR11其他改动不属于本项）。
