// F4-4 亮色切换（方案 §7.3"暗色默认，亮色可切"）：F1-1 已交付 html.light 语义 token
// 段与旧名映射，本模块交付开关本体——偏好三态（auto/light/dark）持久
// localStorage("theme")；auto 初始跟随 prefers-color-scheme 并监听系统变化，
// 手动覆盖（顶栏按钮）后固定所选主题，系统变化不再改写。html 根同时只挂
// dark/light 之一（tokens.css :root=暗色基座、html.light=亮色段+EP 亮色映射）。
import { ref } from "vue";

export const THEME_KEY = "theme";
export type ThemePref = "auto" | "light" | "dark";
export type ResolvedTheme = "dark" | "light";

const SYSTEM_DARK_QUERY = "(prefers-color-scheme: dark)";

function safeStorage(): Pick<Storage, "getItem" | "setItem" | "removeItem"> | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null; // 隐私模式/禁存储环境下仅本次会话生效
  }
}

export function readThemePref(storage: Pick<Storage, "getItem"> | null = safeStorage()): ThemePref {
  const raw = storage?.getItem(THEME_KEY) ?? null;
  return raw === "light" || raw === "dark" ? raw : "auto";
}

export function resolveTheme(pref: ThemePref, systemPrefersDark: boolean): ResolvedTheme {
  if (pref === "auto") return systemPrefersDark ? "dark" : "light";
  return pref;
}

export function systemPrefersDark(doc: Document = document): boolean {
  return doc.defaultView?.matchMedia?.(SYSTEM_DARK_QUERY)?.matches ?? true;
}

/** 把解析结果落到文档根：dark/light 互斥挂类（两类并存时 tokens 段会互相覆盖，必须互斥）。 */
export function applyThemeClass(resolved: ResolvedTheme, doc: Document = document): void {
  const root = doc.documentElement;
  root.classList.toggle("dark", resolved === "dark");
  root.classList.toggle("light", resolved === "light");
}

/** 应用启动引导（main.ts 挂载前调用，避免主题闪烁）：按持久偏好+系统偏好上屏。 */
export function initTheme(doc: Document = document): ResolvedTheme {
  const resolved = resolveTheme(readThemePref(), systemPrefersDark(doc));
  applyThemeClass(resolved, doc);
  return resolved;
}

/** 顶栏开关共享逻辑（admin AppShell 与 webapp 顶栏同源）。 */
export function useTheme(doc: Document = document) {
  const pref = ref<ThemePref>(readThemePref());
  const resolved = ref<ResolvedTheme>(resolveTheme(pref.value, systemPrefersDark(doc)));
  const media = doc.defaultView?.matchMedia?.(SYSTEM_DARK_QUERY) ?? null;
  const onSystemChange = () => {
    if (pref.value !== "auto") return; // 手动覆盖后系统变化不再生效
    resolved.value = resolveTheme("auto", !!media?.matches);
    applyThemeClass(resolved.value, doc);
  };
  media?.addEventListener?.("change", onSystemChange);
  function setTheme(next: ThemePref) {
    pref.value = next;
    const storage = safeStorage();
    try {
      if (next === "auto") storage?.removeItem(THEME_KEY);
      else storage?.setItem(THEME_KEY, next);
    } catch {
      /* 写失败仅影响持久化，本次会话仍生效 */
    }
    resolved.value = resolveTheme(next, systemPrefersDark(doc));
    applyThemeClass(resolved.value, doc);
  }
  /** 顶栏一键切换：在解析结果之间翻转并持久化手动覆盖。 */
  function toggleTheme() {
    setTheme(resolved.value === "dark" ? "light" : "dark");
  }
  function dispose() {
    media?.removeEventListener?.("change", onSystemChange);
  }
  return { pref, resolved, setTheme, toggleTheme, dispose };
}
