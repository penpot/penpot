import { defineConfig } from "vitest/config";

export default defineConfig({
  envPrefix: [],
  test: {
    setupFiles: ["./test/setup.ts"],
  },
});
