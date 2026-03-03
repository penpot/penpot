import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'
import { defineConfig } from 'vitest/config'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  resolve: {
    alias: {
      '@plugin': resolve(__dirname, '../penpot-exporter-figma-plugin/plugin-src'),
      '@ui': resolve(__dirname, '../penpot-exporter-figma-plugin/ui-src'),
      '@common': resolve(__dirname, '../penpot-exporter-figma-plugin/common'),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    deps: {
      optimizer: {
        ssr: {
          include: ['penpot-exporter/plugin', 'penpot-exporter/lib'],
          enabled: true,
        },
      },
    },
  },
})
