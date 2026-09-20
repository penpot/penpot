import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-available-viewer-wasm",
  ]);
});

const interactionBlocksChildFileId = "cc000000-0000-0000-0000-000000000001";
const interactionBlocksChildPageId = "cc000000-0000-0000-0000-000000000002";
const interactionBlocksChildScreenTwoFrameId =
  "cc000000-0002-0000-0000-000000000001";
// Screen2 starts at (500, 0). HoverQuickToolArrowRight is at
// (780, 190)-(840, 230) in page coordinates, so its center is (310, 210)
// in Screen2's viewBox. The WASM hotspot expands this rect by 1px to
// [279, 341] x [189, 231].
const interactiveDesignPoint = { x: 310, y: 210 };

async function getViewerLayerBounds(page) {
  return page.evaluate(() => {
    const canvas = document.querySelector("#viewer-section canvas");
    const layer = canvas?.parentElement;
    const svg = layer?.querySelector("svg");

    if (!canvas || !layer || !svg) {
      throw new Error("WASM viewer layers are not rendered");
    }

    const readRect = (element) => {
      const { left, top, width, height } = element.getBoundingClientRect();
      return { left, top, width, height };
    };

    return {
      wrapper: readRect(layer),
      canvas: readRect(canvas),
      svg: readRect(svg),
      canvasId: canvas.id,
      canvasBuffer: { width: canvas.width, height: canvas.height },
      canvasClient: { width: canvas.clientWidth, height: canvas.clientHeight },
      dpr: window.devicePixelRatio,
      viewBox: svg.getAttribute("viewBox"),
    };
  });
}

async function waitForViewerRender(page, expectedFrameId) {
  await expect
    .poll(
      async () => {
        try {
          const bounds = await getViewerLayerBounds(page);
          return (
            bounds.canvasId === `screenshot-${expectedFrameId}` &&
            bounds.canvas.width > 0 &&
            bounds.canvas.height > 0 &&
            bounds.svg.width > 0 &&
            bounds.svg.height > 0 &&
            (await hasViewerCanvasPixels(page, expectedFrameId))
          );
        } catch {
          return false;
        }
      },
      { timeout: 30000 },
    )
    .toBe(true);
}

function designPointToCanvas(bounds, point) {
  const [viewBoxX, viewBoxY, viewBoxWidth, viewBoxHeight] = bounds.viewBox
    .split(/[ ,]+/)
    .map(Number);

  return {
    x:
      bounds.canvas.left +
      ((point.x - viewBoxX) / viewBoxWidth) * bounds.canvas.width,
    y:
      bounds.canvas.top +
      ((point.y - viewBoxY) / viewBoxHeight) * bounds.canvas.height,
  };
}

async function goToScreenTwo(viewer) {
  await viewer.goToViewer({
    fileId: interactionBlocksChildFileId,
    pageId: interactionBlocksChildPageId,
  });

  await viewer.page.getByRole("button", { name: "Next" }).click();
  await expect(viewer.page).toHaveURL(/index=1/);
  await waitForViewerRender(
    viewer.page,
    interactionBlocksChildScreenTwoFrameId,
  );
}

async function decreaseZoom(page, count) {
  const zoom = page.getByTitle("Zoom");
  await zoom.click();
  const zoomOut = page.getByRole("button", { name: "Zoom out" });

  for (let i = 0; i < count; i++) {
    await zoomOut.click();
  }
}

test("WASM viewer layers keep the same CSS bounds while zooming", async ({
  page,
}) => {
  const viewer = new ViewerPage(page);
  await viewer.setupFileWithInteractionBlocksChild();
  await goToScreenTwo(viewer);

  const hotspot = page
    .locator("#viewer-section svg g[style*='cursor: pointer']")
    .first();
  await expect(hotspot).toBeVisible();

  const atOne = await getViewerLayerBounds(page);
  await decreaseZoom(page, 2);
  await expect
    .poll(async () => (await getViewerLayerBounds(page)).canvas.width)
    .toBeLessThan(atOne.canvas.width);

  const zoomedOut = await getViewerLayerBounds(page);
  for (const bounds of [atOne, zoomedOut]) {
    expect(bounds.dpr).toBeGreaterThan(1);
    for (const layer of ["wrapper", "canvas", "svg"]) {
      expect(Math.abs(bounds[layer].left - bounds.svg.left)).toBeLessThan(0.5);
      expect(Math.abs(bounds[layer].top - bounds.svg.top)).toBeLessThan(0.5);
      expect(Math.abs(bounds[layer].width - bounds.svg.width)).toBeLessThan(
        0.5,
      );
      expect(Math.abs(bounds[layer].height - bounds.svg.height)).toBeLessThan(
        0.5,
      );
    }
    expect(bounds.canvasBuffer.width).toBeGreaterThan(0);
    expect(bounds.canvasBuffer.height).toBeGreaterThan(0);
    expect(bounds.canvasClient.width).toBeGreaterThan(0);
    expect(bounds.canvasClient.height).toBeGreaterThan(0);
    expect(bounds.canvasBuffer.width / bounds.canvasClient.width).toBeCloseTo(
      bounds.dpr,
      1,
    );
    expect(bounds.canvasBuffer.height / bounds.canvasClient.height).toBeCloseTo(
      bounds.dpr,
      1,
    );
  }
  expect(zoomedOut.viewBox).toBeTruthy();
});

test("WASM hotspots follow the visible element after zooming out", async ({
  page,
}) => {
  const viewer = new ViewerPage(page);
  await viewer.setupFileWithInteractionBlocksChild();
  await goToScreenTwo(viewer);

  const hotspot = page
    .locator("#viewer-section svg g[style*='cursor: pointer']")
    .first();
  await expect(hotspot).toBeVisible();

  const atOne = await getViewerLayerBounds(page);
  await page.mouse.click(
    ...Object.values(designPointToCanvas(atOne, interactiveDesignPoint)),
  );
  await expect(page).toHaveURL(/index=0/);

  await page.getByRole("button", { name: "Next" }).click();
  await expect(page).toHaveURL(/index=1/);
  await waitForViewerRender(page, interactionBlocksChildScreenTwoFrameId);

  await decreaseZoom(page, 2);
  await expect
    .poll(async () => (await getViewerLayerBounds(page)).canvas.width)
    .toBeLessThan(atOne.canvas.width);
  await waitForViewerRender(page, interactionBlocksChildScreenTwoFrameId);

  const zoomedOut = await getViewerLayerBounds(page);
  await page.mouse.click(
    ...Object.values(designPointToCanvas(zoomedOut, interactiveDesignPoint)),
  );
  await expect(page).toHaveURL(/index=0/);

  await page.getByRole("button", { name: "Next" }).click();
  await expect(page).toHaveURL(/index=1/);
  await waitForViewerRender(page, interactionBlocksChildScreenTwoFrameId);
});
