# 应用生命周期 G4 后端补齐证据

依据：冻结详设 v1.2，管理与发布 F1/F2/F3、§3.5 和状态机“任意→复制→新应用 DRAFT”。工作区仅本独立工程；未读取或复用相邻工程，未提交 Git、外发或重启服务。

## 已实现接口契约

统一响应沿用 `ApiResponse`，以下均描述 `data`。认证身份及租户来自已验签 JWT；写接口沿用 `EDITOR`/`ADMIN` 校验。

| 接口 | 输入 | 输出/约束 |
| --- | --- | --- |
| `POST /admin/apps/{sourceCode}/copy` | JSON `{ "code": "new_app", "name": "新应用" }` | 返回新应用详情。code/name 必须为字符串，沿用现有编码与名称校验；编码冲突 `409 APP_CODE_CONFLICT`、无效输入 `400 INVALID_INPUT`、源应用不可见 `404 APP_NOT_FOUND`。只使用 code/name，不接受 body 中 tenant/status/version/config 覆盖。 |
| `GET /admin/apps` | 可选 `q,code,domain,bizCategory,tag,status` | 保留现有数组。q 对名称/编码不区分大小写的字面子串；其他四项精确匹配，tag 精确成员匹配；所有条件 AND。status 枚举 `DRAFT/PUBLISHED/DISABLED/ARCHIVED`，未知值 400。无 status（含空白）排除 ARCHIVED，只有显式 `status=ARCHIVED` 可查归档。按更新时间降序、编码稳定排序。 |
| `GET /admin/apps/{code}/lifecycle-logs` | 路径 code | 数组 `{id,actor,action,fromState,toState,detail,createdAt}`，按 id 降序。当前应用及当前租户限定；无访问权返回 404。读者可读，不能复制或修改。 |
| `GET /admin/apps/{code}/versions/diff` | `fromVersion` 和 `toVersion` 正整数 | `{appCode,fromVersion,toVersion,fromSha256,toSha256,changes}`。两版本必须同属当前租户当前应用，缺失返回 `404 VERSION_NOT_FOUND`；非正整数 400。同版本返回空 changes。 |

版本差异示例：

```json
{
  "path": "/config/temperature",
  "operation": "REPLACE",
  "before": 0.1,
  "after": 0.7
}
```

`changes` 使用确定性排序的 JSON Pointer（`~`→`~0`，`/`→`~1`）。`ADD` 只有 after，`REMOVE` 只有 before，`REPLACE` 两者都有；显式 null 和字段不存在可区分。对象逐字段比较，数组作为完整值比较。比较不可变版本中的业务配置及应用元数据（type/name/description/domain/bizCategory/tags/config/definition）；排除生成的发布身份、评测证据、依赖冻结元数据及展示 layout。不会访问当前草稿作为对照源，也不会写回任何版本。

## 数据与事务边界

- 复制支持 DRAFT、PUBLISHED、DISABLED、ARCHIVED，也支持已发布后另有未发布草稿的应用；取当前草稿，沿用草稿行锁保障与编辑/发布竞争同一边界。
- 新应用 status=DRAFT、currentVersionNo=null、draftRev=1、draftNo=1。复制 type/description/domain/bizCategory/tags/config/definition/layout，新 code/name 来自结构化输入。
- 用户配置中的 modelBinding/modelOverride、技能版本等引用保持原样；冻结候选模型在 `candidate_model_snapshot`，评测/预检在发布报告，发布记录及依赖快照在版本表，这些均不按源复制。集成凭据、发布引用关系与外服务运行数据不复制。
- 新应用、新草稿、CREATE/COPY 来源留痕在同一事务；COPY detail 包含 sourceAppCode/sourceDraftRev/sourceCurrentVersionNo，源无发布版本时保留 null。
- 额外补齐原 CREATE 与元数据 MODIFY 日志，放在原事务内；原保存/发布/停用/归档/删除/回滚状态边界及 draftRev 并发语义保持。没有新增归档恢复接口或扩大 enable 范围。
- 沿用现有表结构，无新迁移、依赖或抽象层。列表和日志沿用数组，未引入分页响应契约。

## 红绿验证

命令（每轮相同，仅运行应用模块及其公共依赖）：

```powershell
& scripts/test.ps1 -Modules app-management
```

真实 PostgreSQL 16.15：`127.0.0.1:25432/agent_platform_v12`，本工程集成测试库；Flyway app schema=103。测试不使用 H2、内存库或相邻系统。用例以随机 tenant 隔离数据；未清理开发库。MockMvc 只承担 HTTP 请求映射，业务访问真实 PG。

1. `application-lifecycle-red.log`：31 tests，8 failures，0 errors。新复制/日志缺路由（404），diff 被旧动态路由识别为版本号（400），筛选被忽略。既有应用 22 tests 仍通过。
2. `application-lifecycle-audit-red.log`：33 tests，1 failure，0 errors。新增创建/元数据变更留痕断言期望 `[MODIFY,PUBLISH,CREATE]`，实际 `[PUBLISH]`，据此补齐两条原事务日志。
3. `application-lifecycle-green.log`：2026-10-20 04:44:37 +08:00，app-management 33 tests，0 failures/errors/skips（新增生命周期 11，既有发布事务 13、领域规则 9）；公共依赖 admin-common 同轮通过；BUILD SUCCESS。

覆盖：已发布且有新草稿复制、所有源状态、版本/发布证据/集成凭据隔离、引用保留、code 冲突/非法/非字符串、忽略伪造租户及状态输入、读者拒写、跨租户源不可见、六维组合筛选及字面通配符、显式归档、未知状态、租户日志/稳定顺序、diff 静态路由/正整数/跨应用/跨租户/不可变版本/未发布草稿隔离/JSON Pointer/null。

格式：Google Java Format 1.25.2 `--aosp`，仅格式化下列三个 Java 文件。自审确认 SQL 列名来自硬编码白名单，值均参数化；复制无发布表写入；diff 无写操作；日志和版本查询均带 tenant/code。

## 明确文件清单

- `app-management/src/main/java/cn/agentplatform/app/api/AppController.java`
- `app-management/src/main/java/cn/agentplatform/app/application/AppService.java`
- `app-management/src/test/java/cn/agentplatform/app/application/ApplicationLifecycleTest.java`
- `docs/evidence/application-lifecycle-completion.md`
- `docs/evidence/application-lifecycle-red.log`
- `docs/evidence/application-lifecycle-audit-red.log`
- `docs/evidence/application-lifecycle-green.log`

前端未改动，待后续接入。独立评审由主智能体按此文件清单进行，本报告只记录实现者自审与测试证据。
