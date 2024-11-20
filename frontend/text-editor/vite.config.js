import { resolve } from "node:path";
import { defineConfig } from "vite";
import { coverageConfigDefaults } from 'vitest/config'

export default defineConfig({
  resolve: {
    alias: {
      "~": resolve("."),
    },
  },
  build: {
    minify: false,
    sourcemap: true,
    lib: {
      entry: "editor/TextEditor.js",
      name: "TextEditor",
      fileName: "TextEditor",
      formats: ["es"],
    },
    terserOptions: {
      compress: true,
      mangle: true,
    },
  },
  test: {
    coverage: {
      enabled: true,
      exclude: ["main.js", "**/scripts/**", ...coverageConfigDefaults.exclude],
    },
    poolOptions: {
      threads: {
        singleThread: true,
      },
    },
    environmentOptions: {
      jsdom: {
        resources: "usable",
      },
    },
    browser: {
      name: "chromium",
      provider: "playwright",
    },
    exclude: ["main.js", "**/scripts/**", "**/node_modules/**", "**/dist/**"],
  },
});
