import { defineConfig, devices } from "@playwright/test";
import { platform } from "os";

/**
 * Read environment variables from file.
 * https://github.com/motdotla/dotenv
 */
// require('dotenv').config();

const userAgent = platform === 'darwin' ?
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" :
  undefined;

/**
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: "./playwright",
  outputDir: './test-results',
  /* Run tests in files in parallel */
  fullyParallel: true,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Opt out of parallel tests by default; can be overriden with --workers */
  workers: 1,
  /* Timeout for expects (longer in CI) */

  timeout: 80000,
  expect: {
    timeout: process.env.CI ? 40000 : 5000,
  },

  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: "list",
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    /* Base URL to use in actions like `await page.goto('/')`. */
    baseURL: "http://localhost:3000",

    locale: "en-US",

    permissions: ["clipboard-write", "clipboard-read"],
  },

  /* Configure projects for major browsers */
  projects: [
    {
      name: "default",
      testDir: "./playwright/ui/specs",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1920, height: 1080 }, // Add custom viewport size
        video: 'retain-on-failure',
        trace: 'retain-on-failure',
        userAgent,
      },
      snapshotPathTemplate: "{testDir}/{testFilePath}-snapshots/{arg}.png",
      expect: {
        toHaveScreenshot: {
          maxDiffPixelRatio: 0.001,
        },
      },
    },
    {
      name: "ds",
      use: { ...devices["Desktop Chrome"] },
      testDir: "./playwright/ui/visual-specs",
      expect: {
        toHaveScreenshot: { maxDiffPixelRatio: 0.005 },
      },
    },
    {
      name: "render-wasm",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1920, height: 1080 }, // Add custom viewport size
        deviceScaleFactor: 2,
      },
      testDir: "./playwright/ui/render-wasm-specs",
      snapshotPathTemplate: "{testDir}/{testFilePath}-snapshots/{arg}.png",
      timeout: 2 * 60 * 1000,
      expect: {
        timeout: process.env.CI ? 20000 : 10000,
        toHaveScreenshot: {
          maxDiffPixelRatio: 0.001,
        },
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
