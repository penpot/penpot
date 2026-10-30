import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";
import { Clipboard } from "../../helpers/Clipboard";

test.beforeEach(async ({ page, context }) => {
  await Clipboard.enable(context, Clipboard.Permission.ALL);

  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-variants"]);
});

test.afterEach(async ({ context }) => {
  await context.clearPermissions();
});

// One `setupEmptyFile` only: a second call registered `get-team?id=*` again and,
// because the route added last wins, it cancelled the variants team fixture
// that used to sit between the two calls. These tests have always run with the
// default team, so that fixture is gone and the effective mocks stay the same.
const setupVariantsFile = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-not-empty.json",
  );
  await workspacePage.mockRPC(
    "update-file-v2?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
    pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
  });
};

// A shape is only a component once its layer row shows the component icon; this
// is the condition the fixed three seconds used to stand in for.
const waitForComponentIcon = async (workspacePage, name) => {
  await expect(
    workspacePage.layers
      .getByTestId("layer-row")
      .filter({ hasText: name })
      .getByTestId("icon-component"),
  ).toBeVisible();
};

const setupVariantsFileWithVariant = async (workspacePage) => {
  await setupVariantsFile(workspacePage);

  await workspacePage.selectLayer("Rectangle");
  await workspacePage.page.keyboard.press("ControlOrMeta+k");
  await waitForComponentIcon(workspacePage, "Rectangle");
  await workspacePage.page.keyboard.press("ControlOrMeta+k");

  // The second shortcut turns the main component into a variant container and
  // its children into variants; wait for those rows instead of a fixed delay.
  const variant = await findVariant(workspacePage, 0);
  await expect(variant.variant1).toBeVisible();
  await expect(variant.variant2).toBeVisible();
};

const findVariant = async (workspacePage, index) => {
  const container = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Rectangle" })
    .filter({ has: workspacePage.page.getByTestId("icon-component") })
    .nth(index);

  const variant1 = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Value 1" })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .nth(index);

  const variant2 = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Value 2" })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .nth(index);

  await container.waitFor();

  return {
    container: container,
    variant1: variant1,
    variant2: variant2,
  };
};

const findVariantNoWait = (workspacePage, index) => {
  const container = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Rectangle" })
    .filter({ has: workspacePage.page.getByTestId("icon-component") })
    .nth(index);

  const variant1 = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Value 1" })
    .nth(index);

  const variant2 = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Value 2" })
    .nth(index);

  return {
    container: container,
    variant1: variant1,
    variant2: variant2,
  };
};

// A container keeps its children rows in the tree only while it is expanded, and
// clicking the toggle of an already expanded container collapses it: a duplicate
// or a paste can inherit the expanded state, so check it before clicking.
const expandContainer = async (container) => {
  const toggle = container.getByTestId("toggle-content");
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
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
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  await workspacePage.clickLeafLayer("Rectangle");

  const variant = await findVariant(workspacePage, 0);
  // The variant is valid
  await validateVariant(variant);

  // Extra validators
  await workspacePage.selectRow(variant.container);

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
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant container; Ctrl+D duplicates whatever is selected.
  await workspacePage.selectRow(variant.container);

  //Duplicate the variant container
  await workspacePage.page.keyboard.press("ControlOrMeta+d");

  // Assert the second container appears before building the locators, so a slow
  // duplication fails here with a count, not with a long wait inside a helper.
  const containerRows = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Rectangle" })
    .filter({ has: page.getByTestId("icon-component") });
  await expect(containerRows).toHaveCount(2);

  // The copy is inserted above the original: index 0 is the duplicate and index
  // 1 is the original.
  const variant_original = await findVariant(workspacePage, 1);
  const variant_duplicate = await findVariant(workspacePage, 0);

  // Expand the layers; the original may have lost its expansion in the meantime.
  await expandContainer(variant_duplicate.container);
  await expandContainer(variant_original.container);

  // The variants are valid
  await validateVariant(variant_original);
  await validateVariant(variant_duplicate);
});

test("User copy paste a variant container", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  // Access to the read/write clipboard necesary for this functionality
  await setupVariantsFileWithVariant(workspacePage);
  await workspacePage.mockRPC(
    /create-file-object-thumbnail.*/,
    "workspace/create-file-object-thumbnail.json",
  );

  const variant = findVariantNoWait(workspacePage, 0);

  // Copy the variant container itself. `clickLeafLayer` matches the text as a
  // substring and clicks the first match, and this tree also holds the shapes
  // named Rectangle inside the variants, so it can select the wrong one.
  await workspacePage.selectRow(variant.container);
  await workspacePage.copy("keyboard");

  // Deselect and place the pointer without clicking: a click here could select a
  // shape and send the paste inside it.
  await page.keyboard.press("Escape");
  await workspacePage.viewport.hover({ position: { x: 400, y: 400 } });
  await workspacePage.paste("keyboard");

  const variants = workspacePage.layers.getByText("Rectangle");
  await expect(variants).toHaveCount(2);

  // As with the duplicate, the pasted container is inserted above the original.
  const variantDuplicate = findVariantNoWait(workspacePage, 0);
  const variantOriginal = findVariantNoWait(workspacePage, 1);

  // Expand the layers
  await variantDuplicate.container.waitFor();
  await expandContainer(variantDuplicate.container);

  await validateVariant(variantOriginal);
  await validateVariant(variantDuplicate);
});

test("User cut paste a variant container", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant container; Ctrl+X cuts whatever is selected.
  await workspacePage.selectRow(variant.container);

  //Cut the variant container
  await workspacePage.cut("keyboard");

  // Deselect and place the pointer in empty canvas without clicking, so the paste
  // cannot land inside a shape that the click selected.
  await page.keyboard.press("Escape");
  await workspacePage.viewport.hover({ position: { x: 500, y: 500 } });
  await workspacePage.paste("keyboard");

  const variantPasted = await findVariant(workspacePage, 0);

  // Expand the layers
  await expandContainer(variantPasted.container);

  // The variants are valid
  await validateVariant(variantPasted);
});

test("User cut paste a variant container into a board, and undo twice", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  //Create a board
  await workspacePage.boardButton.click();
  // NOTE: this board should not intersect the existing variants, otherwise
  // this test is flaky
  await workspacePage.clickWithDragViewportAt(200, 200, 100, 100);
  await workspacePage.clickAt(495, 495);
  const board = await workspacePage.rootShape.locator("Board");

  // Select the variant container
  // await variant.container.click();
  await workspacePage.clickLeafLayer("Rectangle");

  //Cut the variant container
  await workspacePage.cut("keyboard");
  await expect(variant.container).not.toBeVisible();

  //Select the board
  await workspacePage.clickLeafLayer("Board");

  //Paste the variant container inside the board
  await workspacePage.paste("keyboard");
  await expect(variant.container).toBeVisible();

  //Undo twice
  await workspacePage.page.keyboard.press("ControlOrMeta+z");

  await expect(variant.container).not.toBeVisible();

  await workspacePage.page.keyboard.press("ControlOrMeta+z");
  await expect(variant.container).toBeVisible();

  const variantAfterUndo = await findVariant(workspacePage, 0);

  // The variants are valid
  await validateVariant(variantAfterUndo);
});

test("User copy paste a variant", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant1
  await workspacePage.selectRow(variant.variant1);

  // Copy the variant
  await workspacePage.copy("keyboard");

  // Deselect and place the pointer in empty canvas, for the same reason as above.
  await page.keyboard.press("Escape");
  await workspacePage.viewport.hover({ position: { x: 500, y: 500 } });
  await workspacePage.paste("keyboard");

  const copy = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Rectangle") })
    .filter({ has: workspacePage.page.getByTestId("icon-component-copy") });

  //The copy exists and is visible
  await expect(copy).toBeVisible();
});

test("User cut paste a variant outside the container", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Select the variant1
  await workspacePage.selectRow(variant.variant1);

  //Cut the variant
  await workspacePage.cut("keyboard");

  // Deselect and place the pointer in empty canvas, so the paste does not land
  // inside a shape that the click could have selected.
  await page.keyboard.press("Escape");
  await workspacePage.viewport.hover({ position: { x: 500, y: 500 } });
  await workspacePage.paste("keyboard");

  const component = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Rectangle / Value 1") })
    .filter({ has: workspacePage.page.getByTestId("icon-component") });

  //The component exists and is visible
  await expect(component).toBeVisible();
});

test("User drag and drop a variant outside the container", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  // Drag and drop the variant
  // FIXME: to make this test more resilient, we should get the bounding box of the Value 1 variant
  // and use it to calculate the target position
  await workspacePage.clickWithDragViewportAt(600, 500, 0, 300);

  await expect(
    workspacePage.layers.getByText("Rectangle / Value 1"),
  ).toBeVisible();
});

test("User cut paste a component inside a variant", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  //Create a component
  await workspacePage.selectToolFromFlyout(workspacePage, {
    triggerToolName: "Rectangle (R)",
    targetToolName: "Ellipse (E)",
  });
  await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
  await workspacePage.clickLeafLayer("Ellipse");
  await workspacePage.page.keyboard.press("ControlOrMeta+k");

  //Cut the component
  await workspacePage.cut("keyboard");

  //Paste the component inside the variant
  await workspacePage.selectRow(variant.container);
  await workspacePage.paste("keyboard");

  const variant3 = await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Ellipse") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .first();

  //The new variant exists and is visible
  await expect(variant3).toBeVisible();
});

test("User takes a component with a path into a variant", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  const variant = await findVariant(workspacePage, 0);

  const variantNamed = (name) =>
    workspacePage.layers
      .getByTestId("layer-row")
      .filter({ has: workspacePage.page.getByText(name) })
      .filter({ has: workspacePage.page.getByTestId("icon-variant") });

  const createNamedComponent = async () => {
    await workspacePage.selectToolFromFlyout(workspacePage, {
      triggerToolName: "Rectangle (R)",
      targetToolName: "Ellipse (E)",
    });
    await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
    await workspacePage.clickLeafLayer("Ellipse");
    await workspacePage.page.keyboard.press("ControlOrMeta+k");
    await waitForComponentIcon(workspacePage, "Ellipse");

    await workspacePage.layers.getByText("Ellipse").dblclick();
    await workspacePage.page
      .getByTestId("layer-item")
      .getByRole("textbox")
      .pressSequentially("button / hover");
    await workspacePage.page.keyboard.press("Enter");
    await expect(
      workspacePage.layers.getByText("button / hover"),
    ).toBeVisible();
  };

  // The drag moves the component away from where it was created, so the second
  // one can be drawn at the same coordinates. The row count is what proves each
  // step put its own component into the container.
  await test.step("drag and drop", async () => {
    await createNamedComponent();
    await workspacePage.clickWithDragViewportAt(510, 510, 200, 0);
    await expect(variantNamed("button, hover")).toHaveCount(1);
  });

  await test.step("cut and paste", async () => {
    const before = await variantNamed("button, hover").count();

    await createNamedComponent();
    await workspacePage.cut("keyboard");
    await workspacePage.selectRow(variant.container);
    await workspacePage.paste("keyboard");

    await expect(variantNamed("button, hover")).toHaveCount(before + 1);
  });
});

test("User cut paste a variant into another container", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await setupVariantsFileWithVariant(workspacePage);

  // Create anothe variant
  await workspacePage.selectToolFromFlyout(workspacePage, {
    triggerToolName: "Rectangle (R)",
    targetToolName: "Ellipse (E)",
  });
  await workspacePage.clickWithDragViewportAt(500, 500, 20, 20);
  await workspacePage.clickLeafLayer("Ellipse");
  await workspacePage.page.keyboard.press("ControlOrMeta+k");
  await workspacePage.page.keyboard.press("ControlOrMeta+k");

  const variantOrigin = await findVariantNoWait(workspacePage, 1);

  // Select the variant1
  await variantOrigin.variant1.waitFor();
  await variantOrigin.variant1.click();
  await variantOrigin.variant1.click();

  //Cut the variant
  await workspacePage.cut("keyboard");

  //Paste the variant
  await workspacePage.layers.getByText("Ellipse").first().click();
  await workspacePage.paste("keyboard");

  const variant3 = workspacePage.layers
    .getByTestId("layer-row")
    .filter({ has: workspacePage.page.getByText("Value 1, rectangle") })
    .filter({ has: workspacePage.page.getByTestId("icon-variant") })
    .first();

  //The new variant exists and is visible
  await expect(variant3).toBeVisible();
});
