import { onUnmounted, ref, getCurrentInstance, type Ref } from "vue";

/**
 * F2-3（方案 §5.6 / §8 新增清单）：运行中心实时列表轮询 hook。
 *
 * useLiveList(fetcher, intervalMs?) → { rows, loading, error, refresh }
 * - 创建即拉取一次，并按 intervalMs（默认 15s）setInterval 轮询；
 * - 出错保留上次成功数据，只更新 error（下次轮询继续，不中断实时性）；
 * - refresh() 手动立即拉取（翻页/筛选/刷新按钮复用同一入口）；
 * - 定时器在组件卸载（onUnmounted）时清理；同轮次票据守卫，慢响应不覆盖新数据；
 * - 上一轮未完成时跳过本轮轮询（避免请求堆积，手动 refresh 不受限）。
 *
 * fetcher 返回行数组即可；分页总数等旁路状态由调用方在 fetcher 闭包内自行落位
 * （RunList 的 total/traceId 均在闭包内写，hook 不感知分页契约）。
 * AbortError（会话停止/卸载中断）不计为列表错误。
 */
export function useLiveList<T>(fetcher: () => Promise<T[]>, intervalMs = 15_000) {
  const rows: Ref<T[]> = ref([]);
  const loading = ref(false);
  const error = ref("");
  let sequence = 0;
  let timer: ReturnType<typeof setInterval> | undefined;

  async function refresh() {
    const ticket = ++sequence;
    loading.value = true;
    try {
      const data = await fetcher();
      if (ticket !== sequence) return;
      rows.value = Array.isArray(data) ? data : [];
      error.value = "";
    } catch (e: unknown) {
      if (ticket !== sequence) return;
      const failure = e as { name?: unknown; message?: unknown } | null;
      // 卸载/会话切换触发的中断不是列表错误，保留上次数据与错误语义。
      if (failure?.name === "AbortError") return;
      error.value = typeof failure?.message === "string" && failure.message
        ? failure.message
        : "列表刷新失败，正在按原间隔重试";
    } finally {
      if (ticket === sequence) loading.value = false;
    }
  }

  function start() {
    if (timer !== undefined) return;
    timer = setInterval(() => {
      // 上轮未完成跳过本轮，防止慢响应堆积；手动 refresh 不受限。
      if (!loading.value) void refresh();
    }, intervalMs);
  }
  function stop() {
    if (timer === undefined) return;
    clearInterval(timer);
    timer = undefined;
  }

  void refresh();
  if (intervalMs > 0) start();
  if (getCurrentInstance()) onUnmounted(stop);

  return { rows, loading, error, refresh, start, stop };
}
