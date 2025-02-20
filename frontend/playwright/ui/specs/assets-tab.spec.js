import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

test("User adds a library and its automatically selected in the color palette", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "link-file-to-library",
    "workspace/link-file-to-library.json",
  );
  await workspacePage.mockRPC(
    "unlink-file-from-library",
    "workspace/unlink-file-from-library.json",
  );
  await workspacePage.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-libraries-non-empty.json",
  );

  await workspacePage.goToWorkspace();

  // Add Testing library 1
  await workspacePage.clickColorPalette();
  await workspacePage.clickAssets();
  // Now the get-file call should return a library
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-library.json");
  await workspacePage.openLibrariesModal();
  await workspacePage.clickLibrary("Testing library 1");
  await workspacePage.closeLibrariesModal();

  await expect(
    workspacePage.palette.getByRole("button", { name: "test-color-187cd5" }),
  ).toBeVisible();

  // Remove Testing library 1
  await workspacePage.openLibrariesModal();
  await workspacePage.clickLibrary("Testing library 1");
  await workspacePage.closeLibrariesModal();

  await expect(
    workspacePage.palette.getByText(
      "There are no color styles in your library yet",
    ),
  ).toBeVisible();
});

test("BUG 10090 - Local library should be expanded by default", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace();

  await workspacePage.clickAssets();

  await expect(workspacePage.sidebar.getByText("Local library")).toBeVisible();
  await expect(workspacePage.sidebar.getByText("Components")).toBeVisible();
  await expect(workspacePage.sidebar.getByText("Colors")).toBeVisible();
  await expect(workspacePage.sidebar.getByText("Typographies")).toBeVisible();
});
