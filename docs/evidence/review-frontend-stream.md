# G5 前端 SSE 恢复增量独立评审

最新状态（2026-10-20 定向闭环）：下述初评两项 P2 均已关闭。独立审阅修复源码并亲跑 13 条定向单测、4 个 Chrome 小场景，全部通过；闭环详情和新证据见文末。初评问题及首次失败结果保留为历史证据。

后续 RUNNING/output:{} 误终答缺陷也已定向复核关闭：本人新增 8 个受控动画 Chrome 场景全部通过，具体源码摘要与边界见末节。真实 55/65 秒浏览器联调尚未复验。

评审范围仅本新工程 `web/shared/run-observer.ts`、Chat、原生 SDK、共享 SSE 解析和本轮测试/证据。使用 Vue、code-review、requesting-code-review、verification-before-completion 与 Ponytail 技能。工程无 HEAD，以 `.local/frontend-stream-review-package.txt` 的全文及 SHA256 作为冻结输入。2026-10-20 独立读取并逐项比对，包内 9 文件均与当前文件 SHA256 一致。未修改业务源码、未操作 Git、未启停服务、未重跑已绿 70 条测试和三构建。唯一交付写入本文件。

## 初评结论（历史，已由文末闭环更新）

局部规格评审：请求修改。发现两个可复现 P2，涉及确定 HTTP 错误分类和停止观察后的共享登录状态污染；未发现确定 P1。共享恢复循环本身符合先应用权威快照、再采用水位重开同一 run 的核心顺序，但消费者的 HTTP 边界尚未满足全部错误与隔离要求。

局部质量评审：共享循环较小，没有新增依赖或重复创建/取消机制。应在修复下述问题后定向验证；本报告不将原有成功场景外推为全部故障场景通过，也不签收整个 G5/T15。

## 初评必须修复的问题（两项均已定向关闭）

### [P2] SDK 状态错误解析丢失 HTTP status，确定 4xx 被重试

位置：`web/assistant.js/src/main.ts:150`（request 中 `await r.json()`，HTTP status 到下一分支才附到异常）。

新恢复路径将 request 用于 `readStatus`。状态接口若返回 `403 text/plain` 或网关的 HTML 错误页，JSON 解析首先抛出无 `status` 的 SyntaxError，`observeRun` 无法识别确定权限/资源错误，重复读取状态五次，最后向用户显示 JSON 语法错误。真实 JSON 的 401/403 可以停止，不能覆盖该路径。

新增真实 Chrome 组件 probe 结果：`creates=1, streams=1, reads=5`；最终错误为 `Unexpected token 'o', "forbidden" is not valid JSON；可查看权威运行记录。`。probe 仅将浏览器 750/1500/3000/6000/8000ms 退避定时器改为零以缩短等待，未改生产代码或观察器的次数/分类逻辑。

建议最小修复：为错误响应的 JSON 解析提供可读兜底，并确保不论响应体格式都保留 `r.status`；补 SDK status 401/403/404 非 JSON 的定向断言，期望每种只读一次且不重开流、不重建运行。该项不要求改动其他业务模块。

### [P2] WebApp 停止观察未终止 status HTTP，晚到 401 清空共享 token

位置：`web/shared/Chat.vue:152-155`，新 `readStatus` 没有把观察的 AbortSignal 传到 API transport。相关现有边界：`web/shared/api.ts:33-45,58-65,88-95`。

`observeRun.pending` 的 Promise.race 能停止等待并拦住 `onStatus`，但无法撤销已经执行的 API 副作用。Chat 卸载或用户停止之后，未中止的状态请求仍继续处理响应；如果其晚到结果为 401，createApi 仍调用共享 `onUnauthorized`，把 Pinia auth.token 清空。新组件或新的登录状态也可能受到旧观察请求影响；挂起的状态 HTTP 同样不会随观察释放。原测试只放回晚到的 RUNNING 成功快照，故没有检测该路径。

新增真实 Chrome 组件 probe 结果：状态请求已发出，卸载 Chat 后 `mountedChats=0`，放回 401 后 auth 从 `component-fixture-only` 变为 `""`，状态请求数为 1。此复现只证明并展示晚到未授权响应的全局副作用；未把推导的新登录受影响情形冒充已单独测试。

建议最小修复：让本次状态 GET 接收并传递 AbortSignal 到实际 axios 请求，并在响应处理/鉴权副作用前检查该请求是否已停止；同时对 SDK 状态 fetch 接上同一 signal，保证停止观察释放挂起 HTTP。补卸载/显式停止后晚到 401 的定向回归，并确认活动观察的正常 401 仍沿原 Auth 路径清理 token。

两个 P2 已在完成报告前即时发给主协调者。本次评审没有自行修复源码。

## 已核对的规格与证据

- 两消费者都把新建得到的 runId 闭包绑定到观察器；观察器没有 POST 创建、确认或取消。显式 stop 仍在 Chat 原 cancel 成功后 abort。
- 正常 EOF、stream_reset、流网络异常之后设置 awaitingSnapshot；状态失败只重试状态，不提前重开流。状态成功后先调用 onStatus，再采用该快照 eventsWatermark；CURSOR_EXPIRED/AHEAD 的控制帧水位不直接采用。
- CHUNK、stream_status/reset 不推进事实游标；事实只交付安全整数且大于当前水位的 seq。EVENT_LIMIT 设置 snapshotOnly，之后以 750ms 间隔只读状态，没有同一超大事实的重连死循环。
- JSON 状态/流的确定 4xx 按分类停止，状态连续失败默认至多五次；真实退避等待可 abort。旧流回调和晚到成功快照由 accepting/signal/check 阻断。上述第二项问题明确指出 transport 副作用超出了这道 gate。
- busy/running 持续到权威终态；WAITING_APPROVAL 可继续确认。权威输出覆盖临时文本，并取消排队动画写；原 finalAnswer 提示与 safeMarkdown 的 DOMPurify 消毒路径仍使用。SDK 每次 stream/status 重新请求短票据，未引入 API Key 或持久 token。
- 独立读取 `.local/frontend-stream-all-green.log`：24 文件、70 条通过；读取 build 日志：vue-tsc 后 admin/WebApp/SDK 构建完成，保留既有 >500kB 提示。读取新增 13 条测试源码及浏览器五场景断言、JSON 和日志，与作者报告一致。这些均是核对已有执行证据，未称为本评审重跑。
- 亲自抽查 `frontend-stream-webapp-final.png`：最终答案、运行身份及格式校验提示可读，没有临时文本残留。本评审仅查看这一张，未冒称四张全部复核。

## 本次新增 probe 的复现方法

在本根 `web` 用 `node --input-type=module` 执行以下脚本；使用现有 15175 fixture 和真实 Chrome，只有 Playwright route 返回测试网络结果，不访问真实业务数据。浏览器 `finally` 关闭，脚本不写截图、源码、日志或 JSON 文件。本次命令 exit 0，stdout 结果如下。

```json
{"probe":"SDK status 403 text/plain","creates":1,"streams":1,"reads":5,"error":"Unexpected token 'o', \"forbidden\" is not valid JSON；可查看权威运行记录。"}
{"probe":"WebApp unmount late status 401","statusCalls":1,"authBefore":"component-fixture-only","authAfter":"","mountedChats":0}
```

```javascript
import { chromium } from '@playwright/test';
const browser = await chromium.launch({
  executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true
});
const ok = data => ({status:200,contentType:'application/json',
  body:JSON.stringify({code:'OK',data})});
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);
  return {resolve,promise};};
const eof={status:200,contentType:'text/event-stream',
  body:'event: stream_status\ndata: {"chunkReplay":false}\n\n'};
try {
  const page=await browser.newPage();let reads=0,streams=0,creates=0;
  await page.addInitScript(()=>{const native=window.setTimeout;
    window.setTimeout=(fn,delay,...args)=>native(fn,
      [750,1500,3000,6000,8000].includes(delay)?0:delay,...args);});
  await page.route('**/v1/**',route=>{
    const p=new URL(route.request().url()).pathname;
    if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
    if(p.endsWith('/runs')){creates++;return route.fulfill(ok({runId:'r_nonjson'}));}
    if(p.endsWith('/events')){streams++;return route.fulfill(eof);}
    reads++;return route.fulfill({status:403,contentType:'text/plain',body:'forbidden'});
  });
  await page.goto('http://127.0.0.1:15175/stream-recovery.html?sdk=1');
  await page.getByRole('button',{name:'打开智能助手'}).click();
  await page.getByRole('textbox',{name:'输入问题'}).fill('status non-json auth boundary');
  await page.getByRole('button',{name:'发送',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('agent-platform-assistant')
    .shadowRoot.querySelector('.send').disabled);
  console.log(JSON.stringify({probe:'SDK status 403 text/plain',creates,streams,reads,
    error:await page.getByRole('alert').innerText()}));
  await page.close();
  const second=await browser.newPage(),started=deferred(),release=deferred();
  let statusCalls=0;
  await second.route('**/v1/**',async route=>{
    const p=new URL(route.request().url()).pathname;
    if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
    if(p.endsWith('/runs'))return route.fulfill(ok({runId:'r_late401'}));
    if(p.endsWith('/events'))return route.fulfill(eof);
    statusCalls++;started.resolve();await release.promise;
    return route.fulfill({status:401,contentType:'application/json',
      body:JSON.stringify({code:'UNAUTHORIZED',message:'revoked'})});
  });
  await second.goto('http://127.0.0.1:15175/stream-recovery.html');
  await second.getByPlaceholder('输入问题，Shift+Enter 换行').fill('late status auth boundary');
  await second.getByRole('button',{name:'提交运行',exact:true}).click();
  await started.promise;
  const before=await second.evaluate(()=>window.fixtureAuth());
  await second.evaluate(()=>window.disposeFixture());release.resolve();
  await second.waitForFunction(()=>window.fixtureAuth()==='');
  console.log(JSON.stringify({probe:'WebApp unmount late status 401',statusCalls,
    authBefore:before,authAfter:await second.evaluate(()=>window.fixtureAuth()),
    mountedChats:await second.locator('.chat').count()}));
  await second.close();
} finally { await browser.close(); }
```

## 验收边界

所有新增 probe 均为真实浏览器组件加网络 fixture。没有据此声称真实网关、实际 55 秒 EOF、两个独立 engine 进程、Redis 跨进程恢复或生产凭据已经验证。后端另行修复的两 P2、统计/历史、旧关闭问题均不在本前端增量结论内。chunkReplay=false 且丢失在线 token 不重播，最终内容仍以 PG 权威快照为准。首次交付时两个前端 P2 尚未修复验证；后续关闭状态见下文。

## 两项 P2 独立定向闭环

本次只复核原两项错误边界及必要调用链，没有扩大到旧组件。确切 Chat 路径为 `web/shared/Chat.vue`，不存在所述 `webapp/src/components/Chat.vue`。独立计算当前四源码 SHA256，均与修复者交接值一致：

| 文件 | SHA256 |
| --- | --- |
| web/shared/api.ts | 85BAF479648EC1E9AE4CD541E2612ECA1418223C04262BFBDEF80ACB140D7C8F |
| web/shared/Chat.vue | 666313CFB21A13F5013204B14800C6F10E19EB31984374BE1F5090F98A254EFC |
| web/assistant.js/src/main.ts | 8776C0048005B83FC2D0CFB9CFA3806172E8DFD3039B4FDC1028018102A2F6A2 |
| web/debug-panel/events.ts | 4ED0EA7F6577D0A1D3129B338639A085BA1D9F78BB576D7FB9CB58C5B46FE8F7 |

P2-1 关闭：SDK request 对错误体 JSON 解析提供兜底；`!r.ok` 仍优先于信封 code，异常稳定保留 HTTP status。空体、纯文本、HTML 不再变成无 status 的语法异常。状态 fetch 接收观察的 signal，并在取票据、fetch 和解析后检查 abort；短票据仍按需获取，没有新增浏览器存储或长期凭据。

P2-2 关闭：Chat 状态 GET 传入观察 signal，createApi 将它传到 transport，axiosTransport 再传给实际 axios.request。请求发起前、响应后及异步解析后检查 abort，防止已停止请求的鉴权副作用。API 与 SSE 均捕获请求时 token，只有当前 token 仍等于该值时才处理活动 401 的清理；因此旧请求不能撤销已更换的登录。原活动 401 仍清理当前 auth。

独立亲跑命令 `npm.cmd test -- tests/stream-auth.test.ts`，工作目录本根 `web`，05:34:33 输出 1 文件、13 passed、exit 0；包含真实 axios 参数断言、异步 body 期间 abort、晚到成功/401、旧票据与活动 401。未重跑 83 条全套或构建。

独立亲跑下述 4 个 Chrome 小场景（同样 `node --input-type=module`，工作目录本根 `web`），exit 0，未使用退避时间改写，未写其他证据文件。原始 stdout：

```json
{"probe":"independent SDK nonJSON403","creates":1,"streams":1,"reads":1,"error":"服务返回不可读响应 (403)；可查看权威运行记录。"}
{"probe":"independent unmount late401","reads":1,"cancelled":1,"authAfter":"component-fixture-only"}
{"probe":"independent new-login late401","reads":1,"cancelled":0,"authAfter":"independent-new-login"}
{"probe":"independent active late401","reads":1,"cancelled":0,"authAfter":""}
```

```javascript
import { chromium, expect } from '@playwright/test';
const browser=await chromium.launch({
  executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true
});
const ok=data=>({status:200,contentType:'application/json',
  body:JSON.stringify({code:'OK',data})});
const eof={status:200,contentType:'text/event-stream',
  body:'event: stream_status\ndata: {"chunkReplay":false}\n\n'};
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);
  return{resolve,promise};};
try {
 const sdk=await browser.newPage();let reads=0,streams=0,creates=0;
 await sdk.route('**/v1/**',route=>{
  const p=new URL(route.request().url()).pathname;
  if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
  if(p.endsWith('/runs')){creates++;return route.fulfill(ok({runId:'r_review403'}));}
  if(p.endsWith('/events')){streams++;return route.fulfill(eof);}
  reads++;return route.fulfill({status:403,contentType:'text/plain',body:'forbidden'});
 });
 await sdk.goto('http://127.0.0.1:15175/stream-recovery.html?sdk=1');
 await sdk.getByRole('button',{name:'打开智能助手'}).click();
 await sdk.getByRole('textbox',{name:'输入问题'}).fill('independent close403');
 await sdk.getByRole('button',{name:'发送',exact:true}).click();
 await expect(sdk.getByRole('button',{name:'发送',exact:true})).toBeEnabled();
 const error=await sdk.getByRole('alert').innerText();
 expect(reads).toBe(1);expect(streams).toBe(1);expect(creates).toBe(1);
 expect(error).toContain('(403)');
 console.log(JSON.stringify({probe:'independent SDK nonJSON403',creates,streams,reads,error}));
 await sdk.close();
 for(const mode of ['unmount','new-login','active']) {
  const page=await browser.newPage(),started=deferred(),release=deferred(),replied=deferred();
  let reads=0,cancelled=0;
  page.on('requestfailed',request=>{
    if(request.url().endsWith('/r_review401'))cancelled++;
  });
  await page.route('**/v1/**',async route=>{
   const p=new URL(route.request().url()).pathname;
   if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
   if(p.endsWith('/runs'))return route.fulfill(ok({runId:'r_review401'}));
   if(p.endsWith('/events'))return route.fulfill(eof);
   reads++;started.resolve();await release.promise;
   await route.fulfill({status:401,contentType:'application/json',
     body:'{"code":"UNAUTHORIZED","message":"revoked"}'});replied.resolve();
  });
  await page.goto('http://127.0.0.1:15175/stream-recovery.html');
  await page.getByPlaceholder('输入问题，Shift+Enter 换行').fill('independent close late401');
  await page.getByRole('button',{name:'提交运行',exact:true}).click();await started.promise;
  if(mode==='unmount')await page.evaluate(()=>window.disposeFixture());
  if(mode==='new-login')await page.evaluate(async()=>{
    const u=performance.getEntriesByType('resource').map(e=>e.name)
      .find(u=>/\/shared\/api\.ts/.test(u));
    const{useAuth}=await import(u);useAuth().token='independent-new-login';
  });
  release.resolve();await replied.promise;
  if(mode!=='unmount')await page.getByRole('alert').filter({hasText:'revoked'}).waitFor();
  await page.waitForTimeout(150);
  const authAfter=await page.evaluate(()=>window.fixtureAuth());
  expect(reads).toBe(1);
  expect(authAfter).toBe(mode==='active'?'':mode==='new-login'
    ?'independent-new-login':'component-fixture-only');
  expect(cancelled).toBe(mode==='unmount'?1:0);
  console.log(JSON.stringify({probe:'independent '+mode+' late401',reads,cancelled,authAfter}));
  await page.close();
 }
} finally {await browser.close();}
```

另独立核对修复者原始证据（以下未由本评审重跑）：`frontend-stream-auth-unit-red.log` 为 10 failed/3 passed，green 为 13 passed；`frontend-stream-auth-all-green.log` 为 25 文件/83 passed；`frontend-stream-auth-build.log` 为三构建完成且保留 bundle 体积提示；`frontend-stream-auth-recovery-regression.log` 及 JSON 保留原恢复 5 场景。最终 `frontend-stream-auth-browser-green.log` 为 **12** Chrome 场景通过，JSON 也为 12/12（交接时提到的 11 已追加 HTTP overrides envelope 用例）；这些结果及断言均已阅读，未把修复者执行计为本人执行。

闭环局部规格及质量结论：原两项 P2 已满足关闭条件，未在本次修复范围发现新的确定阻断问题。全 G5/T15 与真实双 engine 纵切的边界维持上述说明。

修复者最终 `docs/evidence/frontend-stream-auth-fix.md` 已生成并独立阅读；其最终 12 场景口径、四源码摘要、83 条与三构建引用、原恢复 5 场景及 fixture/生产边界均与上述原始证据一致。

## RUNNING 空对象误终答：独立定向复核

本节只复核 live 阶段发现的非终态 `output:{}` 误覆盖/锁住后续 CHUNK，以及对应两消费者修改。报告来源 `docs/evidence/frontend-stream-snapshot-fix.md` 已独立阅读；与作者确认生产代码已冻结。没有修改源码、重跑大构建、启停服务或另派 agent。

独立计算当前 SHA256：Chat 为 `FACA2CA174BC577AB20FD82833F2A1E11FAB0E9CC753B4369E8797C36B377A42`，SDK 为 `1E0CEE4A93B8DB268716480F8B417839A7C40814E836553AFAA113B21A0B9448`，与交接值相符。冻结包 `.local/frontend-stream-snapshot-review-package.json` 的 protected 6 项、sources 3 项全部与当前文件相符。直接与修复前两文件全文比较，变更仅为导入既有 terminal、终态快照应用/错误优先及恢复提示；API、events、observer、原恢复/鉴权脚本与 HTML 未变。

源码规格判断：`onStatus` 只有在 `terminal(snapshot.status)` 成立时才调用 applyFinal，RUNNING/WAITING_APPROVAL 的空或非空 output 不改变答案或 receivedFinal；真正成功终态不按内容真假过滤，故 `{}`、空字符串/空 text、null/缺输出都替换临时内容。FAILED/CANCELLED 优先 error，再 output，再默认提示。原事实终态可暂时显示，但 busy 仍到权威终态。Chat applyFinal 取消 frame 并清空 buffer，SDK 取消 frame 并把 text 替换为最终值；原 finalAnswer warning、safeMarkdown 消毒及 auth/AbortSignal 传递保留。两恢复文案明确提示临时片段可能缺失。

本人新增并亲跑 **8** 个 Chrome controlled 小场景，两端各覆盖成功空对象、空 text、null、FAILED/error 优先。特别将 requestAnimationFrame 存入可控队列：首流 A 片段在 RUNNING {} 后仍可 flush 显示，第二流 B 片段在 WAITING_APPROVAL/非空 output 后继续显示，第三流片段尚未 flush 时权威终态到达；随后手动 flush 不得回写旧片段。八场均单次创建、3 流/3 状态，exit 0。没有使用生产网关或模型，没有把这组 fixture 当真实 55 秒 EOF 验证。

亲跑命令为本根 `web` 中 `node --input-type=module` 执行以下脚本，不输出文件。原始 stdout：

```json
{"probe":"webapp pendingRAF object","creates":1,"streams":3,"reads":3,"shown":"{}","lateFramePollution":false}
{"probe":"webapp pendingRAF empty-text","creates":1,"streams":3,"reads":3,"shown":"","lateFramePollution":false}
{"probe":"webapp pendingRAF null","creates":1,"streams":3,"reads":3,"shown":"\"运行尚无输出\"","lateFramePollution":false}
{"probe":"webapp pendingRAF failed","creates":1,"streams":3,"reads":3,"shown":"{ \"message\": \"权威错误\" }","lateFramePollution":false}
{"probe":"sdk pendingRAF object","creates":1,"streams":3,"reads":3,"shown":"{}","lateFramePollution":false}
{"probe":"sdk pendingRAF empty-text","creates":1,"streams":3,"reads":3,"shown":"","lateFramePollution":false}
{"probe":"sdk pendingRAF null","creates":1,"streams":3,"reads":3,"shown":"\"运行尚无输出\"","lateFramePollution":false}
{"probe":"sdk pendingRAF failed","creates":1,"streams":3,"reads":3,"shown":"{\n  \"message\": \"权威错误\"\n}","lateFramePollution":false}
```

```javascript
import { chromium, expect } from '@playwright/test';
const browser=await chromium.launch({executablePath:
  'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
const ok=data=>({status:200,contentType:'application/json',body:JSON.stringify({code:'OK',data})});
const sse=text=>({status:200,contentType:'text/event-stream',
  body:'event: chunk\ndata: '+JSON.stringify({text})+'\n\n'});
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{resolve,promise};};
try {
 for(const sdk of [false,true])for(const final of [
  {name:'object',status:'SUCCEEDED',output:{},expected:'{}'},
  {name:'empty-text',status:'SUCCEEDED',output:{text:''},expected:''},
  {name:'null',status:'SUCCEEDED',output:null,expected:'"运行尚无输出"'},
  {name:'failed',status:'FAILED',output:{text:'wrong-output'},
    error:{message:'权威错误'},expected:'权威错误'}]) {
  const page=await browser.newPage(),second=deferred(),third=deferred();
  let creates=0,streams=0,reads=0;
  await page.addInitScript(()=>{let next=1;const queue=new Map();
   window.requestAnimationFrame=callback=>{const id=next++;queue.set(id,callback);return id;};
   window.cancelAnimationFrame=id=>queue.delete(id);
   window.reviewFlushRAF=()=>{const jobs=[...queue.values()];queue.clear();
     for(const callback of jobs)callback(performance.now());};
  });
  await page.route('**/v1/**',async route=>{
   const p=new URL(route.request().url()).pathname;
   if(p.endsWith('/input-schema'))return route.fulfill(ok({type:'FLOW',inputs:[]}));
   if(p.endsWith('/runs')){creates++;return route.fulfill(ok({runId:'r_pending_raf'}));}
   if(p.endsWith('/approvals'))return route.fulfill(ok([]));
   if(p.endsWith('/events')){
     streams++;if(streams===1)return route.fulfill(sse('A片段'));
     if(streams===2){await second.promise;return route.fulfill(sse('B片段'));}
     await third.promise;return route.fulfill(sse('不应晚刷的片段'));
   }
   reads++;return route.fulfill(ok(reads===1
     ?{status:'RUNNING',output:{},eventsWatermark:3}:reads===2
     ?{status:'WAITING_APPROVAL',output:{text:'错误非终态输出'},eventsWatermark:4}
     :{...final,eventsWatermark:5}));
  });
  await page.goto('http://127.0.0.1:15175/stream-recovery.html'+(sdk?'?sdk=1':''));
  if(sdk)await page.getByRole('button',{name:'打开智能助手'}).click();
  const input=sdk?page.getByRole('textbox',{name:'输入问题'})
    :page.getByPlaceholder('输入问题，Shift+Enter 换行');
  const send=page.getByRole('button',{name:sdk?'发送':'提交运行',exact:true});
  const answer=sdk?page.locator('agent-platform-assistant .log .bubble').last()
    :page.locator('.chat .bubble.assistant > div').last();
  await input.fill('independent pending animation');await send.click();
  await expect.poll(()=>streams).toBe(2);await page.evaluate(()=>window.reviewFlushRAF());
  await expect(answer).toHaveText('A片段');
  second.resolve();await expect.poll(()=>streams).toBe(3);
  await page.evaluate(()=>window.reviewFlushRAF());await expect(answer).toHaveText('A片段B片段');
  if(sdk)await expect(send).toBeDisabled();else await expect(send).toHaveCount(0);
  third.resolve();await expect(send).toBeEnabled();
  await page.evaluate(()=>window.reviewFlushRAF());
  const shown=(await answer.innerText()).trim();
  if(final.name==='failed')expect(shown).toContain(final.expected);
  else expect(shown).toBe(final.expected);
  expect(shown).not.toContain('不应晚刷');expect(shown).not.toContain('错误非终态');
  expect(shown).not.toContain('wrong-output');
  expect({creates,streams,reads}).toEqual({creates:1,streams:3,reads:3});
  console.log(JSON.stringify({probe:(sdk?'sdk':'webapp')+' pendingRAF '+final.name,
    creates,streams,reads,shown,lateFramePollution:false}));
  await page.close();
 }
} finally {await browser.close();}
```

另核对修复者原始日志/JSON及测试断言：新测试改前 16 场执行、74 条行为断言失败，改后 16 场、0 失败；原恢复 5 场和最终单独执行的鉴权 12 场均通过；全套 25 文件/83 条、vue-tsc 和三构建完成，既有 bundle 提示保留。这些未由本人重跑。作者如实保留首次并行鉴权的两个 goto 超时，并未改 timeout；单独复跑为 12/12。新增测试对 DOM 空白的 trim 不会将 `{}` 或错误非终态文本变为期望片段，因此不掩盖本缺陷。本人抽查 SDK recovered 截图，首流与恢复后片段均可读，缺失提示和禁发状态可见；未称本次亲看两图。

独立局部规格/质量结论：本轮 RUNNING{} 误终答缺陷满足关闭条件，未发现新的确定 P1/P2；原两鉴权 P2 的关闭状态保持。真正 55 秒 EOF/65 秒模型任务、双 engine/Redis 与网关浏览器纵切仍未在本节复验，由主协调者及 live agent 后续执行，不能从上述 8+16 个受控场景推导通过。
