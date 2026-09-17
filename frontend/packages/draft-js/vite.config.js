import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["index.test.js"],
    environment: "node",
  },
});
