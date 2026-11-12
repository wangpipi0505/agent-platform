import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
const plugin = vue()
const transform = plugin.transform
if (typeof transform === 'function') plugin.transform = function(code, id, options) { return transform.call(this, code, id, { ...options, ssr: false }) }
export default defineConfig({ plugins: [plugin], test: { include: ['tests/planning-ui.check.ts'], environment: 'node' } })
