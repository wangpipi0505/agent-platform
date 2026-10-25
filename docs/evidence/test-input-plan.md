# 命名测试输入与评测提升实施阶段

按冻结详设册30 §1.7/§4.2/§5.4、册50用例版本化要求推进 T20/T22；原设计契约不改。现有调试入口和 monitor.dataset 已有真实持久实现，本阶段补尚缺的命名输入 CRUD 和提升链，不引入相邻实现。

1. 应用服务提供按真实 draftId 读取租户内草稿元信息的内部适配端点，仅已验 SERVICE+EDITOR。引擎不跨 schema 读 app 表；使用现 HTTP 客户端验证草稿与 appCode 归属、当前 draft 状态。不存在/跨租户默认拒绝。
2. 引擎 V214 增 debug_input，按设计保存 tenant/app_draft_id/name/inputs/created/updated；增加 app_code、owner 和 revision 作资源边界与 CAS。默认仅本人命名项可读写，EDITOR 必需；不以知道ID或ADMIN角色自动披露其他人的变量。列表有明确上限/分页，输入 JSON 有字节/深度等边界。POST 保存、GET 列表/详情、PUT CAS 更新、DELETE；旧 revision 冲突不覆盖，当前应用输入声明变更后的复用须重新校验，不能静默补参。
3. 提升必须由编排者明确提供 expected、用例ID和 safety 类型，绝不从实际输出生成 expected 冒充评测。源 inputs 取本人持久输入及指定 revision，不能由请求 body 替换。命名项与同租户草稿均须重新核对。
4. monitor 增版本化数据集接收/查询入口（V502 仅有必要提升幂等元信息，复用 dataset，不另造评分器）。一份提升创建不可变 datasetVersion 的单用例版本，含来源输入ID/revision/digest；按租户+应用+版本验证内容，重复同内容返回同版本，冲突409。多用例完整治理仍按原后续范围，不把单项提升称完整用例库已验收。
5. 引擎先短事务冻结 promotion requestId、源 revision/inputs 和目标 expected 等摘要，再事务外 HTTP 交给 monitor；响应丢失可按同 requestId 取同源快照恢复，不因后来编辑源输入而改写旧提升。同 requestId 不同内容409。两域各自短事务，不跨HTTP持锁/假装分布式原子提交。
6. 前端后续接入命名保存/选用/删除/提升，沿结构化输入与渐进展开；本后端阶段先交实际接口与失败路径证据供独立复核，不冒称页面完成。

验证：真实PG CRUD/tenant/owner/role/不存在、JSON大小深度、CAS冲突/并发；真实HTTP草稿校验和两域提升、响应丢失/重复/冲突、源编辑之后重试仍同快照、expected不可自动取实际值；最终数据集能被既有 EvaluationService 以同版本同内容实际执行并写报告，内容冲突沿原不可变规则拒绝。只用本工程专用测试库，空库/升级两条迁移需覆盖。例行 root 与 agent 必须错开同模块 Maven target。

具体端点报文在实现报告中固定并供前端使用；设计原有 `/admin/debug-inputs?appDraftId=` 主路径保持。真正生产IAM、真实供应商/业务评测集和完整容量验收不由本阶段替代。
