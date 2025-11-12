import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-feature-text-editor-v2"]);
});

test.skip("BUG 11552 - Apply styles to the current caret", async ({ page }) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-11552.json");
  await workspace.mockRPC(
    "update-file?id=*",
    "text-editor/update-file-11552.json",
  );

  await workspace.goToWorkspace({
    fileId: "238a17e0-75ff-8075-8006-934586ea2230",
    pageId: "238a17e0-75ff-8075-8006-934586ea2231",
  });
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.clickLeafLayer("Lorem ipsum");

  const fontSizeInput = workspace.rightSidebar.getByRole("textbox", {
    name: "Font Size",
  });
  await expect(fontSizeInput).toBeVisible();

  await workspace.page.keyboard.press("Enter");
  await workspace.page.keyboard.press("ArrowRight");

  await fontSizeInput.fill("36");

  await workspace.clickLeafLayer("Lorem ipsum");

  // display Mixed placeholder
  await expect(fontSizeInput).toHaveValue("");
  await expect(fontSizeInput).toHaveAttribute("placeholder", "Mixed");
});
