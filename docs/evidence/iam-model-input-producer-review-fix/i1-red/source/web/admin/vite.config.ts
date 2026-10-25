import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
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
      "/admin": { target: "http://127.0.0.1:18080" },
      "/auth": { target: "http://127.0.0.1:18080" },
      "/v1": { target: "http://127.0.0.1:18080" },
    },
  },
  build: { outDir: "dist", emptyOutDir: true },
});

