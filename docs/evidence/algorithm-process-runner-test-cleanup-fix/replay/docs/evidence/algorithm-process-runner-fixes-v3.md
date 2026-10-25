# 算法进程 runner 第三轮窄修复：有界异步 PowerShell

2026-10-20，依据 root 对 v3 同线程阻塞实证的明确授权，仅将 runner 的 PowerShell 执行改成 Node 内置有界异步 execFile，并适配必要 await 调用链及有限检查。生产/src/POM/target/原 fixture、实际 SQL/事实、原4秒技能期限、健康与清理预算、精确归属和秘密白名单均保持。没有执行第四轮真实入口。

## 原失败与阻塞证据保持

第三轮报告 `algorithm-process-live-v3.md`、summary、execution、专用原始目录 `algorithm-process-runner/live-5b3418e87817/`、独立PG/进程只读补核和阻塞诊断均已归档。实际complete场景仅一次POST，Job fixture UID已生成而PG尚未采纳；原fact/call UNKNOWN、run WAITING_EXTERNAL、binding SENDING、工具逻辑提交0。没有业务重发、对账或降低complete预期。两自有JVM62900/46180均已停止、端口55459/55458为空，原finally成功；root独立检查同样无残留（`.local/algorithm-live-v3-cleanup-root.json`）。原未知事实和各轮错误不改写。

原e306 helper 的实际定点诊断：一次归属检查耗1971.955ms，25ms timer和同进程有限HTTP均只能在同步检查返回后响应。合成PS350ms等待同样造成545.466ms阻塞。诊断只查询已退出的本轮PID/端口并访问独立本地 `/finite-probe`，没有触真实业务或Job。同步检查发生在每次status HTTP前，而K8s fixture在同一Node线程，因此会抢占原SDK4秒预算。这是可靠runner验收必须修复的结构问题；原请求未新增精确时序埋点，报告仍保留未排除其他网络因素的限制。

## 新增五项行为红绿

先新增 `tests/algorithm-process-runner-async.test.mjs`，实际原helper上5项全失败、退出1，完整red.log和红阶段源码保存在本轮目录。检查的是实际提取的runner helper：①合成PS等待期间，同线程有限HTTP/timer应先响应，旧helper实际已经同步结算而失败；②小绝对timeout应拒绝重复输出的长命令，旧helper忽略该小界限；③④stdout/stderr分别超界应保留标准原生溢出错误，旧helper全部接受；⑤非零exit应保留原code/stdout/stderr，旧包装丢失原exit code。测试不触真实Redis、PG、MinIO或JVM。

实现使用 `promisify(execFile)`，固定默认10秒timeout和stdout/stderr各65536字节maxBuffer，保留显式UTF8。测试只通过私有小参数缩短界限，参数验证不允许超过运行默认上界；真实调用未传自定义bounds。Node内置timeout从调用启动计时，不因输出chunk续期；超时/溢出只结束execFile自己创建的PowerShell child，拒绝继续执行该归属调用，不据此停止尚未确认的JVM。原生Error不替换，保留code/signal/killed和stdout/stderr，可被原逐项cleanup收集。

inspect/freeBeforeStart及最终records、owned、stop、start的必要调用全部await。只有完整确认PID/创建时间/精确JAR/所有listener owner后才继续HTTP；停止前仍有JS与PowerShell内部双重归属核验。未新增通用进程管理框架或依赖。此前Unicode行为测试仅适配实际async helper和await，其余21项检查保留。

实际 `node --test tests/algorithm-process-runner.test.mjs tests/algorithm-process-runner-fixes.test.mjs tests/algorithm-process-runner-redis.test.mjs tests/algorithm-process-runner-async.test.mjs` 为26/26通过、0 fail/skip/cancel、退出0，见green.log。新增HTTP/timer确实在PS仍等待时完成；绝对timeout、stdout/stderr溢出和非零退出都检查原生错误内容。5份源码语法检查与replay命令记录syntax.log/replay.log。

## 新增冻结与重放

本轮baseline完整保存上轮六文件，SHA与fix-v2 sources逐项一致；baseline-absence.json登记新增async测试与本报告。frozen保存最终八文件，其中原报告/Redis/其他检查等不变文件也完整保留。`algorithm-process-runner-fixes-v3.patch` 为baseline→frozen真实完整差异，只改runner及Unicode测试的async适配，并新增测试/报告；原v1/v2/v3与之前两fix原字节保持。

本轮freeze.mjs实际核对上轮frozen/replay/evidence/patch/历史manifest和已记录v1/v2原始证据，记录本轮v3报告/summary/raw/诊断/独立PG/root收尾检查SHA。在新replay目录复制baseline，实际git apply --check与apply退出0，八文件SHA与frozen一致；只读复制原fixture后实际语法检查五文件并执行26项有限检查。完整命令/输出见本轮replay.log，SHA见 `algorithm-process-runner-fixes-v3-sha256.json`。

本窄修复只解决已实证的测试控制线程阻塞，不修改算法业务预算，不代表五实际算法场景通过。当前停止在冻结交付，等待同一独立审查者复核，第四轮必须由root另行移交原锁授权。
