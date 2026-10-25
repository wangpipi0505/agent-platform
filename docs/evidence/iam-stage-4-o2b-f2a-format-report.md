# F2a M2 具名纯空白修正

仅修改 Connection 原124/387行的条件体 throw 缩进，以及 TlsTest 原382–591行的 array initializer、client 嵌套正文/收尾、failure/factory 成员缩进。两existing source修改/new0；其它616 source身份继承已通过root五phase/6296文件审计的fix epoch，没有重新hash这616文件。未动target、旧owned、旧报告/review或八索引。

两文件 before/after 原字节在 .local/iam-o2b-f2a-format/before 与 /after；docs/evidence/iam-stage-4-o2b-f2a-format/whitespace.diff 为实际行差异。词法token对照包括完整注释/字符串/字符字面量，两个token数组逐项完全相同，且每行 trimStart 后完全相同：只改变具名行leading whitespace，行数/换行不变。token-proof 4288B/SHA256 2af97638945ba82edbdb15fa47c4ebee706ca7b65581569c624ce21e18139017；diff 27120B/SHA256 2a61ae77ae5d36241a09341a190111b723e66cc0ed9e703973b60e4fc5a23034。

source-before/after618、delta2/new0、source-snapshot与copy-lineage在.local/iam-o2b-f2a-format/。完整618新source snapshot仅按冻结fix replay的manifest逐文件copyFile；616继承已验身份、两改从新after source复制并额外核对字节。没有复制.git，没有生成patch replay，也没有把复制当作新测试或新编译。

功能final47/0/0/0及focused19main/15test属于此前功能epoch。当前新字节只具备token等价证明，未重编译、未跑测试/PG/Git/provider/replay，也未重跑任何既有审计。M3/M4及生产装配/容量边界保持；M2关闭仍待原reviewer具名复核和root裁定。

本epoch仅新owned控制/两文件beforeafter/full source copy/report/helper。handoff不嵌inventory自身身份；inventory只排自身。完成后冻结并归还全部source与新owned写权，不自行Accepted或commit。
