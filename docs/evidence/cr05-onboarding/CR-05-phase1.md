# CR-05 持久接入会话与已发布技能读取路线（阶段1）

日期：2026-11-04。本阶段不能代表CR-05整组完成；未启动全量验收或三款业务软件场景。

## 当前实现

- EDITOR业务使用入口与ADMIN模板维护分离；会话与请求均以验签tenant/principal限定，os_会话ID，expectedRev CAS，requestId同载荷重放/异载荷冲突。
- V118持久化Session、按planSha256冻结的StepReceipt和RequestReceipt；V119持久化取消原因。更新/取消不覆盖在途步骤；晚到worker不能覆盖新执行token。原runId/发布commitRequest持久收据用于恢复，不把UNKNOWN当重新发业务写的许可。
- 初始fieldsBundle从批准包工具/精确技能inputSchema推导，保留false/0/null；选择、映射及摘要/卡片进入真实FLOW的SKILL.args与END.args，使用现有Values方言；文件仅允许原技能已提供的受控文件集合。数组下标返回投影/复杂字段明确拒绝。
- 复用已RESOLVED的精确发布只读HTTP技能；受控连接候选按当前skill.<code>/read grant、批准档案/绑定、操作和环境过滤，不向业务客户端返回地址/凭据。
- 会话专属包组装复用prepare/validateCapabilityDraft/AppService.create/真实来源关联/save，同一包可创建不同应用。旧包级applicationPlan/receipt不改；已有人工编辑触发冲突，不覆盖。
- VERIFY复用真实debug-runs入口并保留原runId轮询；可提供明确evaluation请求调用原monitor。没有评测用例时仅给原应用工作台入口及EVALUATION_REQUIRED，不生成PASS。PUBLISH仍调用原AppService.precheck/commit及其评测/安全/环境确认门；真实发布回执才产生templateCode、实际appCode和当时appVersion。助手入口按当前前端支持使用/w/<appCode>，不附未消费的固定版本参数。
- 固定RuntimeClient不接收客户端URL；禁重定向、2MiB硬响应闸、保留当前主体服务票据边界。

## 必要功能两轮

专属DEV库：jdbc:postgresql://127.0.0.1:35432/agent_cr05_20261104；dev_ap_test账户。凭据仅由私有资源文件dev-ap条目注入环境，未写证据。未删除/重置/清空共享schema，未访问BASE库。

- RED：`mvn -q -pl app-management -am -Dtest=OnboardingSessionPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test`，exit 1，2项在真实PG完成既有迁移后因OnboardingSessionRepository尚不存在失败，无跳过。
- 第二轮GREEN：`mvn -q -pl app-management -am -Dtest=OnboardingSessionPostgresTest,OnboardingAssemblyPostgresTest -Dsurefire.failIfNoSpecifiedTests=false test`，exit 0，**5/5通过，0失败/错误/跳过**。真实PG应用V118/V119；4项核验创建/重放/重启可读、主体隔离、CAS和false/0/null、取消原因/终态、原run回执与晚worker栅栏；1项由当前OnboardingSessions实际生成FLOW，经过真实prepare→validateCapabilityDraft→AppService→PG来源关系，验证同包两个应用及人工修改不覆盖。外部技能元数据与图校验是明确协议替身，不冒充远端业务读取。
- 纯规则OnboardingPlan由协作席独立两轮记录，12/12通过。app定向test-compile exit0。最后取消原因长度修正只编译、不再补第三轮功能检查。

## 未完成与未覆盖

1. 未RESOLVED/尚无发布技能的模板：只返回准确missing/resourcePlan；尚未把规格诊断→新技能草稿→受控验证/发布→session不可变来源接通。这是CR-05剩余开发，不作为“已闭环”统计。
2. 真实engine/skill/monitor跨服务验证/评测/发布与浏览器连通未在这5项中验证；没有真实模型或业务资源通过结论。
3. 现有CapabilitySkillClient与新RuntimeClient沿用ServiceTokens，IAM模式会拒绝旧票据，正式授权客户端通路仍需单独收口；未透传用户token或临时绕过IAM。
4. 首版一个QUERY会话只选择一个标准同步只读HTTP操作；复杂Schema、返回数组下标、新增文件获取/部分文件集合、MCP/异步/写操作不伪装为已支持。未自动代授新技能连接grant。
