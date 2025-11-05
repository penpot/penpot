/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import { configDefaults } from "vitest/config";
import react from "@vitejs/plugin-react";
import { copyFileSync } from "fs";

import { resolve } from "path";

const copyCssPlugin = () => ({
  name: "copy-css",
  closeBundle: () => {
    try {
      copyFileSync(
        "./ts/dist/frontend.css",
        "./resources/public/css/ts-style.css",
      );
    } catch (e) {
      console.log("Error copying css file", e);
    }
  },
});

// https://vitejs.dev/config/
import path from "node:path";
import { fileURLToPath } from "node:url";
import { storybookTest } from "@storybook/addon-vitest/vitest-plugin";
const dirname =
  typeof __dirname !== "undefined"
    ? __dirname
    : path.dirname(fileURLToPath(import.meta.url));

// More info at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon
export default defineConfig({
  plugins: [react(), copyCssPlugin()],
  test: {
    exclude: [...configDefaults.exclude, "target/**", "resources/**"],
    environment: "jsdom",
    projects: [
      {
        extends: true,
        plugins: [
          // The plugin will run tests for the stories defined in your Storybook config
          // See options at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon#storybooktest
          storybookTest({
            configDir: path.join(dirname, ".storybook"),
          }),
        ],
        test: {
          name: "storybook",
          browser: {
            enabled: true,
            headless: true,
            provider: "playwright",
            instances: [
              {
                browser: "chromium",
              },
            ],
          },
          setupFiles: [".storybook/vitest.setup.ts"],
        },
      },
    ],
  },
  resolve: {
    alias: {
      "@target": resolve(__dirname, "./target/storybook"),
      "@public": resolve(__dirname, "./resources/public/js/"),
    },
  },
  build: {
    outDir: './ts/dist/',
    emptyOutDir: true,
    lib: {
      entry: './ts/src/index.tsx',
      fileName: () => `index.js`,
      formats: ['es'],
    },
    rollupOptions: {
      external: ['react', 'react-dom'],
      output: {
        globals: {
          react: 'React',
          "react-dom": "ReactDOM",
        },
      },
    }
  },
});
