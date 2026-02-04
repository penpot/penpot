/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import tsconfigPaths from 'vite-tsconfig-paths';

export default defineConfig({
  root: __dirname,
  server: {
    port: 4305,
    host: '0.0.0.0',
  },

  preview: {
    port: 4305,
    host: '0.0.0.0',
  },
  plugins: [tsconfigPaths()],
  build: {
    outDir: '../../dist/apps/create-palette-plugin',
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
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],

    reporters: ['default'],
    coverage: {
      reportsDirectory: '../../coverage/apps/create-palette-plugin',
      provider: 'v8',
    },
  },
});
