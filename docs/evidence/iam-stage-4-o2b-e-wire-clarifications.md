# O2b E wire边界补充

本补充澄清正式packet-decisions和engine-packet-brief的既有完整材料/容量/EOF规则，不改schema、8文件scope、预算或v1.2/四契约；原冻结文档不改写。

1. 首WINDOW、游标reset WINDOW和需要向接收端证明终态/排空的WINDOW都必须是带完整已验材料的真实WINDOW。已有初始WINDOW后，只允许跳过无事件且仍活动的idle轮询；不能在新观察到terminal/drained时直接以EOF代替terminal材料。若上一完整WINDOW已经包含终态、且其受保护watermark已由完整事件推进达到，接收端可以据该完整材料判排空；否则关闭前应先输出相应完整终态无event WINDOW。终态/重置不新增schema字段，由已验state/window/cursor派生。当前cursor只在完整COMMIT+flush后推进，EOF本身仍无终态证明。

2. eventType既要保留C原始已验值，也受固定wire header总8192和header parser string4096上限交集约束；不能截断/改名。超过wire字符串/总长上限是BUFFER_LIMIT容量路径，不是新授权语法拒绝，不使用新64字符正则。其他header/metadata字符串、数字与各解析上限同理。

3. 本轮E codec为encoder，不实现F恶意输入生产decoder；输出向量必须清晰覆盖首空窗口、后续idle无包、终态空窗口、游标reset、JSON null与无event的差异。F接收端必须独立完整验证后才公开输出，E阶段不能以正常编码器输出或test-only检查器证明该F能力。
