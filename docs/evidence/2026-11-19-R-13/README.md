# R-13 前端 §9.4 基线 13 例失败清零——2026-11-19

分支 `close-wt-r13`（基于 5bde1c30）。目标：`npx vitest run` 全量由 13F/621P → **0F/≥634P**，不再以"零新增"作为通过口径。13 例全部**修复**（0 判废）。

## 一、前后对照

| 指标 | 修前（基线，2026-11-19 复跑） | 修后（2026-11-19） |
| --- | --- | --- |
| 测试文件 | 4 failed / 78 passed (82) | **82 passed (82)** |
| 用例 | **13 failed / 621 passed (634)** | **0 failed / 634 passed (634)** |
| 失败分布 | assertion-editor 6 / mcp-registration 2 / run-detail-navigation 4 / satellite-planning 1 | 无 |
| 通过口径 | "较 §9.4 基线零新增" | **绝对 0 失败**，总数 634 不降（0 判废，无需替代断言补回） |

## 二、逐例处置（13/13 修复，根因与改动）

### 组 1：tests/assertion-editor.test.ts（6 例）——修复（夹具漂移）

根因：`debug-panel/NamedInputs.vue` 新增 `import { parseJsonInput } from '../shared/json-input'`（输入 JSON 解析收敛为 shared 模块），而该测试的 SFC 转译夹具（compileScript → CJS → 自定义 require）没有该模块的解析分支，落到真实 `require` 后报 `Cannot find module '../shared/json-input'`，凡经 `mountNamed()` 的 6 个用例全数挂掉（组件语义本身无回归）。

| 用例 | 失败现象 | 处置 |
| --- | --- | --- |
| submits the selected assertion and re-reads the frozen server case | Cannot find module '../shared/json-input' | 修复 |
| omits the default EXACT assertion but still submits the independently entered expected value | 同上 | 修复 |
| blocks a blank REGEX pattern before sending the promote request | 同上 | 修复 |
| shows a readable backend error, keeps the dialog open and never fabricates a created version | 同上 | 修复 |
| reports a server assertion mismatch after create instead of trusting the local draft | 同上 | 修复 |
| switches a previously selected unsafe mode back to EXACT with an explicit note | 同上 | 修复 |

改动：`tests/assertion-editor.test.ts` 顶部 `import * as jsonInput from '../shared/json-input'`，require 分支补 `id.endsWith('/json-input') ? jsonInput : …`。断言零放宽。

### 组 2：tests/mcp-registration.test.ts（2 例）——修复（夹具漂移）

根因：`admin/src/ResourceDetail.vue` 新增两个 shared 依赖——`shared/artifact-upload`（内部 `import './mock-artifacts'`）与 `shared/useUnsavedChanges`（内部依赖 vue-router 与 `./components/DangerConfirm`）。测试夹具的 `sharedModule()` 把 shared 模块按 web/shared 转译执行，但对其**嵌套相对导入**直接交给 createRequire 落到 tests/ 下解析 → `Cannot find module './mock-artifacts'`；两个 mount('ResourceDetail') 的用例挂掉（selectedDefinition 语义本身无回归）。

| 用例 | 失败现象 | 处置 |
| --- | --- | --- |
| uses the selected nested definition, never the published top-level binding | Cannot find module './mock-artifacts' | 修复 |
| discards stale resource loads after switching code or account | 同上 | 修复 |

改动：`tests/mcp-registration.test.ts` 的 `sharedModule()` 升级为：①嵌套相对导入（`./x` → shared/x）递归按同一规则解析并加实例缓存；②对模块内 `import.meta`（mock-artifacts 的 `import.meta.env.VITE_MOCK`）以 `{ env: {} }` 桩替换（vitest 环境非 mock，语义一致）。真实模块语义保留，不用浅桩；断言零放宽。

### 组 3：tests/run-detail-navigation.test.ts（4 例）——修复（夹具漂移）

根因：MNT-01 深链语义测试（锁 route.query 读取/跟随/显式 nodeId 优先/普通详情不受影响）随 RunDetail.vue 演进失效：①`connect()` 新增 `if (import.meta.env.VITE_MOCK === 'true') return;`（M5-2 演示模式开关），CJS `new Function` 内 `import.meta` 直接 SyntaxError；②新增 `import { reasonCodeText } from '../../shared/reason-code-text'`，夹具无该模块解析分支。4 个用例全数挂掉（深链语义本身在组件中完好保留）。

| 用例 | 失败现象 | 处置 |
| --- | --- | --- |
| Hash路由深链从路由query读取nodeId定位（MNT-01缺陷实红） | SyntaxError: Cannot use 'import.meta' outside a module | 修复 |
| 同run修改query时定位跟随，不重建组件 | 同上 | 修复 |
| 显式nodeId输入优先，覆盖无router的既有嵌入用法 | 同上 | 修复 |
| 普通运行详情不受影响（锚：无nodeId时过滤为空） | 同上 | 修复 |

改动：`tests/run-detail-navigation.test.ts`：①转译产物 `\bimport\.meta\b` → `importMetaShim`，以第三实参注入 `{ env: {} }`（非 mock → 保持真实 SSE 分支语义）；②顶部 `import * as reasonCodeText from '../shared/reason-code-text'` 并补 require 分支。断言零放宽。

### 组 4：tests/satellite-planning.test.ts（1 例）——修复（mock 演示线偏离自身契约）

根因：`shared/mock-transport.ts` demo-chat 在产生制品时统一以 `input:{planId}` 追加 `planning-file-export` 与 `planning-file-download` 两条工具调用，而 `planning-file-download` 的目录契约（mock-tool-catalog `definition('下载规划制品',{artifactId},['artifactId'])`，经 `normalizeDemoToolSchemas` 规范化后生效于 planning-scenarios 目录）声明 `required:['artifactId']` + `additionalProperties:false`——mock 数据自己违背了自己声明的契约（§9.4 归属"M5-1/M5-2 mock 资产化对齐"）。

| 用例 | 失败现象 | 处置 |
| --- | --- | --- |
| 工具链实际输入输出符合各自声明的Schema | planning-file-download输入不符合契约: expected false to be true | 修复 |

改动：`shared/mock-transport.ts`（mock 演示线最小修，一行）：file-download 的输入改为 `{artifactId:result.artifacts[0].id}`（按制品定位下载入口，与 mock-selected-tools 链的口径一致）；file-export 维持 `{planId}`。不放宽测试断言（该用例本就是契约校验器）。

## 三、改动文件清单

| 文件 | 性质 |
| --- | --- |
| `web/tests/assertion-editor.test.ts` | 测试夹具修复（+json-input 解析分支） |
| `web/tests/mcp-registration.test.ts` | 测试夹具修复（sharedModule 嵌套依赖+import.meta 桩+缓存） |
| `web/tests/run-detail-navigation.test.ts` | 测试夹具修复（import.meta 桩+reason-code-text 分支） |
| `web/shared/mock-transport.ts` | mock 数据最小修（file-download 输入对齐目录契约，1 行） |

组件/被测逻辑零语义改动；13 例断言零放宽、零判废。

## 四、验证

- `npx vitest run`（web/）：**82 文件全过，634/634 用例通过，0 失败**（2026-11-19， Duration 3.76s）。
- `npm run build`（vue-tsc --noEmit + admin/webapp/assistant.js 三端 vite build + copy-assistant）：通过（见提交记录）。
- 台账 §9.4 改写建议：见汇报（基线表由主线代录）。
