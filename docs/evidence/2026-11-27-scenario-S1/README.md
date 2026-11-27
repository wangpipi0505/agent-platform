# S-1 PR-A 全链

冻结候选 `57945918`。真实 user-mgmt 主体 `operatorA` 在 E2 门户以 USER 建会话。三个运行均 SUCCEEDED，输出依次为甲、乙、丙。三个同号重试各自复用原运行。

断开于 seq=2，重连从 3 开始，最终 1–12 连续且无重放。跨用户与跨应用请求均 HTTP 403，分别落库 SUBJECT_MISMATCH、APP_MISMATCH。撤销请求 HTTP 200，旧票复用 HTTP 401，撤销落库。两份测试文档的判据已按用户确认对齐。

页面复验显示六条完整消息，浏览器控制台、页面异常和 HTTP 错误均为 0。E2 候选入口的浏览器默认图标此前 404，由候选 Nginx 为该静态路径返回 204 后复验。三轮接口、SSE 原始帧、截图及撤销审计均在本目录。临时 USER 授权、门户票和 user-mgmt key 已撤销。

[逐条结果](results.json) · [浏览器结果](browser.json) · [浏览器截图](three-turns.png)
