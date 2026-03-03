import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'
import { rollup } from 'rollup'
import dts from 'rollup-plugin-dts'
import { type Plugin, defineConfig } from 'vite'

const __dirname = dirname(fileURLToPath(import.meta.url))

function dtsBundlePlugin(): Plugin {
  return {
    name: 'dts-bundle',
    apply: 'build',
    async closeBundle() {
      const bundle = await rollup({
        input: resolve(__dirname, 'src/index.ts'),
        plugins: [
          dts({
            tsconfig: resolve(__dirname, 'tsconfig.lib.json'),
          }),
        ],
      })
      await bundle.write({
        file: resolve(__dirname, 'dist/index.d.ts'),
        format: 'es',
      })
      await bundle.close()
    },
  }
}

export default defineConfig({
  resolve: {
    alias: {
      '@plugin': resolve(__dirname, '../penpot-exporter-figma-plugin/plugin-src'),
      '@ui': resolve(__dirname, '../penpot-exporter-figma-plugin/ui-src'),
      '@common': resolve(__dirname, '../penpot-exporter-figma-plugin/common'),
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
    rollupOptions: {
      external: [
        'skia-rs-wasm',
        'skia-rs-wasm/common',
        'penpot-exporter',
        'penpot-exporter/lib',
        'penpot-exporter/plugin',
      ],
    },
  },
  plugins: [dtsBundlePlugin()],
})
