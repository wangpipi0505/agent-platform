# DEV验收运行环境装配

2026-11-04。用户只授权必要配置/测试夹具及验收准备，没有启动全量、三路线或独立审核。

- 从升级worktree当前源码跳过测试重新package。只修改两个Vite配置，允许AGENT_GATEWAY_URL注入DEV代理地址，原默认值保持；产品Java/业务断言没有修改。
- AP六核心服务38080～38085均实际健康200；Authority38086以独立稳定私钥提供JWKS200；OR38182未登录401、登录后200。三个前端35173/35174/35184均200，AP和OR页面代理实际登录/受权读取通过，未连接旧18080/18182。
- DEV PG35432/35472实际data_directory已核，新场景库装配前已备份。AP场景schema迁移app121/engine228/skill320/model306/monitor506、OR42，均无失败项。连接池外置限制为每服务2、最小空闲0，适配本机DEV连接预算，没有修改数据库参数或旧版实例。
- 五用途DEV MinIO分别39000/39002/39004/39006/39008，逐个健康及本用途bucket鉴权成功。既有dev-or-test进程经精确PID/程序/数据根/凭据核实复用，不由本批停机。Redis36379为独立目录和私有口令，真实AUTH/PING通过。
- 真实模型代理38400按用户指定key文件引用配置，上游目录、最小chat及工具协议检查通过。通过正常管理API登记provider1/entry1；只赋PUBLIC信任标签，不为外部模型伪授LOCAL或放行未分类数据。8192/1024是平台保守预算，不是官方模型上限；tokenizerUrl不配置，保持估算标记。
- 首次工具探针使用命名强制tool_choice而被thinking模式拒绝；按产品客户端已有auto协议修正探针后，仍严格检查返回函数名匹配，不执行业务工具。依据：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。这不是降低业务断言。
- SDK已从当前源码重建，35174/assistant.js实际可取，SHA256为b4a597b51508a0beb2d1c684dbd437bf09e7a6dcb6beb20083d47544e6f3e0ee。最后13项运行/页面/SDK探针均达到预期状态，不代表助手/业务场景通过。

外置装配程序和私有配置引用：`D:/CodexPrep/algorithm-reorg/dual-20261103/prepare_dev_runtime_20261104.py`、`private/dev-runtime-20261104.json`；运行目录`dev/runtime-20261104/`，进程清单及不含秘密的实际证据在其`processes.json`、`evidence/`。私有目录ACL限制当前用户与SYSTEM。证明key仅注入app/skill，上游模型key仅注入代理；没有把旧版环境或凭据整包带进DEV。

装配阶段保留原始失败：MinIO既有DEV占用经核实复用；Redis Cygwin路径改用本批工作目录相对配置；连接池预算、OR前端工作目录及Authority ASCII NIO临时目录均在外置配置修正。未以假服务、跳过Bean或改产品源码掩盖失败。
