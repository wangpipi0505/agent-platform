# 发布运行与冻结模型引用

## 发布运行（R-43）

IAM 网关与 engine 同时启用 `AGENT_PUBLISHED_RUN_ENABLED=true`。网关从 `AGENT_APP_URL` 获取受权发布元数据，经 `AGENT_ENGINE_URL` 向绑定版本提交运行。正式部署提供受信 HTTPS 源；隔离 test 配置支持 HTTP 回环。关闭入口返回 503，启用且缺失或不可信的依赖配置使装配失败。经验证的业务状态保留 `APP_NOT_FOUND`（404）和 `APP_UNAVAILABLE`（409）。依赖故障返回 503。

## 显式重冻结（R-45）

`POST /admin/apps/{code}/model-bindings/refreeze` 接受 `{draftRev,requestId}`。网关执行平台权限检查，app 复核发布权限、租户与应用；模型服务返回当前模型条目的冻结回执，app 校验后才按原草稿修订号提交。响应含新 draftRev 与 modelBindingRefs。同一请求号与原修订重放复用回执，草稿已前移返回 412，同一请求号改指另一修订返回 409。成功操作写入 `app.lifecycle_log`。已发布快照固定，替换在线引用需重新评测、预检并发布新版本。

运行材料检查使用持久化清单的原始 JSON 计算规范摘要。冻结绑定单独对照运行快照、冻结回执和存储摘要。缺少运行材料使用 `MATERIAL_MISSING`，内容摘要不一致使用 `MATERIAL_DIGEST_MISMATCH`，缺少合规冻结引用使用 `BINDING_NOT_FROZEN`。错误响应不包含原始材料或票据。

## 修复 V901 种子（R-44）

运行环境通过 `AGENT_PORTAL_TOKEN` 提供有权限的管理员票据。在脚本的 `--gateway` 参数提供目标 HTTPS 源。在 `scripts/r44/republish_demo.py` 先执行 `prepare --model-entry-id <当前租户模型条目>`，核对新草稿并完成正常评测，再执行 `publish --draft-rev <目标修订>`。要求确认 DEMO 评测时，核实预检证据后显式加 `--confirm-demo`。历史 V901 不重写。适用应用为 `satellite-planning-assistant`；`user-governance-assistant` 为 FLOW，保留构建面用法，外部 AGENT 提交要求已发布的 CHAT。
