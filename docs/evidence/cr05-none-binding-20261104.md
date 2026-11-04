# CR05 NONE 环境绑定前置修正

日期：2026-11-04。产品文件白名单：

- `app-management/src/main/java/cn/agentplatform/app/application/EnvironmentBindings.java`
- `web/admin/src/pages/integration/EnvironmentBindingsPage.vue`

绑定创建/修订读取当前租户被引用的 APPROVED 不可变连接档案修订，以真实 `authScheme.kind` 校验：NONE 必须显式 `secretRef=""`；其它鉴权仍必须为非空 `scheme:ref`。未声明 kind 沿用既有 resolve 的 NONE 语义。缺失、非字符串、空白及无效引用均拒绝。前端原本可以提交空串，仅调整填写说明；共享 integration-console 无阻空校验，不修改。V111 的 NOT NULL 已允许空串，本次不修改历史迁移或新增迁移。

仅执行编译/类型检查：

- JDK 21 `javac -encoding UTF-8 --release 21 -cp <已有隔离依赖类路径> -d D:/CodexPrep/algorithm-reorg/cr05-none-binding app-management/src/main/java/cn/agentplatform/app/application/EnvironmentBindings.java`：退出码 0；隔离编译日志 `D:/CodexPrep/algorithm-reorg/cr05-none-binding/compile.log` 无诊断。类路径读取 `D:/CodexPrep/algorithm-reorg/cr05-plan-rules/classpath.txt`，未写共享 Maven target。
- 在 web 目录执行 `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`：退出码 0，无诊断。
- 两个产品文件执行 `git diff --check -- <上述两个路径>`：无诊断。

本次按指令未新增或执行功能测试，未复核真实 HTTP/PG、NONE 创建及 Bearer 拒绝空串的业务行为；不以编译/类型通过代替功能验收。未修改旧断言，未运行全量测试，未暂存或提交。
