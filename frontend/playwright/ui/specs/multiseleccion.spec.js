import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-token-input"]);
});

test("Multiselection shows the mixed value of every sidebar section", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-copy-paste.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-copy-paste-fragment.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "870f9f10-87b5-8137-8005-934804124660",
    pageId: "870f9f10-87b5-8137-8005-934804124661",
  });

  const measuresSection = workspacePage.rightSidebar.getByRole("region", {
    name: "shape-measures-section",
  });
  const fillSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Fill section",
  });
  const strokeSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Stroke section",
  });
  const widthInput = measuresSection.getByRole("textbox", {
    name: "Width",
    exact: true,
  });

  // Rows are checkboxes bound to the selection (`aria-checked`), so reading
  // that attribute replaces the 500 ms sleep of `clickLeafLayer`. A plain click
  // selects one shape and Shift extends the selection to the range; both are
  // asynchronous, so the helpers assert the whole state they need and not only
  // the row they clicked: a plain click on an already selected row changes
  // nothing visible on that row.
  const rows = workspacePage.layers.getByTestId("layer-row");
  const firstRow = rows.nth(0);
  const secondRow = rows.nth(1);

  // The rows flip before the sidebar re-renders for the new selection: the
  // options panel is throttled and the selection arrives through a deferred
  // render, so a value typed into that stale render is written to every shape
  // of the previous selection. After a multi-selection the stale `Width` is
  // still "Mixed", so this waits for the multi -> single transition only. A
  // change from one single shape to another needs its own wait on the value it
  // expects before editing.
  const waitForSingleShape = async () => {
    await expect(widthInput).not.toHaveAttribute("placeholder", "Mixed");
  };

  const selectOnly = async (row, other) => {
    await row.click();
    await expect(row).toBeChecked();
    await expect(other).not.toBeChecked();
    await waitForSingleShape();
  };

  const selectBoth = async (first, second) => {
    await first.click();
    await expect(first).toBeChecked();
    await expect(second).not.toBeChecked();
    await waitForSingleShape();

    await second.click({ modifiers: ["Shift"] });
    await expect(first).toBeChecked();
    await expect(second).toBeChecked();
  };

  await test.step("measures", async () => {
    await selectOnly(firstRow, secondRow);

    const heightInput = measuresSection.getByRole("textbox", {
      name: "Height",
      exact: true,
    });
    const xPosInput = measuresSection.getByRole("textbox", {
      name: "X axis",
      exact: true,
    });
    const yPosInput = measuresSection.getByRole("textbox", {
      name: "Y axis",
      exact: true,
    });

    await expect(widthInput).toHaveValue("360");
    await expect(heightInput).toHaveValue("53");
    await expect(xPosInput).toHaveValue("1094");
    await expect(yPosInput).toHaveValue("856");

    // The two shapes differ in every measure, so all four go blank.
    await selectBoth(firstRow, secondRow);
    await expect(widthInput).toHaveAttribute("placeholder", "Mixed");
    await expect(heightInput).toHaveAttribute("placeholder", "Mixed");
    await expect(xPosInput).toHaveAttribute("placeholder", "Mixed");
    await expect(yPosInput).toHaveAttribute("placeholder", "Mixed");
  });

  await test.step("fill", async () => {
    await selectOnly(firstRow, secondRow);

    await expect(fillSection).toBeVisible();
    await expect(fillSection.getByText(/Mixed/i)).not.toBeVisible();

    await selectBoth(firstRow, secondRow);
    await expect(fillSection.getByText("Mixed")).toBeVisible();
  });

  await test.step("stroke", async () => {
    await selectOnly(firstRow, secondRow);

    await expect(strokeSection).toBeVisible();
    await expect(strokeSection.getByText(/Mixed/i)).not.toBeVisible();

    await selectBoth(firstRow, secondRow);
    await expect(strokeSection.getByText("Mixed")).toBeVisible();
  });

  // The rotation step mutates the document, so it runs last: no earlier step
  // can see a shape whose rotation changed.
  await test.step("rotation", async () => {
    await selectOnly(secondRow, firstRow);

    const rotationInput = measuresSection.getByRole("textbox", {
      name: "Rotation",
      exact: true,
    });
    await expect(rotationInput).toBeVisible();

    await rotationInput.fill("45");
    await page.keyboard.press("Enter");
    await expect(rotationInput).toHaveValue("45");

    // Only one of the two rotated, so the grouped value differs.
    await firstRow.click({ modifiers: ["Shift"] });
    await expect(firstRow).toBeChecked();
    await expect(secondRow).toBeChecked();
    await expect(rotationInput).toHaveAttribute("placeholder", "Mixed");
  });
});

test("Multiselection of text and typographies", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/multiselection-typography.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "c7ce0794-0992-8105-8004-38f280443849",
    pageId: "1062e0a0-8fe0-80ae-8007-e70b4993f5f0",
  });

  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-empty.json",
  );

  const plainTextLayer = workspacePage.layers.getByTestId("layer-row").nth(5);
  const plainTextLayerTwo = workspacePage.layers
    .getByTestId("layer-row")
    .nth(2);
  const typographyTextLayerOne = workspacePage.layers
    .getByTestId("layer-row")
    .nth(7);
  const typographyTextLayerTwo = workspacePage.layers
    .getByTestId("layer-row")
    .nth(4);
  const tokenTypographyTextLayerOne = workspacePage.layers
    .getByTestId("layer-row")
    .nth(6);
  const tokenTypographyTextLayerTwo = workspacePage.layers
    .getByTestId("layer-row")
    .nth(3);
  const rectangleLayer = workspacePage.layers.getByTestId("layer-row").nth(1);
  const elipseLayer = workspacePage.layers.getByTestId("layer-row").nth(0);
  const textSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Text section",
  });
  // Select rectangle and elipse together
  await rectangleLayer.click();
  await elipseLayer.click({ modifiers: ["Control"] });
  await expect(textSection).not.toBeVisible();

  // Select plain text layer
  await plainTextLayer.click();

  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed assets")).not.toBeVisible();

  // Select two plain text layer with different font family
  await plainTextLayerTwo.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(
    textSection.getByTitle("Font family").getByText("Mixed Font Families"),
  ).toBeVisible();

  // Select typography text layer
  await typographyTextLayerOne.click();
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Typography one")).toBeVisible();

  // Select two typography text layer with different typography
  await typographyTextLayerTwo.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed assets")).toBeVisible();

  // Select token typography text layer
  await tokenTypographyTextLayerOne.click();
  await expect(textSection).toBeVisible();
  await expect(textSection.getByLabel("token-typo-one")).toBeVisible();

  // Select two token typography text layer with different token typography
  await tokenTypographyTextLayerTwo.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed tokens")).toBeVisible();

  //Select plain text layer and typography text layer together
  await plainTextLayer.click();
  await typographyTextLayerOne.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed assets")).toBeVisible();

  //Select plain text layer and typography text layer together on reverse order
  await typographyTextLayerOne.click();
  await plainTextLayer.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed assets")).toBeVisible();

  //Selen token typography text layer and typography text layer together
  await tokenTypographyTextLayerOne.click();
  await typographyTextLayerOne.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed assets")).toBeVisible();

  //Select token typography text layer and typography text layer together on reverse order
  await typographyTextLayerOne.click();
  await tokenTypographyTextLayerOne.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Mixed assets")).toBeVisible();

  // Select rectangle and elipse together
  await rectangleLayer.click();
  await elipseLayer.click({ modifiers: ["Control"] });
  await expect(textSection).not.toBeVisible();
});
