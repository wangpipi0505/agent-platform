# PS5 JSON数组采集修复与独立复核

2026-10-29。root修复，ex04_pg_scope独立复核；不使用superpower。

首轮`gateway-module-20261029-ex15a-01`在admission第96行停止：collector2、nativeMavenExit=null、targetTestFailureInferred=false。真实旧基线仍27XML/288=273P7F8E；并非基线改动或产品红测。PS5的Read-Json管道直接返回ConvertFrom-Json数组时，调用者`@(Read-Json...)`得到1个Object[]。原49个child及1321指纹分别被包装为count1。旧453B脚本已完整保存在该run/executed-run-module.ps1。

唯一功能变更：Read-Json先赋给局部$value再return，显式枚举数组；其余全文不变，原数量、方法、失败原因、安全门、SQL和产品断言均未改。runner新SHA `9a7ba2911997276e61c7ea22dd2a3a010019043f0d60400cee6b861d34ebf208`。prepare只同步冻结runner SHA，新SHA `19b249313690591838b5cc942a899a18833ad34202756bfee69a22cf7b1bcb94`；逆换该字符串精确恢复原F4B8708指纹。

root与非本修复作者ex04_pg_scope分别以显式Windows PowerShell 5.1.26100.9711提取该纯函数并读取同三原文件：旧数组/数组/对象外层计数1/1/1，新49/1321/1，元素恢复PSCustomObject，scalar对象不变。两版runner及prepare语法错误均0。root实际数值见read-json-fix-proof.json；未运行整个runner来制造语言层证明。

ex04_pg_scope独立结论：接受上述单函数修复及prepare冻结值同步，可新RunId重做准入后执行完整网关；不是业务回归通过。原run01和其Before四库历史保留。新轮`gateway-module-20261029-ex15a-02`使用自己的Before回执与指纹，显式传`-ApRoot`，完整Maven于06:11:20 UTC开始。

补充调用历史：第一次prepare省略ApRoot时，PS5默认参数表达式中的PSScriptRoot为空，参数绑定native1；函数体/输出目录/PG均未执行。随后显式传本仓绝对ApRoot，原Before通过。后续命令一律显式提供ApRoot；不将参数绑定失败混作数据库或产品失败，亦不静默重用旧批准回执。