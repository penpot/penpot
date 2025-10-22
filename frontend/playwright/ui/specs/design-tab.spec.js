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

test.describe("Shape attributes", () => {
  test("Cannot add a new fill when the limit has been reached", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.mockConfigFlags(["enable-feature-render-wasm"]);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "design/get-file-fills-limit.json");

    await workspace.goToWorkspace({
      fileId: "d2847136-a651-80ac-8006-4202d9214aa7",
      pageId: "d2847136-a651-80ac-8006-4202d9214aa8",
    });

    await workspace.clickLeafLayer("Rectangle");

    await workspace.page.getByTestId("add-fill").click();
    await expect(
      workspace.page.getByRole("button", { name: "#B1B2B5" }),
    ).toHaveCount(8);

    await expect(workspace.page.getByTestId("add-fill")).toBeDisabled();
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

test("BUG 11177 - Font size input not showing 'mixed' when needed", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "design/get-file-11177.json");

  await workspace.goToWorkspace({
    fileId: "b3e5731a-c295-801d-8006-3fc33c3b1b13",
    pageId: "b3e5731a-c295-801d-8006-3fc33c3b1b14",
  });

  await workspace.clickLeafLayer("Ipsum");
  await workspace.clickLeafLayer("Lorem", { modifiers: ["Shift"] });

  const fontSizeInput = workspace.page.getByLabel("Font size");

  await expect(fontSizeInput).toHaveValue("");
  await expect(fontSizeInput).toHaveAttribute("placeholder", "Mixed");
});

test("BUG 12287 Fix identical text fills not being added/removed", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "design/get-file-12287.json");

  await workspace.goToWorkspace({
    fileId: "4bdef584-e28a-8155-8006-f3f8a71b382e",
    pageId: "4bdef584-e28a-8155-8006-f3f8a71b382f",
  });

  await workspace.clickLeafLayer("Lorem ipsum");

  const addFillButton = workspace.page.getByRole("button", {
    name: "Add fill",
  });

  await addFillButton.click();
  await addFillButton.click();
  await addFillButton.click();
  await addFillButton.click();

  await expect(
    workspace.page.getByRole("button", { name: "#B1B2B5" }),
  ).toHaveCount(4);

  await workspace.page
    .getByRole("button", { name: "Remove color" })
    .first()
    .click();

  await expect(
    workspace.page.getByRole("button", { name: "#B1B2B5" }),
  ).toHaveCount(3);
});

test("BUG 12384 - Export crashing when exporting a board", async ({ page }) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "design/get-file-12384.json");

  let hasExportRequestBeenIntercepted = false;
  await workspace.page.route("**/api/export", (route) => {
    if (hasExportRequestBeenIntercepted) {
      route.continue();
      return;
    }

    hasExportRequestBeenIntercepted = true;
    const payload = route.request().postData();
    const parsedPayload = JSON.parse(payload);

    expect(parsedPayload["~:exports"]).toHaveLength(1);
    expect(parsedPayload["~:exports"][0]["~:file-id"]).toBe(
      "~ufa6ce865-34dd-80ac-8006-fe0dab5539a7",
    );
    expect(parsedPayload["~:exports"][0]["~:page-id"]).toBe(
      "~ufa6ce865-34dd-80ac-8006-fe0dab5539a8",
    );

    route.fulfill({
      status: 200,
      contentType: "application/json",
      response: {},
    });
  });

  await workspace.goToWorkspace({
    fileId: "fa6ce865-34dd-80ac-8006-fe0dab5539a7",
    pageId: "fa6ce865-34dd-80ac-8006-fe0dab5539a8",
  });

  await workspace.clickLeafLayer("Board");

  let exportRequest = workspace.page.waitForRequest("**/api/export");

  await workspace.rightSidebar
    .getByRole("button", { name: "Export 1 element" })
    .click();

  await exportRequest;
});
