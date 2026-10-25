import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";
export default defineConfig({
  root: fileURLToPath(new URL(".", import.meta.url)),
  build: {
    lib: {
      entry: fileURLToPath(new URL("src/main.ts", import.meta.url)),
      name: "AIAssistant",
      formats: ["iife"],
      fileName: () => "assistant.js",
    },
    outDir: "dist",
    emptyOutDir: true,
  },
});
