import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

const setupFileWithAssets = async (workspace) => {
  const fileId = "015fda4f-caa6-8103-8004-862a00dd4f31";
  const pageId = "015fda4f-caa6-8103-8004-862a00ddbe94";
  const fragments = {
    "015fda4f-caa6-8103-8004-862a9e4b4d4b":
      "assets/get-file-fragment-with-assets-components.json",
    "015fda4f-caa6-8103-8004-862a9e4ad279":
      "assets/get-file-fragmnet-with-assets-page.json",
  };

  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "assets/get-file-with-assets.json");

  for (const [id, fixture] of Object.entries(fragments)) {
    await workspace.mockRPC(
      `get-file-fragment?file-id=*&fragment-id=${id}`,
      fixture,
    );
  }

  return { fileId, pageId };
};

test("Shows the workspace correctly for a blank file", async ({ page }) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();

  await workspace.goToWorkspace();

  await expect(workspace.page).toHaveScreenshot();
});

test.describe("Design tab", () => {
  test("Shows the design tab when selecting a shape", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-not-empty.json");

    await workspace.goToWorkspace({
      fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
      pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
    });

    await workspace.clickLeafLayer("Rectangle");

    await expect(workspace.page).toHaveScreenshot();
  });

  test("Shows expanded sections of the design tab", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-not-empty.json");

    await workspace.goToWorkspace({
      fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
      pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
    });

    await workspace.clickLeafLayer("Rectangle");
    await workspace.rightSidebar.getByTestId("add-stroke").click();

    await expect(workspace.page).toHaveScreenshot();
  });
});

test.describe("Assets tab", () => {
  test("Shows the libraries modal correctly", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      "link-file-to-library",
      "workspace/link-file-to-library.json",
    );
    await workspace.mockRPC(
      "get-team-shared-files?team-id=*",
      "workspace/get-team-shared-libraries-non-empty.json",
    );

    await workspace.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await workspace.goToWorkspace();
    await workspace.clickAssets();
    await workspace.openLibrariesModal();
    await expect(workspace.page).toHaveScreenshot();

    await workspace.clickLibrary("Testing library 1");
    await expect(
      workspace.librariesModal.getByText("File library"),
    ).toBeVisible();
    await expect(workspace.page).toHaveScreenshot();
  });

  test("Shows the assets correctly", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    const { fileId, pageId } = await setupFileWithAssets(workspace);

    await workspace.goToWorkspace({ fileId, pageId });

    await workspace.clickAssets();
    await workspace.sidebar.getByRole("button", { name: "Components" }).click();
    await workspace.sidebar.getByRole("button", { name: "Colors" }).click();
    await workspace.sidebar
      .getByRole("button", { name: "Typographies" })
      .click();

    await expect(workspace.page).toHaveScreenshot();

    await workspace.sidebar.getByTitle("List view").click();

    await expect(workspace.page).toHaveScreenshot();
  });
});

test.describe("Palette", () => {
  test("Shows the bottom palette expanded and collapsed", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    const { fileId, pageId } = await setupFileWithAssets(workspace);

    await workspace.goToWorkspace({ fileId, pageId });

    await expect(workspace.page).toHaveScreenshot();

    await workspace.palette
      .getByRole("button", { name: "Typographies" })
      .click();
    await expect(
      workspace.palette.getByText("Source Sans Pro Regular"),
    ).toBeVisible();
    await expect(workspace.page).toHaveScreenshot();

    await workspace.palette
      .getByRole("button", { name: "Color Palette" })
      .click();
    await expect(
      workspace.palette.getByRole("button", { name: "#7798ff" }),
    ).toBeVisible();
  });
});
