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
  await workspace.mockFileMediaAsset(
    [
      "1ebcea38-f1bf-8101-8006-4c8fd68e7c84",
      "1ebcea38-f1bf-8101-8006-4c8f579da49c",
    ],
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
  await workspace.mockFileMediaAsset(
    [
      "202c1104-9385-81d3-8006-5074e4682cac",
      "202c1104-9385-81d3-8006-5074c50339b6",
      "202c1104-9385-81d3-8006-507560ce29e3",
    ],
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

test("Renders a file with mutliple strokes", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-multiple-strokes.json");

  await workspace.goToWorkspace({
    id: "c0939f58-37bc-805d-8006-51cc78297208",
    pageId: "c0939f58-37bc-805d-8006-51cc78297209",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with shapes with multiple fills", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-multiple-fills.json");

  await workspace.goToWorkspace({
    id: "c0939f58-37bc-805d-8006-51cd3a51c255",
    pageId: "c0939f58-37bc-805d-8006-51cd3a51c256",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

// TODO: update the screenshots for this test once Taiga #11325 is fixed
// https://tree.taiga.io/project/penpot/task/11325
test("Renders shapes taking into account blend modes", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-blend-modes.json");

  await workspace.goToWorkspace({
    id: "c0939f58-37bc-805d-8006-51cdf8e18e76",
    pageId: "c0939f58-37bc-805d-8006-51cdf8e18e77",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders shapes with exif rotated images fills and strokes", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockFileMediaAsset(
    [
      "27270c45-35b4-80f3-8006-63a39cf292e7",
      "27270c45-35b4-80f3-8006-63a41d147866",
      "27270c45-35b4-80f3-8006-63a43dc4984b",
      "27270c45-35b4-80f3-8006-63a3ea82557f",
    ],
    "render-wasm/assets/landscape.jpg",
  );
  await workspace.mockGetFile(
    "render-wasm/get-file-shapes-exif-rotated-fills.json",
  );

  await workspace.goToWorkspace({
    id: "27270c45-35b4-80f3-8006-63a3912bdce8",
    pageId: "27270c45-35b4-80f3-8006-63a3912bdce9",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Updates canvas background", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text.json");

  await workspace.goToWorkspace({
    id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
    pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
  });
  await workspace.waitForFirstRender({ hideUI: false });

  const canvasBackgroundInput = workspace.page.getByRole("textbox", {
    name: "Color",
  });
  await canvasBackgroundInput.fill("FABADA");
  await workspace.page.keyboard.press("Enter");

  // can't hide UI cause this will trigger a re-render
  // await workspace.hideUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with blurs applied to any kind of shape", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-blurs.json");

  await workspace.goToWorkspace({
    id: "aa0a383a-7553-808a-8006-ae1237b52cf9",
    pageId: "aa0a383a-7553-808a-8006-ae160ba8bd86",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});
