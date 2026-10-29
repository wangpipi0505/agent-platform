# EX-12 AP 运行中心观测展示

RunList 沿真实 page 端点传 app/package/route/tool/release/trace 条件；新增列表来源展开和 Trace 列。RunDetail 版本与依赖页使用同源 RunObservation 展示冻结软件/包/路线/环境/技能版本与选择状态；历史缺值显示未记录，不从当前目录推断，不把冻结全部绑定标作实际执行。保留原始版本记录折叠查看。无新依赖、业务事实或镜像测试。

一次验证：web 下 `node node_modules/vitest/vitest.mjs run tests/run-protocol.test.ts tests/run-observer.test.ts`，既有69 PASS；`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` 与 `node node_modules/vite/bin/vite.js build --config admin/vite.config.ts` 均exit0，3个UI源稳定。记录 `.local/ex12-observation-ui/round1-20261030-01/`。已有通过不重复，不安排第二轮。

ROOT 完成一次非作者轻量检查。69项是原协议/观察器控制，不是新Vue组件的浏览器验收；真实浏览器、OR可信维度落点和跨侧trace仍待。本次只交AP运行中心接线，不关闭整个EX-12。回滚本地提交撤3个UI文件差异，后端与持久事实保持。
