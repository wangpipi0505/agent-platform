# EX-12 AP 六维观测查询

沿原 run/page 查询增加包、路线、工具、release及trace条件；租户/主体仍来自当前身份。包/路线/工具匹配同一冻结来源，工具还必须存在实际精确版本 tool_call。列表和 legacy 详情返回同源 observation，历史 null 不补造。新运行 versionRefs 捕获当前 trace 并沿原入库事务保存；无新表/TraceStore或迁移。

最多两轮：

- 首轮 `.local/ex12-observation-run/observation-first-20261030-01/` 编译失败，nativeExit=1、0执行/0新XML；新测试漏 RunScheduler import，不能算业务红。
- 只补 import，业务断言不变；第二轮 `mvn -B -ntp -pl engine-runtime test -Dtest=cn.agentplatform.engine.infra.RunObservationDimensionsTest,cn.agentplatform.engine.application.PromptFreezeDigestTest`，7 PASS，nativeExit=0、2新XML、源稳定。完整参数/结果 `.local/ex12-observation-run/observation-second-20261030-01/`，不第三轮追测。

真实专用PG/MVC证明过滤同源、实际调用、tenant/principal隔离、参数拒绝及历史null；trace捕获用原versionRefs入口及MDC夹具，不冒称全链HTTP透传。ROOT完成一次非作者轻量代码检查，无阻断问题。范围RunService、新测试、能力包工程契约及本记录。回滚本地提交撤新查询/DTO，不删除已冻结事实。

运行中心UI接续。OR执行事实已有空间/操作/release/trace，但缺可信package/route/AP app-tool映射；本批未猜映射或改OR。跨服务trace与真实联合仍为EX-13/后续挂点，不以本批7P关闭整个EX-12。
