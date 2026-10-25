# D4a I1 修复交还

已冻结并交还source/target/本次own写权，待原D4a reviewer聚焦复审。没有提交。

- 仅Verifier与common session test两源修改；elapsedSession恢复exp>nbf，原默认路径不动。
- 新一个JUnit case内四个current/ticket equal/greater变体实际红全部403、修复绿全部503，来源/grant有效；green还仅跑两指定控制，总3case pass。已停止测试。
- source380，78337B，SHAf56b205dcf81af1e9be357e49fae284768916a920cb36343d2aac50f0ee5aed2。
- target1933，398551B，SHA8b52aa85f9eb05ce6572e3e45668dac8566ae661c6b2e2d42a7165ffe3c00a30；增2/改5/删0，全7目标已归因。
- full-context patch两文件22883B，SHA426444b1809af8d39a5f8b00cf533a72c032b1150a800d2118808ac520cc1b54；实际工程root-cwd check/apply及完整380逻辑重放一致，target1933逻辑合成一致。
- 12原/新明确锚点及8索引未变，继承root旧审计和原完整历史谱系，不再扫旧123837/16347或重跑原24/HTTP/resources。
- 两个记录PID/start精确退出；M1日志原件保留，未关闭检查/清洗/额外重构。

详情在 `docs/evidence/iam-stage-4-d4a-time-order-fix-report.md` 和本目录stage-ledger/variants、replay-check、target-attribution、protection-check、resource-check。新own leaf manifest及seal无循环；原D4a报告/594seal/review保持原件。
