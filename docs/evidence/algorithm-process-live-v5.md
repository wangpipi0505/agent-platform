# 算法真实进程纵切 V5

本轮固定 runner/oracle 与 root 移交的 962 engine/skill 制品实际执行，五个有限场景均通过，runner exit=0。结果仍待 root 与原独立 reviewer 事后复核。没有修改源码、请求、原 4 秒期限（offload 15 秒）、环境、fixture 或清理预算；未重试任何初次业务提交。

命令：`node tests/smoke-algorithm-processes.mjs --execute-reviewed .local/algorithm-reviewed-artifact-lock.json`

数据库：`agent_platform_algorithm_live_ec8285eb1f44`，为本轮唯一新库，保留追溯。原始目录：`docs/evidence/algorithm-process-runner/live-ec8285eb1f44`。真实服务为每场景新建的一对私有 engine/skill；K8s 使用独立 namespace 的有限 HTTP fixture。

| 场景 | run / call | 原外部 attempt | 本地 attempts / 提交 attempt | Job UID | POST / 逻辑提交 / 终态 note | 终态 |
|---|---|---|---|---|---|---|
| complete | r_78455d30c3b34e879f895a8db4e3d7cf / 1 | 2 | 2 / 2 | a8c2429f-a8e7-487a-8b73-23c099cec329 | 1 / 1 / 1 | SUCCEEDED; fact SUCCEEDED; binding DONE |
| ack-loss | r_6e081b156e86429e801bbaea898549b6 / 2 | 5 | 5,6 / 6 | bf00e73e-1f87-4682-a7ad-1fad4b54f0cb | 1 / 1 / 1 | SUCCEEDED; fact SUCCEEDED; binding DONE |
| failed | r_418b69e5e2a04dadaff3fa2995497e2d / 4 | 9 | 9 / 无 | 57baf85a-a26b-4ae8-84c2-351ef63bb48f | 1 / 0 / 1 | FAILED; fact FAILED; binding DONE |
| running | r_606c4879744346dc8320e3abebd78d3f / 5 | 11 | 11 / 无 | 46d001d8-272e-4d10-914b-b4c03c96bf1a | 1 / 0 / 0 | CANCELLED; fact UNKNOWN; binding SUBMITTED |
| offload | r_6bd924a669174f2c9b9c0897334b95bf / 6 | 13 | 13 / 13 | f8e23464-e04c-4a74-a488-d3c70f6d12d7 | 1 / 1 / 1 | SUCCEEDED; fact SUCCEEDED; binding DONE |

Complete 记录先 GET 404、单次创建、可信 Pod/controller/algorithm 容器与完整 JSON；ACK 丢失仅对账原 Job，恰一次本地 no2 消费原成功事实，原 call/fact/binding/snapshot/结果 owner 均保持原外部 attempt。可信 Failed 以 NON_RETRYABLE_FAILURE 交付且不构成 NOT_APPLIED。以上与 offload 都执行 controlledCallId 重检并重新 capture；逐行集合、原身份、UID、终态 note 和逻辑提交仍满足冻结门禁。

Running 对账仍 UNKNOWN，随后只取消本地 run 为 CANCELLED，原 fact/call 仍 UNKNOWN、binding SUBMITTED，逻辑提交与终态 note 为 0。取消不表示删除 Job、停止集群执行或撤销效果。该场景没有额外添加重检请求，保留原测试序列。

Offload 实际输出 1212 字节（outputMaxBytes=1024），真实 MinIO 上传/回读后 artifact=art_378961d017934f0789bfc77f50d40df6、state=READY；受控下载内容逐字匹配且 SHA=d0150502d84a1181a40bb6d26b8332e63c62f54283f056e0d1891fa866fc1d63，fact structured=null、唯一 fileRef 指向该 artifact。事后 PG artifact 行另存只读快照。

全部场景 fixture POST=1；未用上游拒绝端点请求数=0。事后仅对本轮库做 BEGIN READ ONLY + default_transaction_read_only、statement_timeout=5s、连接/进程有界的完整行快照，得到 5 runs / 5 calls / 5 facts / 5 bindings / 1 artifact。原始 SQL、stdout、stderr、命令参数边界与执行状态均保存；真实凭据仅由本工程 .env 注入环境且脱敏。

10 个私有 JVM 每次 HTTP 前都通过 exact PID/creation/JAR/TCP 检查，逐项 finally 停止；最终 records 及另一次只读 PID/TCP 核验均无本轮进程或端口残留。fixture 均按原预算关闭；没有接管六个主服务，没有 drop 新库。清理详情见 processes.json 和 algorithm-process-live-v5-cleanup-readonly.json。没有业务错误或清理错误被省略。

runner SHA=e2878c7ae3275eaa26e6a7e24a178fae79c9bac38c91d9ecdfbe55d7c348a1e4；oracle SHA=4c63d3c66764250211866f36ea511668639c95834acfa07357ec5cd5b4e69abe。固定 engine SHA=d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7，skill SHA=2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196。冻结 16 source SHA 与此前 305 份历史证据 SHA 均复核未变，V1–V4 原失败事实保持。

本结果证明隔离的真实 JVM + PG + MinIO 与有限 K8s HTTP fixture 路径；不证明实际集群/容器/RBAC/生产 IAM、容量、多副本恢复或全部 G2/功能验收。
