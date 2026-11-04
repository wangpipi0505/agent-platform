import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
const gatewayTarget = process.env.AGENT_GATEWAY_URL || "http://127.0.0.1:18080";
export default defineConfig({
  base: process.env.NODE_ENV === "production" ? "/console/" : "/",
  root: fileURLToPath(new URL(".", import.meta.url)),
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 15173,
    strictPort: true,
    watch: { awaitWriteFinish: { stabilityThreshold: 100, pollInterval: 20 } },
    fs: { allow: [fileURLToPath(new URL("../../", import.meta.url))] },
    proxy: {
      "/admin": { target: gatewayTarget },
      "/auth": { target: gatewayTarget },
      "/v1": { target: gatewayTarget },
    },
  },
  build: { outDir: "dist", emptyOutDir: true },
});

