# O1c POST 回归 CP 尾空项裁定

这是 classpath 补充后的具体差异裁定，不是已通过证据。作者独立比较报告：app 的115项有序相同；engine 的134个非空项有序相同，但原 locator 末尾多一个分号。Java 验证循环 split 会丢弃尾空项，子 JVM 实际 -cp 字符串则含 ROOT 当前目录回退，不能称 engine 字面完全等同。

Root 允许保留该原 locator，在本次自有目录编译/运行不初始化业务类的 Java21 有限 probe：仅核精确 ROOT/cn/agentplatform 不存在，并分别用旧/新各自 module CP 检查受核入口 fixture、common authority 及该 app/engine 关键实际类的第一来源与 class 字节 SHA。Class.forName 使用 initialize=false；不混两业务 module、不启服务、不访问外部源，输出和临时文件仅在自有 .local。若有来源或字节差异，不运行 POST，先记录具体差异。

证明成立后可运行原已批准具名 POST 一次；报告写“非空有序项相同，旧尾 ROOT 差异保留，所列实际解析已核”，不将旧 CP 重标 fresh。继续执行前补充的新 UUID tenant、旧行不改、不清空及自有 JVM 关闭要求。无新 source seam，仍8生产/4新测试/必要源585；不修改原 locator/brief/前补充。实际命令、范围、结果及 XML 以后续独立封存为准。
