# R-45 重启后材料与冻结绑定
真实 ApiKey 提交后任务 SUCCEEDED，读取详情、事件与结果。重启 model 与 engine 后，JSON 解析所得完整 result 与重启前相等。[任务回执](e2-task.json)。再次按同一 run 在重启两侧分别读库，版本、候选 SHA、绑定 SHA 及 43 条材料记录逐行相等。[两份快照](material-restart.txt)。
E2 独立临时 PostgreSQL 和回环夹具运行冻结代码的绑定验证与引擎用例，19+1 项通过，覆盖未冻结与摘要不符。另用回环 HTTP 404 核实 MATERIAL_MISSING。[用例汇总](isolated.txt) · [材料缺失原件](missing-material.txt)。
