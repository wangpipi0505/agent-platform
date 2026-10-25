# MCP 真实后端与完整页面纵切

2026-10-20，root 实际执行新工程 tests/smoke-mcp-protocol.mjs --browser，退出0。使用846阶段的真实gateway/engine/skill和独立PG，仅远端MCP由本机28418有限SSE/JSON-RPC端点模拟。固定SDK0.10.0、协议2024-11-05；没有请求拦截假后端。摘要和原日志SHA见mcp-protocol-live-summary.json。

- 精确草稿工具检查返回两页unrelated/lookup，完整Schema含pattern保留；VIEWER403且不打开协议会话。
- 实际Chrome从已保存草稿smoke_mcp_good_f6954eaef1发无正文POST到所选版本1；工具lookup精确匹配、原Schema显示，1440/390均无页面溢出、无pageErrors；只有该发现POST，没有保存/发布/业务调用。两张frontend-mcp-live-tools截图已实际读图。
- 只读执行r_44c305a67d4d4ad692957c191b2165c7返回独立已知value7，skill/engine均SUCCEEDED、逻辑提交1；重复受控连接ID不重做业务。缺工具及远端Schema拒绝各FAILED/PARAM_ERROR且业务调用0。
- 声明写技能在真实EDITOR审批及摘要核对后才调用。isError后run WAITING_EXTERNAL、fact UNKNOWN；两次严格内部服务票据对账均UNKNOWN、retryBusinessAllowed=false，审计2条，新增会话/业务调用均0，原身份/合同/参数/attempt/key不变。整轮业务调用2（只读1+获批写1）。
- finally仅取消本轮未终结run；只读SQL确认其本地CANCELLED，外部执行事实仍UNKNOWN。不能解释为外部停止或效果撤销；6个SSE会话最终活动数0。草稿/事实/审计保留，peer关闭。

原404红和390→1366红日志/截图保留。布局修复仅App局部样式，127测试/三构建及独立review-frontend-mcp-live-layout通过；原受保护App摘要因授权修复改变，其余15不变。此纵切不替代供应商/真实远端能力或完整技能治理验收。
