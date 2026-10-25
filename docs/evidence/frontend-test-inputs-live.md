# 命名测试输入真实 Admin/API 联调

日期：2026-10-20。本报告为真实 Admin15173→网关18080→应用/引擎/monitor 的浏览器联调，独立于此前23项受控 SFC 测试。脚本未安装 route mock，未创建票据桥，未读取或打印登录响应、headers/token；开发登录通过现有 Admin 页面完成。未启停服务、修改应用候选配置或发布正式版本。

## 真实结果

命令：`node web/tests/named-inputs-live-browser.mjs --engine-ready`，退出0。`frontend-test-inputs-live-browser.log`、`frontend-test-inputs-live.json` 保留实际请求/响应、9项检查、空 pageErrors。仅使用 root 自建 FLOW 应用 `smoke_named_inputs_3db244a637`，draftId34、draftRev3；节点仅 START→END，输出映射 `${input.question}`。输入与独立预期来自运行前已存在的 `.local/named-input-live-fixture.json`，不是从运行实际输出生成。

| 项目 | 实际证据 |
| --- | --- |
| 命名保存与显式使用 | 创建 inputId2/revision1；改当前表单为未保存临时值后，点击“使用输入”恢复 `{question:"命名输入完整联调"}`。 |
| 真实调试 | `r_0d146af8bc274df2af751baedf7c687f` 为 SUCCEEDED；完整 output 精确等于预先给定的 `{answer:"命名输入完整联调"}`。 |
| CAS 编辑 | 页面编辑命名项名称，PUT 使用 revision1；服务端返回 revision2，inputs 不变，应用候选不变。 |
| 明确提升 | 实际运行完成后提升对话框 expected 仍为空；手动填写独立 expected、显式选择安全用例。改变当前未保存输入不影响提升来源。实际请求只带源revision2及目标参数，不带inputs/output。 |
| 不可变数据集 | 创建 `debug-2026-10-19-cbe1858b`；source.inputId2、revision2、appDraftId34；保存的cases含已保存inputs、独立expected、safety=true。 |
| 真实页面选集 | 发布区下拉选择该真实版本，GET detail；向原 `/admin/evaluations` 发送完整原始cases/version，无复制手工JSON。 |
| 真实评测 | 报告 `er_dc98bd58-2c7b-415a-8c45-edc6f449034e` 返回 ACCEPTED、accepted=true；用例运行 `r_1e1164c67c8744eaa37054706431783d` 为 SUCCEEDED、passed=true、safety=true、attribution=NONE。 |
| 候选绑定 | 全程 draftRev3；原 candidateSha256=`676db1269ccad10ec2fca00a4fd9bc90fc4e8df33d4b8e9a38e1ec5089c55e8d` 保持不变。 |
| FLOW手机可见性 | viewport/documentWidth均390；canvas宽244；start/end各约75.37×27.72，完整位于画布边界内；不是隐藏溢出掩盖画布。 |

真实桌面图 `frontend-test-inputs-live-debug-input.png`、`frontend-test-inputs-live-debug-succeeded.png`、`frontend-test-inputs-live-promotion.png`、`frontend-test-inputs-live-dataset-selected.png`、`frontend-test-inputs-live-accepted.png`；手机图 `frontend-test-inputs-live-flow-390.png`、`frontend-test-inputs-live-flow-canvas-390.png`、`frontend-test-inputs-live-promotion-390.png`、`frontend-test-inputs-live-accepted-390.png`。已实际读图，确认两节点、命名项及提升操作可见，评测页面显示真实ACCEPTED报告。

## 真实失败及修复记录

1. 初次打开页面发生15173连接拒绝，未发起业务接口。root恢复Admin Vite后才继续；证据 `frontend-test-inputs-live-connection-refused.json/.log`。未以修改生产代码绕过未启动的开发服务。
2. 第一次真实FLOW运行后390px整页宽528，既有只读画布属性列最小22rem及运行标题/动作横排使页面溢出。此轮已创建inputId1/revision2、调试run `r_25ef4e31175e4bb3a8638176b2ac3384`；尚未提升。完整现场保存在 `frontend-test-inputs-live-flow-mobile-red.json/.log/.png`。root亲自读图后明确授权仅DebugPanel scoped mobile CSS解冻；详 `frontend-test-inputs-mobile-fix.md`。

失败中途产生的inputId1和成功链inputId2均为该专用测试应用的本人测试记录，保留用于追溯。没有修改其他人的数据或删除评测版本。

本轮证明专用无模型FLOW的真实前端保存→复用→调试→更新→单项提升→不可变选集→原评测接受链路；不代替完整多用例库治理、生产IAM、真实供应商/外部业务或业务评测集容量验收。历史23项受控测试仍明确记为fixture，未升级称真实API证据。
