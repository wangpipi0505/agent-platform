import { onBeforeUnmount, ref, watchEffect } from "vue";
import { ElMessage } from "element-plus";
import { useApi, useAuth } from "../../shared/api";
import { pageChrome } from "./page-chrome";

/** Page-level request state. It is intentionally local so one page cannot
 * clear another page's error banner after a route change. */
export function usePageOperation() {
  const auth = useAuth();
  const api = useApi();
  const session = auth.snapshot();
  const busy = ref(false);
  const error = ref("");
  const trace = ref("");
  let disposed = false;
  const current = () => !disposed && session.current();

  function success(message: string) {
    if (current()) ElMessage.success(message);
  }

  function failure(e: any) {
    if (!current() || e?.name === "AbortError") return;
    error.value = e?.message || "请求失败，请稍后重试";
    trace.value = e?.traceId || "";
    if (e?.status === 401) auth.invalidate(session.generation);
    if (e?.status === 403)
      error.value = "当前账号没有此操作权限，请联系管理员。";
  }

  async function operation(fn: () => Promise<void>) {
    if (!current()) return;
    busy.value = true;
    error.value = "";
    try {
      await fn();
    } catch (e) {
      failure(e);
    } finally {
      if (current()) busy.value = false;
    }
  }

  onBeforeUnmount(() => {
    disposed = true;
  });

  return {
    auth,
    api,
    session,
    busy,
    error,
    trace,
    current,
    success,
    failure,
    operation,
  };
}

/** Keep the shell breadcrumb in sync with the page's own heading. */
export function usePageTitle(value: () => string) {
  watchEffect(() => {
    pageChrome.title = value();
  });
}
