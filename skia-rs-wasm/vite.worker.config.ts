/**
 * Vite config for building the worker as a single ESM file for CDN or plugin use.
 * Output: dist/worker.js (loadable with new Worker(url, { type: 'module' }))
 */
import { defineConfig } from 'vite'
import { resolve, dirname } from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

export default defineConfig({
  define: {
    'process.env.NODE_ENV': '"production"',
  },
  resolve: {
    alias: {
      '@skia-rs-wasm/common': resolve(__dirname, 'src/lib/common'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: false,
    lib: false,
    rollupOptions: {
      input: resolve(__dirname, 'src/lib/worker/index.ts'),
      output: {
        format: 'es',
        entryFileNames: 'worker.js',
        inlineDynamicImports: true,
      },
    },
    sourcemap: true,
    minify: false,
  },
})
