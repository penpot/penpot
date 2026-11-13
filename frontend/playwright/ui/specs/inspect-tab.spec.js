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

  test("Shadow and blur", async ({ page }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await test.step("Single shadow", async () => {
      await selectLayer(workspacePage, shapeToLayerName.shadow);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expectPanelRows(panel, 2);
    });

    await test.step("Multiple shadows", async () => {
      await selectLayer(workspacePage, shapeToLayerName.shadowMultiple);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expectPanelRows(panel, 4);
    });

    await test.step("Blur", async () => {
      await selectLayer(workspacePage, shapeToLayerName.blur);

      const panel = await getPanelByTitle(workspacePage, "Blur");
      await expectPanelRows(panel, 1);
    });
  });

  // The two blur cases below mutate the selected shape with controls that
  // live in the Design tab, so they keep a page load of their own.

  test("Shape - Blur on not svg compatible shape", async ({ page }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.blur);

    await workspacePage.page.getByTestId("add-stroke").click();
    await workspacePage.page.getByTestId("stroke.alignment").click();
    await workspacePage.page.getByRole("option", { name: "Center" }).click();

    await openInspectTab(workspacePage);

    const panel = await getPanelByTitle(workspacePage, "Blur");
    await expectPanelRows(panel, 1);
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
    await expectPanelRows(panel, 1);
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
    await expect.poll(() => propertyRow.count()).toBeGreaterThanOrEqual(3);

    const compositeShadowRow = propertyRow.first();
    await compositeShadowRow.waitFor();

    await expect(compositeShadowRow).toBeVisible();

    const compositeShadowTerm = compositeShadowRow.locator("dt");

    const compositeShadowDefinition = compositeShadowRow.locator("dd");

    await expect(compositeShadowTerm).toHaveText("Shadow", { exact: true });
    await expect(compositeShadowDefinition).toContainText("shadowToken");
  });

  test("Fill", async ({ page }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await test.step("Solid", async () => {
      await selectLayer(workspacePage, shapeToLayerName.fill.solid);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expectPanelRows(panel, 1);
    });

    await test.step("Gradient", async () => {
      await selectLayer(workspacePage, shapeToLayerName.fill.gradient);

      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expectPanelRows(panel, 1);
    });

    await test.step("Image", async () => {
      await selectLayer(workspacePage, shapeToLayerName.fill.image);

      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expectPanelRows(panel, 1);

      await expect(
        panel.getByRole("img", { name: "Preview of the shape's fill" }),
      ).toBeVisible();
    });

    await test.step("Multiple", async () => {
      await selectLayer(workspacePage, shapeToLayerName.fill.multiple);

      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expectPanelRows(panel, 3);

      await expect(
        panel.getByRole("img", { name: "Preview of the shape's fill" }),
      ).toBeVisible();
    });

    await test.step("Token", async () => {
      await selectLayer(workspacePage, shapeToLayerName.fill.token);

      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expectPanelRows(panel, 1);

      const backgroundRow = panel
        .getByTestId("property-row")
        .filter({ hasText: "Background" });
      await expect(backgroundRow).toContainText("primary");
      await expect(backgroundRow).toBeVisible();
    });
  });

  test("Copy and colour space", async ({ page }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await setupFile(workspacePage);

    await test.step("Copy one property", async () => {
      await selectLayer(workspacePage, shapeToLayerName.flex);
      await openInspectTab(workspacePage);

      const panel = await getPanelByTitle(workspacePage, "Layout");
      await expectPanelRows(panel, 1);

      await copyPropertyFromPropertyRow(panel, "Display");

      const shorthand = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(shorthand).toBe("display: flex;");
    });

    await test.step("Copy the shorthand", async () => {
      await selectLayer(workspacePage, shapeToLayerName.shadow);

      const panel = await getPanelByTitle(workspacePage, "Shadow");
      await expectPanelRows(panel, 2);

      await copyShorthand(panel);

      const shorthand = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(shorthand).toBe("box-shadow: 4px 4px 4px 0px #00000033;");
    });

    // The colour space is a view setting for the sidebar, so the step that
    // changes it runs last.
    await test.step("Colour space changes the value and the copy", async () => {
      await selectLayer(workspacePage, shapeToLayerName.fill.solid);

      const panel = await getPanelByTitle(workspacePage, "Fill");
      await expectPanelRows(panel, 1);

      const backgroundRow = panel
        .getByTestId("property-row")
        .filter({ hasText: "Background" });
      await expect(backgroundRow).toContainText("#0438d5 100%");

      await copyPropertyFromPropertyRow(panel, "Background");
      const backgroundHEX = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(backgroundHEX).toContain("background: #0438d5FF;");

      await selectColorSpace(workspacePage, "rgba");

      await expect(backgroundRow).toContainText("4, 56, 213, 1");

      await copyPropertyFromPropertyRow(panel, "Background");
      const backgroundRGBA = await page.evaluate(() =>
        navigator.clipboard.readText(),
      );
      expect(backgroundRGBA).toContain("background: rgba(4, 56, 213, 1);");
    });
  });

  test.describe("Inspect tab - Stroke", () => {
    test("Stroke", async ({ page }) => {
      const workspacePage = new WasmWorkspacePage(page);
      await setupFile(workspacePage);

      await test.step("Solid", async () => {
        await selectLayer(workspacePage, shapeToLayerName.stroke.solid);
        await openInspectTab(workspacePage);

        const panel = await getPanelByTitle(workspacePage, "Stroke");
        await expectPanelRows(panel, 1);
      });

      await test.step("Gradient", async () => {
        await selectLayer(workspacePage, shapeToLayerName.stroke.gradient);

        const panel = await getPanelByTitle(workspacePage, "Stroke");
        await expectPanelRows(panel, 1);
      });

      await test.step("Image", async () => {
        await selectLayer(workspacePage, shapeToLayerName.stroke.image);

        const panel = await getPanelByTitle(workspacePage, "Stroke");
        await expectPanelRows(panel, 1);

        await expect(
          panel.getByRole("img", { name: "Preview of the shape's fill" }),
        ).toBeVisible();
      });

      await test.step("Multiple", async () => {
        await selectLayer(workspacePage, shapeToLayerName.stroke.multiple);

        const panel = await getPanelByTitle(workspacePage, "Stroke");
        await expectPanelRows(panel, 3);

        await expect(
          panel.getByRole("img", { name: "Preview of the shape's fill" }),
        ).toBeVisible();
      });

      await test.step("Token", async () => {
        await selectLayer(workspacePage, shapeToLayerName.stroke.token);

        const panel = await getPanelByTitle(workspacePage, "Stroke");
        await expectPanelRows(panel, 1);

        const borderColorRow = panel
          .getByTestId("property-row")
          .filter({ hasText: "Border color" });
        await expect(borderColorRow).toContainText("primary");
        await expect(borderColorRow).toBeVisible();
      });
    });
  });

  test.describe("Inspect tab - Typography", () => {
    test("Typography", async ({ page }) => {
      const workspacePage = new WasmWorkspacePage(page);
      await setupFile(workspacePage);

      await test.step("Simple text", async () => {
        await selectLayer(workspacePage, shapeToLayerName.text.simple);
        await openInspectTab(workspacePage);

        const panel = await getPanelByTitle(workspacePage, "Text");
        await expectPanelRows(panel, 1);
        await expect(panel.getByRole("presentation")).toBeVisible();
      });

      await test.step("Tokens", async () => {
        await selectLayer(workspacePage, shapeToLayerName.text.token);

        const panel = await getPanelByTitle(workspacePage, "Text");
        await expectPanelRows(panel, 1);

        const propertyRow = panel.getByTestId("property-row");

        const fontFamilyToken = propertyRow.filter({
          hasText: "Font family",
        });
        await expect(fontFamilyToken).toBeVisible();
        await expect(fontFamilyToken).toContainText("font.sans");

        const fontSizeToken = propertyRow.filter({
          hasText: "Font size",
        });
        await expect(fontSizeToken).toBeVisible();
        await expect(fontSizeToken).toContainText("medium");

        const fontWeightToken = propertyRow.filter({
          hasText: "Font weight",
        });
        await expect(fontWeightToken).toBeVisible();
        await expect(fontWeightToken).toContainText("bold");

        await expect(panel.getByRole("presentation")).toBeVisible();
      });

      await test.step("Composite token", async () => {
        await selectLayer(workspacePage, shapeToLayerName.text.compositeToken);

        const panel = await getPanelByTitle(workspacePage, "Text");
        await expectPanelRows(panel, 1);

        const compositeTypographyRow = panel
          .getByTestId("property-row")
          .filter({ hasText: "Typography" });
        await expect(compositeTypographyRow).toBeVisible();
        await expect(compositeTypographyRow).toContainText("body");

        await expect(panel.getByRole("presentation")).toBeVisible();
      });
    });
  });
});
