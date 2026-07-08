/// <reference types="vitest/config" />
import { defineConfig } from 'vite';

export default defineConfig({
  root: __dirname,
  server: {
    port: 4202,
    host: '0.0.0.0',
    cors: true,
  },
  preview: {
    port: 4202,
    host: '0.0.0.0',
    cors: true,
  },
  resolve: {
    tsconfigPaths: true,
  },
  build: {
    outDir: '../../dist/apps/plugin-api-test-suite',
    // Keep false so `watch` rebuilds don't wipe the sibling tests-bundle.js /
    // headless.js outputs. The `build` script passes --emptyOutDir for a clean
    // one-shot build.
    emptyOutDir: false,
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
