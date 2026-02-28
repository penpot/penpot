import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'
import { defineConfig } from 'vite'
import dts from 'vite-plugin-dts'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  resolve: {
    alias: {
      '@common': resolve(__dirname, '../penpot-exporter-figma-plugin/common'),
      '@common/*': resolve(__dirname, '../penpot-exporter-figma-plugin/common/*'),
      '@plugin': resolve(__dirname, '../penpot-exporter-figma-plugin/plugin-src'),
      '@plugin/*': resolve(__dirname, '../penpot-exporter-figma-plugin/plugin-src/*'),
      '@ui': resolve(__dirname, '../penpot-exporter-figma-plugin/ui-src'),
      '@ui/*': resolve(__dirname, '../penpot-exporter-figma-plugin/ui-src/*'),
      '@skia-rs-wasm/common': resolve(__dirname, '../../src/lib/common'),
      '@skia-rs-wasm/common/*': resolve(__dirname, '../../src/lib/common/*'),
    },
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'FigmaAdapter',
      formats: ['es', 'cjs'],
      fileName: (format) => `figma-adapter.${format === 'es' ? 'es' : 'cjs'}.js`,
    },
    sourcemap: true,
  },
  plugins: [
    dts({
      tsconfigPath: resolve(__dirname, 'tsconfig.json'),
      include: ['src'],
      exclude: ['**/*.test.ts'],
      outDir: 'dist',
    }),
  ],
})
