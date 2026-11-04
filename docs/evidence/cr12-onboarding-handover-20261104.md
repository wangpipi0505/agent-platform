# CR-12 模板与应用身份分离

2026-11-04。模板发布事件只表示模板状态；父页再通过模板详情读取真实appCode，并读取应用实际currentVersionNo和停用状态。只有真实应用可用时才向AssistantLaunchStep交接，模板code不再被当作应用code拼地址；首跑清单随应用/版本切换清空。

必要功能检查第一轮：`vitest run tests/onboarding-handover.test.ts tests/onboarding-launch.test.ts`，2文件11/11通过。新增3例覆盖templateCode≠appCode、应用未发布/停用/错身份、未发布模板不触发应用读取、授权错误保持可见；原8项入口/交接约束断言全部保留，未放宽。

本项修复既有模板引用应用的交接；CR05新接入会话最终入口只消费服务端PUBLISH实际回执，不从模板PUBLISHED推断新应用已交付。未启动全量/真实模型或业务场景验收。
