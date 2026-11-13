import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

const flags = ["enable-inspect-styles"];

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

const setupFile = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockConfigFlags(flags);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-inspect-tab.json",
  );

  await workspacePage.mockRPC(
    /update\-file\?/,
    "workspace/update-file-empty.json",
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
 * @param {WasmWorkspacePage} workspacePage - The workspace page instance
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
 * Waits for a style panel to render and checks how many property rows it
 * shows. Polling keeps the count honest while the panel fills.
 * @param {object} panel - The style panel locator
 * @param {number} minimum - The minimum number of property rows
 */
const expectPanelRows = async (panel, minimum) => {
  await expect(panel).toBeVisible();
  await expect
    .poll(() => panel.getByTestId("property-row").count())
    .toBeGreaterThanOrEqual(minimum);
};

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Finds a layer row by its exact name. `getByText` matches substrings, and
 * the fixture has names that are prefixes of others ("shape - text" and
 * "shape - text - token - composite"), so the match has to be anchored.
 * The tree capitalises the first letter, hence the case-insensitive pattern.
 * @param {WasmWorkspacePage} workspacePage - The workspace page instance
 * @param {string} layerName - The exact name of the layer row
 */
const getLayerRow = (workspacePage, layerName) =>
  workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: new RegExp(`^${escapeRegExp(layerName)}$`, "i") })
    .first();

/**
 * Expands a layer row when it is collapsed. The layer tree keeps its state
 * across selections, so clicking an already expanded toggle would collapse
 * the children the next selection needs.
 * @param {WasmWorkspacePage} workspacePage - The workspace page instance
 * @param {string} layerName - The name of the layer whose children to show
 */
const expandLayer = async (workspacePage, layerName) => {
  const toggle = getLayerRow(workspacePage, layerName).getByTestId(
    "toggle-content",
  );

  await expect(toggle).toBeVisible();

  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
  }
};

/**
 * Selects a layer in the layers panel
 * @param {WasmWorkspacePage} workspacePage - The workspace page instance
 * @param {string} layerName - The name of the layer to select
 * @param {string} parentLayerName - The name of the parent layer to expand (optional)
 */
const selectLayer = async (workspacePage, layerName, parentLayerName) => {
  await expandLayer(workspacePage, "Board");
  if (parentLayerName) {
    await expandLayer(workspacePage, parentLayerName);
  }

  await getLayerRow(workspacePage, layerName).click();

  const selectedLayer = workspacePage.page
    .getByTestId("layer-tree")
    .getByRole("checkbox", { checked: true })
    .first();
  await expect(selectedLayer).toHaveText(layerName, { ignoreCase: true });
};

/**
 * Opens the Inspect tab and waits for its panels to render
 * @param {WasmWorkspacePage} workspacePage - The workspace page instance
 */
const openInspectTab = async (workspacePage) => {
  const inspectButton = workspacePage.page.getByRole("tab", {
    name: "Inspect",
  });
  await inspectButton.waitFor();
  await inspectButton.click();

  const sidebar = workspacePage.page.getByTestId("right-sidebar");
  await expect(sidebar.getByRole("article").first()).toBeVisible();
};

/**
 * @typedef {'hex' | 'rgba' | 'hsla'} ColorSpace
 *
 * @param {WasmWorkspacePage} workspacePage - The workspace page instance
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
  test("Layout and size", async ({ page }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await test.step("Flex container", async () => {
      await selectLayer(workspacePage, shapeToLayerName.flex);
      await openInspectTab(workspacePage);

      // The deleted "Open Inspect tab" test only asserted this switcher, and
      // this step is the first one to open the tab.
      await expect(page.getByText("Layer info", { exact: true })).toBeVisible();

      const panel = await getPanelByTitle(workspacePage, "Layout");
      await expectPanelRows(panel, 1);
    });

    await test.step("Flex element", async () => {
      await selectLayer(
        workspacePage,
        shapeToLayerName.flexElement,
        shapeToLayerName.flex,
      );

      const panel = await getPanelByTitle(workspacePage, "Flex Element");
      await expectPanelRows(panel, 1);
    });

    await test.step("Grid", async () => {
      await selectLayer(workspacePage, shapeToLayerName.grid);

      const panel = await getPanelByTitle(workspacePage, "Layout");
      await expectPanelRows(panel, 1);
    });

    await test.step("Border radius - individual", async () => {
      await selectLayer(
        workspacePage,
        shapeToLayerName.borderRadius.individual,
        shapeToLayerName.borderRadius.main,
      );

      const panel = await getPanelByTitle(workspacePage, "Size and position");
      await expectPanelRows(panel, 2);

      const propertyRow = panel.getByTestId("property-row");
      await expect(
        propertyRow.filter({ hasText: "Border start start radius" }),
      ).toBeVisible();
      await expect(
        propertyRow.filter({ hasText: "Border end end radius" }),
      ).toBeVisible();
    });

    await test.step("Border radius - multiple", async () => {
      await selectLayer(
        workspacePage,
        shapeToLayerName.borderRadius.multiple,
        shapeToLayerName.borderRadius.main,
      );

      const panel = await getPanelByTitle(workspacePage, "Size and position");
      await expectPanelRows(panel, 4);

      const propertyRow = panel.getByTestId("property-row");
      await expect(
        propertyRow.filter({ hasText: "Border start start radius" }),
      ).toBeVisible();
      await expect(
        propertyRow.filter({ hasText: "Border start end radius" }),
      ).toBeVisible();
      await expect(
        propertyRow.filter({ hasText: "Border end end radius" }),
      ).toBeVisible();
      await expect(
        propertyRow.filter({ hasText: "Border end start radius" }),
      ).toBeVisible();
    });

    await test.step("Border radius - token", async () => {
      await selectLayer(
        workspacePage,
        shapeToLayerName.borderRadius.token,
        shapeToLayerName.borderRadius.main,
      );

      const panel = await getPanelByTitle(workspacePage, "Size and position");
      await expectPanelRows(panel, 4);

      const propertyRow = panel.getByTestId("property-row");

      const borderStartEndRadius = propertyRow.filter({
        hasText: "Border start end radius",
      });
      await expect(borderStartEndRadius).toBeVisible();
      await expect(borderStartEndRadius).toContainText("radius");

      const borderEndStartRadius = propertyRow.filter({
        hasText: "Border end start radius",
      });
      await expect(borderEndStartRadius).toBeVisible();
      await expect(borderEndStartRadius).toContainText("radius");
    });
  });

  test.describe("Inspect tab - Shadow", () => {
    test("Shape Shadow - Single shadow", async ({ page }) => {
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.blur);

    await openInspectTab(workspacePage);

    const panel = await getPanelByTitle(workspacePage, "Blur");
    await expect(panel).toBeVisible();

    const propertyRow = panel.getByTestId("property-row");
    const propertyRowCount = await propertyRow.count();

    expect(propertyRowCount).toBeGreaterThanOrEqual(1);
  });

  test("Shape - Blur on not svg compatible shape", async ({ page }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.blur);

    await workspacePage.page.getByTestId("add-stroke").click();
    await workspacePage.page.getByTestId("stroke.alignment").click();
    await workspacePage.page.getByRole("option", { name: "Center" }).click();

    await openInspectTab(workspacePage);

    const panel = await getPanelByTitle(workspacePage, "Blur");
    await expect(panel).toBeVisible();

    const propertyRow = panel.getByTestId("property-row");
    const propertyRowCount = await propertyRow.count();

    expect(propertyRowCount).toBeGreaterThanOrEqual(1);
  });

  test("Shape - Background Blur on not svg compatible shape", async ({
    page,
  }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.blur);

    await workspacePage.page
      .getByRole("combobox", { name: "Blur type select" })
      .click();
    await workspacePage.page
      .getByRole("option", { name: "Background blur" })
      .click();
    await workspacePage.page.getByTestId("add-stroke").click();
    await workspacePage.page.getByTestId("stroke.alignment").click();
    await workspacePage.page.getByRole("option", { name: "Center" }).click();

    await openInspectTab(workspacePage);

    const panel = await getPanelByTitle(workspacePage, "Blur");
    await expect(panel).toBeVisible();

    const propertyRow = panel.getByTestId("property-row");
    const propertyRowCount = await propertyRow.count();

    expect(propertyRowCount).toBeGreaterThanOrEqual(1);
  });

  test.describe("Inspect tab - Fill", () => {
    test("Shape - Fill - Solid", async ({ page }) => {
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
      const workspacePage = new WasmWorkspacePage(page);
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
