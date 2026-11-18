import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
const gatewayTarget = process.env.AGENT_GATEWAY_URL || "http://127.0.0.1:18080";
// dev server 一律经网关（与生产同路径）。M5-2 演示期曾设 AGENT_DIRECT_SERVICE_PROXY=1
// 按服务前缀直连 182xx 绕网关代理三缺陷（wt-m53 登记②③④：任务计划端点未登记/PATCH
// ProtocolException/DELETE 体丢弃）——R-9②③④⑤ 源码收口后该绕行下线（2026-11-18），
// 任务面 {taskId} 业务号端到端经网关可达，无直连分支残留。
const proxy: Record<string, { target: string }> = {
  "/admin": { target: gatewayTarget },
  "/auth": { target: gatewayTarget },
  "/v1": { target: gatewayTarget },
};
export default defineConfig(({mode})=>({
  base: mode==='mock' ? '/' : process.env.NODE_ENV === "production" ? "/console/" : "/",
  root: fileURLToPath(new URL(".", import.meta.url)),
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 15173,
    strictPort: true,
    watch: { awaitWriteFinish: { stabilityThreshold: 100, pollInterval: 20 } },
    fs: { allow: [fileURLToPath(new URL("../../", import.meta.url))] },
    proxy,
  },
  build: { outDir: mode==='mock'?'dist-mock':"dist", emptyOutDir: true },
}));
