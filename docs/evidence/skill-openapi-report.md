# T07 OpenAPI 导入安全子集（2026-10-20）

工作根仅为 `G:/207/项目/算法重组工具/代码/agent-platform`。依据本工程 AGENTS.md、implementation-brief、contracts/README、冻结 detailed-design-v1.2 技能中心 §1.1/§5.3 及现有 HTTP 适配器实现。采用 test-driven-development、ponytail、verification-before-completion；未声称阅读 swagger-parser 上游源码。

## 可检查的结果

- JSON/YAML 文本预览生成带唯一 operationId 的候选列表及可见 diagnostics；缺失 operationId 时确定生成 `METHOD /path` 标识。生成步骤重用同一解析器，按选中的操作确定转换，不接受上传文档直接提供执行凭据、角色或服务地址。
- 管理员/编辑者显式补职责说明、执行属性、访问守卫、返回规范及失败语义后，经现有 Catalog 写入真实 PostgreSQL DRAFT。参数/输出 Schema 与 HTTP 映射由服务器转换。来源 `{kind,operationId,method,path,documentSha256}` 写入合同并参与规范摘要。
- 来源摘要用于追踪上传原文，不是文档签名，也不是授权证明。编辑可完善合同；服务端在行锁内保留省略的来源/映射，拒绝替换来源或与映射不一致的新 Schema。
- 新声明式 HTTP 映射把标量 path/query 与 JSON body 分开；body 参数名固定为 `body`，避免把 path/query 错发为 JSON。路径/查询以 UTF-8 百分号编码，路径点段/空值、非标量和未映射字段均拒绝；参数不能改变 scheme/authority。既有无映射 GET/JSON 路径保持原分支。
- 不创建连接/验收报告，不更新发布指针，不发布。实际 PG 测试证明导入后仍被 `CONNECTION_TEST_REQUIRED` 拦截；编辑后合同摘要变化，旧报告也不能替代新候选验收。

## API（统一 ApiResponse 包装）

`POST /admin/skills/import-openapi/preview`

```json
{"document":"OpenAPI JSON 或 YAML 原文"}
```

返回 data：`{documentSha256, operations:[{operationId,method,path,supported,diagnostics,inputSchema?,outputSchema?,httpMapping?,requiresAuth?}],warnings}`。不支持的 operation `supported=false` 且有可展示诊断，不能生成草稿。

`POST /admin/skills/import-openapi`

```json
{
  "document":"同一上传原文；服务器重新解析",
  "includeOps":["readItem"],
  "defaults":{"baseUrl":"https://api.example.test/base"},
  "overrides":{"readItem":{
    "code":"read_item","name":"读取条目","description":"人工填写能力、适用、不适用与参数示例",
    "execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},
    "guard":{"roles":["READER"],"attributes":{"id":[]}},
    "errorSemantics":[],
    "returnSpec":{"summaryTemplate":"已读取条目","structuredPath":"$","fileRefPaths":[]},
    "timeoutMs":30000
  }}
}
```

可选 `authRef` 仅 ADMIN 显式输入，使用现有 SecretVault 解析和租户约束；不回传引用，只回传 authConfigured。Bearer operation 要求显式已配置引用。返回 data：`{drafts:[实际DRAFT记录],warnings}`。批量导入通过外层数据库事务包围 Catalog 写入；无迁移。

## 安全与支持边界

- 全请求硬上限 2 MiB（含无 Content-Length 的流），上传文档 UTF-8 上限 1 MiB；文档层级 32、原始节点 20,000、operation 100、operation 参数 64。单 Schema 展开深度 16、展开节点 2,048、对象属性 128；本地引用链最多 16。YAML anchor/alias、重复键、多文档拒绝。
- swagger-parser 2.1.27 只接收已完成边界扫描的内存 JSON，关闭 resolve/resolveFully/resolveCombinators/resolveRequestBody，不调用 readLocation。只允许 `#/components/` 内部引用，远程/文件/相对 `$ref` 先于解析器拒绝；循环引用诊断拒绝。
- 支持 OpenAPI 3.0.x；GET/POST/PUT/PATCH/DELETE；默认序列化的非空标量 path/query；required 单一 application/json 请求体；明确且相同 Schema 的 2xx 单一 JSON 成功响应；单一 HTTP Bearer 安全方案。
- OpenAPI 3.1/Swagger2、HEAD/OPTIONS/TRACE、数组/对象 path/query、header/cookie 参数、复杂 style、optional/multipart/多媒体请求体、空成功响应、不同成功 Schema、allOf/oneOf/anyOf、nullable、readOnly/writeOnly 自动投影、循环 Schema、复杂安全组合、OAuth/API-key/Basic、operation/path servers/callback 等明确诊断，不生成伪可执行合同。
- 上传文档中的服务地址不被执行，要求人工明确 baseUrl。本轮不提供 URL 抓取（避免引入不受控网络入口）；文本上传覆盖设计允许的安全子集。
- 保留外部验收项：真实业务服务连接、实际模型选型验收、完整注册向导 UI 与端到端联调；本测试 HTTP 端点仅存在于 tests，不能计作真实业务验收。

## 测试证据

环境：Java 21.0.12，独立 PG `127.0.0.1:25432/agent_platform_v12`，项目 `.env` 仅在进程内加载；未打印密钥。Maven 使用本工程 `.tools/m2`，NIO 临时目录 `V:/.local/tmp`。

共同命令前缀：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn -B -ntp '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' -pl skill-registry -am test
```

针对性测试增加 `-Dtest=OpenApiImportsTest,OpenApiHttpMappingTest,OpenApiImportPersistenceTest,OpenApiImportControllerTest -Dsurefire.failIfNoSpecifiedTests=false`。

- `skill-openapi-red.log`：功能空实现时 6 测试出现预期失败（未拒绝外部 ref、没有候选/草稿）。
- `skill-openapi-mapping-red.log`：真实适配器与 Catalog 尚不支持 path 模板，2 项预期失败。
- `skill-openapi-boundary-red.log`：YAML alias、未配置认证引用、编辑破坏 Schema 映射三项失败；对应 `skill-openapi-boundary-green.log` 16/16 通过。
- `skill-openapi-api-red.log`：整个请求过大仍被绑定并接受，预期 413 而实际 200；对应 `skill-openapi-api-green.log` 19/19 通过。
- `skill-openapi-edit-red.log`：省略来源映射导致模板校验失败、null/尾随 JSON 未拒绝；最终修复在 `skill-openapi-final-green.log`。
- `skill-openapi-regression-green.log`：首次全模块 67/67 通过，无失败/错误/跳过；含真实 PG 与本地真实 HTTP 传输。
- `skill-openapi-final-green.log`：来源缺省保留、拒绝来源替换/Schema映射破坏、null/尾随JSON修补后，最终 skill-registry 全套 **68/68 通过，0 失败、0 错误、0 跳过**，BUILD SUCCESS（48.401秒）；admin-common 同一 reactor 亦通过。

自审：新类均位于 application/api/infra 分层；没有额外生产依赖、迁移、脚本执行或客户端代码生成。HTTP增量只在存在httpMapping时启用，旧GET/JSON保持原分支。來源是可追踪输入摘要，不能赋予认证/授权能力。不支持的协议语义保留可见诊断；不把本安全子集称为全OpenAPI支持。模型/服务fixture均在src/test内。

未重启或停止服务，未 package，未提交或远程发布；未使用相邻工程配置/数据/进程。
