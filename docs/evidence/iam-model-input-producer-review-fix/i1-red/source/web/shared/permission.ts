import type { App } from "vue";
import { useAuth } from "./api";
export function installPermissions(app: App) {
  app.directive("permission", {
    mounted(el: HTMLElement, binding) {
      el.hidden = !useAuth().can(binding.value);
      if (el.hidden) el.style.setProperty("display", "none", "important");
      else el.style.removeProperty("display");
    },
    updated(el: HTMLElement, binding) {
      el.hidden = !useAuth().can(binding.value);
      if (el.hidden) el.style.setProperty("display", "none", "important");
      else el.style.removeProperty("display");
    },
  });
}
