/// <reference types='vitest' />
import { defineConfig } from 'vite';

export default defineConfig({
  root: __dirname,
  cacheDir: '../../node_modules/.vite/e2e',
  test: {
    testTimeout: 20000,
    watch: false,
    globals: true,
    cache: {
      dir: '../node_modules/.vitest',
    },
    environment: 'happy-dom',
    include: ['src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],
    reporters: ['default'],
    coverage: {
      reportsDirectory: '../coverage/e2e',
      provider: 'v8',
    },
    setupFiles: ['dotenv/config'],
  },
});
