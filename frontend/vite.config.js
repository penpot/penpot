/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import { configDefaults } from "vitest/config";
import { resolve } from "path";

import { playwright } from '@vitest/browser-playwright'

// https://vitejs.dev/config/
import path from "node:path";
import { storybookTest } from "@storybook/addon-vitest/vitest-plugin";
const dirname = import.meta.dirname;

// More info at: https://storybook.js.org/docs/next/writing-tests/integrations/vitest-addon
export default defineConfig({
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
            provider: playwright({
              launchOptions: {
                slowMo: 100,
                timeout: 160000,
              },
              actionTimeout: 5000,
            }),
            instances: [
              {browser: "chromium"},
            ],
          },
        },
      },
    ],
  },
  resolve: {
    alias: {
      "@target": resolve(dirname, "./target/storybook"),
      "@public": resolve(dirname, "./resources/public/js/"),
    },
  },
});
