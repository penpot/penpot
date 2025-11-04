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

const getPanelByTitle = async (workspacePage, title) => {
  return workspacePage.page
    .getByTestId("style-panel")
    .filter({ hasText: title });
};

const selectLayer = async (workspacePage, layerName, parentLayerName) => {
  await workspacePage.clickToggableLayer("Board");
  if (parentLayerName) {
    await workspacePage.clickToggableLayer(parentLayerName);
  }
  await workspacePage.clickLeafLayer(layerName);
};

const openInspectTab = async (workspacePage) => {
  const inspectButton = workspacePage.page.getByRole("tab", {
    name: "Inspect",
  });
  await inspectButton.click();
};

test.describe("Inspect tab - Styles", () => {
  test("Open Inspect tab", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await setupFile(workspacePage);

    await selectLayer(workspacePage, shapeToLayerName.flex);
    await openInspectTab(workspacePage);

    const switcherLabel = workspacePage.page.getByTestId(
      "inspect-tab-switcher-label",
    );
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

      const imagePreview = panel.getByTestId("color-image-preview");
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

      const imagePreview = panel.getByTestId("color-image-preview");
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

      const imagePreview = panel.getByTestId("color-image-preview");
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

      const imagePreview = panel.getByTestId("color-image-preview");
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

      const textPreview = panel.getByTestId("text-preview");
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

      const textPreview = panel.getByTestId("text-preview");
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

      // Test with multiple tokens
      const compositeTypographyRow = propertyRow.filter({
        hasText: "Typography",
      });
      await expect(compositeTypographyRow).toBeVisible();
      expect(compositeTypographyRow).toContainText("body");

      const textPreview = panel.getByTestId("text-preview");
      await expect(textPreview).toBeVisible();
    });
  });
});
