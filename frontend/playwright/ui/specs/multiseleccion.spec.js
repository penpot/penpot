import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-token-input"]);
});

test("Multiselection - check multiple values in measures", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
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

  // Select first shape (single selection first)
  await page.getByTestId("layer-item").getByRole("button").first().click();
  await workspacePage.layers.getByTestId("layer-row").nth(0).click();

  // === CHECK SINGLE SELECTION - ALL MEASURE FIELDS ===
  const measuresSection = workspacePage.rightSidebar.getByRole("region", {
    name: "shape-measures-section",
  });
  await expect(measuresSection).toBeVisible();

  // Width
  const widthInput = measuresSection.getByRole("textbox", {
    name: "Width",
    exact: true,
  });
  await expect(widthInput).toHaveValue("360");

  // Height
  const heightInput = measuresSection.getByRole("textbox", {
    name: "Height",
    exact: true,
  });
  await expect(heightInput).toHaveValue("53");

  // X Position (using "X axis" title)
  const xPosInput = measuresSection.getByRole("textbox", {
    name: "X axis",
    exact: true,
  });
  await expect(xPosInput).toHaveValue("1094");

  // Y Position (using "Y axis" title)
  const yPosInput = measuresSection.getByRole("textbox", {
    name: "Y axis",
    exact: true,
  });
  await expect(yPosInput).toHaveValue("856");

  // === CHECK MULTI-SELECTION - MIXED VALUES ===
  // Shift+click to add second layer to selection
  await workspacePage.layers
    .getByTestId("layer-row")
    .nth(1)
    .click({ modifiers: ["Shift"] });

  // All measure fields should show "Mixed" placeholder when values differ
  await expect(widthInput).toHaveAttribute("placeholder", "Mixed");
  await expect(heightInput).toHaveAttribute("placeholder", "Mixed");
  await expect(xPosInput).toHaveAttribute("placeholder", "Mixed");
  await expect(yPosInput).toHaveAttribute("placeholder", "Mixed");
});

test("Multiselection - check fill multiple values", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
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

  await page.getByTestId("layer-item").getByRole("button").first().click();
  await workspacePage.layers.getByTestId("layer-row").nth(0).click();

  // Fill section
  const fillSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Fill section",
  });
  await expect(fillSection).toBeVisible();

  // Single selection - fill color should be visible (not "Mixed")
  await expect(fillSection.getByText(/Mixed/i)).not.toBeVisible();

  // Multi-selection with Shift+click
  await workspacePage.layers
    .getByTestId("layer-row")
    .nth(1)
    .click({ modifiers: ["Shift"] });

  // Should show "Mixed" for fills when shapes have different fill colors
  await expect(fillSection.getByText("Mixed")).toBeVisible();
});

test("Multiselection - check stroke multiple values", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
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

  await page.getByTestId("layer-item").getByRole("button").first().click();
  await workspacePage.layers.getByTestId("layer-row").nth(0).click();

  // Stroke section
  const strokeSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Stroke section",
  });
  await expect(strokeSection).toBeVisible();

  // Single selection - stroke should be visible (not "Mixed")
  await expect(strokeSection.getByText(/Mixed/i)).not.toBeVisible();

  // Multi-selection
  await workspacePage.layers
    .getByTestId("layer-row")
    .nth(1)
    .click({ modifiers: ["Shift"] });

  // Should show "Mixed" for strokes when shapes have different stroke colors
  await expect(strokeSection.getByText("Mixed")).toBeVisible();
});

test("Multiselection - check rotation multiple values", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
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

  await page.getByTestId("layer-item").getByRole("button").first().click();
  await workspacePage.layers.getByTestId("layer-row").nth(1).click();

  // Measures section contains rotation
  const measuresSection = workspacePage.rightSidebar.getByRole("region", {
    name: "shape-measures-section",
  });
  await expect(measuresSection).toBeVisible();

  // Rotation field exists
  const rotationInput = measuresSection.getByRole("textbox", {
    name: "Rotation",
    exact: true,
  });
  await expect(rotationInput).toBeVisible();

  // Rotate that shape
  await rotationInput.fill("45");
  await page.keyboard.press("Enter");
  await expect(rotationInput).toHaveValue("45"); // Rotation should be 45

  // Multi-selection
  await workspacePage.layers
    .getByTestId("layer-row")
    .nth(0)
    .click({ modifiers: ["Shift"] });

  // Rotation should show "Mixed" placeholder
  await expect(rotationInput).toHaveAttribute("placeholder", "Mixed");
});

test("Multiselection of text and typographies", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/multiselection-typography.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "1062e0a0-8fe0-80ae-8007-e70b4993f5ef",
    pageId: "1062e0a0-8fe0-80ae-8007-e70b4993f5f0",
  });

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
  await expect(
    textSection.getByText("Multiple typographies"),
  ).not.toBeVisible();

  // Select two plain text layer with different font family
  await plainTextLayerTwo.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(
    textSection.getByTitle("Font family").getByText("--"),
  ).toBeVisible();

  // Select typography text layer
  await typographyTextLayerOne.click();
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Typography one")).toBeVisible();

  // Select two typography text layer with different typography
  await typographyTextLayerTwo.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Multiple typographies")).toBeVisible();

  // Select token typography text layer
  // TODO: CHANGE WHEN TOKEN TYPOGRAPHY ROW IS READY
  await tokenTypographyTextLayerOne.click();
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Metrophobic")).toBeVisible();

  // Select two token typography text layer with different token typography
  // TODO: CHANGE WHEN TOKEN TYPOGRAPHY ROW IS READY
  await tokenTypographyTextLayerTwo.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(
    textSection.getByTitle("Font family").getByText("--"),
  ).toBeVisible();

  //Select plain text layer and typography text layer together
  await plainTextLayer.click();
  await typographyTextLayerOne.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Multiple typographies")).toBeVisible();

  //Select plain text layer and typography text layer together on reverse order
  await typographyTextLayerOne.click();
  await plainTextLayer.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Multiple typographies")).toBeVisible();

  //Selen token typography text layer and typography text layer together
  await tokenTypographyTextLayerOne.click();
  await typographyTextLayerOne.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Multiple typographies")).toBeVisible();

  //Select token typography text layer and typography text layer together on reverse order
  await typographyTextLayerOne.click();
  await tokenTypographyTextLayerOne.click({ modifiers: ["Control"] });
  await expect(textSection).toBeVisible();
  await expect(textSection.getByText("Multiple typographies")).toBeVisible();

  // Select rectangle and elipse together
  await rectangleLayer.click();
  await elipseLayer.click({ modifiers: ["Control"] });
  await expect(textSection).not.toBeVisible();
});
