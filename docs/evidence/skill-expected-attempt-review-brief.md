# 原尝试前置独立审查

仅在独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 内遵守AGENTS，不接触相邻旧工程。需求先读 `skill-expected-attempt-brief.md`，作者报告 `skill-expected-attempt.md`，由报告定位真实完整patch、baseline/frozen、前后SHA与最终完整回归日志。无HEAD，不用空git diff证明无改动。

审查原attempt参数从SkillExecution execute/reconcile到四种adapter的真实传播；缺/错claim attempt拒绝、无默认0/无身份重载、HTTP异步bind完整身份/快照/合同、两类迟到发送窗口、CAS读后身份变化、ACK/查询/DONE原代写回、queryOnly错代零查询/零POST、同代并发仅一次。核实当前合法只读重试、scope扩展对账、审批/Mock/CALLBACK/proof/MCP防护没有被削弱。只能声明HTTP异步的持久CAS保证；HTTP同步/MCP/算法本次只做原代参数传播，不误称全适配器跨代原子保证。

请实际核SHA和patch，选择有意义定向测试独立运行（含真实新工程PG与有限HTTP夹具，测试默认集成库agent_platform_v12与独立skill_async_test，均非运行库dev_v12）；不需要重跑资源/慢正则/耗尽探针、完整846或实际生产端点。不改源/原证据、不package、不启停任何主服务、无许可API/新代reset。target已在作者冻结后移交你独占；root另有纯Node脚本修复不使用Maven。

报告写 `docs/evidence/review-skill-expected-attempt.md`，分别给出需求符合性、代码质量结论，列出实际命令/红绿证据核实/关键限制，问题给行号、触发和影响。不以作者自测替代你的独立执行。发现需修问题先报告，不自行修改。不得提交/发布/派子agent。返回简短结果。
