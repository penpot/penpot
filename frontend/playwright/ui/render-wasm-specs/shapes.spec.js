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
  await workspace.waitForFirstRenderWithoutUI();

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
    "render-wasm/assets/pattern-thumbnail.png", // FIXME: get real thumbnail
  );
  await workspace.mockGetFile("render-wasm/get-file-shapes-fills.json");

  await workspace.goToWorkspace({
    id: "1ebcea38-f1bf-8101-8006-4c8ec4a9bffe",
    pageId: "1ebcea38-f1bf-8101-8006-4c8ec4a9bfff",
  });
  await workspace.waitForFirstRenderWithoutUI();

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
    "render-wasm/assets/pattern-thumbnail.png", // FIXME: get real thumbnail
  );
  await workspace.mockGetFile("render-wasm/get-file-shapes-strokes.json");

  await workspace.goToWorkspace({
    id: "202c1104-9385-81d3-8006-507413ff2c99",
    pageId: "202c1104-9385-81d3-8006-507413ff2c9a",
  });
  await workspace.waitForFirstRenderWithoutUI();

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
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with shapes with multiple fills", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-multiple-fills.json");
  await workspace.mockFileMediaAsset(
    ["c0939f58-37bc-805d-8006-51cda84a405a"],
    "render-wasm/assets/penguins.jpg",
    "render-wasm/assets/pattern-thumbnail.png", // FIXME: get real thumbnail
  );

  await workspace.goToWorkspace({
    id: "c0939f58-37bc-805d-8006-51cd3a51c255",
    pageId: "c0939f58-37bc-805d-8006-51cd3a51c256",
  });
  await workspace.waitForFirstRenderWithoutUI();

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
  await workspace.waitForFirstRenderWithoutUI();

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
    "render-wasm/assets/pattern-thumbnail.png", // FIXME: get real thumbnail
  );
  await workspace.mockGetFile(
    "render-wasm/get-file-shapes-exif-rotated-fills.json",
  );

  await workspace.goToWorkspace({
    id: "27270c45-35b4-80f3-8006-63a3912bdce8",
    pageId: "27270c45-35b4-80f3-8006-63a3912bdce9",
  });
  await workspace.waitForFirstRenderWithoutUI();

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
  await workspace.waitForFirstRender();

  const canvasBackgroundInput = workspace.page.getByRole("textbox", {
    name: "Color",
  });
  await canvasBackgroundInput.fill("FABADA");
  await workspace.page.keyboard.press("Enter");
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with blurs applied to any kind of shape", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-blurs.json");
  await workspace.mockFileMediaAsset(
    [
      "aa0a383a-7553-808a-8006-ae13a3c575eb",
      "aa0a383a-7553-808a-8006-ae13c84d6e3a",
      "aa0a383a-7553-808a-8006-ae131157fc26",
    ],
    "render-wasm/assets/pattern.png",
    "render-wasm/assets/pattern-thumbnail.png", // FIXME: get real thumbnail
  );

  await workspace.goToWorkspace({
    id: "aa0a383a-7553-808a-8006-ae1237b52cf9",
    pageId: "aa0a383a-7553-808a-8006-ae160ba8bd86",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with shadows applied to any kind of shape", async ({
  page,
}) => { 
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-shadows.json");

  await workspace.goToWorkspace({
    id: "9502081a-e1a4-80bc-8006-c2b968723199",
    pageId: "9502081a-e1a4-80bc-8006-c2b96872319a",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with flex layouts and different directions", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-flex-layouts.json");

  await workspace.goToWorkspace({
    id: "31fe2e21-73e7-80f3-8007-73894fb58240",
    pageId: "02e9633d-4ce7-80da-8007-736558496fa8",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with a closed path shape with multiple segments using strokes and shadow", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-subpath-stroke-shadow.json");

  await workspace.goToWorkspace({
    id: "3f7c3cc4-556d-80fa-8006-da2505231c2b",
    pageId: "3f7c3cc4-556d-80fa-8006-da2505231c2c",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders solid shadows after select all and zoom to selected", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-solid-shadows.json");

  await workspace.goToWorkspace({
    id: "93113137-fe66-80fb-8007-99ca9fd96841",
    pageId: "93113137-fe66-80fb-8007-99ca9fd96842",
  });
  await workspace.waitForFirstRender();

  await workspace.viewport.click();
  await page.keyboard.press("ControlOrMeta+A");
  const previousRenderCount = await workspace.getRenderCount();
  await page.keyboard.press("f");
  await workspace.waitForNextRender(previousRenderCount);

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders strokes with solid shadows", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-solid-strokes-shadows.json");

  await workspace.goToWorkspace({
    id: "93113137-fe66-80fb-8007-99cfd5cbf361",
    pageId: "93113137-fe66-80fb-8007-99cfd5cbf362",
  });
  await workspace.waitForFirstRender();

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with paths and svg attrs", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-svg-attrs.json");

  await workspace.goToWorkspace({
    id: "4732f3e3-7a1a-807e-8006-ff76066e631d",
    pageId: "4732f3e3-7a1a-807e-8006-ff76066e631e",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with nested frames with inherited blur", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile(
    "render-wasm/get-file-frame-with-nested-blur.json",
  );

  await workspace.goToWorkspace({
    id: "58c5cc60-d124-81bd-8007-0ee4e5030609",
    pageId: "58c5cc60-d124-81bd-8007-0ee4e503060a",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with nested clipping frames", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile(
    "render-wasm/get-file-frame-nested-clipping.json",
  );

  await workspace.goToWorkspace({
    id: "44471494-966a-8178-8006-c5bd93f0fe72",
    pageId: "44471494-966a-8178-8006-c5bd93f0fe73",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders clipped frames with strokes correctly (no double painting)", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile(
    "render-wasm/get-file-frame-strokes-opacity.json",
  );

  await workspace.goToWorkspace({
    id: "3144ac7c-a5cc-80e8-8007-8bbb29a4e56e",
    pageId: "3144ac7c-a5cc-80e8-8007-8bbb29a510ac",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a clipped frame with a large blur drop shadow", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-large-blur-shadow.json");

  await workspace.goToWorkspace({
    id: "b4133204-a015-80ed-8007-192a65398b0c",
    pageId: "b4133204-a015-80ed-8007-192a65398b0d",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with solid, dotted, dashed and mixed stroke styles", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-stroke-styles.json");

  await workspace.goToWorkspace({
    id: "b888b894-3697-80d3-8006-51cc8a55c200",
    pageId: "b888b894-3697-80d3-8006-51cc8a55c210",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders shapes with multiple fills and blur", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-fill-blend-blurs.json");

  await workspace.goToWorkspace({
    id: "b15901d7-d46d-8056-8007-8d5e34fc1f0c",
    pageId: "b15901d7-d46d-8056-8007-8d5e34fc1f0d",
  });
  await workspace.waitForFirstRenderWithoutUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Keeps component visible when focusing after creating it", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "workspace/get-file-not-empty.json");
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspace.goToWorkspace({
    fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
    pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
  });
  await workspace.waitForFirstRender();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle");
  await page.keyboard.press("ControlOrMeta+k");

  const componentLayer = workspace.layers
    .getByTestId("layer-row")
    .filter({ has: page.getByTestId("icon-component") })
    .first();
  await expect(componentLayer).toBeVisible();
  await componentLayer.click();

  const previousRenderCount = await workspace.getRenderCount();
  await page.keyboard.press("f");
  await workspace.waitForNextRender(previousRenderCount);

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Check inner stroke artifacts", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-inner-strokes-artifacts.json");

  await workspace.goToWorkspace({
    id: "effcbebc-b8c8-802f-8007-9a0b2e2c863f",
    pageId: "effcbebc-b8c8-802f-8007-9a0b2e2c8640",
  });
  await workspace.waitForFirstRenderWithoutUI();

  const previousRenderCount = await workspace.getRenderCount();
  await page.keyboard.press("ControlOrMeta++");
  await workspace.waitForNextRender(previousRenderCount);

  // Stricter comparison: artifacts are very subtle
  await expect(workspace.canvas).toHaveScreenshot({
    maxDiffPixelRatio: 0,
    threshold: 0.1,
  });
});

test("No white seam at intersections of overlapping shapes with inner strokes", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-inner-stroke-overlap-seam.json");

  await workspace.goToWorkspace({
    id: "aaa00001-0001-0001-8007-000000000001",
    pageId: "aaa00001-0001-0001-8007-000000000002",
  });
  await workspace.waitForFirstRender();

  await workspace.viewport.click();
  await page.keyboard.press("ControlOrMeta+A");
  const previousRenderCount = await workspace.getRenderCount();
  await page.keyboard.press("f");
  await workspace.waitForNextRender(previousRenderCount);

  await workspace.hideUI();

  // Stricter comparison: white seam artifacts are very subtle
  await expect(workspace.canvas).toHaveScreenshot({
    maxDiffPixelRatio: 0,
    threshold: 0.1,
  });
});

test("Correct stroke closing at self-intersection of overlapping shapes with outer strokes", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-outer-stroke-overlap-seam.json");

  await workspace.goToWorkspace({
    id: "aaa00001-0001-0001-8007-000000000001",
    pageId: "aaa00001-0001-0001-8007-000000000002",
  });
  await workspace.waitForFirstRender();

  await workspace.viewport.click();
  await page.keyboard.press("ControlOrMeta+A");
  const previousRenderCount = await workspace.getRenderCount();
  await page.keyboard.press("f");
  await workspace.waitForNextRender(previousRenderCount);

  await workspace.hideUI();

  // Stricter comparison: white seam artifacts are very subtle
  await expect(workspace.canvas).toHaveScreenshot({
    maxDiffPixelRatio: 0,
    threshold: 0.1,
  });
});

test("BUG 13551 - Blurs affecting other elements", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-blurs-affecting-other-elements.json");

  await workspace.goToWorkspace({
    id: "effcbebc-b8c8-802f-8007-a7dc677169cd",
    pageId: "a5508528-5928-8008-8007-a7de9feef61bd",
  });
  await workspace.waitForFirstRenderWithoutUI();

  // Stricter comparison: blur is very subtle
  await expect(workspace.canvas).toHaveScreenshot({
    maxDiffPixelRatio: 0,
    threshold: 0.1,
  });
});

test("BUG 13610 - Huge inner strokes", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-huge-inner-strokes.json");

  await workspace.goToWorkspace({
    id: "effcbebc-b8c8-802f-8007-b11dd34fe190",
    pageId: "effcbebc-b8c8-802f-8007-b11dd34fe191",
  });
  await workspace.waitForFirstRenderWithoutUI();
  await expect(workspace.canvas).toHaveScreenshot();
});