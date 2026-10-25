# T20 命名测试输入前端消费者

2026-10-20。唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`；依据 AGENTS、implementation-brief、contracts/README、冻结详设册30 §1.7/4.2/5.4 与 `test-input-plan.md`。原 v1.2 契约未改。源码冻结 SHA 见 `frontend-test-inputs-sha256.json`。

## 已实现

- 调试页从真实应用详情读取 draftId 与 CHAT/FLOW 输入声明，默认使用现有 SchemaInputs 结构化表单；无声明时才显示明确标注的高级 JSON。调试运行直接收集结构化值。
- NamedInputs 按草稿调用真实 REST 接口，提供命名保存、元数据游标分页、显式使用、编辑、CAS 删除、刷新、空态、载入失败及权限错误。保存不要求手填 ID；PUT/DELETE 携带服务端 revision，409 保留本地编辑并提示重新载入。
- 当详情因草稿 Schema 变化返回400时保留元数据，渐进显示“用当前输入修正保存项”。该操作使用用户当前表单并明确保存，避免自动补参或重写旧输入。
- 详情请求发起后若当前表单被修改，迟到结果不会替换用户输入。草稿切换及卸载会中止请求并使异步副作用失效；DebugPanel 的状态恢复、开始及观察回调也核对当前草稿上下文。
- 提升对话框显示来源名称与保存版本，预期值初始为空，安全类型不设默认选项；文案明确“按完整输出精确比较，请独立填写预期值”。版本与用例标识自动生成，并可在展开项中修改。请求只发送 revision/expected/safety 等目标信息，不发送当前输入或运行实际输出。
- 同一次提升交付失败后保留完整 body 与 requestId，重试不改变来源 revision；修改提升内容时生成新 requestId。busy 防止重复提交。成功后提示已创建不可变数据集版本。
- 发布评测区 EvaluationInputs 分页读取真实数据集元数据，选中后读取不可变 cases/version，再交给原 `/admin/evaluations` API。保留渐进展开的手工用例入口。没有选择并载入数据集时阻断提交。
- SchemaInputs 仅新增向后兼容的 `replace` 与可选 change 事件；原 collect/默认值/校验保持兼容。未修改 Chat、SDK 源码、shared/api、events/observer、统计或生命周期组件；未引入依赖。

## 红绿证据

首轮真实 DebugPanel SFC 的 `named-inputs-browser.mjs red` 在实施前失败：结构化输入与命名消费者两项均为 false，见 `frontend-test-inputs-red.json`。随后逐步扩充真实 SFC 场景并运行，未以纯 helper 镜像测试替代组件交互。

手机验证另发现两个真实失败并保留红证据：全局 el-dialog 最小宽度使新对话框溢出（`frontend-test-inputs-mobile-red.json`、`frontend-test-inputs-promotion-390-red.png`）；原应用 shell 的桌面最小宽度使实际发布页在390px溢出（`frontend-test-inputs-app-mobile-red.json`、`frontend-test-inputs-evaluation-390-red.png`）。修复仅作用于新弹窗类及调试/发布页模式，未更改全局 tokens。

最终执行（工程根目录运行 browser 命令，web 目录运行 npm 命令）：

| 命令 | 结果与证据 |
| --- | --- |
| `node web/tests/named-inputs-browser.mjs final` | 18项检查通过，pageErrors为空；`frontend-test-inputs-final.json`、`frontend-test-inputs-browser.log` |
| `node web/tests/named-evaluation-browser.mjs` | 5项检查通过，pageErrors为空；`frontend-test-inputs-evaluation.json`、`frontend-test-inputs-evaluation-browser.log` |
| `npm test` | 原25文件83项全部通过；`frontend-test-inputs-unit.log` |
| `npm run build` | vue-tsc、Admin、WebApp、SDK构建及复制脚本均退出0；`frontend-test-inputs-build.log`。保留既有大chunk提示，未将提示当失败。 |

18项覆盖：声明式表单、命名入口、载入期间编辑保护、显式使用与对象字段、结构化调试请求、保存不手填ID、游标分页、CAS409保留编辑、Schema400纠正、显式安全类型、独立预期且失败重试完整body相同、改内容新requestId、删除CAS、空态、403、草稿切换拒绝晚到输入、手机无横溢、卸载无晚到副作用。运行实际输出存在后再打开提升对话框，预期仍为空；当前未保存输入不进入提升报文。

5项覆盖：未选择版本不能提交、实际Admin发布页390px不溢出、原评测API收到保存数据集的完整原始cases/version、手工高级入口兼容、实际Admin调试页390px不溢出。

Chrome 使用本机已安装 Chrome 与工程现有 Playwright，复用 root 所有的15175 fixture服务，未启停任何 root Vite/后端。截图已逐张读取检查：`frontend-test-inputs-desktop.png`、`frontend-test-inputs-390.png`、`frontend-test-inputs-promotion-390.png`、`frontend-test-inputs-evaluation.png`、`frontend-test-inputs-evaluation-390.png`、`frontend-test-inputs-admin-debug-390.png`。390px下表单、动作换行、弹窗宽度及原工作区导航均可见，无横向滚动。

## 边界

上述浏览器证据为真实 Vue SFC 配合测试目录内受控 HTTP 响应，验证消费者交互与准确报文。生产代码所有数据均来自真实 API，无假数据 fallback、浏览器输入库或前端评分器。后端默认本人+EDITOR、CAS、不可变性及跨域执行的真实PG证据由后端任务提供；当前尚未部署新路由/服务进行本前端真实API联调，等待 root 安排后继续。未提交、未发布、未另派 agent。单项提升消费者不代表多用例库完整治理或真实业务评测集验收。
