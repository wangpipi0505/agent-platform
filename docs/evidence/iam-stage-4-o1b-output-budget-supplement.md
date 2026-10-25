# O1b：同步输出上限与层次补充裁定

本补充在作者首次源码编辑前、初始发现后由root批准；与正式brief（19771B，SHA256 `c2749821ad0ff71ccf08c72f35b0dce4308f5a325ed8a925ce8594c7609b260f`）共同绑定O1b。原brief不倒改，作者在自己的scope/命令记录中保留实际增补时间。此文件是实施要求，不是已测试结果。

## 1. 新IAM读路径的有限序列化

实际发现：旧RunController.status返回Map/ApiResponse交MVC转换，RunService.composeStatus与TraceStore.statusPublished没有适用的完整status字节上限；engine的SSE配置/单event上限不适用于此响应。gateway PublishedRunProxy的2 MiB值仅属于此前有限POST，不是原GET已存在的限制。

root明确批准 **仅新IAM admin status路径** 的完整ApiResponse UTF-8输出上限为 **2*1024*1024 bytes**。这是本切片新增的有限实现边界，仅借用既有有限POST的数值；不能写成原GET已有限制，不修改legacy/POST/原契约文档或执行语义。成功仍返回完整原字段，超限明确失败，不截断、不返回空成功。

必须在序列化过程中有界，使用本请求独占有界buffer/OutputStream；写入会超过上限时立即终止，不能先生成无界String/byte[]再事后量长度。复用现公开Json.mapper()的原mapper与配置进行writeValue，不新增ObjectMapper、依赖或通用序列化框架，Json.java不改。新增buffer累计保留不超过上限；如toByteArray存在有界副本，应如实说明峰值，不称整个请求堆内存最多2 MiB，也不声称限制原材料查询/对象图大小。

超限采用现PlatformException的403处理方式，独立稳定code **RUN_STATUS_TOO_LARGE**，明确提示完整运行状态响应超出本接口允许大小。该code不是AUTHORITY_*，不表示撤权，不修改run_authority blocked/check/grant，不自动重试/换许可或降级。异常层输出错误envelope允许有字节，但不得写出status成功业务内容。不使用语义主要为请求体过大的413。

序列化通过后维持正式brief的顺序：本地终检→取得此次实际ServletOutputStream→同frame/retained/期限/目的再次终检→首次write。header等操作放在最终终检前。不得改为临时文件/磁盘spool、多段异步writer或scope外新生命周期框架。

真实HTTP/PG必须覆盖完整envelope恰上限成功、超过1 byte失败，非ASCII与转义后的实际UTF-8计数、buffer上界、超限零status成功字节及执行事实不变。大材料应由合法受保护运行/材料及一致scope建立；不能绕开真实reader直接传假Map当联合链证明。原完整字段、legacy/POST直接相关行为保持。输出错误码与授权错误分开断言，不把超限冒称撤权或payload读取前拒绝。

## 2. 保持原载体与纯规则的分层

原public PublishedView record及POST用途保持原状。新增封闭ObservedView/ObservedStatus由private constructor持有观察结果/终检能力；提取private composeStatusBody共享原数据组装算法，POST/observer两个窄wrapper各做对应终检。不能仅为挂观察proof把旧public record改class或为观察路径输出raw retained accessor。

允许额外窄改 **engine-runtime/src/main/java/cn/agentplatform/engine/domain/GraphRules.java**：将现RunService.requirePublishedFlow的纯规则及必要纯dependency helper移到domain静态方法，RunService原private入口保留委托；TraceStore可在已授权snapshot/refs读取后、同RR内调用这份域纯规则，再读剩余材料。现规则语义逐项保持，不能把RuntimeClients、prepareSnapshot、网络/执行逻辑搬入domain，也不引任意validator回调。

最终默认scope为原brief八个修改文件，加GraphRules一个修改文件，以及新增RunObservationStatusHttpPostgresTest。不修改Json.java/ServiceAuthorization/catalog/O1a api-service-domain；发现确有必要时仍先报告具体原因。提取纯规则与共享control helper必须带原相关query/POST/纯FLOW具名回归，不跑旧全套。

## 3. 实际响应流窗口

允许测试专用HttpServletResponseWrapper在getOutputStream中先调用super取得真实stream，再推进可控clock或关闭frame，随后返回该真实stream；以真实HTTP证明生产最终检查覆盖这一窗口。不得只在controller内部插入假hook、伪造输出计数或以Socket无字节替代业务status无字节。序列化后、stream取得后等窗口分别保留真实结果。一次阻塞write内部仍无连续撤权/物理硬断保证。

其他正式brief的授权分离、全部scope先验、单RR、snapshot读后关系、only-admin、唯一source写权、隔离环境、真实红绿及证据约束不变。
