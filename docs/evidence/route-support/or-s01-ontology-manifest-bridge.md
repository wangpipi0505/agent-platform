# OR-S01：本体发布manifest导入桥（ontorepo → agent-platform skill-registry）

> 日期：2026-10-27 ｜ 模块：skill-registry ｜ 支撑路线：路线三（本体约束调用的技能上架路径）
> 契约依据：ontorepo `contracts/p2-supply.md`（assistantTool 形状）、`p4-release.md`（正式路由），
> 字段以 `onto-contract/src/main/java/cn/ontorepo/contract/ContractGenerator.java` 为准。

## 现状（改动前）

两平台之间没有任何集成代码：ontorepo 五门禁发布产出的正式 manifest
（`{"manifests":[ContractGenerator.formalize 产物]}`，编译于 `PublicationService.compilePlan`）
只能手工逐字段录入技能中心。本项把这条管道接通。

## 映射设计（默认拒绝）

| manifest 字段 | 平台契约字段 | 说明 |
| --- | --- | --- |
| tool_code | code | 必须 ASCII 字母开头（平台编码规则），否则拒绝 |
| endpoint | endpoint | 必须匹配 `/onto/r/{spaceCode}/releases/{releaseId}/ops/{toolCode}` 发布路由形态，防任意URL注入 |
| input_schema / output_schema | inputSchema / outputSchema | 直传（output 已是 code/data/meta 信封） |
| exec_attrs | execution{readonly,sideEffect,idempotent,cancellable} + 顶层 timeoutMs | readonly_flag→readonly；timeout 必须在平台 100–600000ms 内 |
| guard_attrs | guard.guardAttrs | 仅声明属性名；运行时由 Actor 已验授权的非空集合供给（路线三 = OR-S02 绑定属性 dept_scope 等）；roles 留空 |
| error_semantics | errorSemantics | {match, semantic} 形状一致，直传 |
| return_spec | returnSpec | 直传（含 ontologyMetaPath） |
| ontology_meta | importSource（kind=ONTOLOGY，编辑不可变） | 空间/版本/内容摘要进来源，保证草稿可追溯 |
| risk_level | 不映射 | 平台按 readonly 旗标确定性推导 READ_ONLY/CONFIRM_REQUIRED，不让 manifest 主张绕过 |

**硬校验（默认拒绝）**：category=ontology_service、adapter_type=HTTP、invocation_kind=SYNC、
http_method=POST 逐一强制；`ontology_meta.preview` 必须为 false——preview 的 endpoint 指向
候选内部执行入口，导入会生产不可发布或指向错误路由的草稿。authRef 可选，非空时由
catalog 端强制 ADMIN+vault 校验（签发的 key 来自 OR-S02 管理端点）。

**整批语义**：与 OpenAPI 导入一致——先全部构建并结构校验（任一失败零创建），再逐个
`catalog.create`；创建期失败（如编码已存在）时已建草稿保留，与既有导入行为相同。

## 改动清单

| 文件 | 内容 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java`（新增） | manifest→契约映射 + 整批导入编排 |
| `skill-registry/src/main/java/cn/agentplatform/skill/api/OntologyImportController.java`（新增） | `POST /admin/skills/import-ontology-manifests`，2MiB 请求体上限 |
| `admin-common/src/main/resources/agent-service-authority.json` | 追加 1 条路由目录（gateway /admin/** 通配代理已覆盖，无需改 Routes.java） |
| `skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java`（新增） | 9 个失败路径/映射测试（mock catalog，仿 OpenApiImportsTest） |

## 测试

```
mvn -pl skill-registry test -Dtest=OntologyManifestImportsTest,MultipartFormMappingTest
OntologyManifestImportsTest: Tests run: 9, Failures: 0, Errors: 0
MultipartFormMappingTest:    Tests run: 13, Failures: 0, Errors: 0（回归）
```

覆盖：完整字段映射（含 importSource/authRef/guardAttrs）、preview 拒绝、非发布路由拒绝、
adapter/kind/method 硬校验、tool_code 编码规则、timeout 越界、guard_attrs 非法、
整批校验先行零创建、批内编码重复拒绝、缺 manifests 拒绝。

## 未覆盖范围与遗留

1. 真实端到端（ontorepo 发布 → 导入 → 连接测试 → 选型验收 → 发布）属路线三联调 IN-11，
   需 ontorepo 服务与发布 API key 就绪。
2. 语义级校验（input_schema 内部合法性等）由 catalog.create 统一收口，本项不重复实现。
3. guard_attrs 的属性值语义（如 region 取值域）由本体侧守卫声明与 OR-S02 绑定属性共同决定，
   平台侧仅按"非空授权集"约束。
