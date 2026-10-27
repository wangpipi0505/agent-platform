# T01.3 PLAT-01 前端：评测用例断言编辑器

日期：2026-10-27
范围：仅前端（`web/`）。未改后端 Java、`pom.xml`、数据库迁移；未执行任何 `git add/commit/push`；未引入新依赖。

## 1. 改动文件

| 文件 | 说明 |
| --- | --- |
| `web/shared/assertion.ts`（新增，246 行） | 断言模式的唯一前端模型：7 种模式元数据、安全模式白名单、中文校验、提交形状规范化、只读展示解析 |
| `web/shared/AssertionEditor.vue`（新增，104 行） | 断言编辑器：模式下拉 + 模式相关参数输入 + 安全提示 + 模式说明 |
| `web/debug-panel/NamedInputs.vue`（修改） | 「提升为评测用例」对话框接入编辑器；提交真实 API；成功后回读服务器冻结用例；`readBack()` 在 108-131 行，`promote()` 在 132-165 行，对话框在 204-210 行 |
| `web/admin/src/EvaluationInputs.vue`（修改） | 载入数据集后展示每条用例的真实断言（只读查看面），见 40 行；手工用例说明补充 `assertion` |
| `web/tests/assertion-editor.test.ts`（新增，11 个用例） | 覆盖模式提交体、REGEX 空 pattern 拦截、安全锁定、后端 4xx 可读错误、服务器回读不一致 |

## 2. 契约核对结果（先读代码，未猜字段）

### 2.1 `assertion` 的真实 JSON 形状

`monitor/src/main/java/cn/agentplatform/monitor/domain/EvaluationAssertions.java`：

- 字段名：`public static final String FIELD = "assertion";`（:41）；缺失等价 `EXACT`（:49 `public static final Mode DEFAULT = Mode.EXACT;`）。
- 7 种模式（:79-87）：`EXACT, JSON_SUBSET, TEXT_EQUALS, CONTAINS, REGEX, SCHEMA, NUMERIC_TOLERANCE`。
- `parseMode`（:179-197）接受三种形状：`null/缺失` → EXACT；模式名字符串（大小写不敏感）；`Map` 且含字符串 `mode`。
- **`assertion` 里没有 `pattern` 字段**：`rejectExtraKeys`（:275-289）非数值模式只允许 `mode`，数值模式只允许 `mode,tolerance`；其它键直接 `EVALUATION_ASSERTION_INVALID`「断言配置含不支持的参数」。REGEX 的 pattern 就是用例的 `expected` 字符串（:355-371），CONTAINS 的片段、SCHEMA 的 Schema、NUMERIC_TOLERANCE 的期望数值同样在 `expected`（:210-257）。
- 规范化形状 `Spec.canonical()`（:93-104）：`NUMERIC_TOLERANCE` 输出 `{mode:"NUMERIC_TOLERANCE",tolerance:<number>}`；其余输出纯模式名（如 `"JSON_SUBSET"`）。前端 `buildAssertion()` 正是产出这两种形状（`web/shared/assertion.ts:140-183`）。
- REGEX 判分为整串匹配 `Pattern.compile(pattern).matcher(act).matches()`（:355-371），空 pattern 被拒（「REGEX 的 expected 不能为空模式（空模式会匹配任意输出）」），长度上限 2000，且用 RE2J 编译，不支持 lookaround/反向引用。
- 安全用例：`requireValid`（:133-141）在 `safety=true` 且模式非 EXACT/SCHEMA 时抛 `EVALUATION_ASSERTION_SAFETY_CONSTRAINT`，消息为「安全用例只允许 EXACT 或 SCHEMA 断言，当前为 X；包含/正则/子集/容差匹配可能把危险输出判为通过，已拒绝保存」。

### 2.2 哪个 API 接收用例断言

- `POST /admin/evaluations` → `EvaluationService.evaluate(@RequestBody Map<String,Object>)`（`monitor/src/main/java/cn/agentplatform/monitor/api/EvaluationController.java:18-22`）。用例只作为请求体 `cases` 数组出现；`EvaluationService.java:156` 读取 `c.get("assertion")`，:157-158 显式配置规范化为 `spec.canonical()`，:159 冻结 `assertionSnapshot`。
- 真正的「保存用例」入口是「提升」链路：`POST /admin/debug-inputs/{id}/promote`（`engine-runtime/src/main/java/cn/agentplatform/engine/api/TestInputController.java:13`），前端原本在 `web/debug-panel/NamedInputs.vue` 的「提升为评测用例」对话框调用。
- 查看用例断言：`GET /admin/evaluation-datasets/{appCode}/{version}`（`monitor/src/main/java/cn/agentplatform/monitor/api/DatasetController.java:23-26`），返回 `cases`（含 `assertion`，若有）；但 `DatasetService.get`（:141-149）只返回 `promoted_by=当前 principal` 的版本。

### 2.3 契约缺口（不改后端，仅记录）

**提升链路目前无法携带 `assertion`，是两个独立缺口叠加：**

1. `engine-runtime/src/main/java/cn/agentplatform/engine/application/TestInputService.java:229-231` 的请求白名单是 `{requestId,revision,datasetVersion,caseId,expected,safety}`，带 `assertion` 会 400 `INVALID_INPUT`「不支持字段: assertion」。
2. 即使放开白名单，同文件 :257-289 组装 `payload.cases` 时只放 `id/inputs/expected/safety`，**会把 `assertion` 静默丢弃**。
3. 下游 `monitor/src/main/java/cn/agentplatform/monitor/application/DatasetService.java:90` 的用例白名单 `Set.of("id","inputs","expected","safety")` 同样拒绝 `assertion`（400 `INVALID_DATASET`「不支持字段: assertion」）。

结论：**需要后端补字段**。建议最小改动：`TestInputService.promote` 白名单加入 `assertion`、组装 case 时仅在存在时放入 `body.path("assertion")`；`DatasetService.promote` 用例白名单加入 `assertion`（并沿用 `EvaluationAssertions.requireValid` 的校验口径）。前端已实现到「非 EXACT 模式提交时带 `assertion`」为止，缺口修复后无需再改前端即可端到端保存。

### 2.4 EXACT 的兼容决策

`assertion` 缺失即 EXACT（:41,49），且历史提升链路不识别 `assertion`。因此前端在 **EXACT 时省略 `assertion` 字段**（`web/debug-panel/NamedInputs.vue:143`），非 EXACT 才携带规范形状；这样默认用例的既有链路不被破坏，且语义完全等价。该决策有测试锁定（「omits the default EXACT assertion…」）。

## 3. 前端行为

- 模式下拉 7 项，附中文标签与语义说明：EXACT「整份输出逐字段相等」、JSON_SUBSET「期望值是输出子集」、REGEX「整串匹配」等（`web/shared/assertion.ts:47-97`）。
- 模式相关参数：REGEX 为 pattern 文本框（提示整串匹配、RE2 限制）；NUMERIC_TOLERANCE 为期望数值 + tolerance 数字输入；SCHEMA 为 Schema JSON 文本域；JSON_SUBSET/EXACT 为期望 JSON 文本域；TEXT_EQUALS/CONTAINS 为文本域。**所有参数都落在 `expected`，不塞进 `assertion`**。
- 提交前中文校验（`buildAssertion`，:140-183）：空正则「正则表达式不能为空」、超长「正则表达式长度不能超过 2000 个字符」、RE2 不兼容结构、非法 JSON、空 Schema/非对象 Schema、空包含片段、容差为空/负数等；失败时不发任何请求。
- 安全用例：`assertionOptions(safety)` 保留 7 项展示但禁用非 EXACT/SCHEMA（:127-134）；选择安全用例时若当前模式不安全，自动改回 EXACT 并提示「安全用例只允许严格相等或 Schema 校验，已将断言模式从 REGEX 改为 EXACT。」（`NamedInputs.vue:175-181`）；编辑器内再次拦截程序化切换（`AssertionEditor.vue:24-28`）。
- 保存走真实 `POST /admin/debug-inputs/{id}/promote`；成功后**回读** `GET /admin/evaluation-datasets/{appCode}/{version}`，展示服务器冻结的断言（`readBack()`，:108-131）。回读失败、找不到用例、或服务器断言与提交不一致时，都显示可恢复错误并提示「勿重复提交 / 到评测面板核对」，不伪造成功数据。
- 失败路径：4xx 直接显示后端消息；提升接口返回「不支持字段: assertion」时追加可恢复说明「当前提升接口还不能保存断言配置。可先改用 EXACT（严格相等）保存，或请后端在提升链路接收 assertion 后重试。」；对话框保持打开可修改重试；创建响应缺 `datasetVersion` 时视为结果未知并提示核对，不写假版本号。

## 4. 测试

### 4.1 定向 vitest（原始输出）

命令：

```
cd web && npx vitest run tests/assertion-editor.test.ts
```

输出：

```
 RUN  v4.1.12 G:/207/项目/算法重组工具/代码/agent-platform/web

 ✓ tests/assertion-editor.test.ts (11 tests) 193ms

 Test Files  1 passed (1)
      Tests  11 passed (11)
   Start at  20:41:08
   Duration  594ms (transform 83ms, setup 0ms, import 290ms, tests 193ms, environment 0ms)
```

覆盖点：

1. `builds the canonical assertion shape for every mode…`：7 种模式的 `expected`/`assertion` 形状逐一断言。
2. `omits the default EXACT assertion…`：EXACT 提交体不含 `assertion`，但仍提交独立填写的 `expected`。
3. `submits the selected assertion and re-reads the frozen server case`：JSON_SUBSET 提交体含 `assertion:"JSON_SUBSET"`，并断言发生了真实回读、notice 展示服务器冻结断言。
4. `blocks a blank REGEX pattern before sending the promote request`：`error === '正则表达式不能为空'`，POST 次数 0。
5. `shows a readable backend error, keeps the dialog open and never fabricates a created version`：后端 400「不支持字段: assertion」显示为可恢复错误（含改选 EXACT 的提示），notice 为空，无数据集回读、无假版本。
6. `reports a server assertion mismatch after create…`：服务器返回 EXACT 而提交 JSON_SUBSET 时报告不一致，不信任本地草稿。
7. `disables every non EXACT/SCHEMA option…`：安全用例下仅 EXACT/SCHEMA 可选，程序化 `setMode('REGEX')` 被忽略。
8. `switches a previously selected unsafe mode back to EXACT with an explicit note`：安全标记切换后的自动降级与提示。
9. 其余纯函数用例覆盖中文错误、`assertionKey` 缺失=EXACT、`describeAssertion` 的真实容差展示。

### 4.2 全量 vitest（回归）

命令：

```
cd web && npx vitest run
```

输出（尾部）：

```
 Test Files  35 passed (35)
      Tests  259 passed (259)
   Start at  20:41:28
   Duration  1.26s (transform 2.83s, setup 0ms, import 7.12s, tests 1.85s, environment 5ms)
```

（登录用例存在既有的 Pinia 提示性 stderr，非失败。）

### 4.3 类型检查（基线对比）

改动前基线：

```
cd web && npx vue-tsc --noEmit
# exit 0；grep -c "error TS" = 0（无输出）
```

改动后：

```
cd web && npx vue-tsc --noEmit
EXIT=0
```

结论：无新增错误（前后均 0 错误）。

## 5. 未覆盖 / 已知限制

- **浏览器 E2E 未跑**：未启动真实后端与浏览器，未验证 Element Plus 下拉/输入在真实 DOM 中的渲染与交互；测试用自定义 renderer 挂载真实 setup/watchers，Error 绑定到对话框内 `role="alert"` 是状态级断言，不是 DOM 断言。
- **非 EXACT 提升的端到端保存被后端缺口阻断**：前端会带 `assertion`，但当前 `TestInputService`/`DatasetService` 白名单会 400；需按 2.3 补后端。
- **已冻结数据集版本不可改**：`EvaluationService.java:203-224` 对同版本不同内容返回 409 `DATASET_IMMUTABLE`；因此编辑器只接在「新建提升版本」入口，未在已冻结数据集上做断言改写（避免必然 409）。
- 没有独立的「评测用例 CRUD 页面」；`web/admin/src/pages/EvaluationsPage.vue` 只是报告列表。本次以既有唯一的用例创建入口（提升对话框）+ 数据集用例只读列表作为「编辑/查看界面」。
- 未用 `ajv` 在浏览器本地预校验 JSON Schema 结构（`ajv` 已是现有依赖，但避免误判后端 RE2/JSON Schema 方言，留给后端权威校验并展示其错误）。
- 回读依赖 `promoted_by=当前 principal`：换账号查看他人提升的版本会 404，界面提示到评测面板核对。

## 6. 约束遵守

- 未改后端 Java、`pom.xml`、迁移。
- 未执行 `git add / commit / push`。
- 未引入新依赖（`package.json` 未改）。
- 未触碰工作区中他人未跟踪文件。
