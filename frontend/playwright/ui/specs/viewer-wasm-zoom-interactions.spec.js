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
      canvasBuffer: { width: canvas.width, height: canvas.height },
      canvasClient: { width: canvas.clientWidth, height: canvas.clientHeight },
      dpr: window.devicePixelRatio,
      viewBox: svg.getAttribute("viewBox"),
    };
  });
}

async function hasViewerCanvasPixels(page, expectedFrameId) {
  return page.evaluate((frameId) => {
    const canvas = document.querySelector("#viewer-section canvas");
    // draw-bitmap! assigns this id only after the current frame is blitted.
    if (
      !canvas ||
      canvas.id !== `screenshot-${frameId}` ||
      canvas.width === 0 ||
      canvas.height === 0
    ) {
      return false;
    }

    try {
      const context = canvas.getContext("2d");
      if (!context) {
        return false;
      }

      const pixels = context.getImageData(
        0,
        0,
        canvas.width,
        canvas.height,
      ).data;
      for (let index = 0; index < pixels.length; index += 4) {
        if (
          pixels[index] ||
          pixels[index + 1] ||
          pixels[index + 2] ||
          pixels[index + 3]
        ) {
          return true;
        }
      }
    } catch {
      return false;
    }

    return false;
  }, expectedFrameId);
}

async function waitForViewerRender(page, expectedFrameId) {
  await expect
    .poll(
      async () => {
        try {
          const bounds = await getViewerLayerBounds(page);
          return (
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
  await page.getByTitle("Zoom").click();
  const decreaseButton = page.getByTitle("Zoom").locator("button").first();

  for (let i = 0; i < count; i++) {
    await decreaseButton.click();
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
  }

  expect(zoomedOut.canvasBuffer.width).toBeGreaterThan(0);
  expect(zoomedOut.canvasBuffer.height).toBeGreaterThan(0);
  expect(zoomedOut.canvasClient.width).toBeGreaterThan(0);
  expect(zoomedOut.canvasClient.height).toBeGreaterThan(0);
  expect(
    zoomedOut.canvasBuffer.width / zoomedOut.canvasClient.width,
  ).toBeCloseTo(zoomedOut.dpr, 1);
  expect(
    zoomedOut.canvasBuffer.height / zoomedOut.canvasClient.height,
  ).toBeCloseTo(zoomedOut.dpr, 1);
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

  const oldVisiblePoint = designPointToCanvas(atOne, interactiveDesignPoint);
  await page.mouse.click(oldVisiblePoint.x, oldVisiblePoint.y);
  await expect(page).toHaveURL(/index=1/);
});
