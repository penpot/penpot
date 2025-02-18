import { defineConfig, devices } from "@playwright/test";

/**
 * Read environment variables from file.
 * https://github.com/motdotla/dotenv
 */
// require('dotenv').config();

/**
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: "./playwright",
  /* Run tests in files in parallel */
  fullyParallel: true,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Opt out of parallel tests by default; can be overriden with --workers */
  workers: 1,
  /* Timeout for expects (longer in CI) */
  expect: {
    timeout: process.env.CI ? 20000 : 5000,
  },
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: "html",
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    /* Base URL to use in actions like `await page.goto('/')`. */
    baseURL: "http://localhost:3000",

    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: "on-first-retry",

    locale: "en-US",

    permissions: ["clipboard-write", "clipboard-read"],
  },

  /* Configure projects for major browsers */
  projects: [
    {
      name: "default",
      use: { ...devices["Desktop Chrome"] },
      testDir: "./playwright/ui/specs",
    },
    {
      name: "ds",
      use: { ...devices["Desktop Chrome"] },
      testDir: "./playwright/ui/visual-specs",
      expect: {
        toHaveScreenshot: { maxDiffPixelRatio: 0.005 },
      },
    },
  ],

  /* Run your local dev server before starting the tests */
  webServer: {
    timeout: 2 * 60 * 1000,
    command: "yarn run e2e:server",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
  },
});
