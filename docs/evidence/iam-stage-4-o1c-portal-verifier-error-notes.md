# O1c 原Portal验证器错误分类：窄核补充

结论：**具名的初次Portal JWK服务不可用风险不需要新增第9生产文件。** 冻结TrustedJwtDecoder已经保留可区分的503异常；O1c在现已列GatewayIngressBoundary/HttpCurrentAuthorityClient新STATUS分支按异常类型及发生阶段消费即可。未实现、未测试；旧接口notes与候选不改，O1b仍按root最终窄修复/评审状态为准。

唯一cwd：`G:/207/项目/算法重组工具/代码/agent-platform`。本次只读下列accepted冻结common原件与O1b source manifest的两个定位项；只新增本文，没有源码/索引/测试/probe/服务/子代理。

## 实际异常传递

1. `TrustedJwtDecoder.decode`34–54先作token格式/固定issuer候选选择，再调用固定Nimbus decoder。`decoder`71–82把JWKSource绑定到固定配置URL；不接受token给出的URL。
2. `keys`119–120调用`BoundedIamHttp.get`。后者request55–62对非200/过大body拒绝、future的ExecutionException/TimeoutException/CancellationException及中断统一抛`PlatformException(503,"IAM_AUTHORITY_UNAVAILABLE",...)`；subscriber onError/overflow同样503。正常受控网络失败有明确503类型。
3. JWKS返回200但JSON/keys结构、重复kid、非法RSA/private material等不合格时，`parseKeys`122–136捕获并转换同一503；不能以坏JWKS响应推定浏览器token无效。
4. Nimbus/Spring可能包装JWKSource异常。`TrustedJwtDecoder.decode`64–67明确遍历JwtException的cause链；遇任何PlatformException且status503，直接重新抛出它。没有503 cause的JWT验证失败才变成`BadJwtException("JWT_INVALID")`。直接PlatformException在68行原样传出。
5. 已读冻结Boundary的IngressFilter286–289分别catch JwtException→401、PlatformException→其status。因此初始Portal JWK不可用沿**PlatformException503**分支；不需要靠消息文本/cause猜测，也不需要改Nimbus/BoundedIamHttp/TrustedJwtDecoder来补本已存在的seam。

这与“整个chain的内部JwtException可能误落401”是两件事：初次decode已保留上述503；新Q/M内部ServiceTicket/JWK异常仍必须在固定客户端方法内按阶段转换，不能漏到Boundary的大catch。

## O1c最小范围与错误矩阵

保留原8生产文件范围，不修改TrustedJwtDecoder或BoundedIamHttp。新增STATUS的处理要做到：

| 事件 | 浏览器结果 |
|---|---|
| 原Portal明确格式/签名/issuer/profile非法，或独立确认已过期 | 401；不发Q/M业务请求 |
| 原Portal验证依赖网络不可用/timeout/非200/超限/坏JWKS，decoder抛503 | 安全503，保留依赖失败分类；不能变Portal401 |
| gateway-local Q/M current、ServiceTicket、grant、operation预算到期 | 安全503；除非另有独立可信事实已确认原Portal过期，才可Portal401 |
| 新Q/M内部authority/service-ticket签名验证漏出的JwtException | 在固定Q/M方法内转安全503，不能冒泡触发Boundary401 |
| engine Q/M HTTP401 | 安全503；不转WWW-Authenticate，不重exchange/重试 |
| engine严格合法envelope的403/AUTHORITY_EXPIRED | 保留授权403；它不是gateway本地预算到期，也不是Portal失效 |
| engine严格合法403/RUN_STATUS_TOO_LARGE | 独立403，非授权错误；原判定保持 |

Boundary现通用reject对503仍输出`SYS-AUTH-403`文本，但status本身是503。新STATUS分支可用本地固定安全依赖code/message表达它，避免语义误导；该调整属于已列Boundary文件，不应借此改旧RUN/SESSION行为。不要为了重用session方法，把所有caught JwtException或PlatformException统一invalidPortal。

建议新增真实HTTP验收分开证明：同一合法Portal遇自有JWKS端点503/断连/超时、200 malformed JWKS时gateway503且Q/M为0；同一端点健康时坏签名/过期Portal401；未知kid且收到结构有效的可信当前JWKS按现验证失败处理，不冒称JWK服务不可用。随后分别测内部签名/JWK故障503、本地Q/M预算到期503、真正原Portal到期401、真实engine403/AUTHORITY_EXPIRED保持403。必须检查浏览器HTTP状态/安全envelope与authority/engine调用位置，不能只测异常helper。

## 结论的具体限制

本次静态核对证明已有正常有界HTTP/JWKS错误路径保留503，未运行新的行为验证。`decode`69还有泛化Exception→invalid分支；BoundedIamHttp.sendAsync在future等待try之前。本文不保证任意程序bug、错误构造配置或JDK同步启动异常都可追溯为依赖故障，也不把common JDK全生命周期强取消宣称已解决。按现有效固定配置、明确owner生命周期及上述具名故障矩阵实施；若真实测试暴露某种验证基础设施异常在到达STATUS前已失去类型，保留异常链并向root报告该具体窄seam，再裁定，不能先为假设扩成第9文件或通用异常框架。

## 冻结来源与实际读取范围

两个原件均位于：
`G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/admin-common/src/main/java/cn/agentplatform/common/authority/`

| 文件 | Bytes | SHA-256 | 本次内容范围 |
|---|---:|---|---|
| `TrustedJwtDecoder.java` | 10667 | `26d3d1d668131b20950cdce4fa69c709078fd6ca1ebdf6433568b119b9dad5d0` | decode/JWK/exception窄rg；18–88、119–137、149–153 |
| `BoundedIamHttp.java` | 5634 | `f844e3299452b849d36301bc4dea5fa7baa46ce35254c8484710477d80ed6b49` | get/error窄rg；34–65、78–86 |

上述bytes/SHA均实际计算并与冻结 `docs/evidence/iam-stage-4-o1b-readonly-status/source-after.json` 对应条目相等。Boundary286–289来自上一轮已读D4a冻结原件（SHA `2525da73a86ce83f538c03745cec69e8e835965bfa41851eab4bccc3610c2442`），本次没有重读全文件。未读取第三方源码或声称新版本行为验证。交还后本文冻结。