# 受控产物接口（2026-10-20，V306）

`ResultEnvelope.fileRefs[]` 大结果引用：

```json
{
  "artifactId":"art_<32位小写十六进制>",
  "downloadUrl":"/admin/artifacts/{artifactId}/download",
  "externalDownloadUrl":"/v1/runs/{runId}/artifacts/{artifactId}/download",
  "sha256":"<64位SHA-256>",
  "bytes":5242891,
  "contentType":"application/json",
  "summary":"结果已转存，请使用受控下载查看"
}
```

不含 `uri`、bucket、object key、MinIO URL 或临时签名URL。`structured=null`，`modelSummary` 为短摘要。大结果原文上传后仍回读验证原始 `outputSchema`，不能用摘要替代原文验收。

- `GET /admin/artifacts/{artifactId}/download`：已验门户身份，按tenant+principal读取不可变run绑定的产物。
- `GET /v1/runs/{runId}/artifacts/{artifactId}/download`：另要求路径runId与登记runId完全一致；网关另核对集成票据的app/run权限。
- 两者返回二进制JSON文件流（非ApiResponse包装），`Content-Disposition: attachment`、`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff`。前端应携带Authorization执行fetch并下载blob，不能绕过鉴权直接使用物理路径。
- 身份来自 `Actor.current()` 已验JWT；body、query、X-User-Id不能改变身份。无身份401；不存在、过期、归属不匹配统一404 `ARTIFACT_NOT_FOUND`；存储暂不可读503 `ARTIFACT_STORAGE_UNAVAILABLE`。

`returnSpec.fileRefPaths` 所选叶子只接受 `{"artifactId":"art_..."}` 或其opaque字符串，数组可递归。必须是同tenant+principal+run的READY且未过期产物，输出由服务重新封装。附加uri等字段、外部http/s3/pg/file URL、未知资源均明确拒绝，不能以协议匹配冒充受控产物。无已核实run的连接探活不生成产物。

产物保留7天；过期立即禁止新下载。后台每60秒至多处理8个登记产物，READY到期与UPLOADING超过1小时恢复窗口均可清理。清理只按PG登记的tenant+artifactId+固定bucket/key执行；未完成multipart只列完整键前缀、最多8个upload，逐项精确相等校验后abort，不列全桶。失败持久保留CLEANUP_PENDING、错误类别和尝试数，5分钟后重试；DELETED记录保留作为清理证明。多副本以PG SKIP LOCKED与租约认领。

对调用方的变动仅fileRefs字段与下载路由；已同步root网关和frontend代理。PG迁移V306，旧V300-V305未改。
