import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
const gatewayTarget = process.env.AGENT_GATEWAY_URL || "http://127.0.0.1:18080";
// M5-2 本地演示分流（仅 dev server；生产构建不受影响）：默认仍走网关；
// 可用 AGENT_DIRECT_SERVICE_PROXY=1 按服务前缀直连 182xx 段服务——网关代理三处平台缺陷
// （wt-m53 缺陷登记②③④：任务计划端点未登记/PATCH ProtocolException/DELETE 体丢弃）
// 在本地演示期经服务直连绕过，属 dev-only 降级，如实登记。
const directServices = process.env.AGENT_DIRECT_SERVICE_PROXY === "1";
const engineTarget = process.env.AGENT_DIRECT_ENGINE_URL || "http://127.0.0.1:18202";
const skillTarget = process.env.AGENT_DIRECT_SKILL_URL || "http://127.0.0.1:18203";
const appTarget = process.env.AGENT_DIRECT_APP_URL || "http://127.0.0.1:18201";
const proxy: Record<string, { target: string }> = directServices
  ? {
      "/auth": { target: gatewayTarget },
      // engine 域（任务/会话/记忆/确认中心/运行观察/计划）
      "/v1": { target: engineTarget },
      "/admin/confirmation-center": { target: engineTarget },
      "/admin/debug": { target: engineTarget },
      "/admin/runs": { target: engineTarget },
      "/admin/operations": { target: engineTarget },
      "/admin/approvals": { target: engineTarget },
      // skill 域（技能/MCP/插件/附件）
      "/admin/skills": { target: skillTarget },
      "/admin/mcp-connections": { target: skillTarget },
      "/admin/plugins": { target: skillTarget },
      "/admin/plugin-sources": { target: skillTarget },
      "/admin/artifacts": { target: skillTarget },
      // app-management 域（应用/规则/集成配置/模型目录）
      "/admin/apps": { target: appTarget },
      "/admin/rules": { target: appTarget },
      "/admin/config-releases": { target: appTarget },
      "/admin/connection-profiles": { target: appTarget },
      "/admin/environment-bindings": { target: appTarget },
      "/admin/integration-profiles": { target: appTarget },
      "/admin/model-providers": { target: appTarget },
      "/admin/model-entries": { target: appTarget },
      "/admin/prompt-templates": { target: appTarget },
      "/admin/statistics": { target: appTarget },
      "/admin/classifications": { target: appTarget },
      "/admin/audit": { target: appTarget },
      "/admin/services": { target: appTarget },
      "/admin/system": { target: appTarget },
    }
  : {
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
