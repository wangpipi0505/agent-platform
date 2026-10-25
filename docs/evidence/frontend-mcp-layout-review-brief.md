# MCP 完整页面布局修复独立复核

仅在独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 内工作，遵守 AGENTS.md。不得读取、关联或修改相邻旧代码、配置、进程。用户已授权持续实施及本机 Chrome/Playwright 检查。无 HEAD，不能使用空 git diff 作为审查依据。

本次需求：技能详情完整 App 在 390px 时，原全局 shell 最小宽 1366px 导致页面撑宽；修复要求保持真实功能、数据、权限、查询行为及桌面布局，并在窄屏可用。不能用隐藏 overflow 掩盖内容。

输入：`frontend-mcp-live-layout-fix.md`、`frontend-mcp-live-layout-fix-forward.patch`、`frontend-mcp-live-layout-fix-sha256.json` 以及 baseline/frozen 完整文件。原 MCP 注册阶段 127 单测和 7 文件已独立批准；此次 App.vue 原受保护 SHA 改变是明确授权的布局修改，其他保护项应仍不变。

独立检查要求：验证实际 source/patch/SHA；检查模板 class 是否只针对技能详情；读取新增真实浏览器脚本及红/绿证据；独立重跑 1440/390 完整 App 只读检查并读图，允许使用自己前缀的报告/截图，不改作者和 root 的原始证据。检查可访问内容和统计表内滚动，无额外写请求。root 正在运行完整 MCP 发现脚本；不要点击远端工具、保存或发布，也不占用 peer28418。只读回归使用已保存草稿 `smoke_mcp_good_f8e15ace6f`。

报告写 `docs/evidence/review-frontend-mcp-live-layout.md`，给出需求符合性、代码质量两个结论和实际命令结果、限制。若发现问题，提供路径行号、触发条件、影响。不得声称真实 MCP 全协议已通过（由 root 单独验证）；不改业务源代码、不启停后端、不运行 Maven、不提交、不派子 agent。返回简短状态和报告路径。
