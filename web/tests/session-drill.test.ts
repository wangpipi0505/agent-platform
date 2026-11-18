// F3-6（台账 §7.5 / 前端稿 §5.4）：会话详情与步骤调用下钻——mock 驱动。
// 锁定五项验收（卡面）：①压缩标记无正文（USER 普通视图响应不含 summary 正文/原始材料，
// 仅"此处已压缩"标记=区间+token 计数）；②调用锚点定位与无锚点隐藏（eventSeq→消息流 seq；
// appCode/sessionId/eventSeq 空或锚点被压缩遮蔽→保留错误摘要、隐藏跳转）；
// ③恢复三出口字段一致（contracts/h1-6-h1-8-stubs.md §3：USER 恰三字段/OPERATOR 全量，
// 详情/双视角切换同一事实）；④刷新重查锚点（同输入同输出，taskId/stepId/callId 复查）；
// ⑤直链拒绝审计（mock 403 形状+拒绝原因记录）。
// 与现有页面测试相同：真实 SFC setup（compileScript+transpile），按依赖替换为受控桩，setupState 断言。
import { readFileSync } from 'node:fs';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import * as Vue from 'vue';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import * as mock from '../shared/mock-session-detail';
import * as reasonCodeText from '../shared/reason-code-text';

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach((close) => close());
});
beforeEach(() => {
  mock.resetSessionDetailDemo();
});

async function flush() {
  for (let index = 0; index < 10; index++) {
    await Promise.resolve();
    await Vue.nextTick();
  }
}

/** 真实 SessionDetailPage setup：模板不编译（渲染桩），依赖按 ID 显式替换。 */
function mountSession(sessionId: string) {
  const route = Vue.reactive({ params: { sessionId }, query: {} as Record<string, unknown> });
  const { descriptor, errors } = parse(
    readFileSync(new URL('../admin/src/pages/SessionDetailPage.vue', import.meta.url), 'utf8'),
  );
  expect(errors).toEqual([]);
  const code = ts.transpileModule(
    compileScript(descriptor, { id: 'SessionDetailPageTest' }).content,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } },
  ).outputText;
  expect(code).not.toContain('import.meta'); // new Function 执行环境无 import.meta（run-detail 基线教训）
  const exports = {} as { default: Vue.Component & { render: () => void } };
  const requireStub = (id: string) => {
    if (id === 'vue') return Vue;
    if (id.includes('vue-router')) return { useRoute: () => route };
    // 子组件（PageHeader/EmptyState/ErrorBanner）：本组测 setup 逻辑，渲染桩。
    if (id.endsWith('.vue')) return { default: { render: () => null } };
    if (id.includes('usePageOperation')) return { usePageTitle: () => {} };
    if (id.includes('mock-session-detail')) return mock;
    // R-3：纯函数模块用真实实现（无外部依赖）。
    if (id.includes('reason-code-text')) return reasonCodeText;
    throw new Error(`unexpected require: ${id}`);
  };
  new Function('require', 'exports', code)(requireStub, exports);
  exports.default.render = () => null;
  const renderer = Vue.createRenderer<object, object>({
    createComment: () => ({}),
    insert() {},
    remove() {},
    parentNode: () => null,
    nextSibling: () => null,
    createElement: () => ({}),
    createText: () => ({}),
    setText() {},
    setElementText() {},
    patchProp() {},
  });
  const app = renderer.createApp({ render: () => Vue.h(exports.default) });
  const instance = app.mount({}).$ as unknown as { subTree: { component: { setupState: any } } };
  cleanup.push(() => app.unmount());
  return {
    state: instance.subTree.component!.setupState as any,
    route,
    async reload() {
      await instance.subTree.component!.setupState.refresh();
      await flush();
    },
  };
}

const callBy = (state: any, callId: string) =>
  state.calls.find((call: { callId: string }) => call.callId === callId);

describe('会话详情与步骤调用下钻 F3-6（方案 §5.4，mock 驱动）', () => {
  it('压缩标记无正文①：USER 普通视图响应不含 summary 正文/模型细节/原始材料，仅区间+token 计数标记', () => {
    const page = mock.sessionEvents('recon-assistant', 'sess-demo-28', {}, 'USER');
    expect('httpStatus' in page && page.httpStatus).not.toBe(403);
    if ('httpStatus' in page) throw new Error('USER 普通视图不应被拒');
    const text = JSON.stringify(page);
    // 压缩正文与模型调用细节（评审二十一轮补）不出现
    expect(text).not.toContain('已压缩第 1 轮检测与外部通知对话');
    expect(text).not.toContain('modelCalls');
    expect(text).not.toContain('deepseek-chat');
    // 原始材料（接口稿 §5.1 USER 不可读清单）不出现
    expect(text).not.toContain('完整原始参数');
    expect(text).not.toContain('rawPayload');
    expect(text).not.toContain('rawRows');
    expect(text).not.toContain('上下文预算提醒');
    // 诊断类事件与被压缩覆盖区间（seq 2..10）不参与普通投影
    const hiddenTypes = ['system/message', 'request/header', 'request/context', 'assistant/attempt'];
    for (const type of hiddenTypes) expect(page.events.some((e) => e.type === type)).toBe(false);
    expect(page.events.some((e) => e.seq >= 2 && e.seq <= 10)).toBe(false);
    // 压缩标记事件保留：区间+token 计数，无 summary 正文键
    const marker = page.events.find((e) => e.type === 'compaction/summary');
    expect(marker).toBeTruthy();
    expect(marker!.data).toEqual({ fromSeq: 2, toSeq: 10, tokensBefore: 18600, tokensAfter: 4300 });
    // 过滤游标语义（评审三轮 07）：nextAfterSeq=最后扫描原始 seq（不 +1），全部扫描完 truncated=false
    expect(page.nextAfterSeq).toBe(28);
    expect(page.truncated).toBe(false);
    // OPERATOR 诊断视图（surfaceOnly=false）才含正文/原始材料/诊断事件
    const diag = mock.sessionEvents('recon-assistant', 'sess-demo-28', { surfaceOnly: false }, 'OPERATOR');
    if ('httpStatus' in diag) throw new Error('OPERATOR 诊断视图不应被拒');
    const diagText = JSON.stringify(diag);
    expect(diagText).toContain('已压缩第 1 轮检测与外部通知对话');
    expect(diagText).toContain('rawPayload');
    expect(diagText).toContain('request/header');
    expect(diagText).toContain('上下文预算提醒');
    expect(diag.events.some((e) => e.seq >= 2 && e.seq <= 10)).toBe(true);
  });

  it('压缩标记无正文②：USER 显式 surfaceOnly=false → 403 诊断门+拒绝审计；页面 USER 视角无正文、OPERATOR 有', async () => {
    const denied = mock.sessionEvents('recon-assistant', 'sess-demo-28', { surfaceOnly: false }, 'USER');
    expect(denied).toMatchObject({
      error: 'SESSION_DIAGNOSTIC_VIEW_FORBIDDEN',
      httpStatus: 403,
      category: 'AUTH',
    });
    expect(mock.auditTrail()).toHaveLength(1);
    expect(mock.auditTrail()[0].reason).toContain('OPERATOR/ADMIN');
    // 页面：USER 视角（普通视图）无正文；切 OPERATOR（诊断视图）正文出现
    const page = mountSession('sess-demo-28');
    await page.reload();
    page.state.switchPerspective('USER');
    await flush();
    expect(page.state.perspective).toBe('USER');
    expect(page.state.compactionSummaries).toEqual([]);
    expect(page.state.compactions).toEqual([{ fromSeq: 2, toSeq: 10, tokensBefore: 18600, tokensAfter: 4300 }]);
    const userText = JSON.stringify({
      stream: page.state.stream,
      rows: page.state.rows.map((r: any) => ({ title: r.title, text: r.text })),
    });
    expect(userText).not.toContain('已压缩第 1 轮检测与外部通知对话');
    expect(userText).not.toContain('完整原始参数');
    expect(page.state.rows.some((row: any) => row.title === '此处已压缩')).toBe(true);
    page.state.switchPerspective('OPERATOR');
    await flush();
    expect(page.state.compactionSummaries.length).toBe(1);
    expect(page.state.compactionSummaries[0]).toContain('已压缩第 1 轮检测与外部通知对话');
    expect(page.state.rows.some((row: any) => row.type === 'request/header')).toBe(true);
  });

  it('调用锚点①：eventSeq 有值→定位该 seq；无锚点（无会话/无可见事件）→隐藏跳转保留错误摘要', async () => {
    const page = mountSession('sess-demo-28');
    await page.reload();
    expect(page.state.calls).toHaveLength(6);
    // OPERATOR 视角=诊断视图：被压缩覆盖的锚点（call-a01→seq4、call-a02→seq8）仍可定位（§5.7）
    const operatorExpect: Record<string, { mode: string; seq?: number; reason?: string }> = {
      'call-a01': { mode: 'jump', seq: 4 },
      'call-a02': { mode: 'jump', seq: 8 },
      'call-p31': { mode: 'jump', seq: 19 },
      'call-f23': { mode: 'jump', seq: 23 },
      'call-n41': { mode: 'hidden', reason: 'no-session' },
      'call-n42': { mode: 'hidden', reason: 'no-visible-event' },
    };
    for (const [callId, expected] of Object.entries(operatorExpect)) {
      const anchor = page.state.callAnchorView(callBy(page.state, callId));
      expect(anchor, callId).toEqual(expected);
    }
    // 无锚点行保留错误摘要（页面保留错误摘要并隐藏跳转）
    expect(callBy(page.state, 'call-n41').errorSummary).toContain('独立任务无会话关联');
    expect(callBy(page.state, 'call-n42').errorSummary).toContain('保留待对账');
    // 定位：设置锚点并指向消息流对应 seq（消息流含该原始 seq）
    page.state.locateCall(callBy(page.state, 'call-a01'));
    expect(page.state.anchored).toMatchObject({ callId: 'call-a01', seq: 4, taskId: 'task-demo-95', stepId: 'detect_a' });
    expect(page.state.rows.some((row: any) => row.seq === 4 && row.type === 'tool/call')).toBe(true);
    // USER 视角：被压缩覆盖锚点隐藏（仅见压缩标记），可见锚点仍可跳
    page.state.switchPerspective('USER');
    await flush();
    expect(page.state.callAnchorView(callBy(page.state, 'call-a01'))).toEqual({ mode: 'hidden', reason: 'shadowed' });
    expect(page.state.callAnchorView(callBy(page.state, 'call-a02'))).toEqual({ mode: 'hidden', reason: 'shadowed' });
    expect(page.state.callAnchorView(callBy(page.state, 'call-p31'))).toEqual({ mode: 'jump', seq: 19 });
    expect(page.state.callAnchorView(callBy(page.state, 'call-f23'))).toEqual({ mode: 'jump', seq: 23 });
    expect(page.state.callAnchorView(callBy(page.state, 'call-n41'))).toEqual({ mode: 'hidden', reason: 'no-session' });
    expect(page.state.callAnchorView(callBy(page.state, 'call-n42'))).toEqual({ mode: 'hidden', reason: 'no-visible-event' });
    // USER 对遮蔽调用定位无效（仅见标记）；对可见锚点定位有效
    page.state.locateCall(callBy(page.state, 'call-a01'));
    expect(page.state.anchored).toBeNull();
    page.state.locateCall(callBy(page.state, 'call-p31'));
    expect(page.state.anchored).toMatchObject({ callId: 'call-p31', seq: 19 });
  });

  it('调用锚点②：列表按 callId 过滤；快照/事件流/子代理目录形状逐字段对齐 H1-9 端点', async () => {
    const page = mountSession('sess-demo-28');
    await page.reload();
    // 列表过滤（§5.7 callId 直接定位语义的页面侧投影）
    page.state.callIdFilter = 'call-a';
    expect(page.state.filteredCalls.map((c: any) => c.callId)).toEqual(['call-a01', 'call-a02']);
    page.state.callIdFilter = 'call-n';
    expect(page.state.filteredCalls.map((c: any) => c.callId)).toEqual(['call-n41', 'call-n42']);
    page.state.callIdFilter = 'no-such-call';
    expect(page.state.filteredCalls).toEqual([]);
    page.state.callIdFilter = '';
    expect(page.state.filteredCalls).toHaveLength(6);
    // 快照：普通视图 messages 投影（被压缩覆盖的消息不参与）+eventWatermark
    expect(page.state.snap).toMatchObject({
      sessionId: 'sess-demo-28',
      appCode: 'recon-assistant',
      linkedTaskId: 'task-demo-95',
      eventWatermark: 28,
      demo: true,
    });
    expect(page.state.snap.messages.map((m: any) => m.seq)).toEqual([14, 27]); // seq2 用户消息已被压缩覆盖
    // 事件流序列有序、tool 调用-回执配对（诊断视图含配对双方）
    const seqs = page.state.stream.map((e: any) => e.seq);
    expect([...seqs].sort((a: number, b: number) => a - b)).toEqual(seqs);
    const callIdx = page.state.stream.findIndex((e: any) => e.type === 'tool/call' && e.data.callId === 'call-p31');
    const resultIdx = page.state.stream.findIndex((e: any) => e.type === 'tool/result' && e.data.callId === 'call-p31');
    expect(callIdx).toBeGreaterThan(-1);
    expect(resultIdx).toBe(callIdx + 1);
    // 子代理目录（§5.3）：无提示词/参数/正文字段；停因按角色投影（USER 摘要/OPERATOR 原因码）
    expect(page.state.children).toHaveLength(2);
    expect(page.state.children[0]).toMatchObject({ childSessionId: 'sess-demo-28-c1', label: '算法候选探索' });
    expect(page.state.children[0].stopReason).toBe('COMPLETE');
    expect(page.state.children.some((c: any) => 'prompt' in c || 'args' in c || 'output' in c)).toBe(false);
    page.state.switchPerspective('USER');
    await flush();
    expect(page.state.children.map((c: any) => c.stopReason)).toEqual(['正常完成', '深度受限停止']);
    // USER 直查子代理目录（mock 端点同形状）
    expect(mock.sessionSubagents('sess-demo-28', 'USER').children[1].stopReason).toBe('深度受限停止');
    expect(mock.sessionSubagents('sess-demo-28', 'OPERATOR').children[1].stopReason).toBe('DEPTH_LIMIT');
    // 事件增量分页游标：afterSeq=10 排他续读（seq>10），普通视图首页扫描推进到窗口末尾
    const paged = mock.sessionEvents('recon-assistant', 'sess-demo-28', { afterSeq: 10, limit: 5 }, 'USER');
    if ('httpStatus' in paged) throw new Error('分页不应被拒');
    expect(paged.nextAfterSeq).toBe(15); // 窗口扫描到 15（含被过滤跳过的 11..14 原始 seq），不 +1
    expect(paged.events.every((e) => e.seq > 10)).toBe(true);
    expect(paged.truncated).toBe(true);
  });

  it('恢复三出口字段一致：USER 恰三字段/OPERATOR 全量，双视角切换同一事实不变；未恢复如实 null', async () => {
    // mock 端点投影（详情/resume/事件共用同一事实源）
    const user = mock.taskRecovery('task-demo-95', 'USER');
    expect(Object.keys(user as object).sort()).toEqual(['reasonCode', 'reasonSummary', 'recoverySource']);
    expect(user).toMatchObject({ recoverySource: 'AUTO', reasonCode: 'LEASE_EXPIRED', reasonSummary: '执行租约到期后已接续' });
    expect(JSON.stringify(user)).not.toContain('run-551'); // USER 不返回检查点与内部运行引用
    const operator = mock.taskRecovery('task-demo-95', 'OPERATOR');
    expect(operator).toMatchObject({
      recoverySource: 'AUTO',
      reasonCode: 'LEASE_EXPIRED',
      reasonSummary: '执行租约到期后已接续',
      checkpoint: { eventWatermark: 13, runRefs: ['run-551'] },
      reusedSteps: [{ stepId: 'detect_a', resultDigest: 'ab41' }],
      blockedReasons: ['外部任务 EXT-77 回执未决'],
    });
    // 三出口字段一致：共有三字段在两角色投影间逐字段相等
    expect(user!.recoverySource).toBe(operator!.recoverySource);
    expect(user!.reasonCode).toBe(operator!.reasonCode);
    expect(user!.reasonSummary).toBe(operator!.reasonSummary);
    expect(mock.taskRecovery('task-demo-96', 'USER')).toBeNull();
    expect(mock.taskRecovery('task-demo-96', 'OPERATOR')).toBeNull();
    // 页面：OPERATOR 全量分区有则显示；切换 USER 只剩三字段且事实不变
    const page = mountSession('sess-demo-28');
    await page.reload();
    expect(page.state.recovery.checkpoint).toEqual({ eventWatermark: 13, runRefs: ['run-551'] });
    expect(page.state.recovery.blockedReasons).toEqual(['外部任务 EXT-77 回执未决']);
    page.state.switchPerspective('USER');
    await flush();
    // 页面视图模型：USER 附加分区如实空缺（有则显示，无则不渲染；wire 层恰三字段由上方 mock 断言锁定）
    expect(page.state.recovery.checkpoint).toBeNull();
    expect(page.state.recovery.reusedSteps).toEqual([]);
    expect(page.state.recovery.blockedReasons).toEqual([]);
    expect(page.state.recovery.reasonCode).toBe('LEASE_EXPIRED');
    expect(page.state.recovery.reasonSummary).toBe('执行租约到期后已接续');
    page.state.switchPerspective('OPERATOR');
    await flush();
    expect(page.state.recovery.recoverySource).toBe('AUTO');
    expect(page.state.recovery.checkpoint.eventWatermark).toBe(13);
  });

  it('刷新重查锚点：同输入同输出（taskId/stepId/callId 复查锚点不变）；视角切换后锚点复核降级', async () => {
    const page = mountSession('sess-demo-28');
    await page.reload();
    page.state.locateCall(callBy(page.state, 'call-a01'));
    expect(page.state.anchored).toMatchObject({ callId: 'call-a01', seq: 4 });
    // 刷新（重新 GET 快照/事件/列表）后锚点保持——同输入同输出
    await page.reload();
    expect(page.state.anchored).toMatchObject({ callId: 'call-a01', seq: 4 });
    // §5.7 端点级复查：callId 直查两次，锚点字段逐字段相等（确定性）
    const first = mock.stepCalls('task-demo-95', 'detect_a', 'OPERATOR', { callId: 'call-a01' });
    const second = mock.stepCalls('task-demo-95', 'detect_a', 'OPERATOR', { callId: 'call-a01' });
    expect(second).toEqual(first);
    expect(first.calls[0]).toMatchObject({ appCode: 'recon-assistant', sessionId: 'sess-demo-28', eventSeq: 4 });
    // OPERATOR 行带 reconciliation（服务端对账事实），USER 投影省略
    const operatorCalls = mock.stepCalls('task-demo-95', 'notify_ext', 'OPERATOR');
    const userCalls = mock.stepCalls('task-demo-95', 'notify_ext', 'USER');
    expect(operatorCalls.calls.find((c) => c.callId === 'call-a02')!.reconciliation).toMatchObject({ state: 'PENDING', reasonCode: 'EXTERNAL_RESULT_UNKNOWN' });
    expect(userCalls.calls.find((c) => c.callId === 'call-a02')!.reconciliation).toBeUndefined();
    // 列表按 callId 稳定排序（§5.7）：两角色排序一致
    expect(operatorCalls.calls.map((c) => c.callId)).toEqual(['call-a02', 'call-n42']);
    expect(userCalls.calls.map((c) => c.callId)).toEqual(operatorCalls.calls.map((c) => c.callId));
    // 视角切换（USER 普通视图）→ 锚点复核：遮蔽调用不可再跳，如实降级提示并解除
    page.state.switchPerspective('USER');
    await flush();
    expect(page.state.anchored).toBeNull();
    expect(page.state.anchorNotice).toContain('call-a01');
    expect(page.state.anchorNotice).toContain('压缩');
    // 再切回 OPERATOR（诊断视图）可重新定位
    page.state.switchPerspective('OPERATOR');
    await flush();
    page.state.locateCall(callBy(page.state, 'call-a01'));
    expect(page.state.anchored).toMatchObject({ callId: 'call-a01', seq: 4 });
  });

  it('直链拒绝审计：无权会话 403+拒绝原因记录（双角色），未知会话 404；页面呈现拒绝态', async () => {
    const asOperator = mock.sessionSnapshot('vault-assistant', 'sess-demo-forbidden', 'OPERATOR');
    expect(asOperator).toMatchObject({ error: 'SESSION_READ_FORBIDDEN', httpStatus: 403, category: 'AUTH' });
    expect((asOperator as any).audit).toMatchObject({ principal: 'op-demo', reason: '工作区未授权', sessionId: 'sess-demo-forbidden' });
    const asUser = mock.sessionSnapshot('vault-assistant', 'sess-demo-forbidden', 'USER');
    expect((asUser as any).httpStatus).toBe(403);
    expect((asUser as any).audit.principal).toBe('u-demo');
    expect((asUser as any).audit.reason).toContain('本人');
    expect(mock.auditTrail()).toHaveLength(2);
    expect(mock.auditTrail().every((record) => record.action === 'SESSION_READ_FORBIDDEN')).toBe(true);
    // 未知会话 404（资源不存在），不记 403 审计
    const missing = mock.sessionSnapshot('recon-assistant', 'sess-demo-404', 'USER');
    expect(missing).toMatchObject({ error: 'SESSION_NOT_FOUND', httpStatus: 404 });
    // 页面直链拒绝态：snap 为空、reject 含 403 形状与审计说明
    const page = mountSession('sess-demo-forbidden');
    await page.reload();
    expect(page.state.reject).toBeTruthy();
    expect(page.state.reject.httpStatus).toBe(403);
    expect(page.state.reject.userMessage).toContain('没有权限');
    expect(page.state.snap).toBeNull();
    // 正常直链可打开（合法主体深链可达）
    const ok = mountSession('sess-demo-28');
    await ok.reload();
    expect(ok.state.reject).toBeNull();
    expect(ok.state.snap.sessionId).toBe('sess-demo-28');
  });
});
