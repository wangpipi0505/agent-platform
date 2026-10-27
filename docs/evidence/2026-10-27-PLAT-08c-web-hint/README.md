# PLAT-08c：技能向导的 Schema 方言提示（T08.5）

## 目的

技能向导的 `inputSchema`/`outputSchema` 是裸 JSON 文本框，而后端（`SchemaDialect`）接受两种可空写法：`type: ["string","null"]` 与 `nullable: true`，并拒绝多具体类型联合与远程 `$ref`。写错只能在保存后从 400 里看到。本卡加**不阻塞保存**的实时提示。

## 改动

| 文件 | 性质 |
| --- | --- |
| `web/shared/schema-dialect.ts` | 新增，235 行纯函数：`analyzeSchema(text)` → `{ok, nullable[], problems[{path,message}]}` |
| `web/tests/schema-dialect.test.ts` | 新增，12 例 |
| `web/admin/src/SkillWizard.vue` | 两个 textarea 下加 `el-alert`/`el-text` 提示与 `computed` 派生，不参与 `next()`/`save`/请求体 |
| `web/tests/mcp-registration.test.ts` | 挂载器修复（见下），**断言零改动** |

`analyzeSchema` 只沿 JSON Schema 的已知子关键字遍历（`properties`/`items`/`not`/`if`/`then`/`else`/`contains`/`propertyNames`/`additionalProperties`/`unevaluated*`/`allOf`/`anyOf`/`oneOf`/`prefixItems`/`$defs`/`definitions`/`dependentSchemas`/`patternProperties`），递归上限 200 层，路径用 JSON Pointer（`~`→`~0`、`/`→`~1`）。

## 挂载器修复（为什么改测试文件）

`mcp-registration.test.ts` 的 `mount()` 把 SFC 用 `ts.transpileModule` 转成 CommonJS，再用 `createRequire(import.meta.url)`（即 `web/tests/`）解析裸 `require(id)`。SFC 里的相对导入本应相对**组件文件**（`web/admin/src/`）解析，于是 `../../shared/schema-dialect` 落到了 `agent-platform/shared`，4 个用例在模块加载阶段失败。

修法是给挂载器加 `sharedModule(id, fallback)`：从 `id` 取 `/shared/` 之后的名字，读 `web/shared/<name>.ts`，同样用 `ts.transpileModule` 转 CJS 后求值。只在 require 回调里加一条 `id.includes('/shared/')` 分支，断言、props、api 桩全部未动。生产代码保持仓库惯例的相对导入，不为测试改用裸包名。

## 测试与类型检查（本机实测）

```text
$ cd 代码/agent-platform/web && npx vitest run
 Test Files  36 passed (36)
      Tests  271 passed (271)
$ npx vue-tsc --noEmit
(无输出，exit 0)
```

先红后绿：`tests/schema-dialect.test.ts` 先于实现编写，实现前 12 例全部失败（模块不存在），实现后 12/12 通过。`mcp-registration` 25 例（含恢复的 4 例）同时通过。

构建：仓库根目录无 `index.html`，`npx vite build` 不是有效入口；按 `package.json` 的 build 脚本 `npx vite build --config admin/vite.config.ts` → `✓ built`。

## 未覆盖

1. 提示块无 DOM 断言：该挂载器的 `render` 返回 `null`，只验证 `setupState`；渲染由模板编译与构建保证。
2. 不做语义校验（`required` 与 `properties` 一致性、`pattern` 合法性、`$ref` 目标存在性），不拦提交。
3. 启发式遍历有盲区：boolean schema 作为 `oneOf` 成员、自定义关键字内的子 schema 不深入。
4. 每次按键全量解析，无防抖；超大 schema 粘贴时可能有输入延迟。
5. `sharedModule` 只支持零依赖 shared 模块（未来模块互相 import 会退回真实 require 并失败，已有注释说明）。
