# ONB-07 AP 侧证据：正式本体来源消费边界

日期：2026-11-04　席位：O（OR 与语义）

## 1. 范围与边界

- 新增 `OntologyManifestClient`，只在接入模板显式声明 `releaseId` 时回读 OR 正式来源；
  声明缺省返回空且**不触网**，既有模板/普通 HTTP 路线零变化。
- 不缓存投影正文、不写本地库、不把候选预览或停用扩展当正式来源；AP 只保存四元组与摘要。
- 依赖的 OR 端点为 ONB-07 OR 侧新增的 `GET /onto/releases/{releaseId}/formal-source`，
  由既有 `SkillClients` 签票访问，不跨库。

## 2. 防御性核对

客户端逐项核对，任一不符按 `502 DEPENDENCY_UNAVAILABLE` 拒绝：

- `schemaVersion=="1.0"`、`readOnly==true`；
- `releaseRef.releaseId` 与请求一致、`spaceCode` 与请求一致（若提供）、`version` 形如 `vN`、
  `contentDigest` 为 64 位小写十六进制且等于 `bundleSha256`；
- `manifestSha256` 为 64 位小写十六进制；
- 操作 ≤64，`opCode` 字符集/长度、`route` ≤512、状态 ∈ {ACTIVE,DISABLED}；
- 扩展 ≤64，`extensionId` 为 UUID、profile 形如 `read-only@1.0`、`extensionSha256` 64hex、
  仅接受 `ACTIVE`；
- 计数字段为 0..1,000,000 的整数。

错误映射：OR 404/409 `SEMANTIC_REF_INVALID` 原样阻断；“缺本体/版本不匹配”不会被补成可用；
连接失败/OR 502 → `503 SEMANTIC_SOURCE_UNAVAILABLE`；声明中的 releaseId 非 UUID → 400。

## 3. 两轮检查

1. `mvn -o -pl skill-registry -am -Dtest=OntologyManifestClientTest -Dsurefire.failIfNoSpecifiedTests=false test`
   → 8/8 通过（首轮即绿，未放宽断言）。
2. 回归：`OntologyManifestClientTest,SemanticFactsClientTest,SemanticDefinitionClientTest,SemanticDecisionClientTest,SemanticExecutionGateTest`
   → 8 + 9 + 8 + 6 + 13 = 44/44 通过。

## 4. 未覆盖

- 未接 ONB-04 向导页面（该页由 F 席按模板声明调用本客户端；本子步只交付服务端消费边界）。
- 未跑真实 OR 网络（服务账号 `release.read` 角色与空间成员需部署配置），由集成窗口覆盖。
- 未新增 AP OpenAPI（客户端无新端点）；OR OpenAPI 已同步，AP 侧类型/前端待向导子步。

## 5. 回退

删除 `OntologyManifestClient` 及其测试即回到“无本体路线”；不修改任何既有技能/模板路径，
普通 HTTP 查询与文件分析路线不受影响。
