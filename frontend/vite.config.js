import { defineConfig } from "vite";
import { configDefaults } from "vitest/config";

import { resolve } from "path";

// https://vitejs.dev/config/
export default defineConfig({
  test: {
    exclude: [...configDefaults.exclude, "target/**", "resources/**"],
    environment: "jsdom",
  },
  resolve: {
    alias: {
      "@target": resolve(__dirname, "./target/storybook"),
    },
  },
});
