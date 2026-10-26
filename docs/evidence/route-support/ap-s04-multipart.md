# AP-S04：技能契约与HTTP适配器支持A2 multipart文件输入

> 日期：2026-10-27 ｜ 模块：skill-registry ｜ 支撑路线：路线一（IN-07 文件链的软件前提）
> 状态：实现与单元测试完成；真实 8080 multipart 联调属 IN-07，不在本项。

## 现状（改动前）

- HTTP 契约的 `httpMapping` 只允许 `{path, query, body}` 三键，`body` 仅为 JSON 体（`OpenApiHttpMapping.validate` 硬性限定"requestBody只支持单一application/json"）；
- `SkillAdapters.http()` 一律 `contentType(APPLICATION_JSON).body(Json.tree(...))`，没有任何 multipart 能力；
- OpenAPI 导入遇到 multipart/form-data 直接报错。

## 设计与默认拒绝语义

- 契约在 `httpMapping` 中可选声明 `multipart`（与 `body` 互斥，可与可选 path/query 组合）：
  ```json
  "httpMapping": {
    "path": [], "query": [],
    "multipart": {
      "fields": [{ "name": "payloadType", "from": "payloadType" }],
      "files":  [{ "name": "file", "from": "fileRef", "filename": "trajectory.csv",
                   "maxBytes": 67108864, "allowedContentTypes": ["text/csv"] }]
    }
  }
  ```
- **文件字节只来自产物**：`from` 绑定的参数值必须是 `art_[hex32]` 产物ID或受控下载路径
  （`/admin/artifacts/{id}/download`、`/v1/runs/{run}/artifacts/{id}/download`），
  字节经 `ArtifactStore.download` 按**租户+主体+READY+未过期**解析（runId 传 null，
  与 /admin 下载语义一致，允许引用此前运行产物）；URL 本身不是授权凭据。
- **不变量**：所有部分构建期全部必填（缺失即 PARAM_ERROR）；`maxBytes`∈[1, 64MiB]；
  `allowedContentTypes` 必填且禁止通配/参数；`filename` 由契约固定（禁路径注入），
  文件 Content-Type 以产物登记为准且必须在白名单内；元数据字节数与实际流不一致按
  RESULT_UNKNOWN 拒绝（防静默截断）。
- **未声明 multipart 的契约永远不会发出 multipart 请求**（`declared()` 门控，默认拒绝）。
- multipart 仅支持同步调用（SUBMIT_QUERY/CALLBACK 声明即校验失败）：异步恢复语义以请求体可重放为前提，文件流不可重放。

## 改动清单

| 文件 | 内容 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java`（新增） | 声明校验（结构/上限/白名单/Schema一致性）+ 表单组装（字节解析、防截断读、白名单判定） |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/OpenApiHttpMapping.java` | validate 允许 `{path,query,multipart}` 组合并委托校验；request 对 multipart 契约返回 endpoint+null body；path/query 键可缺席 |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | http() 增加 multipart 分支（MULTIPART_FORM_DATA + MultiValueMap），JSON 分支语义不变 |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/OpenApiImports.java` | 导入接受 `multipart/form-data` 请求体：标量属性→fields，`format:binary`→files（filename=name.bin，白名单占位 octet-stream，**发布前必须在契约评审收紧**）；所有部分 required |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartFormMappingTest.java`（新增） | 13 个失败路径测试 |

## 测试

```
mvn -pl skill-registry test -Dtest=MultipartFormMappingTest
Tests run: 13, Failures: 0, Errors: 0
```

覆盖：multipart与JSON体互斥 / GET拒绝 / 异步拒绝 / 白名单通配与空表拒绝 / filename路径注入拒绝 /
maxBytes越界 / 输入Schema一致性 / 未声明契约不进入multipart构建 / 缺参数 / 超限 / 类型不符 /
非法产物引用 / 元数据与流字节数不一致 / 构建产物文本+文件部分与filename / request端点解析与未映射参数 /
受控下载路径与art直引等价。

## 未覆盖范围与遗留

1. **真实 8080 multipart 联调**（上传→preview/fit/retime 四步链）属 IN-07，需先完成 12 技能上架。
2. **PG 集成测试被环境阻塞**：`AGENT_DB_PASSWORD` 对测试用户 `agent_v12` 认证失败
   （`FATAL: password authentication failed`），AsyncHttpExecutionTest 等既有集成测试在本机同样无法连接——
   属 IN-01 环境责任项（需要测试库真实凭据），与本次改动无关；编译与单元测试均绿。
3. 异步（SUBMIT_QUERY/CALLBACK）的 multipart 有意不支持（校验层拒绝），如未来需要须先解决文件流重放语义。
4. 前端未新增结构化 multipart 配置表单：契约经 OpenAPI 导入或 JSON 编辑流转，专用表单列 P2。
5. OpenAPI 导入的文件白名单以 `application/octet-stream` 占位，四道门契约评审必须收紧后才可发布（代码注释已标注）。
