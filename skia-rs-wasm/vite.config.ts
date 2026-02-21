import { defineConfig } from 'vite'
import { resolve, dirname, join } from 'path'
import { fileURLToPath } from 'url'
import fs from 'fs'

// ESM doesn't have __dirname, so we create it
const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

// https://vite.dev/config/
export default defineConfig({
  resolve: {
    alias: {
      '@skia-rs-wasm/common': resolve(__dirname, 'src/lib/common'),
      '@penpot-exporter/transformers': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/plugin-src/transformers'),
      '@penpot-exporter/types': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/ui-src/types'),
      '@penpot-exporter/lib/types': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/ui-src/lib/types'),
      '@penpot-exporter/common': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/common')
    }
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'SkiaWasmRenderer',
      formats: ['es', 'cjs'],
      fileName: (format) => `renderer.${format === 'es' ? 'es' : 'cjs'}.js`,
    },
    rollupOptions: {
      external: [],
      output: {
        assetFileNames: (assetInfo) => {
          if (assetInfo.name?.endsWith('.wasm')) {
            return 'render_wasm.wasm'
          }
          return assetInfo.name || 'asset'
        },
      },
    },
    copyPublicDir: true,
    sourcemap: true,
  },
  plugins: [
    {
      name: 'wasm-content-type-plugin',
      configureServer(server) {
        const publicDir = join(__dirname, 'public')
        
        server.middlewares.use((req, res, next) => {
          const url = req.url?.split('?')[0] || '';
          if (url.endsWith('.wasm')) {
            const wasmPath = join(publicDir, url);
            console.log('WASM request:', url, '-> Looking for:', wasmPath);
            
            if (fs.existsSync(wasmPath)) {
              console.log('Found WASM file, serving with correct MIME type');
              res.setHeader('Content-Type', 'application/wasm');
              fs.createReadStream(wasmPath).pipe(res);
              return;
            } else {
              console.log('WASM file NOT found at:', wasmPath);
            }
          }
          next();
        });
      },
    },
  ],
  server: {
    host: '0.0.0.0',
    port: 5173,
    sourcemapIgnoreList: false,
  },
  esbuild: {
    sourcemap: true,
  },
  worker: {
    format: 'es',
    plugins: () => [],
    resolve: {
      alias: {
        '@skia-rs-wasm/common': resolve(__dirname, 'src/lib/common'),
        '@penpot-exporter/transformers': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/plugin-src/transformers'),
        '@penpot-exporter/types': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/ui-src/types'),
        '@penpot-exporter/lib/types': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/ui-src/lib/types'),
        '@penpot-exporter/common': resolve(__dirname, 'packages/penpot-exporter-figma-plugin/common')
      }
    }
  },
})