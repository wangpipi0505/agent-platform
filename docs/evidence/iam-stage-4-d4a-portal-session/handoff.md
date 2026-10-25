# D4a 作者交还

实现/源码/target/作者证据已冻结并交还root；待新的独立复审，不是生产IAM或浏览器验收。没有提交。

- Source380：新增3/改8/删0，source-after.json 78337B SHA7e1a1b53ae7b494361bf75b36d2878a1f4dbb1e5d0631c3311643a8e34822708。
- Target1931：增43/改32/删0，target-after.json 398161B SHA0a855288ee3f47bff030da2f562abdad3c6533c1ee224ed80e5e63d980f99516。
- Full-context patch11文件259511B SHA9f2fb9b7a0bfe6ffe0bef75f86299557a6435fdc7d5accea1075a9fc1b27e804；实际工程root-cwd apply/check及完整380条重建一致，见replay-check.json。
- Final source/target只用11/75物理overlay及正式N4b2 immutable bases；完整target1931逻辑合成也一致。中间380/1929冻结原件保留intermediate-freeze。
- Common16+gateway8，共24个唯一具名用例最后适用pass；20次命令中含真实红、setup/oracle失败及适用混合阶段绿，绝非一次同源24绿。原日志/Running XML/source-before/candidate overlay见stage-ledger及各stage。
- 最后行为命令envelope-green01只跑HTTP200空authority封装503这一case；已经停止行为测试。
- PID39240/start2026-10-20T19:34:23.104Z实际退出；gateway/issuer/resolver65087/65058/65059 close后逐个connect-refused；当前精确root+任务start筛选Java为空，6个既有老进程未动。见resource-check。
- 关键历史谱系、8最新索引、v1.2、9 contracts及runner保护见protection/contract-key-anchors；没有重复全历史123837/16347扫描。
- 11位置中的AuthorityResponseVerifier是后批准补充，真实编辑前补采独立保存，最初10候选capturing没有回写。
- 初期stage overlay真实包含尚未改动的批准候选；stage-overlay-index明确标注，不称delta-only。最终overlay严格11个真实变动/新增。

详细结果/不变量/失败分类/源码范围/限制/自审在同级外报告 `docs/evidence/iam-stage-4-d4a-portal-session-report.md`。Own leaf manifest同时封存两个自身目录、唯一报告及不变runner必然生成的20个精确新.local日志；排除自身/evidence-manifest、handoff.json、seal.json防循环，seal绑定它们的descriptor。

Root现在可有限审计并派fresh独立review。作者不再写源码、target或本任务证据；D4b由root另行派发。
