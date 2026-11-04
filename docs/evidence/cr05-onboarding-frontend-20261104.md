# CR-05 接入会话前端收口（两轮功能检查保留结果）

2026-11-04。只修改获交接的前端文件与定向测试，不修改后端、不操作DB、不暂存/提交。功能检查严格两轮；不放宽断言、不开展全量验收。

## 页面与协议收口

- OnboardingSessionWorkspace独立读取`GET /admin/onboarding-sessions/templates`业务目录，不依赖ADMIN父页的模板/来源包读取。客户端支持数组或items包装，只接受PUBLISHED QUERY的明确版本；ADMIN模板维护区与EDITOR业务使用区分开，管理区加载失败不作为业务区前置条件。
- 操作选择展示supported/unsupported与服务端reasons；只允许选择受支持操作并保存到plan.selectedOperations。fields、mapping、returns共用真实计划，经PUT原expectedRev保存，不生成浏览器业务权威。
- BusinessFieldsStep恢复false/0/null时保留显式数值null，布尔控件可区分true/false/null；初始值不会被默认空值改写。终态/忙碌时控件只读，字段事件也在父层拦截。
- dirty时resume/refresh/create均在按钮和处理器阻断，不发送覆盖性读回；明确保存成功才采用返回计划。取消必须填写reason，经原会话rev和requestId提交；PUBLISHED/CANCELLED禁止保存、预检、执行、取消及语义子组件写入，仍允许只读查看回执。
- 交接校验实际session.appCode、templateCode、PUBLISHED状态与正整数appVersion，应用回执须属于同一实际应用。仅真实handover挂AssistantLaunchStep，不把模板编码或模板PUBLISHED视为应用发布。文案明确“本次发布回执版本”；现有入口打开应用当前发布版本，未声称链接固定历史版本，未扩展Chat/runtime版本选择语义。
- QUERY路线使用“助手/应用”通用文案，展示只读resourcePlan、missing与每步receipt的missing。已组装时提供基于实际appCode生成的原应用发布/评测工作区入口；不把后端API链接当网页入口，不编造评测expected或通过结论。
- semanticSaved只GET持久会话并清预检缓存，不重复PUT应用或接入计划。后端QUERY/FLOW组装、debug-run幂等验证、评测与发布门禁由connections席实现，本文件不声称后端已完成。

## 必要检查记录

独立Vue编译配置：`web/tests/onboarding-session.config.ts`，沿用本工程真实SFC browser-render编译及Node自定义宿主；没有用默认Vitest配置加载SFC。

第1轮RED：

```powershell
# cwd: web
node node_modules/vitest/vitest.mjs run --config tests/onboarding-session.config.ts
```

exit 1；9项中8失败、1通过。失败明确复现数值null初始化丢失、无操作选择/unsupported说明、dirty恢复覆盖、无取消reason、两个终态仍可执行、未挂实际handover入口及坏handover解析放行；semanticSaved只读原行为通过。

第2轮（保留原始结果，不记全绿）：同一命令exit 1；13项中12通过、1失败。额外覆盖业务目录隔离、目录状态拒绝、items包装/独立Workspace与评测缺项入口。唯一失败为“Workspace无需ADMIN父页模板即可独立读取业务目录，兼容items包装”的文本断言`expect(text(root)).toContain("独立业务模板")`：组件将名称绑定到el-option.label，自定义宿主当时没有模拟ElementPlus将label呈现为可见文本的行为。记录仍为12/13，不把该解释代替通过证据。

按主席明确要求，第二轮后仅修正测试宿主的el-option渲染，使label成为可见文本；原断言、用例和预期没有删除/放宽。**该最终夹具修正未复跑功能测试，未开展第三轮。**

类型编译：

```powershell
# cwd: AP根目录
node web/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p web/tsconfig.json
```

第2轮并行全项目类型检查exit 0；最后夹具整理一度报setup props隐式any，改用本工程defineComponent提供框架类型推断，最终全项目vue-tsc exit 0。类型编译不作为第3轮功能结果，也不把12/13改记为13/13。

## 限制

HTTP返回、权限上下文和子组件保存通知使用测试替身；生产Vue setup/render与客户端请求路径/载荷实际执行，但这些检查不证明PG落库、真实连接/模型执行、真正发布成功或浏览器视觉通过。后端仍在开发，需主席后续按实际整组状态登记。固定历史应用版本的使用入口依父席决定后置，本次只保证实际应用身份正确与回执版本可核对。
