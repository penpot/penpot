/// <reference types="vitest/config" />
import { defineConfig } from 'vite';

export default defineConfig({
  root: __dirname,
  test: {
    watch: false,
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],
    reporters: ['default'],
    coverage: {
      reportsDirectory: '../coverage/lorem-ipsum-plugin',
      provider: 'v8',
    },
  },
});
