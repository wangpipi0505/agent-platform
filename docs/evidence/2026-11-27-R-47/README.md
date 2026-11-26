# R-47 已发布、草稿、缺失三种 HTTP 回执
使用同一 admin 登录向 `POST /admin/apps/{app}/runs` 提交文本。已发布返回 200，草稿返回 409 APP_UNAVAILABLE，缺失应用返回 404 APP_NOT_FOUND。[现场响应](responses.json) · [成功运行的数据库记录](../2026-11-27-R-46/runs.txt) · [旧版错误 503 的对照](../2026-11-26-R-47/README.md)。
