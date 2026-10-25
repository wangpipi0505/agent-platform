# 异步评测消费者 P2 修复交接

日期：2026-10-20。范围：独立评审 `review-frontend-evaluation-async.md` 中 P2-1 / P2-2。本补丁已完成实现与受控回归，等待原 reviewer 独立复验；原 REQUEST_CHANGES 记录与原十文件冻结清单未改写。web target 现释放给 root。

## 修改与边界

唯一修改的生产文件是 `web/admin/src/EvaluationPanel.vue`。

P2-1：创建 POST 发出后，身份/权限 watcher 会中止本地请求并使旧响应失效；旧实现同时清除 creating，却没有留下 ACK 未确认状态。现在在发送前记录“候选身份 + tenant + principal”的未确认标记，身份、权限、token 或候选切换不会删除原标记。同一身份返回原候选时继续禁止重复创建；旧 ACK 仍被丢弃。标记位于组件模块的响应式内存中，能跨本页 SPA 登录退出造成的组件重挂载保留，只存候选/身份键与 datasetVersion，不存 token、报告、用例或业务票据，不使用 localStorage。当前有效 ACK 或明确 4xx 可清除此标记；人工找回时，只有当前候选、原创建人及相同 datasetVersion 的有效详情能解除它。其他人的报告或不同数据集不能解除原创建的不确定性。

P2-2：列表刷新与详情读取现在分别使用 listLoading / choosing 及各自序列。创建入口函数与按钮均检查 choosing，选择入口检查创建/详情状态。详情开始到其有效 finally 期间禁止新建；旧列表 finally 不会解除详情锁，旧候选详情 finally 不会解除新候选的锁。详情失败可释放它自己的锁，并显示原错误。页面新增简短的详情载入提示。

未修改 App.vue、EvaluationReport.vue、evaluation-observer.ts、评分/API、后端、Chat/SDK/observeRun、命名输入、DebugPanel 及冻结手机 CSS；ERROR 双击恢复经评审为非发现，本次没有扩改。未触碰 MCP 向导或 root 的 smoke/scheduler 脚本。未启停任何服务、未执行 Maven/打包 JAR、未提交或发布。

## 红证据

- `npm test -- --run tests/evaluation-panel-review-fixes.test.ts`：修改前实际 SFC setup 的 4 项测试全红，见 `frontend-evaluation-review-fixes-unit-red.log`。覆盖权限恢复后丢失未知 ACK、切换回原候选丢失标记、详情在途仍可调用创建、列表/详情锁没有分离。最后一项包含对缺失 choosing 状态的断言，因此不将该断言单独冒称为已有界面行为反例。
- `node tests/evaluation-review-fixes-browser.mjs red permission`：真实 Chrome 挂载原 App/SFC，受控接口延迟创建 ACK；撤销再恢复权限后实际产生 2 次创建 POST，期望为 1，见 `frontend-evaluation-review-fixes-red-permission.log/.json`。
- P2-2 的已有真实 Chrome 行为反例来自原独立评审：详情 GET 在途时 createEnabledDuringExistingRead=true、createPosts=1，原评审及截图保留。本补丁自身首轮 detail Chrome 红脚本因折叠 details 下按钮定位超时，尚未到达竞态断言，相关 `red-detail.log/.json` 原样保留，明确排除为有效行为红；随后修正测试交互并完成绿测。
- 后加的原创建人/数据集限定、旧候选 finally 两项单元回归加入最终 6 项，不宣称它们属于上述修改前 4 项红测。

## 最终验证

均在本工程 web 目录执行，命令退出码为 0：

| 命令 | 结果与记录 |
| --- | --- |
| `npm test` | 27 文件 / 102 测试通过，原 96 + 新增 6；`frontend-evaluation-review-fixes-unit-all.log` |
| `npm run build` | vue-tsc 与 Admin / WebApp / SDK 三构建通过；`frontend-evaluation-review-fixes-build.log`，保留既有 chunk 大小提示 |
| `node tests/evaluation-review-fixes-browser.mjs green` | 新增 8 项真实 Chrome / 真实 SFC、受控 API 场景全绿，pageErrors=0；`frontend-evaluation-review-fixes-green.log/.json` |
| `node tests/evaluation-review-fixes-regression.mjs browser` | 原 3 项异步界面检查通过；`frontend-evaluation-review-fixes-recheck-browser.log`、`frontend-evaluation-review-fixes-recheck-patch.json` |
| `node tests/evaluation-review-fixes-regression.mjs scenarios` | 原 13 场景通过，pageErrors=0；`frontend-evaluation-review-fixes-recheck-scenarios.log/.json` |
| `node tests/evaluation-review-fixes-regression.mjs compat` | 原命名输入 18 + 评测输入 5 项通过；`frontend-evaluation-review-fixes-recheck-compat.log`、`frontend-evaluation-review-fixes-recheck-compat-regression.json`、`frontend-evaluation-review-fixes-recheck-compat-evaluation.json` |

新增 8 项 Chrome 检查覆盖：权限恢复保留未知 ACK；详情/创建互斥；连续实际列表刷新不解除详情锁；详情失败释放自身锁；候选往返保留原未知 ACK；账号往返保留原未知 ACK；他人报告不能解除原未知 ACK；退出登录再进入仍保留原未知 ACK。逆序的两个在途列表响应、旧候选详情 finally、错误数据集不可解除标记另由真实 SFC 单元回归验证。

重放 runner 读取原冻结测试并仅替换证据输出前缀为 `frontend-evaluation-review-fixes-recheck`，执行后清理临时脚本。原测试与原报告未改写；原兼容 runner 中已授权的报告元数据适配继续保留，不改 inputs / expected / safety、候选摘要或业务断言。

实际截图已读图检查：

- `frontend-evaluation-review-fixes-unknown-1440.png`：未知 ACK 提示、找回入口与禁用创建按钮清晰。
- `frontend-evaluation-review-fixes-unknown-390.png`：相同流程可读，按钮与提示没有横向溢出。
- `frontend-evaluation-review-fixes-detail-1440.png`：选择 RUNNING 现有报告后出现载入提示，创建保持禁用。

## 冻结与未测范围

新补丁 SHA 清单：`frontend-evaluation-review-fixes-sha256.json`，包含本次唯一生产修改与 4 个新增测试/runner 文件，以及原十文件逐项 before/after。原十文件中仅 EvaluationPanel.vue 变化，其余九个 SHA 不变；原清单文件及 REQUEST_CHANGES 评审本身的 SHA 也被记录。DebugPanel、NamedInputs 受保护 SHA 单独核对。

EvaluationPanel.vue 最终 SHA256：`565f4db747169ba30144e6696b515094d202258dc0feb5bdea164396b2b5441d`。

本补丁没有运行真实 G5 后端链路；上述 8 / 16 / 23 项属于 Chrome 受控 API 验证，不能当作真实外部等待、真实审批或部署后 G5 验收。原真实命名输入 9 链历史保持原样。实际 G5 复验等待 root 完成部署与独立审查后统一执行。

原创建 API 没有客户端幂等键，故创建结果不明时仍需从服务端已受理列表找回，不能自动重发。未确认标记只在当前页面会话内存保留，整页刷新/关闭后的持久恢复没有新增保证；服务端列表范围及权限语义保持原有接口边界。没有通过缓存业务票据或猜测报告 ID 扩展契约。
