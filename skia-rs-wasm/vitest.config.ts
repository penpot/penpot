import path from 'path'
import { fileURLToPath } from 'url'
import { defineConfig } from 'vitest/config'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  resolve: {
    alias: {
      '@skia-rs-wasm/common': path.resolve(__dirname, 'src/lib/common'),
    },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
  },
})
