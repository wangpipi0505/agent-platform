import { expect, it } from 'vitest';
import { createApi } from '../shared/api';

it('preserves explicit error fields and trace fallback while auth remains safe and nonretryable', async () => {
  for (const status of [401, 403, 429, 503]) {
    const trace = `ex22-ui-${status}`;
    const denied = status === 401 || status === 403;
    const api = createApi(() => 'verified-test', async () => new Response(JSON.stringify({
      code: denied ? 'REMOTE_SCOPE_DENIED' : 'REMOTE_BUSY',
      message: denied ? 'private SQL/password detail' : '旧提示',
      userMessage: denied ? 'private SQL/password detail' : '服务正忙，请稍后手动操作',
      category: denied ? 'TRANSPORT' : 'QUOTA',
      retryable: status !== 503,
    }), { status, headers: { 'X-Trace-Id': trace } }));
    const error = await api('/admin/ex22').catch((failure: unknown) => failure);
    expect(error).toMatchObject({ status, code: denied ? 'REMOTE_SCOPE_DENIED' : 'REMOTE_BUSY', traceId: trace,
      category: denied ? 'AUTH' : 'QUOTA', retryable: denied ? false : status !== 503 });
    if (denied) {
      expect(error).toHaveProperty('message', status === 401 ? '会话已失效，请重新连接' : '当前账号没有此操作权限，请联系管理员。');
      expect(String(error)).not.toMatch(/SQL|password/);
    } else {
      expect(error).toHaveProperty('message', '服务正忙，请稍后手动操作');
    }
  }
});
