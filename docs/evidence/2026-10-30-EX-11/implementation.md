# EX-11 软件能力目录实现

已实现租户目录列表、版本详情、字面搜索/游标/原声明空间与发布筛选；真实 PG 来源应用及绑定展示，不把声明当作已评测。管理台新增目录入口、登记、显式环境/路线绑定、完整 JSON 应用计划、真实应用/技能链接和恢复错误。只读角色与编辑动作分开，直接 URL 由服务器校验。客户端使用真实 axios 传输。OR 单一导航宿主添加可配置深链，保留空间/发布引用。

本批最多两轮：

1. `mvn -B -ntp -pl app-management test -Dtest=cn.agentplatform.app.application.CapabilityPackageQueryTest`：4 FAIL，nativeExit=1，缺少查询入口 404。`.local/ex11-capability-directory-candidate/directory-first-20261030-01/`。
2. 同选择器：4 PASS，nativeExit=0，新 XML/源稳定；`node node_modules/vitest/vitest.mjs run tests/capability-package-page.test.ts`：4 PASS；AP vue-tsc 与 admin vite build 均0。完整参数及日志 `.local/ex11-capability-directory-candidate/directory-second-20261030-01/`。OR 同轮 vue-tsc、admin vite build 均0，源稳定，记录 OR `.runtime/ex11-directory-navigation-round2/`。

ROOT 完成一次非作者轻量检查。回滚本地提交可撤新增目录/API/导航，原包、来源、应用事实保持。新查询没有新增事实表，也未改 AppService 或原 Repository。

配置 AP `VITE_ONTOLOGY_CONSOLE_URL` 为 OR origin，OR `VITE_CAPABILITY_CATALOG_URL` 为 AP origin；仅接受无凭据、query、hash 的 HTTP(S) 根 origin。不传认证令牌，目标独立判权。

未验：桌面/390px真实浏览器、跨站未登录后保留目标及空间/发布上下文；OR 原登录守卫可能丢目标，现链接仅称原声明引用入口。API生成契约同步接续独立批次。EX-11 整卡仍进行中，不宣称浏览器联合验收完成。
