/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import tsconfigPaths from 'vite-tsconfig-paths';

export default defineConfig({
  root: __dirname,
  server: {
    port: 4201,
    host: '0.0.0.0',
  },

  preview: {
    port: 4201,
    host: '0.0.0.0',
  },

  plugins: [tsconfigPaths()],

  build: {
    outDir: '../../dist/apps/example-styles',
    reportCompressedSize: true,
    commonjsOptions: {
      transformMixedEsModules: true,
    },
  },

  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],

    reporters: ['default'],
    coverage: {
      reportsDirectory: '../../coverage/apps/example-styles',
      provider: 'v8',
    },
  },
});
