import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against a running Penpot backend (a devenv instance), as
 * opposed to the default suite, which mocks every RPC. Point
 * `PENPOT_E2E_URL` at the instance, e.g. `https://localhost:13449` for ws1:
 *
 *   PENPOT_E2E_URL=https://localhost:13449 pnpm run test:e2e:real-backend
 *
 * The backend must run with the `demo-users` flag (the devenv default): each
 * test creates its own throwaway account.
 */
export default defineConfig({
  testDir: "./playwright/real-backend",
  outputDir: "./test-results/real-backend",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 3 * 60 * 1000,
  expect: { timeout: 30000 },
  reporter: "list",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: process.env.PENPOT_E2E_URL ?? "https://localhost:3449",
    ignoreHTTPSErrors: true,
    locale: "en-US",
    viewport: { width: 1920, height: 1080 },
    trace: "retain-on-failure",
    video: "retain-on-failure",
  },
});
