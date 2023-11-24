import { defineConfig } from "vite";
import { resolve } from "path";

// https://vitejs.dev/config/
export default defineConfig({
  resolve: {
    alias: {
      "@target": resolve(__dirname, "./target/storybook"),
    },
  },
});
