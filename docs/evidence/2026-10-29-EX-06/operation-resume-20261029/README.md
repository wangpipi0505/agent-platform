# EX-06 操作编码、PS5空属性与旧会话Resume

本阶段仍 complete=false。All/AP三应用/真实评估发布/J/S1尚未交付。源码与安全回执详manifest；凭据只记录原文件SHA，不公开值，原日志和journal保留私密目录。

1. `operation-red-20261029-01` 调现有Get-JointPreparationPlan：旧32项通过，新OpCode形状断言实际失败/native1。Common增加确定性 `joint_` + SessionId UTF8 SHA24及旧manifest沿用，Prepare在业务写前固化并读取实际旧operation。原测试3EEF未改，`operation-green-20261029-01` 40P/native0/sourceStable；其控制台Select-Object对hashtable曾显示null，落盘summary原字段与结果完整，不把该显示错误当测试失败。共享辅助改变后模型协议run03同63P。
2. 真实同会话Resume `160816-596` native1：空PSCustomObject的Properties.Name在PS5返回单null，触发JOINT_OPERATION_IDENTITY_INVALID。49旧动作/pending=null仍在，未重签；own gateway42584已退出。原静审未识别此PS5行为，operation-review保留为当时的静态准入记录，不追认该轮通过。
3. 新增第41条空名称断言：`empty-names-red-20261029-01` 原40项通过后准确FAIL/native1。仅将Get-JointNames改为逐Properties枚举Name；同CD9F测试在`empty-names-green-20261029-01`41P/native0/sourceStable；原63协议在run04通过，两Node/端口退出，未放宽原预算或断言。
4. 新wrapper以Common4CC9/Prepare8770封源，再执行相同 `Stage Ontology -Resume`：`161303-722` native0/sourceStable。49 actions/resources/context深相同；旧joint_list不变；credentials.env SHA 615615207b70cfb9dd919af22490174de9a4e97bcda13898308007d45e416ed5相同；新增A/B真实operationIds，pending=null/complete=false，own gateway64388已退出，root核18080无监听。登录、读取及消费验证会产生审计/消费事实，不称零副作用。

实际两个命令均为显式WindowsPowerShell5.1 `-NoProfile -ExecutionPolicy Bypass -File <本目录对应executed-wrapper.ps1的原私密路径>`；原私密路径见manifest。只将helper一行修复称为该失败的闭环，计划41/协议63不代表在线冲突负例全验或EX-06整卡完成。新session操作编码实际创建链仍未据此宣称通过。