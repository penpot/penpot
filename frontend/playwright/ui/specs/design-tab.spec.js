import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

const multipleConstraintsFileId = `03bff843-920f-81a1-8004-756365e1eb6a`;
const multipleConstraintsPageId = `03bff843-920f-81a1-8004-756365e1eb6b`;

const setupFileWithMultipeConstraints = async (workspace) => {
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "design/get-file-multiple-constraints.json");
  await workspace.mockRPC(
    "get-file-object-thumbnails?file-id=*",
    "design/get-file-object-thumbnails-multiple-constraints.json",
  );
  await workspace.mockRPC(
    "get-file-fragment?file-id=*",
    "design/get-file-fragment-multiple-constraints.json",
  );
};

test.describe("Constraints", () => {
  test("Constraint dropdown shows 'Mixed' when multiple layers are selected with different constraints", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await setupFileWithMultipeConstraints(workspace);
    await workspace.goToWorkspace({
      fileId: multipleConstraintsFileId,
      pageId: multipleConstraintsPageId,
    });

    await workspace.clickToggableLayer("Board");
    await workspace.clickLeafLayer("Ellipse");
    await workspace.clickLeafLayer("Rectangle", { modifiers: ["Shift"] });

    const constraintVDropdown = workspace.page.getByTestId("constraint-v-select");
    await expect(constraintVDropdown).toContainText("Mixed");
    const constraintHDropdown = workspace.page.getByTestId("constraint-h-select");
    await expect(constraintHDropdown).toContainText("Mixed");

    expect(false);
  });
});

test("BUG 7760 - Layout losing properties when changing parents", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-7760.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-fragment-7760.json",
  );
  await workspacePage.mockRPC("update-file?id=*", "workspace/update-file-create-rect.json");

  await workspacePage.goToWorkspace({
    fileId: "cd90e028-326a-80b4-8004-7cdec16ffad5",
    pageId: "cd90e028-326a-80b4-8004-7cdec16ffad6",
  });

  // Select the flex board and drag it into the other container board
  await workspacePage.clickLeafLayer("Flex Board");

  // Move the first board into the second
  const hAuto = await workspacePage.page.getByTitle("Fit content (Horizontal)");
  const vAuto = await workspacePage.page.getByTitle("Fit content (Vertical)");

  await expect(vAuto.locator("input")).toBeChecked();
  await expect(hAuto.locator("input")).toBeChecked();

  await workspacePage.moveSelectionToShape("Container Board");

  // The first board properties should still be auto width/height
  await expect(vAuto.locator("input")).toBeChecked();
  await expect(hAuto.locator("input")).toBeChecked();
});
