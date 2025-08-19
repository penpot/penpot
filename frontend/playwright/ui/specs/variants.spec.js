import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-variants.json");
});

const setupVariantsFile = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "get-team?id=*",
    "workspace/get-team-variants.json",
  );

  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-not-empty.json",
  );
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
    pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
  });
};

const setupVariantsFileWithVariant = async (workspacePage) => {
  await setupVariantsFile(workspacePage);

  await workspacePage.clickLeafLayer("Rectangle");
  await workspacePage.page.keyboard.press("Control+k");
  await workspacePage.page.keyboard.press("Control+k");
};

const findVariant = async (workspacePage, num_variant) => {
  const container = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Rectangle") })
    .filter({ has: workspacePage.page.getByTestId("icon-component") })
    .nth(num_variant);

  const variant1 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Value 1") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .nth(num_variant);

  const variant2 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Value 2") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .nth(num_variant);

  return {
    container: container,
    variant1: variant1,
    variant2: variant2,
  };
};

const validateVariant = async (variant) => {
  //The variant container exists and is visible
  await expect(variant.container).toBeVisible();

  //The variants exists and are visible
  await expect(variant.variant1).toBeVisible();
  await expect(variant.variant2).toBeVisible();

  // variant1 and variant2 are items inside the childs of variant_container
  const parent_id = "children-" + (await variant.container.getAttribute("id"));
  await expect(variant.variant1.locator("xpath=..")).toHaveAttribute(
    "data-testid",
    parent_id,
  );
  await expect(variant.variant2.locator("xpath=..")).toHaveAttribute(
    "data-testid",
    parent_id,
  );
};

test("User creates a variant", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  await workspacePage.clickLeafLayer("Rectangle");

  const variant = await findVariant(workspacePage, 0);
  // The variant is valid
  await validateVariant(variant);

  // Extra validators
  await variant.container.click();

  const variants = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .all();

  // There are exactly two variants
  expect(variants.length).toBe(2);

  // The design tab shows the variant properties
  await expect(
    workspacePage.page.getByTitle("Property 1: Value 1, Value 2"),
  ).toBeVisible();
});

test("User duplicates a variant container", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant container
  await variant.container.click();

  //Duplicate the variant container
  await workspacePage.page.keyboard.press("Control+d");

  const variant_original = await findVariant(workspacePage, 1); // On duplicate, the new item is the first
  const variant_duplicate = await findVariant(workspacePage, 0);

  // Expand the layers
  await variant_duplicate.container.getByRole("button").first().click();

  // The variants are valid
  await validateVariant(variant_original);
  await validateVariant(variant_duplicate);
});

test("User copy paste a variant container", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant container
  await variant.container.click();

  //Copy the variant container
  await workspacePage.page.keyboard.press("Control+c");

  //Paste the variant container
  await workspacePage.clickAt(500, 500);
  await workspacePage.page.keyboard.press("Control+v");

  const variant_original = await findVariant(workspacePage, 0);
  const variant_duplicate = await findVariant(workspacePage, 1);

  // Expand the layers
  await variant_duplicate.container.getByRole("button").first().click();

  // The variants are valid
  await validateVariant(variant_original);
  await validateVariant(variant_duplicate);
});

test("User cut paste a variant container", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant container
  await variant.container.click();

  //Cut the variant container
  await workspacePage.page.keyboard.press("Control+x");

  //Paste the variant container
  await workspacePage.clickAt(500, 500);
  await workspacePage.page.keyboard.press("Control+v");

  const variant_pasted = await findVariant(workspacePage, 0);

  // Expand the layers
  await variant_pasted.container.getByRole("button").first().click();

  // The variants are valid
  await validateVariant(variant_pasted);
});

test("[Bugfixing] User cut paste a variant container into a board, and undo twice", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  //Create a board
  await workspacePage.boardButton.click();
  await workspacePage.clickWithDragViewportAt(500, 500, 100, 100);
  await workspacePage.clickAt(495, 495);
  const board = await workspacePage.rootShape.locator("Board");

  // Select the variant container
  await variant.container.click();

  //Cut the variant container
  await workspacePage.page.keyboard.press("Control+x");

  //Select the board
  await workspacePage.clickLeafLayer("Board");

  //Paste the variant container inside the board
  await workspacePage.page.keyboard.press("Control+v");

  //Undo twice
  await workspacePage.page.keyboard.press("Control+z");
  await workspacePage.page.keyboard.press("Control+z");

  const variant_after_undo = await findVariant(workspacePage, 0);

  // The variants are valid
  await validateVariant(variant_after_undo);
});

test("User copy paste a variant", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant1
  await variant.variant1.click();

  //Cut the variant
  await workspacePage.page.keyboard.press("Control+c");

  //Paste the variant
  await workspacePage.clickAt(500, 500);
  await workspacePage.page.keyboard.press("Control+v");

  const copy = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Rectangle") })
    .filter({ has: workspacePage.page.getByTestId("icon-component-copy") });

  //The copy exists and is visible
  await expect(copy).toBeVisible();
});

test("User cut paste a variant outside the container", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant1
  await variant.variant1.click();

  //Cut the variant
  await workspacePage.page.keyboard.press("Control+x");

  //Paste the variant
  await workspacePage.clickAt(500, 500);
  await workspacePage.page.keyboard.press("Control+v");

  const component = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Rectangle / Value 1") })
    .filter({ has: workspacePage.page.getByTestId("icon-component") });

  //The component exists and is visible
  await expect(component).toBeVisible();
});

test("User drag and drop a variant outside the container", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Drag and drop the variant
  await workspacePage.clickWithDragViewportAt(350, 400, 0, 200);

  const component = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Rectangle / Value 1") })
    .filter({ has: workspacePage.page.getByTestId("icon-component") });

  //The component exists and is visible
  await expect(component).toBeVisible();
});

test("User cut paste a component inside a variant", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  //Create a component
  await workspacePage.ellipseShapeButton.click();
  await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
  await workspacePage.clickLeafLayer("Ellipse");
  await workspacePage.page.keyboard.press("Control+k");

  //Cut the component
  await workspacePage.page.keyboard.press("Control+x");

  //Paste the component inside the variant
  await variant.container.click();
  await workspacePage.page.keyboard.press("Control+v");

  const variant3 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Ellipse") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .first();

  //The new variant exists and is visible
  await expect(variant3).toBeVisible();
});

test("User cut paste a component with path inside a variant", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  //Create a component
  await workspacePage.ellipseShapeButton.click();
  await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
  await workspacePage.clickLeafLayer("Ellipse");
  await workspacePage.page.keyboard.press("Control+k");

  //Rename the component
  await workspacePage.layers.getByText("Ellipse").dblclick();
  await workspacePage.page
    .getByTestId("layer-item")
    .getByRole("textbox")
    .pressSequentially("button / hover");
  await workspacePage.page.keyboard.press("Enter");

  //Cut the component
  await workspacePage.page.keyboard.press("Control+x");

  //Paste the component inside the variant
  await variant.container.click();
  await workspacePage.page.keyboard.press("Control+v");

  const variant3 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("button, hover") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .first();

  //The new variant exists and is visible
  await expect(variant3).toBeVisible();
});

test("User drag and drop a component with path inside a variant", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  //Create a component
  await workspacePage.ellipseShapeButton.click();
  await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
  await workspacePage.clickLeafLayer("Ellipse");
  await workspacePage.page.keyboard.press("Control+k");

  //Rename the component
  await workspacePage.layers.getByText("Ellipse").dblclick();
  await workspacePage.page
    .getByTestId("layer-item")
    .getByRole("textbox")
    .pressSequentially("button / hover");
  await workspacePage.page.keyboard.press("Enter");

  //Drag and drop the component the component
  await workspacePage.clickWithDragViewportAt(510, 510, 0, -200);

  const variant3 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("button, hover") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .first();

  //The new variant exists and is visible
  await expect(variant3).toBeVisible();
});

test("User cut paste a variant into another container", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  // Create anothe variant
  await workspacePage.ellipseShapeButton.click();
  await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
  await workspacePage.clickLeafLayer("Ellipse");
  await workspacePage.page.keyboard.press("Control+k");
  await workspacePage.page.keyboard.press("Control+k");

  const variant_origin = await findVariant(workspacePage, 1);
  const variant_target = await findVariant(workspacePage, 0);

  // Select the variant1
  await variant_origin.variant1.click();

  //Cut the variant
  await workspacePage.page.keyboard.press("Control+x");

  //Paste the variant
  await workspacePage.layers.getByText("Ellipse").first().click();
  await workspacePage.page.keyboard.press("Control+v");

  const variant3 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Value 1, rectangle") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .first();

  //The new variant exists and is visible
  await expect(variant3).toBeVisible();
});
