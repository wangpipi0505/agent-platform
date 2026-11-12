# 业务演示界面与文件增强

实现业务分类、技能访问策略、已发布技能版本选择、CHAT/FLOW业务过滤；FLOW节点businessPolicy保存业务系统、权限、角色、语义条件、检查时机。用户助手显示业务流程和变更前后；卫星规划面板只在卫星业务展示。对话使用共享安全Markdown，并提供业务身份/目录/权限入口及文件上传、模板和参数预览。

统一制品模块web/shared/mock-artifacts.ts使用IndexedDB储存Blob，上传及聊天/运行详情下载共享同一ID与字节；不可用时内存及小文件session base64回退。模块仅显式VITE_MOCK环境由调用方接入。

验证：执行npx vue-tsc --noEmit两轮；第一轮修复AppChatPanel窗口回调隐式any。第二轮本次Vue、制品及技能helper无类型错误；全局仍报并行协作者新增mock-state-extensions.ts及mock-runtime-extensions.ts类型错误，已通知主任务。不执行全量测试、浏览器回归或正式验收，遵循用户尽快可用要求。

## 文件规划输入适配

新增shared/planning-attachments.ts：planningFileSkills四个技能与preparePlanningAttachments。实际读取上传字节，解析单目标CSV、约束JSON和JSON结构星历参数TXT；校验字段与输入来源冲突后生成规划引擎可识别命令。仅支持当前场景日期2026-11-06；多目标CSV明确要求分批，不声称支持批量规划。轨道描述字段保留于实际附件，当前进入规划的参数包括satelliteId/onlySatelliteId、epochAgeDays、targetId/targetType、planningDate、maxCloudPercent。

轻量Vite SSR直接调用共9项检查通过：CSV推导地面目标；JSON小数云量及卫星生效；目标冲突拒绝；云量冲突拒绝；不支持日期拒绝；无效卫星拒绝；无附件/参数原样；星历TXT过期阻断命令；多行CSV提示分批。首次SSR关闭时产生依赖扫描关闭提示；后续禁用发现扫描的TXT/多行检查正常退出0。未执行全量测试或浏览器回归。

## 发布版本操作

应用概览版本表支持恢复为草稿及设为默认版本，成功后emit reload交由工作区完整刷新。正式助手对话加载版本目录，默认当前版本或首个发布版本，发送versionNo；首次发送固定会话版本。切换版本主动清空上下文创建新会话，草稿不传versionNo。未额外扩大验证，交由主任务统一类型检查/构建。
