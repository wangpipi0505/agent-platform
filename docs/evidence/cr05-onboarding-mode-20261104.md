# CR05 同模式出站与开发专用证明编译记录

日期：2026-11-04。本席产品文件白名单：

- `app-management/src/main/java/cn/agentplatform/app/infra/CapabilitySkillClient.java`
- `app-management/src/main/java/cn/agentplatform/app/application/OnboardingPreflightService.java`
- `admin-common/src/main/java/cn/agentplatform/common/auth/OnboardingServiceProof.java`

两旧客户端仅在 `agent.security.mode=iam` 使用共用 `AppAuthorityTransport`，通过可选 Autowired setter 注入，缺失时关闭该调用。原构造函数及手工实例的 dev-hmac 默认保持；IAM 不签发旧 HMAC 服务 JWT。技能元数据共用同一回复校验函数，保留精确 code/version/versionNo、正整数 id/skillVersionId 一致、definition、已知 skillStatus、PUBLISHED、registered 布尔值与 sha256=digest=definition canonical 摘要断言。IAM 调用传入原截止时间，并在调用前后复核预算。预检固定请求原 `/admin/skills/import-openapi/preview`，仍沿原 normalize，不复制 parser 或增加未使用的新接口。

共用开发证明的 header 为 `X-Agent-Onboarding-Proof`。工具只接受专用 `env:AGENT_ONBOARDING_DEV_APP_KEY`，每次解析且须至少 32 UTF-8 字节，无属性/旧 JWT key 回退。签名为 HmacSHA256，验签使用 MessageDigest.isEqual；Base64URL payload.signature 总长不超过 4096。签名内容绑定 app→skill、传入的已验 Actor 租户/主体、原始 method/path/query、canonical body（包括 null）的 SHA-256、issuedAt/expiresAt（最多60秒）及16字节随机 nonce；校验严格字段、类型、编码及最多5秒时钟偏差。返回不可变审计 Map，不返回密钥；临时密钥字节清零，不缓存密钥。

工具不替代服务 JWT、EDITOR/SERVICE 或精确路由检查，不创建当前授权上下文。调用方仅在显式 dev-hmac 使用该证明，IAM 不调用、不回退；nonce 用于审计，不实现一次性消费。与接收方实现者确认：新增 R2 create/read 的 DEV 入口强制证明，旧 metadata/preview 的 DEV 接收规则不变。

验证：JDK21 `javac -encoding UTF-8 --release 21 -cp <已有隔离依赖类路径> -d D:/CodexPrep/algorithm-reorg/cr05-onboarding-mode`，源文件为上述三个产品文件及另一席实际新增的 `AppAuthorityTransport.java`，退出码 0；原 Map 转换出现 unchecked 编译提示。类路径读取 `D:/CodexPrep/algorithm-reorg/cr05-plan-rules/classpath.txt`，日志位于 `D:/CodexPrep/algorithm-reorg/cr05-onboarding-mode/compile.log`，未写共享 Maven target。Proof 最终源码也已单独隔离编译退出0、无诊断。

按本轮指令只编译，未签发/验证实际 proof、未执行 IAM/DEV HTTP、PG、旧功能用例或全量测试。未更改断言，未暂存/提交。其它席负责 transport、接收端、配置和 session 调用编排，本席未修改。
