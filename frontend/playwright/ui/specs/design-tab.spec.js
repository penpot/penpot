import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

const multipleConstraintsFileId = `03bff843-920f-81a1-8004-756365e1eb6a`;
const multipleConstraintsPageId = `03bff843-920f-81a1-8004-756365e1eb6b`;
const multipleAttributesFileId = `1795a568-0df0-8095-8004-7ba741f56be2`;
const multipleAttributesPageId = `1795a568-0df0-8095-8004-7ba741f56be3`;

const setupFileWithMultipeConstraints = async (workspace) => {
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "design/get-file-multiple-constraints.json",
  );
  await workspace.mockRPC(
    "get-file-object-thumbnails?file-id=*",
    "design/get-file-object-thumbnails-multiple-constraints.json",
  );
  await workspace.mockRPC(
    "get-file-fragment?file-id=*",
    "design/get-file-fragment-multiple-constraints.json",
  );
};

const setupFileWithMultipeAttributes = async (workspace) => {
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "design/get-file-multiple-attributes.json",
  );
  await workspace.mockRPC(
    "get-file-object-thumbnails?file-id=*",
    "design/get-file-object-thumbnails-multiple-attributes.json",
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

    const constraintVDropdown = workspace.page.getByTestId(
      "constraint-v-select",
    );
    await expect(constraintVDropdown).toContainText("Mixed");
    const constraintHDropdown = workspace.page.getByTestId(
      "constraint-h-select",
    );
    await expect(constraintHDropdown).toContainText("Mixed");

    expect(false);
  });
});

test.describe("Multiple shapes attributes", () => {
  test("User selects multiple shapes with sames fills, strokes, shadows and blur", async ({
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

    await expect(workspace.page.getByTestId("add-fill")).toBeVisible();
    await expect(workspace.page.getByTestId("add-stroke")).toBeVisible();
    await expect(workspace.page.getByTestId("add-shadow")).toBeVisible();
    await expect(workspace.page.getByTestId("add-blur")).toBeVisible();
  });

  test("User selects multiple shapes with different fills, strokes, shadows and blur", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await setupFileWithMultipeAttributes(workspace);
    await workspace.goToWorkspace({
      fileId: multipleAttributesFileId,
      pageId: multipleAttributesPageId,
    });

    await workspace.clickLeafLayer("Ellipse");
    await workspace.clickLeafLayer("Rectangle", { modifiers: ["Shift"] });

    await expect(workspace.page.getByTestId("add-fill")).toBeHidden();
    await expect(workspace.page.getByTestId("add-stroke")).toBeHidden();
    await expect(workspace.page.getByTestId("add-shadow")).toBeHidden();
    await expect(workspace.page.getByTestId("add-blur")).toBeHidden();
  });
});

test("BUG 7760 - Layout losing properties when changing parents", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-7760.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-fragment-7760.json",
  );
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

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

test("BUG 9061 - Group blur visibility toggle icon not updating", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "design/get-file-9061.json");
  await workspace.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "design/get-file-fragment-9061.json",
  );
  await workspace.mockRPC("update-file?id=*", "design/update-file-9061.json");

  await workspace.goToWorkspace({
    fileId: "61cfa81d-8cb2-81df-8005-8f3005841116",
    pageId: "61cfa81d-8cb2-81df-8005-8f3005841117",
  });

  await workspace.clickLeafLayer("Group");

  const blurButton = workspace.page.getByRole("button", {
    name: "Toggle blur",
  });
  const blurIcon = blurButton.locator("svg use");
  await expect(blurIcon).toHaveAttribute("href", "#icon-shown");

  await blurButton.click();
  await expect(blurIcon).toHaveAttribute("href", "#icon-hide");
});

test("BUG 9543 - Layout padding inputs not showing 'mixed' when needed", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "design/get-file-9543.json");
  await workspace.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "design/get-file-fragment-9543.json",
  );
  await workspace.mockRPC("update-file?id=*", "design/update-file-9543.json");

  await workspace.goToWorkspace({
    fileId: "525a5d8b-028e-80e7-8005-aa6cad42f27d",
    pageId: "525a5d8b-028e-80e7-8005-aa6cad42f27e",
  });

  await workspace.clickLeafLayer("Board");
  let toggle = workspace.page.getByRole("button", {
    name: "Show 4 sided padding options",
  });

  await toggle.click();
  await workspace.page.getByLabel("Top padding").fill("10");
  await toggle.click();

  await expect(workspace.page.getByLabel("Vertical padding")).toHaveValue("");
  await expect(workspace.page.getByLabel("Vertical padding")).toHaveAttribute(
    "placeholder",
    "Mixed",
  );
});
