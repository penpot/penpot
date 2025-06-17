import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
  ]);
});

test("Renders a file with texts", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text.json");

  await workspace.goToWorkspace({
    id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
    pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
  });
  await workspace.waitForFirstRender();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Updates a text font", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text.json");

  await workspace.goToWorkspace({
    id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
    pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
  });
  await workspace.waitForFirstRender();
  await workspace.clickLeafLayer("this is a text");
  const fontStyle = workspace.page.getByTitle("Font Style");
  await fontStyle.click();
  const boldOption = fontStyle.getByText("bold").first();
  await boldOption.click();
  await expect(workspace.canvas).toHaveScreenshot();
});