import * as Vue from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useLiveList } from '../shared/hooks/useLiveList';

// F2-3（方案 §5.6 运行中心）：useLiveList 轮询 hook 行为锁定——
// 创建即拉取+固定间隔轮询、出错保留上次数据、慢响应不堆积、卸载清理定时器。
// hook 为纯 .ts 模块，直接真实导入；卸载语义用无 DOM 桩渲染器挂宿主组件验证。

async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await Vue.nextTick();
}

function mountHost(setup: () => void) {
  const renderer = Vue.createRenderer<any, any>({
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
  const host = Vue.defineComponent({
    setup() {
      setup();
      return () => null;
    },
  });
  const app = renderer.createApp(host);
  app.mount({});
  return () => app.unmount();
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('useLiveList（F2-3 运行中心实时列表）', () => {
  it('创建即拉取一次并按 interval 轮询，手动 refresh 复用同一入口', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(async () => ['a', 'b']);
    const view = useLiveList<string>(fetcher, 15_000);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(view.rows.value).toEqual(['a', 'b']);
    expect(view.loading.value).toBe(false);
    expect(view.error.value).toBe('');
    vi.advanceTimersByTime(15_000);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(2);
    await view.refresh();
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(view.rows.value).toEqual(['a', 'b']);
  });

  it('intervalMs=0 关闭轮询（只保留手动 refresh）', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(async () => ['a']);
    const view = useLiveList<string>(fetcher, 0);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(60_000);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    await view.refresh();
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('出错保留上次数据并如实回填 error，下一次轮询成功即清错', async () => {
    vi.useFakeTimers();
    let fail = true;
    const fetcher = vi.fn(async () => {
      if (fail) throw new Error('后端 500');
      return ['next'];
    });
    const view = useLiveList<string>(fetcher, 15_000);
    await flush();
    expect(view.rows.value).toEqual([]);
    expect(view.error.value).toBe('后端 500');
    expect(view.loading.value).toBe(false);
    fail = false;
    vi.advanceTimersByTime(15_000);
    await flush();
    expect(view.rows.value).toEqual(['next']);
    expect(view.error.value).toBe('');
  });

  it('慢响应在途时跳过本轮轮询，不堆积请求（票据守卫防乱序覆盖）', async () => {
    vi.useFakeTimers();
    let release!: () => void;
    const gate = new Promise<void>((yes) => (release = yes));
    const fetcher = vi.fn(async () => {
      await gate;
      return ['slow'];
    });
    const view = useLiveList<string>(fetcher, 15_000);
    vi.advanceTimersByTime(30_000);
    release();
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(view.rows.value).toEqual(['slow']);
  });

  it('宿主组件卸载即清理定时器（onUnmounted），卸载后不再轮询', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(async () => ['u']);
    const unmount = mountHost(() => useLiveList<string>(fetcher, 15_000));
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    unmount();
    await flush();
    vi.advanceTimersByTime(60_000);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('非 Error 抛出物也给出可读错误文案，不展示空串', async () => {
    vi.useFakeTimers();
    const fetcher = vi.fn(async () => {
      throw '字符串异常';
    });
    const view = useLiveList<string>(fetcher, 15_000);
    await flush();
    expect(view.error.value).toBe('列表刷新失败，正在按原间隔重试');
  });
});
