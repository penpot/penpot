import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

/**
 * Helper function to setup workspace with frames for export testing
 */
async function setupWorkspaceWithFrames(workspacePage) {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-export-frames.json",
  );
  await workspacePage.mockRPC(
    "get-file-object-thumbnails?file-id=*",
    "workspace/get-file-object-thumbnails-blank.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*",
    "workspace/get-file-fragment-blank.json",
  );
  await workspacePage.goToWorkspace({
    fileId: "8d102c33-b98f-81f6-8006-fdd4ea9780d7",
    pageId: "8d102c33-b98f-81f6-8006-fdd4ea9780d8",
  });
}

// The "file" entry of the main menu has a stable id, so it does not depend on
// which text on the page happens to contain the word. "Export shapes" always
// renders, and it is the anchor that proves the submenu really opened: without
// it, every assertion about the frames entry would also pass on a closed menu.
async function openFileMenu(page) {
  await page.getByRole("button", { name: "Main menu" }).click();
  await page.locator("#file-menu-file").click();
  await expect(page.locator("#file-menu-export-shapes")).toBeVisible();
}

async function openExportFramesDialog(page) {
  await openFileMenu(page);
  await page.locator("#file-menu-export-frames").click();
  await expect(page.getByText("Export as PDF")).toBeVisible();
}

const cancelButton = (page) => page.getByRole("button", { name: "Cancel" });
const exportButton = (page) =>
  page.getByRole("button", { name: "Export", exact: true });

test.describe("Export frames to PDF", () => {
  test("The file menu hides the PDF export when the page has no frames", async ({
    page,
  }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await workspacePage.goToWorkspace();

    await openFileMenu(page);

    // The entry renders only when the page has frames, so it is absent from the
    // DOM rather than hidden; the visible anchor above is what makes this
    // assertion meaningful.
    await expect(page.locator("#file-menu-export-frames")).toHaveCount(0);
  });

  test("The export frames dialog lists the frames and lets the user deselect them", async ({
    page,
  }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    const boardOne = workspacePage.layerRows("Board 1");
    const boardTwo = workspacePage.layerRows("Board 2");

    // One dialog row per frame; they are the only buttons named after a board.
    const dialogRow = (name) => page.getByRole("button", { name });

    await test.step("the file menu offers the PDF export", async () => {
      await openFileMenu(page);

      // Frames exist, so the entry is offered even with nothing selected.
      await expect(page.locator("#file-menu-export-frames")).toBeVisible();
      await page.locator("#file-menu-export-frames").click();
      await expect(page.getByText("Export as PDF")).toBeVisible();
    });

    await test.step("no selection lists every frame", async () => {
      await expect(dialogRow("Board 1")).toBeVisible();
      await expect(dialogRow("Board 2")).toBeVisible();
      await expect(page.getByText("2 of 2 elements selected")).toBeVisible();
      await expect(exportButton(page)).toBeEnabled();

      await cancelButton(page).click();
      await expect(page.getByText("Export as PDF")).not.toBeVisible();
    });

    await test.step("a selected frame is the only row", async () => {
      await boardOne.click();
      await expect(boardOne).toHaveAttribute("aria-checked", "true");

      await openExportFramesDialog(page);
      await expect(page.getByText("1 of 1 elements selected")).toBeVisible();
      await expect(dialogRow("Board 1")).toBeVisible();
      await expect(dialogRow("Board 2")).toHaveCount(0);

      await cancelButton(page).click();
      await expect(page.getByText("Export as PDF")).not.toBeVisible();
    });

    await test.step("deselecting one frame keeps the export enabled", async () => {
      await boardOne.click();
      await boardTwo.click({ modifiers: ["Shift"] });
      await expect(boardOne).toHaveAttribute("aria-checked", "true");
      await expect(boardTwo).toHaveAttribute("aria-checked", "true");

      await openExportFramesDialog(page);
      await expect(page.getByText("2 of 2 elements selected")).toBeVisible();

      await dialogRow("Board 1").click();
      await expect(page.getByText("1 of 2 elements selected")).toBeVisible();
      await expect(exportButton(page)).toBeEnabled();

      await cancelButton(page).click();
      await expect(page.getByText("Export as PDF")).not.toBeVisible();
    });

    await test.step("deselecting every frame disables the export", async () => {
      await boardOne.click();
      await expect(boardOne).toHaveAttribute("aria-checked", "true");
      await expect(boardTwo).toHaveAttribute("aria-checked", "false");

      await openExportFramesDialog(page);
      await dialogRow("Board 1").click();
      await expect(page.getByText("0 of 1 elements selected")).toBeVisible();
      await expect(exportButton(page)).toBeDisabled();

      await cancelButton(page).click();
      await expect(page.getByText("Export as PDF")).not.toBeVisible();
    });
  });
});
