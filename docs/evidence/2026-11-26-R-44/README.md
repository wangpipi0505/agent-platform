# R-44 V901 种子修复

E2 旧版 satellite-planning-assistant 只有模型显示名称和供应商编码，modelRefs 为空，真实 ApiKey 提交被拒绝。历史样本见相邻 R-23 记录。

本次提供 `scripts/r44/republish_demo.py`。脚本按当前租户有效模型条目修改草稿，保留原提示与轮数，走显式重冻结。发布时校验指定修订、预检及证据确认。FLOW 种子 user-governance-assistant 仅用于构建面。

`python -B -m unittest discover -s scripts/r44` 两项通过。数据库回归 `ModelRefreezePostgresTest` 从真实 V901 草稿读取旧结构，经 app 保存及重冻结得到引用和摘要，核对原发布版本、重复请求和操作审计。模型冻结服务使用夹具。`PublishedModelBindingTest` 核对缺失冻结引用的错误分类。

E2 待按正常评测发布流程重发种子，核对新版本冻结摘要，以真实 ApiKey 提交 mode=AGENT 并留存运行回执。本轮没有修改在线种子。
