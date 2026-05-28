import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

// Issue 8257: outer stroke of a rotated board is cropped in View Mode.
// The SVG viewport must be large enough to contain the stroke of a rotated board.
// A 55° rotated board (80×120) with a 20px outer stroke has a rotated bounding box
// of ~144×134px. The viewport must be at least ~202×192px (bbox + stroke margin).

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const rotatedBoardFileId = "aa5cc0bb-91ff-81b9-8004-77df9cd3edb1";
const rotatedBoardPageId = "aa5cc0bb-91ff-81b9-8004-77df9cd3edb2";

test("Viewer shows full outer stroke of a rotated board without clipping", async ({
  page,
}) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithRotatedBoardStroke();

  await viewer.goToViewer({
    fileId: rotatedBoardFileId,
    pageId: rotatedBoardPageId,
  });

  // Wait for the viewer SVG to be rendered
  const svg = page.locator("svg[class*='not-fixed']").first();
  await expect(svg).toBeVisible();

  // The SVG viewBox must be large enough to contain the rotated board plus its
  // 20px outer stroke. For a 55° rotated board (80×120):
  // - The axis-aligned bounding box of the rotated frame is ~144×134px
  // - The outer stroke (20px) adds sqrt(2)*20 ≈ 29px margin on each side
  // - So the viewport must be at least ~202×192px
  //
  // Before the fix, the viewer used the unrotated selrect (80×120) as the viewport,
  // causing the stroke to be heavily clipped.
  const viewBox = await svg.getAttribute("viewBox");
  const [, , vbWidth, vbHeight] = viewBox.split(" ").map(Number);

  // The unrotated selrect is 80×120. If the viewport is close to those dimensions,
  // the stroke is being clipped (bug). The fixed viewport should be much larger.
  expect(vbWidth).toBeGreaterThan(150);
  expect(vbHeight).toBeGreaterThan(150);
});
