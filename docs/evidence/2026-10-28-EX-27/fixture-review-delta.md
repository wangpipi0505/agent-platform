# E175：独立静态审发现的ping夹具修正

root与独立审阅者确认FD34中的普通`GET /ping`不能要求非空SSE RequestSlot。`ContextValve`每次REQUEST先清attribute，仅GET `/admin/runs/r_[0-9a-f]{32}/events`才铸SSE token。此为确定的夹具/API使用错误，不是SSE产品义务缺口。FD34没有编译或运行，不能记目标红；旧状态独立保存在[safety-candidate-fd34-unrun.json](safety-candidate-fd34-unrun.json)。历史447实际诊断红原件保持。

本轮只修已明确的测试点：普通ping probe要求DispatcherType.REQUEST、SSE attribute为null，以已有`protocol.currentSocketForTest()`取得真实bridge socket并确认不是原两条SSE连接；计数观察两次真实handler REQUEST分段，保留实际两次HTTP200/pong及同一keepalive socket。原SSE processor/socket/slot未转向仍由已有安全断言验证；没有声称原SSE processor发生实际复用，也未创建token、手调recycle或修改产品mint规则。

新源SHA256=`E1758505D03E6997D257ECF6D52BAC1A97C6AFDBA782DBB15105E30549F37517`。原37+1=38身份保持，原正例5秒和原断言、恶劣关闭P1/P2/P2b及后续安全要求均未更改。`git diff --check`无错误（既存CRLF提示另计）；未执行编译、Java/Maven、本地HTTP/PG；没有src/main/依赖/index/commit操作。

已将新SHA交独立审阅者继续汇总静态审，候选停在待审状态。root仍独占实际窗口，后续运行须另获明确交接。当前不作编译通过、夹具已修、完整38/27通过或gate通过结论。
