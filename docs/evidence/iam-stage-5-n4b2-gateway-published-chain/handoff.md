# N4b2 实现者完整交还

实现、有限验证、物理冻结与一次完整保护已结束，source/target/ownlocal/evidence 独占写入权现在交还 root。Root 可开始独立 fast/protection、Spec 与 TaskQuality 审核；作者不自签 full Task5。

- 生产8位置、测试5位置；source377（8新增5修改），target1879（65新增20修改0删除）。
- 28阶段actualRunning/XML全部留存；当前48个用例为3 common +28 gateway proxy +7联合PG +10原metadata。按coverage-ledger逐case最后有效green与后续适用复核，不相加轮次或冒称最终一次全绿。
- 最后green28为长chunk header8192上界及普通chunked2绿；PG最后positive green25为1run/2attempt/52material、outputa7、worker0/legacy0，4distinct grants=2query+2main、same origin1；未再重跑历史green。最终engine compile04是0test。
- 实际root cwd完整patch check/apply exit0，全部377 replay source字节相等。source/target current/frozen完整物理SHA也一致。
- 123837 protected文件一次完整物理SHA、28 runners/8最新索引：0 mismatch、0未批准新增。before保持不变。
- 当前精确fixture及本工程Maven/Surefire JVM0；proxy16历史app PID/port/exit未记录，当前0不能补成历史已观测退出。其余ready/PID/port/STOP/exit原raw与构造CP/公开API限制详见resource-evidence。
- 慢reader是fixture5500ms主动close，已提交73728成功字节，starts/exits3；不承诺恶意peer公共API硬关闭。commonIAM JDK retry/close缺口本轮未改；没有whole-chain0retry/全部网络硬取消声明。
- 不开启普通GatewayApplication全局IAM，未改其它业务/SQL/前端/配置/runner/8索引，无commit/package/deploy/push。

报告：docs/evidence/iam-stage-5-n4b2-gateway-published-chain-report.md
主机器描述：docs/evidence/iam-stage-5-n4b2-gateway-published-chain/handoff.json
完整证据清单：docs/evidence/iam-stage-5-n4b2-gateway-published-chain/evidence-manifest.json（不包含自身及handoff.json，避免递归；handoff.json给其SHA）
