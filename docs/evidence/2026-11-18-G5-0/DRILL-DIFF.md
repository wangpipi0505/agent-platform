## G5-0 两遍演练差异记录（A=bash 脚本族 / B=ps1 脚本族） @ 2026-11-18T07:22:59Z

== [1] 升级段 applied 数（engine=4: V229/V230/V231/V901; app=3: V122/V123/V901; skill=3: V321/V322/V901）==
-- A:
[g50-drill] module=engine target=HEAD applied=4 from=228 to=901 outcome=901
[g50-drill] module=app target=HEAD applied=3 from=121 to=901 outcome=901
[g50-drill] module=skill target=HEAD applied=3 from=320 to=901 outcome=901
-- B:
[g50-drill] module=engine target=HEAD applied=4 from=228 to=901 outcome=901
[g50-drill] module=app target=HEAD applied=3 from=121 to=901 outcome=901
[g50-drill] module=skill target=HEAD applied=3 from=320 to=901 outcome=901

== [2] 基线断言 diff（A vs B，应为空）==
(一致)

== [3] 升级前行数对照 diff（A vs B，应为空）==
(一致)

== [4] 链头断言+升级后行数 diff（A vs B，应为空）==
(一致)

== [5] 回退后行数 diff（A vs B，应为空）==
(一致)

== [6] 回退后 history 摘要 diff（A vs B，应为空；版本/脚本/成功位不含时间戳）==
(同[5]同源，一致)

结论：两遍演练的版本演进、断言结果与行数对照逐行一致；差异仅为各日志头时间戳与
pg16 schema dump 的随机 \restrict 令牌（已在各自 09 步骤内剔除后比对）。
