# 运行输入校验及会话修正的真实服务复验

先完成 `review-runtime-input-validation.md` 中会话预约P1的独立闭环，再停止本工程旧engine PID57412并更新为PID59704、18082。`.local/runtime-input-validation-reviewed-deploy.log` 于06:38:28 BUILD SUCCESS；启动日志确认独立开发库engine V213→V214，健康UP。其余真实服务为本工程gateway31252/app40408/skill63724/model44048/monitor44208，没有接管相邻工程。

执行 `node tests/smoke-run-input-validation.mjs`，最终退出0。原红测 `.local/runtime-input-validation-red.log` / `.json` 保留；当前完整结果 `.local/runtime-input-validation-live.log` / `.json`。测试用本工程网关开发票据，评测内部调用用开发服务JWT；模型为本工程已运行的28410协议fixture，不代表真实供应商或生产IAM。

| 入口反例 | 当前结果 | 与红测对照 |
| --- | --- | --- |
| DEBUG缺必填、错误类型、minLength各一次 | 均400 INPUT_SCHEMA_CHANGED，新增run各0 | 原均200，各新增run1 |
| EVALUATION缺必填 | 400，新增run0 | 原200、新增run1 |
| 已发布CHAT正式运行缺必填 | 400，新增run0 | 原200、新增run1 |

五次拒绝对应模型调用总量为0；原红测总量5。新建拒绝fixture应用为 `smoke_run_input_7962cc379b`，拒绝前后以PG限定租户/应用计数，不只检查HTTP文案。

正例 `smoke_input_null_08241acc30` 的真实START→END运行 `r_42203ce61a874dc29bfa70016b97c925` SUCCEEDED。输入与完整输出均精确等于 `{nullValue:null,zero:0,disabled:false,items:[],object:{}}`，每个字段均声明required；没有模型或外部业务节点。此项验证原契约允许的显式NULL与假值没有被新校验误拒。

另外创建两个真实会话，使用已发布fixture CHAT `smoke_chat_bff1adb366`，固定必填message：

| 纠正方式 | sessionId | 最终runId |
| --- | --- | --- |
| 沿用被拒绝请求号 | s_4d10a26ffd724cdebc873025ed440dad | r_92b9d8776b3c4a7db5fb3f7fe0f445ec |
| 使用新请求号 | s_a34a174e40e34c00a66d43fb1b95678d | r_8b15ee440d6b4422a664198133626fab |

两会话均先400、turns=0、nextTurn=1、run增量0；纠正后均SUCCEEDED、只有1个turn、nextTurn=2、模型调用各1。再次提交各自已接纳的完整请求，均读回原runId，模型调用不增加。会话与运行审计保留，不做开发库数据清理。

本次实际网关/服务/PG纵切关闭了前述五类无效运行受理和输入拒绝产生空会话预约的局部问题。它不替代生产身份、真实模型能力、全部输入集治理、调度或容量验收；原契约与详设快照未改。
