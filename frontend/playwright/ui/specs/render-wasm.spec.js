import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test.skip("BUG 10867 - Crash when loading comments", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.showComments();
  await expect(
    workspacePage.rightSidebar.getByText("Show all comments"),
  ).toBeVisible();
});

test("BUG 13541 - Shows error page when WebGL context is lost", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();
  await workspacePage.waitForFirstRender();

  // Simulate a WebGL context loss by dispatching the event on the canvas
  await workspacePage.canvas.evaluate((canvas) => {
    const event = new Event("webglcontextlost", { cancelable: true });
    canvas.dispatchEvent(event);
  });

  await expect(
    page.getByText("Oops! The canvas context was lost"),
  ).toBeVisible();
  await expect(
    page.getByText("WebGL has stopped working"),
  ).toBeVisible();
  await expect(page.getByText("Reload page")).toBeVisible();
});

test.skip("BUG 12164 - Crash when trying to fetch a missing font", async ({
  page,
}) => {
  // mock fetching a missing font
  // FIXME: this is very hacky. I suspect something might be going on with
  // beicon, fetch or http/send and the way we handle requests failures that
  // make Plawyright stop execution of the JS thread immediately.
  await page.addInitScript(() => {
    // Override fetch specifically for the failing font route
    const originalFetch = window.fetch;
    window.fetch = (url, options) => {
      if (url.includes("/internal/gfonts/font/crimsonpro")) {
        console.log("Intercepting font request:", url);
        // Return a rejected promise that we handle
        return Promise.reject(new Error("Font not found (mocked)"));
      }
      return originalFetch.call(window, url, options);
    };
  });

  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile("render-wasm/get-file-12164.json");
  // FIXME: remove this once we fix the issue of downloading emoji fonts that are
  // not needed.
  await workspacePage.mockGoogleFont(
    "noto",
    "render-wasm/assets/notosansjpsubset.ttf",
  );

  await workspacePage.goToWorkspace({
    id: "2b7f0188-51a1-8193-8006-e05bad874e2e",
    pageId: "2b7f0188-51a1-8193-8006-e05bad87b74d",
  });

  await workspacePage.page.waitForTimeout(1000);
  await workspacePage.waitForFirstRender();

  await expect(
    workspacePage.page.getByText("Internal Error"),
  ).not.toBeVisible();
});
