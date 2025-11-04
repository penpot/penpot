import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

// Fix for https://tree.taiga.io/project/penpot/issue/7549
test("Bug 7549 - User clicks on color swatch to display the color picker next to it", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace();
  await workspacePage.moveButton.click();
  const swatch = workspacePage.page.getByRole("button", { name: "E8E9EA" });
  const swatchBox = await swatch.boundingBox();
  await swatch.click();

  await expect(workspacePage.colorpicker).toBeVisible();
  const pickerBox = await workspacePage.colorpicker.boundingBox();
  const distance = swatchBox.x - (pickerBox.x + pickerBox.width);
  expect(distance).toBeLessThan(60);
});

test("Create a LINEAR gradient", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
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

  await workspacePage.clickLeafLayer("Rectangle");

  const swatch = workspacePage.page.getByRole("button", { name: "#B1B2B5" });
  await swatch.click();

  const select = workspacePage.page.getByText("Solid");
  await select.click();

  const gradOption = workspacePage.page.getByText("Gradient");
  await gradOption.click();

  const addStopBtn = workspacePage.page.getByRole("button", {
    name: "Add stop",
  });
  await addStopBtn.click();
  await addStopBtn.click();
  await addStopBtn.click();

  const removeBtn = workspacePage.colorpicker
    .getByRole("button", { name: "Remove color" })
    .last();
  await removeBtn.click();
  await removeBtn.click();

  const inputColor1 = workspacePage.colorpicker
    .getByRole("textbox", { name: "Color" })
    .first();
  await inputColor1.fill("#fabada");

  const inputOpacity1 = workspacePage.colorpicker
    .getByTestId("opacity-input")
    .first();
  await inputOpacity1.fill("100");

  const inputColor2 = workspacePage.colorpicker
    .getByRole("textbox", { name: "Color" })
    .last();
  await inputColor2.fill("#ff0000");

  const inputOpacity2 = workspacePage.colorpicker
    .getByTestId("opacity-input")
    .last();
  await inputOpacity2.fill("40");

  const inputOpacityGlobal = workspacePage.colorpicker.getByTestId(
    "opacity-global-input",
  );
  await inputOpacityGlobal.fill("50");
  await inputOpacityGlobal.press("Enter");
  await expect(inputOpacityGlobal).toHaveValue("50");
  await expect(inputOpacityGlobal).toBeVisible();

  await expect(
    workspacePage.page.getByText("Linear gradient").nth(1),
  ).toBeVisible();
});

test("Create a RADIAL gradient", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
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
  await workspacePage.clickLeafLayer("Rectangle");

  const swatch = workspacePage.page.getByRole("button", { name: "#B1B2B5" });
  await swatch.click();

  const select = workspacePage.page.getByText("Solid");
  await select.click();

  const gradOption = workspacePage.page.getByText("Gradient");
  await gradOption.click();

  const gradTypeOptions = workspacePage.colorpicker
    .locator("div")
    .filter({ hasText: "Linear" })
    .nth(3);
  await gradTypeOptions.click();

  const gradRadialOption = workspacePage.page
    .locator("li")
    .filter({ hasText: "Radial" });
  await gradRadialOption.click();

  const addStopBtn = workspacePage.page.getByRole("button", {
    name: "Add stop",
  });
  await addStopBtn.click();
  await addStopBtn.click();
  await addStopBtn.click();

  const removeBtn = workspacePage.colorpicker
    .getByRole("button", { name: "Remove color" })
    .last();
  await removeBtn.click();
  await removeBtn.click();

  const inputColor1 = workspacePage.page
    .getByRole("textbox", { name: "Color" })
    .first();
  await inputColor1.fill("#fabada");

  const inputOpacity1 = workspacePage.colorpicker
    .getByTestId("opacity-input")
    .first();
  await inputOpacity1.fill("100");

  const inputColor2 = workspacePage.page
    .getByRole("textbox", { name: "Color" })
    .last();
  await inputColor2.fill("#ff0000");

  const inputOpacity2 = workspacePage.colorpicker
    .getByTestId("opacity-input")
    .last();
  await inputOpacity2.fill("100");

  const inputOpacityGlobal = workspacePage.colorpicker.getByTestId(
    "opacity-global-input",
  );
  await inputOpacityGlobal.fill("50");
  await inputOpacityGlobal.press("Enter");
  await expect(inputOpacityGlobal).toHaveValue("50");
  await expect(inputOpacityGlobal).toBeVisible();

  await expect(
    workspacePage.page.getByText("Radial gradient").nth(1),
  ).toBeVisible();
});

test("Gradient stops limit", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
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

// Fix for https://tree.taiga.io/project/penpot/issue/9900
test("Bug 9900 - Color picker has no inputs for HSV values", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace();
  await workspacePage.moveButton.click();
  const swatch = workspacePage.page.getByRole("button", { name: "E8E9EA" });
  await swatch.click();

  const HSVA = await workspacePage.page.getByLabel("HSVA");
  await HSVA.click();

  await workspacePage.page.getByLabel("H", { exact: true }).isVisible();
  await workspacePage.page.getByLabel("S", { exact: true }).isVisible();
  await workspacePage.page.getByLabel("V", { exact: true }).isVisible();
});

test("Bug 10089 - Cannot change alpha", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
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
  await workspacePage.clickLeafLayer("Rectangle");

  const swatch = workspacePage.page.getByRole("button", { name: "#B1B2B5" });
  await swatch.click();

  const alpha = workspacePage.page.getByLabel("A", { exact: true });
  await expect(alpha).toHaveValue("100");

  const alphaSlider = workspacePage.page.getByTestId("slider-opacity");
  await alphaSlider.click();

  await expect(alpha).toHaveValue("50");
});
