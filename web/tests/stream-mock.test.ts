import { afterEach, expect, it, vi } from 'vitest';
import { streamEvents } from '../debug-panel/events';
import { mockTransport } from '../shared/mock-transport';

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it('mock run and paused debug streams use local handlers without HTTP', async () => {
  vi.stubEnv('VITE_MOCK', 'true');
  const network = vi.fn(async () => { throw new Error('unexpected HTTP'); });
  vi.stubGlobal('fetch', network);
  const waiting: any[] = [];
  await streamEvents('/admin/runs/run-104/events', () => 'demo', '0', e => waiting.push(e), new AbortController().signal);
  expect(waiting.at(-1)).toMatchObject({ type: 'RUN_WAITING', data: { status: 'WAITING_APPROVAL' } });

  const response = await mockTransport('/admin/debug-runs', {
    method: 'POST', body: JSON.stringify({ appCode: 'payload-advisor', mode: 'STEP', inputs: {} }),
  });
  const { data: run } = await response.json();
  expect(run.status).toBe('WAITING_STEP');
  // DebugPanel observes via /admin/runs; the debug endpoint shares the same handler data.
  for (const path of [`/admin/runs/${run.runId}/events`, `/admin/debug-runs/${run.runId}/events`]) {
    const events: any[] = [];
    await streamEvents(path, () => 'demo', '0', e => events.push(e), new AbortController().signal);
    expect(events).toContainEqual(expect.objectContaining({ type: 'WAITING_STEP' }));
  }
  expect(network).not.toHaveBeenCalled();
});

it('mock missing streams remain local failures', async () => {
  vi.stubEnv('VITE_MOCK', 'true');
  const network = vi.fn(async () => { throw new Error('unexpected HTTP'); });
  vi.stubGlobal('fetch', network);
  await expect(streamEvents('/missing-stream/events', () => 'demo', '0', vi.fn(), new AbortController().signal))
    .rejects.toMatchObject({ status: 404, code: 'NOT_FOUND' });
  expect(network).not.toHaveBeenCalled();
});

it.each([undefined, 'false'])('real mode %s keeps authenticated native SSE', async mode => {
  vi.stubEnv('VITE_MOCK', mode);
  const network = vi.fn(async () => new Response('id: 8\nevent: RUN_FINISHED\ndata: {"status":"SUCCEEDED"}\n\n'));
  vi.stubGlobal('fetch', network);
  const events: any[] = [];
  const controller = new AbortController();
  await streamEvents('/admin/runs/run-104/events', () => 'real', '7', e => events.push(e), controller.signal);
  expect(network).toHaveBeenCalledExactlyOnceWith('/admin/runs/run-104/events', expect.objectContaining({
    headers: { Authorization: 'Bearer real', 'Last-Event-ID': '7', Accept: 'text/event-stream' },
    signal: controller.signal, credentials: 'omit',
  }));
  expect(events).toEqual([{ seq: '8', type: 'RUN_FINISHED', data: { status: 'SUCCEEDED' }, rawData: '{"status":"SUCCEEDED"}' }]);
});
