import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const multipleBoardsFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb0";
const multipleBoardsPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb3";

const interactionBlocksChildFileId = "cc000000-0000-0000-0000-000000000001";
const interactionBlocksChildPageId = "cc000000-0000-0000-0000-000000000002";

test("Child click interaction works when parent has mouse-leave interaction", async ({
  page,
}) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithInteractionBlocksChild();

  // Start at Screen2 (index=1): HighlightState (frame w/ mouse-leave) contains
  // HoverQuickToolArrowRight (rect w/ click → Screen3)
  await viewer.goToViewer({
    fileId: interactionBlocksChildFileId,
    pageId: interactionBlocksChildPageId,
  });

  // Click Next to go to Screen2 (index=1 in the 3-board sequence)
  await viewer.page.getByRole("button", { name: "Next" }).click();
  await expect(viewer.page).toHaveURL(/index=1/);

  // The child shape (HoverQuickToolArrowRight) has a click interaction.
  // Its shape-container g element has cursor="pointer" set via SVG attribute.
  // The parent (HighlightState) only has mouse-leave, so cursor is not "pointer".
  const childShapeContainer = viewer.page
    .locator('svg.main_ui_viewer_interactions__not-fixed g[cursor="pointer"]')
    .first();

  await expect(childShapeContainer).toBeVisible();

  // Clicking on the child should navigate to Screen3.
  // Bug (before fix): parent's interaction overlay rect is rendered on top and blocks this click.
  // Note: Screen3 is at index=0 in the viewer (frames sorted by Z-index, highest first).
  await childShapeContainer.click();

  await expect(viewer.page).toHaveURL(/index=0/, { timeout: 5000 });
});

test("Navigate with arrows", async ({ page }) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithMultipleBoards(viewer);

  await viewer.goToViewer({
    fileId: multipleBoardsFileId,
    pageId: multipleBoardsPageId,
  });

  const nextButton = viewer.page.getByRole("button", {
    name: "Next",
  });
  await nextButton.click();
  await expect(viewer.page).toHaveURL(/&index=1/);
});
