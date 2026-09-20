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

async function goToScreenTwo(viewer) {
  await viewer.goToViewer({
    fileId: interactionBlocksChildFileId,
    pageId: interactionBlocksChildPageId,
  });

  await viewer.page.getByRole("button", { name: "Next" }).click();
  await expect(viewer.page).toHaveURL(/index=1/);
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
    .locator("#viewer-section svg[class*='not-fixed'] g[cursor='pointer']")
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
    .locator("#viewer-section svg[class*='not-fixed'] g[cursor='pointer']")
    .first();
  await expect(hotspot).toBeVisible();

  const initialBox = await hotspot.boundingBox();
  if (!initialBox) {
    throw new Error("Interactive hotspot is not measurable");
  }

  await hotspot.click();
  await expect(page).toHaveURL(/index=0/);

  await page.getByRole("button", { name: "Next" }).click();
  await expect(page).toHaveURL(/index=1/);

  await decreaseZoom(page, 2);
  await expect
    .poll(async () => (await hotspot.boundingBox())?.width ?? 0)
    .toBeLessThan(initialBox.width);

  const zoomedOutBox = await hotspot.boundingBox();
  if (!zoomedOutBox) {
    throw new Error("Zoomed-out interactive hotspot is not measurable");
  }

  const oldCenter = {
    x: initialBox.x + initialBox.width / 2,
    y: initialBox.y + initialBox.height / 2,
  };
  const oldCenterIsVisible =
    oldCenter.x >= zoomedOutBox.x &&
    oldCenter.x <= zoomedOutBox.x + zoomedOutBox.width &&
    oldCenter.y >= zoomedOutBox.y &&
    oldCenter.y <= zoomedOutBox.y + zoomedOutBox.height;
  expect(oldCenterIsVisible).toBe(false);

  await hotspot.click();
  await expect(page).toHaveURL(/index=0/);

  await page.getByRole("button", { name: "Next" }).click();
  await expect(page).toHaveURL(/index=1/);

  await page.mouse.click(oldCenter.x, oldCenter.y);
  await expect(page).toHaveURL(/index=1/);
});
