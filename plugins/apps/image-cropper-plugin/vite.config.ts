/// <reference types="vitest/config" />
import { defineConfig } from 'vite';

export default defineConfig({
  root: __dirname,
  server: {
    port: 4210,
    host: '0.0.0.0',
  },
  preview: {
    port: 4210,
    host: '0.0.0.0',
  },

  resolve: {
    tsconfigPaths: true,
  },
  build: {
    outDir: '../../dist/apps/image-cropper-plugin',
    reportCompressedSize: true,
    commonjsOptions: {
      transformMixedEsModules: true,
    },
    rollupOptions: {
      input: {
        plugin: 'src/plugin.ts',
        index: './index.html',
      },
      output: {
        entryFileNames: '[name].js',
      },
    },
  },
});
