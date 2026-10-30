import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

const RECTANGLE_FILE = {
  fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
  pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
};

// The two fill-picker tests below share one document: `get-file-not-empty.json`
// holds a single "Rectangle" whose fill is `#B1B2B5`, and every case starts
// from the picker of that fill.
async function openRectangleFill(page) {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-not-empty.json",
  );
  await workspacePage.mockRPC(
    "update-file-v2?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspacePage.goToWorkspace(RECTANGLE_FILE);
  await workspacePage.clickLeafLayer("Rectangle");
  await page.getByRole("button", { name: "#B1B2B5" }).click();
  return workspacePage;
}

// Fix for https://tree.taiga.io/project/penpot/issue/7549 and
// https://tree.taiga.io/project/penpot/issue/9900
test("The canvas color picker opens by its swatch and shows the HSB inputs", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();
  await workspacePage.moveButton.click();

  const swatch = page.getByRole("button", { name: "E8E9EA" });
  const swatchBox = await swatch.boundingBox();
  await swatch.click();

  await test.step("the picker opens next to the swatch", async () => {
    await expect(workspacePage.colorpicker).toBeVisible();
    const pickerBox = await workspacePage.colorpicker.boundingBox();
    const distance = swatchBox.x - (pickerBox.x + pickerBox.width);
    expect(distance).toBeLessThan(60);
  });

  await test.step("the picker shows the HSB inputs", async () => {
    await page.getByLabel("HSBA").click();

    // The old case called `isVisible()` on labels that do not exist ("H", "S",
    // "B(V)" are the icons, not the accessible names) and never awaited them,
    // so it asserted nothing. The inputs are labelled by their real names.
    await expect(page.getByLabel("Hue")).toBeVisible();
    await expect(page.getByLabel("Saturation")).toBeVisible();
    await expect(page.getByLabel("Brightness (Value)")).toBeVisible();
  });
});

// Fix for https://tree.taiga.io/project/penpot/issue/10089 and
// https://tree.taiga.io/project/penpot/issue/10756
test("The fill picker changes alpha and accepts SVG image fills", async ({
  page,
}) => {
  await openRectangleFill(page);

  // The alpha case asserts the initial 100, so it runs before the case that
  // replaces the solid fill with an image.
  await test.step("the alpha can be changed", async () => {
    const alpha = page.getByRole("spinbutton", { name: "Alpha" });
    await expect(alpha).toHaveValue("100");

    await page.getByTestId("slider-opacity").click();
    await expect(alpha).toHaveValue("50");
  });

  await test.step("the image fill accepts SVG files", async () => {
    await page.getByText("Solid").click();
    await page.getByText("Image").click();

    await expect(page.locator("#fill-image-upload")).toHaveAttribute(
      "accept",
      /(?:^|,)image\/svg\+xml(?:,|$)/,
    );
  });
});

test("The fill picker creates a linear and a radial gradient", async ({
  page,
}) => {
  const workspacePage = await openRectangleFill(page);

  const colorpicker = workspacePage.colorpicker;
  const removeStop = colorpicker.getByRole("button", {
    name: "Remove color",
  });
  const addStop = page.getByRole("button", { name: "Add stop" });

  // Both steps add three stops and remove two on their own gradient; counting
  // them is what proves the step did the work instead of inheriting it.
  const addAndRemoveStops = async () => {
    const stopsBefore = await removeStop.count();
    await addStop.click();
    await addStop.click();
    await addStop.click();
    await removeStop.last().click();
    await removeStop.last().click();
    await expect(removeStop).toHaveCount(stopsBefore + 1);
  };

  const setEdgeStops = async (lastOpacity) => {
    await colorpicker
      .getByRole("textbox", { name: "Color" })
      .first()
      .fill("#fabada");
    await colorpicker.getByTestId("opacity-input").first().fill("100");
    await colorpicker
      .getByRole("textbox", { name: "Color" })
      .last()
      .fill("#ff0000");
    await colorpicker.getByTestId("opacity-input").last().fill(lastOpacity);
  };

  await test.step("linear gradient", async () => {
    await page.getByText("Solid").click();
    await page.getByText("Gradient").click();

    await addAndRemoveStops();
    await setEdgeStops("40");

    await expect(page.getByText("Linear gradient")).toBeVisible();
  });

  await test.step("radial gradient", async () => {
    await colorpicker
      .getByRole("combobox")
      .filter({ hasText: "Linear" })
      .click();
    await page.getByRole("option", { name: "Radial" }).click();

    await addAndRemoveStops();
    await setEdgeStops("100");

    await expect(page.getByText("Radial gradient")).toBeVisible();
    await expect(colorpicker.getByTestId("opacity-input").last()).toHaveValue(
      "100",
    );
  });
});

test("Gradient stops limit", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.mockConfigFlags(["enable-feature-render-wasm"]);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-fragment-gradient-limits.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "c7ce0794-0992-8105-8004-38f280443849",
    pageId: "66697432-c33d-8055-8006-2c62cc084cad",
  });

  await workspacePage.clickLeafLayer("Rectangle");

  const swatch = workspacePage.page.getByRole("button", {
    name: "Linear gradient",
  });
  await swatch.click();

  await expect(workspacePage.colorpicker).toBeVisible();

  await expect(
    workspacePage.colorpicker.getByRole("button", { name: "Add stop" }),
  ).toBeDisabled();
});

test("Color picker color list", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
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

  await page.getByRole("tab", { name: "Assets" }).click();
  await page.getByRole("button", { name: "Add color" }).click();

  const rampSelector = page.getByTestId("value-saturation-selector");
  await expect(rampSelector).toBeVisible();
  await rampSelector.click({ position: { x: 50, y: 50 } });
  await page.getByRole("button", { name: "Save color style" }).click();
  await page
    .getByTestId("left-sidebar")
    .locator('input[type="text"]')
    .fill("first color");
  await workspacePage.page.keyboard.press("Enter");

  await page.getByRole("button", { name: "Add color" }).click();
  await rampSelector.click({ position: { x: 40, y: 40 } });
  await page.getByRole("button", { name: "Save color style" }).click();
  await page
    .getByTestId("left-sidebar")
    .locator('input[type="text"]')
    .fill("second color");
  await workspacePage.page.keyboard.press("Enter");

  await page.getByRole("button", { name: "Add color" }).click();
  await rampSelector.click({ position: { x: 60, y: 60 } });
  await page.getByRole("button", { name: "Save color style" }).click();
  await page
    .getByTestId("left-sidebar")
    .locator('input[type="text"]')
    .fill("third  color");
  await workspacePage.page.keyboard.press("Enter");

  await page.getByRole("tab", { name: "Layers" }).click();
  await workspacePage.clickLeafLayer("Rectangle");

  const swatch = workspacePage.page.getByRole("button", { name: "#B1B2B5" });
  await swatch.click();

  const colorpicker = workspacePage.page.getByTestId("colorpicker");
  await expect(colorpicker).toBeVisible();

  const colorItems = colorpicker.getByRole("listitem");

  const colorButtons = colorItems.getByRole("button");
  await expect(colorButtons).toHaveCount(3);

  const toggleButton = colorpicker.getByRole("button", { name: "List view" });
  await toggleButton.click();

  await expect(
    colorpicker.getByRole("listitem", { name: "#708191" }),
  ).toBeVisible();
  await colorpicker
    .getByRole("combobox")
    .filter({ hasText: "Recent colors" })
    .click();
  await colorpicker.getByRole("option", { name: "File library" }).click();

  await expect(
    colorpicker.getByRole("listitem", { name: "First color" }),
  ).toBeVisible();

  //test show and hide color palette
  const paletteToggle = workspacePage.page.getByRole("button", {
    name: "Toggle color palette",
  });
  await paletteToggle.click();
  const paletteBar = workspacePage.page.getByRole("region", {
    name: "Palette bar",
  });
  await expect(paletteBar).toBeVisible();

  // Check that color palette is open by checking the presence of a color swatch in the palette
  const paletteSwatch = paletteBar.getByRole("button", { name: "first color" });
  await expect(paletteSwatch).toBeVisible();

  // Close the color palette
  await paletteToggle.click();
  await expect(paletteSwatch).not.toBeVisible();
});
