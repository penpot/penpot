import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const flags = ["enable-inspect-styles"];

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

const setupFile = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockConfigFlags(flags);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-inspect-tab.json",
  );
  await workspacePage.goToWorkspace({
    fileId: "7b2da435-6186-815a-8007-0daa95d2f26d",
    pageId: "ce79274b-11ab-8088-8007-0487ad43f789",
  });
};

const shapeToLayerName = {
  flex: "shape - layout - flex",
  flexElement: "shape - layout - flex - element",
  grid: "shape - layout - grid",
  gridElement: "shape - layout - grid - element",
  shadow: "shape - shadow - single",
  shadowMultiple: "shape - shadow - multiple",
  shadowComposite: "shape - shadow - composite",
  blur: "shape - blur",
  borderRadius: {
    main: "shape - borderRadius",
    individual: "shape - borderRadius - individual",
    multiple: "shape - borderRadius - multiple",
    token: "shape - borderRadius - token",
  },
  fill: {
    solid: "shape - fill - single - solid",
    gradient: "shape - fill - single - gradient",
    image: "shape - fill - single - image",
    multiple: "shape - fill - multiple",
    style: "shape - fill - style",
    token: "shape - fill - token",
  },
  stroke: {
    solid: "shape - stroke - single - solid",
    gradient: "shape - stroke - single - gradient",
    image: "shape - stroke - single - image",
    multiple: "shape - stroke - multiple",
    style: "shape - stroke - style",
    token: "shape - stroke - token",
  },
  text: {
    simple: "shape - text",
    token: "shape - text - token - simple",
    compositeToken: "shape - text - token - composite",
  },
};

/**
 * Copy the shorthand CSS from a full panel property
 * @param {object} panel - The style panel locator
 */
const copyShorthand = async (panel) => {
  const panelShorthandButton = panel.getByRole("button", {
    name: "Copy CSS shorthand to clipboard",
  });
  await panelShorthandButton.waitFor();
  await panelShorthandButton.click();
};

/**
 * Copy the CSS property from a property row by clicking its copy button
 * @param {object} panel - The style panel locator
 * @param {string} property - The property name to filter by
 */
const copyPropertyFromPropertyRow = async (panel, property) => {
  const propertyRow = panel
    .getByTestId("property-row")
    .filter({ hasText: property });
  const copyButton = propertyRow.getByRole("button");
  await copyButton.waitFor();
  await copyButton.click();
};

/**
 * Returns the style panel by its title
 * @param {WorkspacePage} workspacePage - The workspace page instance
 * @param {string} title - The title of the panel to retrieve
 */
const getPanelByTitle = async (workspacePage, title) => {
  const sidebar = workspacePage.page.getByTestId("right-sidebar");
  const article = sidebar.getByRole("article");
  const panel = article.filter({ hasText: title });
  await panel.waitFor();
  return panel;
};

/**
 * Selects a layer in the layers panel
 * @param {WorkspacePage} workspacePage - The workspace page instance
 * @param {string} layerName - The name of the layer to select
 * @param {string} parentLayerName - The name of the parent layer to expand (optional)
 */
const selectLayer = async (workspacePage, layerName, parentLayerName) => {
  await workspacePage.clickToggableLayer("Board");
  if (parentLayerName) {
    await workspacePage.clickToggableLayer(parentLayerName);
  }
  await workspacePage.clickLeafLayer(layerName);
  await workspacePage.page.waitForTimeout(500);
};

/**
 * Opens the Inspect tab
 * @param {WorkspacePage} workspacePage - The workspace page instance
 */

const openInspectTab = async (workspacePage) => {
  const inspectButton = workspacePage.page.getByRole("tab", {
    name: "Inspect",
  });
  await inspectButton.waitFor();
  await inspectButton.click();
  await workspacePage.page.waitForTimeout(500);
};

/**
 * @typedef {'hex' | 'rgba' | 'hsla'} ColorSpace
 *
 * @param {WorkspacePage} workspacePage - The workspace page instance
 * @param {ColorSpace} colorSpace - The color space to select
 */
const selectColorSpace = async (workspacePage, colorSpace) => {
  const sidebar = workspacePage.page.getByTestId("right-sidebar");
  const colorSpaceSelector = sidebar.getByLabel("Select color space");
  await colorSpaceSelector.click();
  const colorSpaceOption = sidebar.getByRole("option", {
    name: colorSpace,
  });
  await colorSpaceOption.click();
};

test.describe("Inspect tab - Styles", () => {
  test.skip("Open Inspect tab", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.flex);
    await openInspectTab(workspacePage);

    const switcherLabel = workspacePage.page.getByText("Layer info", {
      exact: true,
    });
    await expect(switcherLabel).toBeVisible();
    await expect(switcherLabel).toHaveText("Layer info");
  });
  test.describe("Inspect tab - Flex", () => {
    test("Shape Layout Flex ", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.flex);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Layout");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);
    });

    test("Shape Layout Flex Element", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(
        workspacePage,
        shapeToLayerName.flexElement,
        shapeToLayerName.flex,
      );
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Flex Element");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);
    });
  });

  test("Shape Layout Grid", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.grid);
    await openInspectTab(workspacePage);

    const panel = await getPanelByTitle(workspacePage, "Layout");
    await expect(panel).toBeVisible();

    const propertyRow = panel.getByTestId("property-row");
    const propertyRowCount = await propertyRow.count();

    expect(propertyRowCount).toBeGreaterThanOrEqual(1);
  });

  test.describe("Inspect tab - Shadow", () => {
    test("Shape Shadow - Single shadow", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.shadow);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(2);
    });

    test("Shape Shadow - Multiple shadow", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.shadowMultiple);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(4);
    });

    // FIXME: flaky/random (depends on trace ?)
    test.skip("Shape Shadow - Composite shadow", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.shadowComposite);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(3);

      const compositeShadowRow = propertyRow.first();
      await compositeShadowRow.waitFor();

      await expect(compositeShadowRow).toBeVisible();

      const compositeShadowTerm = compositeShadowRow.locator("dt");

      const compositeShadowDefinition = compositeShadowRow.locator("dd");

      expect(compositeShadowTerm).toHaveText("Shadow", { exact: true });
      expect(compositeShadowDefinition).toContainText("shadowToken");
    });
  });

  test("Shape - Blur", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.blur);
    await openInspectTab(workspacePage);

    const panel = await getPanelByTitle(workspacePage, "Blur");
    await expect(panel).toBeVisible();

    const propertyRow = panel.getByTestId("property-row");
    const propertyRowCount = await propertyRow.count();

    expect(propertyRowCount).toBeGreaterThanOrEqual(1);
  });

  test.describe("Inspect tab - Border radius", () => {
    test("Shape - Border radius - individual", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(
        workspacePage,
        shapeToLayerName.borderRadius.individual,
        shapeToLayerName.borderRadius.main,
      );
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Size & position");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(2);

      const borderStartStartRadius = propertyRow.filter({
        hasText: "Border start start radius",
      });
      await expect(borderStartStartRadius).toBeVisible();

      const borderEndEndRadius = propertyRow.filter({
        hasText: "Border end end radius",
      });
      await expect(borderEndEndRadius).toBeVisible();
    });

    test("Shape - Border radius - multiple", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(
        workspacePage,
        shapeToLayerName.borderRadius.multiple,
        shapeToLayerName.borderRadius.main,
      );
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Size & position");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(4);

      const borderStartStartRadius = propertyRow.filter({
        hasText: "Border start start radius",
      });
      await expect(borderStartStartRadius).toBeVisible();

      const borderStartEndRadius = propertyRow.filter({
        hasText: "Border start end radius",
      });
      await expect(borderStartEndRadius).toBeVisible();

      const borderEndEndRadius = propertyRow.filter({
        hasText: "Border end end radius",
      });
      await expect(borderEndEndRadius).toBeVisible();

      const borderEndStartRadius = propertyRow.filter({
        hasText: "Border end start radius",
      });
      await expect(borderEndStartRadius).toBeVisible();
    });

    test("Shape - Border radius - token", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(
        workspacePage,
        shapeToLayerName.borderRadius.token,
        shapeToLayerName.borderRadius.main,
      );
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Size & position");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(4);

      const borderStartEndRadius = propertyRow.filter({
        hasText: "Border start end radius",
      });
      await expect(borderStartEndRadius).toBeVisible();
      expect(borderStartEndRadius).toContainText("radius");

      const borderEndStartRadius = propertyRow.filter({
        hasText: "Border end start radius",
      });
      expect(borderEndStartRadius).toContainText("radius");
      await expect(borderEndStartRadius).toBeVisible();
    });
  });

  test.describe("Inspect tab - Fill", () => {
    test("Shape - Fill - Solid", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.fill.solid);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);
    });

    test("Change color space and ensure fill and shorthand changes", async ({
      page,
    }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.fill.solid);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const backgroundRow = propertyRow.filter({
        hasText: "Background",
      });
      await expect(backgroundRow).toBeVisible();

      // Ensure initial value and copied value are in HEX format
      expect(backgroundRow).toContainText("#0438d5 100%");

      await copyPropertyFromPropertyRow(panel, "Background");

      const backgroundHEX = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(backgroundHEX).toContain("background: #0438d5FF;");

      // Change color space to RGBA
      await selectColorSpace(workspacePage, "rgba");

      // Ensure new value and copied value are in RGBA format
      expect(backgroundRow).toContainText("4, 56, 213, 1");

      await copyPropertyFromPropertyRow(panel, "Background");
      const backgroundRGBA = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(backgroundRGBA).toContain("background: rgba(4, 56, 213, 1);");
    });

    test("Shape - Fill - Gradient", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.fill.gradient);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);
    });

    test("Shape - Fill - Image", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.fill.image);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      const imagePreview = panel.getByRole("img", {
        name: "Preview of the shape's fill",
      });
      await expect(imagePreview).toBeVisible();
    });

    test("Shape - Fill - Multiple", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.fill.multiple);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(3);

      const imagePreview = panel.getByRole("img", {
        name: "Preview of the shape's fill",
      });
      await expect(imagePreview).toBeVisible();
    });

    test("Shape - Fill - Token", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.fill.token);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      const fillToken = propertyRow.filter({
        hasText: "Background",
      });
      expect(fillToken).toContainText("primary");
      await expect(fillToken).toBeVisible();
    });
  });

  test.describe("Inspect tab - Stroke", () => {
    test("Shape - Stroke - Solid", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.stroke.solid);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Stroke");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);
    });

    test("Shape - Stroke - Gradient", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.stroke.gradient);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Stroke");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);
    });

    test("Shape - Stroke - Image", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.stroke.image);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Stroke");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      const imagePreview = panel.getByRole("img", {
        name: "Preview of the shape's fill",
      });
      await expect(imagePreview).toBeVisible();
    });

    test("Shape - Stroke - Multiple", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.stroke.multiple);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Stroke");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(3);

      const imagePreview = panel.getByRole("img", {
        name: "Preview of the shape's fill",
      });
      await expect(imagePreview).toBeVisible();
    });

    test("Shape - Stroke - Token", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.stroke.token);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Stroke");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      const fillToken = propertyRow.filter({
        hasText: "Border color",
      });
      expect(fillToken).toContainText("primary");
      await expect(fillToken).toBeVisible();
    });
  });

  test.describe("Inspect tab - Typography", () => {
    test("Text - simple", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.text.simple);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Text");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      const textPreview = panel.getByRole("presentation");
      await expect(textPreview).toBeVisible();
    });

    test("Text - token", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.text.token);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Text");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      // Test with multiple tokens
      const fontFamilyToken = propertyRow.filter({
        hasText: "Font family",
      });
      await expect(fontFamilyToken).toBeVisible();
      expect(fontFamilyToken).toContainText("font.sans");

      const fontSizeToken = propertyRow.filter({
        hasText: "Font size",
      });
      await expect(fontSizeToken).toBeVisible();
      expect(fontSizeToken).toContainText("medium");

      const fontWeightToken = propertyRow.filter({
        hasText: "Font weight",
      });
      await expect(fontWeightToken).toBeVisible();
      expect(fontWeightToken).toContainText("bold");

      const textPreview = panel.getByRole("presentation");
      await expect(textPreview).toBeVisible();
    });
    test("Text - composite token", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.text.compositeToken);
      await openInspectTab(workspacePage);
      const panel = await getPanelByTitle(workspacePage, "Text");
      await expect(panel).toBeVisible();

      const propertyRow = panel.getByTestId("property-row");
      const propertyRowCount = await propertyRow.count();

      expect(propertyRowCount).toBeGreaterThanOrEqual(1);

      const compositeTypographyRow = propertyRow.filter({
        hasText: "Typography",
      });
      await expect(compositeTypographyRow).toBeVisible();
      expect(compositeTypographyRow).toContainText("body");

      const textPreview = panel.getByRole("presentation");
      await expect(textPreview).toBeVisible();
    });
  });

  test.describe("Copy properties", () => {
    test("Copy single property", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.flex);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Layout");
      await expect(panel).toBeVisible();

      await copyPropertyFromPropertyRow(panel, "Display");

      const shorthand = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(shorthand).toBe("display: flex;");
    });
    test("Copy shorthand - multiple properties", async ({ page }) => {
      const workspacePage = new WorkspacePage(page);
      await setupFile(workspacePage);

      await selectLayer(workspacePage, shapeToLayerName.shadow);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expect(panel).toBeVisible();

      await copyShorthand(panel);

      const shorthand = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(shorthand).toBe("box-shadow: 4px 4px 4px 0px #00000033;");
    });
  });
});
