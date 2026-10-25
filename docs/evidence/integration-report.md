# 新工程跨服务验证

测试对象为本工程六个真实Spring Boot服务、独立PG及真实Redis/MinIO。模型/写技能外部依赖由 `tests/fixtures/LiveDependencies.java` 显式提供，仅证明协议与业务控制管道，不是实际模型能力或业务服务验收。未使用SQL预改技能/应用发布状态。

## 已完成路径

| 场景 | 实际证据 | 结果 |
| --- | --- | --- |
| 外部短票据正式运行 | r_7731f4acac594f9ba98fd102b5d48bd8 | SUCCEEDED |
| ApiKey直接运行 | r_bc7be9fb98dd40bc9fa87f39731f7c79 | 创建成功；后续控制边界相同 |
| 票据访问管理接口/吊销 | 未过期同一票据 | /admin=403；吊销后/v1=401 |
| 写技能首次上架 | smoke_write_ac2a778bae / skillVersionId=1 / callId=15 | API创建DRAFT→受控DEBUG→真实确认→connection→模型FC选择管道→PUBLISHED，无前置状态环 |
| 写确认前置边界 | r_1c6e189b1706419f9e602ef0b31b1582 | 批准前外部写0，批准后成功且写1 |
| 候选评测含写技能 | er_2603bbf9-70b4-4b7a-b71b-f9e102261cee / r_d73ea99c5c214c8dba03d8d93a95f438 | 等待人工确认后同report/case resume，最终ACCEPTED |
| 原子发布与正式TASK技能 | smoke_flow_3cfa0d2fcb v1 / r_098b50e73b6a44b88e2e02361de0860c | 正式SUCCEEDED，写技能输出value传给END，得到formal-smoke |
| 正式Mock不可越权 | 同上运行主动携带伪Mock | 仍实际外呼一次、isMock=false、统计callKind=FORMAL且SUCCEEDED |

脚本：`tests/smoke-write-skill.ps1`、`tests/smoke-formal-skill.ps1`，原始选定ID结果 `.local/write-skill-smoke-result.json` 和 `.local/formal-skill-smoke-result.json`。登录票据与集成密钥均仅脚本内存，不写证据文件。脚本写入明确标记的隔离测试记录，只面向本地开发租户。

## 真实阻断反例

- 初次FORMAL脚本将结果误写为 `write.output.structured.value`，而SKILL节点输出契约已经是structured本体；实际结果null导致安全用例失败，门禁REJECTED。修正映射为 `write.output.value`，预期值保持独立声明的formal-smoke，没有改预期去迎合错误结果。
- 应用未声明技能要求的EDITOR角色，预检守卫组真实BLOCKED；在新草稿明确声明config.guard后重新评测/预检才发布。
- 浏览器在app重启窗口遇到真实Connection refused，前端显示失败，未回退假数据。公共错误处理已将此类网络依赖失败统一为可恢复502，待下次所有服务构建时消费。

## 限制

这些结果只关闭相应执行路径验证，不等于完整17项功能、跨副本故障、真实模型选型正确率、本体实际版本、真实MCP/K8s、生产IAM或容量验收。R3回调恢复与R4取消竞态已有局部PG精确时机回归，仍须独立修复复核及跨服务网络/进程故障注入。
