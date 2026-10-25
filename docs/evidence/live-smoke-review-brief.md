# 独立复核：真实纵切脚本与证据边界

只在独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 内遵守AGENTS工作，不接触相邻旧代码/配置/进程。root已完成846阶段，独立开发服务运行新制品。用户无外部联调环境，授权继续使用隔离端点及本机Chrome；真实业务测试只发本工程的有限fixture。

需求：复核root四个新增真实纵切脚本能否支持报告中的结论，重点检查断言/计数、PG唯一提交和原代事实、进程/PID/端口/JAR归属、只停自己的进程、finally清理、浏览器真实请求与布局断言、异步原报告身份。无HEAD，新增文件完整diff在 `live-smoke-review.patch`，清单 `live-smoke-review-sha256.json`；实际源须与SHA核对。

输入报告：`mcp-protocol-live.md`、`run-scheduler-replicas-live.md`、`frontend-evaluation-async-live.md`及同名summary.json（MCP和调度文件名另为mcp-protocol-live-summary.json、run-scheduler-replicas-live-summary.json）。相关原始日志在.local。实际完整后端846/262源配置固定，当前另一个agent只改skill原attempt前置源码，不改本次脚本或正在运行旧制品。此次不复核Schema资源防护，不执行耗尽堆/慢模式等资源探针，也不接续任何曾被自动中断的审查。

允许独立静态和有限只读核对已生成的PG证据、源文件与日志；如需重跑四个有限脚本的任何一个，先告知root以免争用Chrome/端口/运行数据。不得自己启停主开发六服务、package或跑Maven；不得修改root脚本或业务源码。把发现写入 `docs/evidence/review-live-smoke.md`，分别给出需求符合性和代码质量结论，具体问题带路径行号和结论影响。明确未做/无资格证明的容量、在途副作用接管、供应商及生产验收。返回简短结论，不派子agent或发布。
