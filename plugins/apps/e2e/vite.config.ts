/// <reference types="vitest/config" />
import { defineConfig } from 'vite';

export default defineConfig({
  root: __dirname,
  test: {
    testTimeout: 20000,
    watch: false,
    globals: true,
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
