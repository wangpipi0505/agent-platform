import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

// 自定义Vue宿主渲染器在Node运行，但需编译浏览器render（不是SSR模板）。
const componentPlugin = vue();
const transform = componentPlugin.transform;
if (typeof transform === "function") {
  componentPlugin.transform = function (code, id, options) {
    return transform.call(this, code, id, { ...options, ssr: false });
  };
}
// 定向组件功能检查使用真实 SFC 编译；不改变既有全量测试的环境或断言。
export default defineConfig({
  plugins: [componentPlugin],
  test: { include: ["tests/onboarding-session.check.ts"], environment: "node" },
});
