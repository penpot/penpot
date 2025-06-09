import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
  ]);
});

test("Renders a file with basic shapes, boards and groups", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-shapes-groups-boards.json");

  await workspace.goToWorkspace({
    id: "53a7ff09-2228-81d3-8006-4b5eac177245",
    pageId: "53a7ff09-2228-81d3-8006-4b5eac177246",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with solid, gradient and image fills", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockAsset(
    "1ebcea38-f1bf-8101-8006-4c8fd68e7c84",
    "render-wasm/assets/penguins.jpg",
  );
  await workspace.mockAsset(
    "1ebcea38-f1bf-8101-8006-4c8f579da49c",
    "render-wasm/assets/penguins.jpg",
  );
  await workspace.mockGetFile("render-wasm/get-file-shapes-fills.json");

  await workspace.goToWorkspace({
    id: "1ebcea38-f1bf-8101-8006-4c8ec4a9bffe",
    pageId: "1ebcea38-f1bf-8101-8006-4c8ec4a9bfff",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with strokes", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockAsset(
    "202c1104-9385-81d3-8006-5074e4682cac",
    "render-wasm/assets/penguins.jpg",
  );
  await workspace.mockAsset(
    "202c1104-9385-81d3-8006-5074c50339b6",
    "render-wasm/assets/penguins.jpg",
  );
  await workspace.mockAsset(
    "202c1104-9385-81d3-8006-507560ce29e3",
    "render-wasm/assets/penguins.jpg",
  );
  await workspace.mockGetFile("render-wasm/get-file-shapes-strokes.json");

  await workspace.goToWorkspace({
    id: "202c1104-9385-81d3-8006-507413ff2c99",
    pageId: "202c1104-9385-81d3-8006-507413ff2c9a",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});
