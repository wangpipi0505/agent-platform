# EX-06 空 operationIds 属性名补修：独立复核

2026-10-29；复核者 `ex04_pg_scope`。未参与 Joint.Common/Prepare 或本次测试/运行器的编写。本轮只读当前源码、冻结副本、两次真实 Resume 私有控制目录及测试证据；仅新增本报告。未执行产品/验收入口、Java/Maven/Node、HTTP/PG、服务启停或 Git index 操作；EX-15b Phase A patch仍未应用。

**结论：本次单行修复及旧49动作会话的准确 Resume 范围通过独立复核，未发现阻断。** 原40断言保持，新增第41条在旧源实红、同测试在新源绿，原63协议检查再次通过；真实Resume由native1变native0，动作/资源/上下文及凭据字节保持，补入在线核实的operation IDs。接受本补修，不把它记为EX-06整卡完成、新session全套创建通过、AP三应用/All/J/S1完成或真实模型验收。

## 1. 精确差异和根因

从 `empty-names-red-20261029-01/Joint.Common.ps1` 的原字节到当前 `scripts/acceptance/Joint.Common.ps1`，独立字符串比对确认**只有一次**如下替换，其余字符相同：

```powershell
- return @($Object.PSObject.Properties.Name)
+ return @($Object.PSObject.Properties | ForEach-Object { $_.Name })
```

位置：`Get-JointNames` 第13行。IDictionary与null分支未动。新写法逐个枚举真实属性，空PSCustomObject不产生一个null名称；非空对象仍返回各真实属性名，没有过滤未知/非法字段。

`Prepare-Joint.ps1:114–120` 在旧journal没有operationIds时会构造 `[pscustomobject]@{}`，随后遍历Get-JointNames结果并严格检查只允许已确认的A/B标签和非空UUID。旧PS5空对象属性快捷枚举导致一项null，被这一原门正确拒为 `JOINT_OPERATION_IDENTITY_INVALID`。修复的是名称枚举，不是放宽标签/UUID/owner/目录身份检查。后续 `Get-JointOwnedSpace`、真实operation目录GET、唯一code/ID冲突门、未知pending拒绝、无回退创建与私密key逻辑均未改。

当前/运行时精确源：

| 文件 | SHA-256 |
|---|---|
| 旧Common（red与Resume01） | `EF5CAE1919812965592D4EB702B9B99EA9C7CA9A75191051C9EC2878FEBB84CE` |
| 修后Common（green/协议04/Resume02/current） | `4CC93F7A3563809D8F4971079B13E440026AE035CBC2068A5D9AC013B533F342` |
| Prepare（red/green/两次Resume/current均未改） | `87709F9FE5CC0C7BB387ED3A09433C713B7CE238F6B78E2BD64AE7C8B17C52EC` |
| 本轮Test-JointPreparation（red/green/current完全相同） | `CD9F6EE6DB452BF724E1893B0D8210AE06661AF6158294B81412BE3E88474F5B` |

## 2. 原断言与真实红绿

原40测试源码为 `operation-green-20261029-01/Test-JointPreparation.ps1`（3EEF5841…DD60D）。从当前CD9测试移除新第132行 `empty journal operation identity has no property names` 后，与它全文相同；不是替换原断言或改变装载名单。新断言严格要求 `@(Get-JointNames ([pscustomobject]@{})).Count -eq 0`。

独立逐项比较原40检查名、红轮passedChecks和绿轮前40项，顺序/内容完全一致：

- [empty-names-red-20261029-01](empty-names-red-20261029-01/summary.json)：native1，原40P，第41条真实 `ASSERTION_FAILED:empty journal operation identity has no property names`；同CD9测试、Common EF5。HTTP0/业务资源0，不是语法/编译/缺函数失败。
- [empty-names-green-20261029-01](empty-names-green-20261029-01/summary.json)：native0，41P，同CD9测试，Common4CC/Prepare877的before=after=current；HTTP0/业务资源0。
- [model-protocol-20261029-04](model-protocol-20261029-04/summary.json)：native0、63P，source/context stable；独立比较03与04的63个checks逐项相同，04的5源before/after一致，Common/Prepare/TestPreparation匹配本轮。protocol-checks SHA `26AB9ABCCF6DCDCF06813D01414D57FE5FC03AD3788604564FA6C66CFD51CCAE` 与summary一致。root回执登记两自有子进程55236/51788和端口63390/63398均退出，businessValidationPerformed=false；这仍是协议夹具，不能代替真实Resume。

本复核未重新执行这些测试，以上结论来自实际原件及独立逐字/结构比较。红绿原件未重写。

## 3. Resume01真实失败保留

私有控制目录 `.local/acceptance/ex06-or-control-20261029-160816-596` 保留原件。其admission固定EF5/877，run.json记录native1、sourcesStable=true、wholeCardComplete=false；ontology-private.log仅抽取错误码核为 `JOINT_OPERATION_IDENTITY_INVALID`。没有将wrapper的failure=null误读为成功，native1及真实错误码才是结果。

第一轮before-partial没有顶层opCode/operationIds；到第二轮before-partial，actions/resources/context深相等且仍49动作，pending=null，仅新增失败元数据。这与源码中空ids名称检查早于真实空间/操作身份补入及New-JointOntology业务写的顺序一致。私密原日志保留，报告未复制其任意原响应或凭据值。

| Resume01 原件 | SHA-256 |
|---|---|
| run.json | `72FE7601CEB75D7E3B48D20CE9BB5B22B394D2FF327C80AE00D98AF825933624` |
| admission.json | `98AE5A5066FCC2E6E4F9AF0F3A765B32FA8A0244C675EE4614BE6D696E3D4BB7` |
| before-operation-partial.json | `DDF24271224836B69EA69DA9436D1BBE98E83CBA8F08E9C23A3C61DF79042678` |
| ontology-private.log（只列指纹） | `AE68A254053F257BC02E3E04E96DCAF5C5C009840E04F37E84B3BF220EF96D98` |

## 4. Resume02最终实际结果的独立核对

私有控制目录 `.local/acceptance/ex06-or-control-20261029-161303-722`。admission固定Common4CC/Prepare877，root run.json为native0、sourcesStable=true、wholeCardComplete=false。没有新建session，仍为 `joint-or-20261029-01`；没有用新派生opCode替换旧资源的joint_list。

独立重新解析第二轮before-operation-partial和会话实际partial进行JSON深比较，未仅采信root comparison布尔值：

- actions 49→49，actions/resources/context全部深相等；第一轮before到第二轮before的这三字段也相同。原release/endpoint/credential/principal/keyRef映射因此保持。
- pending前后均null。顶层变动仅 `lastError`、`opCode`、`operationIds`；lastError清回null，opCode补为joint_list。
- operationIds补为A=`2353b8cc-6550-407e-8830-47dd7ce9c46e`、B=`4fd35e84-137e-4458-b49d-20d0af65875f`，与root comparison完全一致。这些值经本次真实、已核owner/nonce/code空间的operation目录读取后保存；旧journal原先没有该字段，不能说旧历史ID字节被比对过。
- 只对credentials.env算SHA，没有读取/输出其值。当前SHA `615615207B70CFB9DD919AF22490174DE9A4E97BCDA13898308007D45E416ED5` 与两次control的before-credentials-sha.json都相等，不存在本次重签或改写凭据文件字节的证据。
- 实际partial SHA为 `F7C8B44533344237E6E95BAC3A14E3B217743C6BF080EA931A7FA41409B3C337`；第二轮comparison的passed=true、49/actions/context/resources/credentialBytesUnchanged均被上述独立比较支持，complete=false保留。

第二轮自有gateway登记PID64388、creation=`2026-10-29T16:13:07.7109200+08:00`，JAR为本AP target，stop.log记正式停止该PID。复核在16:18:45+08以本机只读Get-Process及netstat观察：该PID不存在，netstat native0、18080 LISTENING为0；未启动/停止进程或访问HTTP。一次CIM读取因本机不可用失败，未把那次null/空结果当作退出证明，最终采用上述成功的本机只读查询。

| Resume02 原件 | SHA-256 |
|---|---|
| run.json | `07BA29EC16AE639A7FADFDB1D2436AF73B121A4BB84291DA62F219DAA387230E` |
| admission.json | `CD4F9F05C0FDF45FCA8ADC68EF73A25CE09F53945CEDA5D4B54B59E8FBE10165` |
| before-operation-partial.json | `A95DCF5D22D4FAF79A11CEC9607E4FFD2A79F7D02C0E46E969E113ECDD99D307` |
| operation-resume-comparison.json | `A10AF9119B3D3908C855880D5FCE1D14E166FF977F06BC1F97E04A9FA38F5FD8` |
| ontology-private.log（只列指纹） | `728E65F63B1D16405232420D6A09B20DFD59824D39A98167FD204891C494C7F8` |
| gateway-stop.log | `C983CC26330B843CCEF161EA3BF753C983942FFD8F7B648DF39851CB98EC1AE7` |

## 5. 接受范围和仍未证明的项

本次修复只消除PS5空属性对象的假null标签；所有非空未知label、异常容器、UUID、operation ID/code冲突、OWNER/ACTIVE/nonce、pending和manifest拒绝门仍在原位置。没有白名单扩张、资源创建重试、repair、清队列、重签或撤销授权路径。

“49动作/资源相同”不是“所有数据库零副作用”：真实Resume包含登录/注销、目录读取审计和原四次消费验证，还会按原规则重写本session服务环境文件；报告只对credentials.env作全字节不变结论，不泛化所有私密文件SHA相同。

新session派生code的新资源创建、在线冲突/未知身份负例、AP后续阶段和完整联合验收仍未由这轮49动作恢复验证覆盖；原报告的阶段/外部阻塞继续有效。root安全归档可以引用本报告中的精确原件SHA，私密raw仍留原路径，不能把raw log/env不经审查直接入Git。本报告完成即停写，不更改主入口状态、旧报告或提交记录。
