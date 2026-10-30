# 显式模板主来源工程约定（EX-20）

`POST /admin/apps` 可选 `promptTemplateRef:{code,version}`，只接受这两个字段。服务端在当前租户内核精确已发布版本及持久正文摘要，写入 `config.promptSource="TEMPLATE"`、`promptRefs:[{code,version,sha256}]`；不选时保留原空配置。创建不接受自报sha或正文。后续保存显式模板时也核精确引用；只有用户明确改编码/版本才重新选择，普通读取/保存保留原摘要。

候选hash材料包含config中的精确引用。`dependencyVersions.frozenPrompts`保存原正文/变量/输出契约；其本身不重复入候选hash，但每次消费须同时核候选hash、精确引用和正文canonical sha。不能接收业务输入或config自报frozenPrompts。明确TEMPLATE才切换提示词主段；旧无标记内联首段与附加模板顺序保持。IAM初始CHAT只开放经核验的单模板有限形状，不扩工具/外部材料权限。

评测报告 `promptTemplateRef` 来自PG持久candidate，恢复沿原candidate，不查新模板。新模板版本不会改变已有引用或运行快照。模板展开沿原材料预算，并限制展开大小；错误沿 `PROMPT_TEMPLATE_SNAPSHOT_MISMATCH`、版本/发布状态错误及原有限域拒绝处理。

管理台默认要求显式选择模板/版本，也可主动选择稍后配置内联；不自动挑列表首项。构建时 `VITE_PROMPT_TEMPLATE_SELECTION=false` 可恢复原创建界面；这不删除已有引用或改变历史重放。回滚只撤本地代码提交，保留已存引用/评测/运行事实。

这是源码接口增补；live服务OpenAPI重新构建/读取、真实模型/IAM及浏览器联合仍按EX-06/07/26验收，不由本地协议测试代替。
