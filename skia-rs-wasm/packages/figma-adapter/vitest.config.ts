import { resolve } from 'path'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    alias: {
      '@common': resolve(__dirname, '../penpot-exporter-figma-plugin/common'),
      '@common/*': resolve(__dirname, '../penpot-exporter-figma-plugin/common/*'),
      '@ui': resolve(__dirname, '../penpot-exporter-figma-plugin/ui-src'),
      '@ui/*': resolve(__dirname, '../penpot-exporter-figma-plugin/ui-src/*'),
      '@skia-rs-wasm/common': resolve(__dirname, '../../src/lib/common'),
      '@skia-rs-wasm/common/*': resolve(__dirname, '../../src/lib/common/*'),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
