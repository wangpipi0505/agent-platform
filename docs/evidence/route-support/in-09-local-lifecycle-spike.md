# IN-09：路线二 LOCAL 服务生命周期 spike（2026-10-27 实测）

> 定位：为 AP-S05（通用服务生命周期正式项）做可行性预研。用真实可运行的最小原型验证
> 平台托管本地算法进程的五条核心能力，产出 Go/No-Go。不含四道门接入、不含 UI、
> 不做通用 manifest 设计定稿。
>
> spike 代码：`skill-registry` 模块 `cn.agentplatform.skill.infra.localspike` 包
> （包名带 localspike 表明预研性质，正式实现时迁移），未触碰任何 M 状态保护文件，未 git commit。

## 被托管对象与指纹

| 项 | 值 |
| --- | --- |
| 算法 jar | `G:/Code/Code-V3/visibility-trajectory/target/alg-toolkit-1.0.0.jar`（55,239,085 字节） |
| jar SHA256 | `15EE019595CBE4308D3DCC81902FF532862DC150239337D93302107CD0ECAC70` |
| 算法内部栈 | Spring Boot **2.7.18** + Tomcat 9.0.83 + **Java 1.8.0_472**（子进程日志实锤，见 in-09-localspike-18091-child.log） |
| JDK8 路径 | `C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/java.exe`（平台 JDK21，必须显式指定） |
| 启动命令 | `<jdk8>/java.exe -jar <jar> --server.port=<port> --server.address=127.0.0.1` |
| 工作目录 | `G:/Code/Code-V3/visibility-trajectory`（载荷 csvPath 是相对路径，必须能解析到仓库根） |
| 健康端点 | `GET /api/orbit/health` → 200 + "OK" |
| 业务端点 | `POST /api/orbit/visibility/search`（响应含 referenceUtc / allResults） |
| spike 端口 | 18091（从 18091-18095 择空）；18090 为 owner 实例全程未动（PID 67144，验证后仍在监听） |

## 构建验证

```
mvn -pl skill-registry -am package -DskipTests            # 失败（见下）
mvn -pl skill-registry -am package -DskipTests -Dspring-boot.repackage.skip=true   # 通过
```

- 第二条 **BUILD SUCCESS**（compile + test-compile 全过，JDK21 = `jdk-21.0.12.8-hotspot`，mvn 3.9.16）。
- 不带 repackage.skip 实测失败：`Unable to rename skill-registry-0.1.0-SNAPSHOT.jar to .jar.original`
  ——skill-registry 服务正在 18083 运行（PID 15004），Windows 锁住 target jar。失败发生在
  rename 一步，旧 jar 完好无损，服务无感。**结论：只要 18083 有运行实例，构建必须加
  `-Dspring-boot.repackage.skip=true`；spike 类均为新增类，不影响运行中服务。**

## 五条验证结果（全部真实执行，命令与原始输出见文末证据文件）

执行命令：
```
JAVA_HOME=<jdk21> AGENT_SPIKE_JDK8="C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/java.exe" \
  mvn -pl skill-registry surefire:test -Dtest=LocalSpikeLifecycleLiveTest
```
结果：**Tests run: 4, Failures: 0, Errors: 0, Skipped: 0**（10.64s）。测试类 `@EnabledIfEnvironmentVariable(AGENT_SPIKE_JDK8)` 门控，未设环境变量时整类跳过不误跑。

### 1. 启动（拉起+就绪轮询+冷启动耗时）— PASS
- ProcessBuilder 显式 JDK8 路径拉起，PID 43932；轮询 `GET /api/orbit/health`（500ms 间隔，120s 超时）。
- **冷启动 6300ms**（含进程 spawn + JVM 启动 + 首次健康通过；子进程日志自述 `Started OrbitApiApplication in 3.737 seconds (JVM running for 5.88)`，量级吻合）。Tomcat 实际绑定 18091（日志实锤）。

### 2. 健康（就绪后定期检查，失败=不健康）— PASS
- 就绪后单次健康检查延迟 **2ms**；连接拒绝/非 200/超时一律判不健康并翻转状态位（UNHEALTHY）。
- watchdog 守护线程 500ms 巡检，连续失败达阈值（spike 配 1 次）即触发自动重启。

### 3. 崩溃重启（外部 taskkill 后自动拉起恢复）— PASS
- 测试用 `taskkill /F /PID 43932` 外部杀死（01:59:08.414Z）。
- 托管器 **~220ms 后判定不健康**（01:59:08.633Z），自动杀残留树→等端口释放→重新拉起→就绪。
- **检测+恢复总耗时 2144ms**，新进程 PID 55144 ≠ 旧 PID，恢复后健康检查通过。

### 4. 孤儿清理（shutdown hook 杀全部子进程，端口释放）— PASS
- `shutdown()`（与 JVM shutdown hook 同一入口，测试另注册真实 hook 兜底）耗时 **272ms**。
- 三重独立核验：`Process.isAlive()=false`；`tasklist /FI "PID eq 55144"` 无 java.exe；`netstat` 复核 18091-18095 无监听（运行结束后人工复核亦无残留）。
- Windows 语义：`taskkill /T /F /PID` 杀整棵树 + `destroy()/destroyForcibly()` 兜底。

### 5. 串行调用（并发=1，两真实调用排队且结果正确）— PASS
- 两个真实 `visibility/search` 经 `CompletableFuture.supplyAsync` 并发提交到单线程队列。
- 实际执行 #1 耗时 **536ms**、#2 耗时 **257ms**，总耗时 816ms ≈ 536+257 → 无并行。
- 断言通过：执行区间不重叠（#1.end ≤ #2.start）、FIFO（先提交先执行）、两响应均含非空 `allResults` 数组与 `referenceUtc`。

### 失败路径测试（非门控，始终可跑）— 4/4 PASS（4.08s）
| 用例 | 断言 | 耗时 |
| --- | --- | --- |
| 端口占用启动预检 | `PortInUseException`，未拉起进程 | 0.005s |
| 进程就绪前退出（java -version） | `ProcessExitedException` 带退出码，FAILED | 0.712s |
| 启动超时（假死进程 FakeDeafMain） | `StartupTimeoutException`，**超时路径回收进程防孤儿** | 3.356s |
| 空闲端口选择 18091-18095 | 落在区间且可绑定 | 0.002s |

## AP-S05 设计输入清单（spike 产出，正式项直接吸收）

1. **Windows 进程树语义**：`taskkill /T /F /PID` 是可靠杀树手段，`Process.destroy()` 只能作兜底；杀后需轮询端口释放（实测释放<1s）再重启，否则重启撞端口。
2. **JDK8 路径是一等配置**：算法 jar 是 Java 8 字节码（内部还是 Spring Boot 2.7/Tomcat 9），启动命令必须显式 java.exe 路径，不能默认继承平台 JVM；manifest 需含 runtime 字段。
3. **工作目录必须显式配置**：载荷里 csvPath 等相对路径按子进程工作目录解析（owner 也是从仓库根启动的）；默认继承平台进程工作目录是错误做法。
4. **端口冲突检测**：启动前 bind 预检（`setReuseAddress(false)`），失败快速抛 `PortInUseException` 且不拉进程——否则算法静默退出只能靠超时兜底，定位成本高。另：**平台自身 18083 运行时会锁住自身 target jar**，"从 target 目录直接跑服务"与"从 target 目录构建"互斥，正式方案应让服务从部署副本目录运行。
5. **就绪判定**：健康端点 200 + 返回体精确匹配 "OK"；轮询 500ms 足够（本次冷启动 6.3s）；超时路径必须回收进程防孤儿。启动失败要区分"进程早退"（带退出码+日志尾部）与"就绪超时"，二者定位手段完全不同。
6. **串行队列实现方式**：单线程 Executor 天然 FIFO 即满足实例级并发=1，无需锁；调用方并发提交、队列内串行执行。AP-S05 需补充：队列深度上限/背压、排队超时、以及多实例分片时的实例亲和。
7. **shutdown hook 可靠性**：hook 与显式 shutdown() 共用同一回收入口（幂等）；JVM 崩溃退出时 hook 仍会执行（正常 SIGTERM/Ctrl-C 场景实测可用）；回收耗时 272ms 量级，可接受。遗留：kill -9 平台自身时 hook 不会执行，需辅以启动时孤儿扫描（见风险）。
8. **自动重启防护**：连续失败阈值（防抖动误杀）+ 重启次数上限（防崩溃循环），超限判 FAILED 停止重试并告警。

## 遗留风险

- **平台自身被 kill -9 时 shutdown hook 不执行**，子进程成孤儿。spike 未验证孤儿扫描/启动时端口清理，AP-S05 需补"启动时检测并接管/清理同配置孤儿进程"。
- 本次冷启动 6.3s 是热缓存数据（owner 实例刚跑过同 jar）；冷盘/低配机冷启动可能数十秒，就绪超时默认值建议 ≥120s 并可配。
- 串行队列在单 JVM 内成立；平台多副本部署时"实例级并发=1"会失效，正式方案需明确单写者/分布式约束。
- 子进程 stdout/stderr 目前整体落单个日志文件（append），无轮转、无结构化采集，长期运行需接日志管道。
- watchdog 与 restart 共用一把对象锁，重启期间健康查询会短暂阻塞（spike 量级无感，正式实现应拆锁）。
- spike 未覆盖：健康检查抖动（瞬时不健康即重启，阈值=1 偏激进，正式建议 ≥2 次）、启动参数校验、算法 jar 更新后的热替换。

## 结论：**Go**

五条验证全部真实通过（4/4 门控 + 4/4 失败路径），核心语义（显式 JDK8 拉起、健康判定、
崩溃自动重启、Windows 进程树清理、实例级串行）在真实算法进程上成立，无阻塞性发现。
AP-S05 可按上文设计输入清单进入正式设计与实现。

## 证据文件

| 文件 | 内容 |
| --- | --- |
| `docs/evidence/route-support/in-09-live-test-raw.log` | 门控测试完整 mvn 输出（含 [IN-09-EVIDENCE] 耗时行） |
| `docs/evidence/route-support/in-09-localspike-18091-child.log` | 子进程合并输出（含算法启动日志、两代实例、FakeDeafMain 标记） |
| `skill-registry/target/surefire-reports/TEST-cn.agentplatform.skill.infra.localspike.*.xml` | surefire 逐用例耗时 |

代码：`skill-registry/src/main/java/cn/agentplatform/skill/infra/localspike/`（Config / Exception / Ports / ProcessManager / Invoker）、
`skill-registry/src/test/java/cn/agentplatform/skill/infra/localspike/`（FakeDeafMain / ManagerFailurePathTest / LifecycleLiveTest）。
