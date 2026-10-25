import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
import { readFile } from "node:fs/promises";
export default defineConfig({
  base: process.env.NODE_ENV === "production" ? "/w/" : "/",
  root: fileURLToPath(new URL(".", import.meta.url)),
  plugins: [
    vue(),
    {
      name: "assistant-dev-artifact",
      configureServer(server) {
        server.middlewares.use("/assistant.js", async (_req, res) => {
          try {
            const content = await readFile(
              fileURLToPath(
                new URL("../assistant.js/dist/assistant.js", import.meta.url),
              ),
            );
            res.setHeader("Content-Type", "application/javascript");
            res.end(content);
          } catch {
            res.statusCode = 503;
            res.end("Assistant build unavailable; run npm run build.");
          }
        });
      },
    },
  ],
  server: {
    host: "127.0.0.1",
    port: 15174,
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
