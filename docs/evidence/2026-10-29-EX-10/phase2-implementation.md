# EX-10 Phase2：精确技能绑定（2026-10-30）

单路线解析实现已完成，完整EX10未关闭。Skill版本读口仅requireRegistered=true时要求精确PUBLISHED版本、原definition摘要及实际AdapterRegistry组合；默认旧读兼容，历史PROCESS缺省protocol由原解释器读取，不改原definition/digest。

app只请求配置的固定技能服务origin，签发内部ServiceTokens、不转发用户票据、不读包内URL、不跟随重定向；有界读取后核版本ID、摘要/注册信息并逐声明比较。全部远端只读结束后，以tenant/id/原包SHA短事务保存唯一计划；同计划复用、异计划409。响应RESOLVED/NOT_EVALUATED/appCreated=false，不创建应用、不执行工具。

两轮到止：首轮Maven默认遇错停止，app三例真实404缺handler失败，skill两例跳过未执行，原expectedRedGate=false保留，不虚称五例实红。实现后第二轮-fae两模块五类23P0F0E0S/native0/sourceStabletrue；app21、skill2。3个app例使用真实PG/MVC与实际验签的loopback元数据协议peer，skill两例单独读取真实PG/实际注册单元；不冒称已跑两个正式服务的网关联调。

一次轻量非作者代码检查未见确定阻断。V109与源表保持声明和首次解析计划不可偷换；schema源/副本045679不变，无POM/第三执行器/外部worker调用。原始材料仅在本仓.local/ex10-package-binding，不提交raw日志/XML/运行数据或凭据。

接续：真实可编辑应用草稿、来源保护及发布/run快照、精确网关路由、多应用/多路线完整映射，业务verification与外部环境另行收口。
