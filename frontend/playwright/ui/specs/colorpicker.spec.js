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
  const swatchBox = await swatch.boundingBox();
  await swatch.click();

  const select = await workspacePage.page.getByText("Solid");
  await select.click();

  const gradOption = await workspacePage.page.getByText("Gradient");
  await gradOption.click();

  const addStopBtn = await workspacePage.page.getByRole("button", {
    name: "Add stop",
  });
  await addStopBtn.click();
  await addStopBtn.click();
  await addStopBtn.click();

  const removeBtn = await workspacePage.page
    .getByTestId("colorpicker")
    .getByRole("button", { name: "Remove color" })
    .nth(2);
  await removeBtn.click();
  await removeBtn.click();

  const inputColor1 = await workspacePage.page.getByPlaceholder("Mixed").nth(1);
  await inputColor1.fill("fabada");

  const inputOpacity1 = await workspacePage.page
    .getByTestId("colorpicker")
    .getByPlaceholder("--")
    .nth(1);
  await inputOpacity1.fill("100");

  const inputColor2 = await workspacePage.page.getByPlaceholder("Mixed").nth(2);
  await inputColor2.fill("red");

  const inputOpacity2 = await workspacePage.page
    .getByTestId("colorpicker")
    .getByPlaceholder("--")
    .nth(2);
  await inputOpacity2.fill("100");

  const inputOpacityGlobal = await workspacePage.page
    .locator("div")
    .filter({ hasText: /^FillLinear gradient%$/ })
    .getByPlaceholder("--");
  await inputOpacityGlobal.fill("100");
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
  const swatchBox = await swatch.boundingBox();
  await swatch.click();

  const select = await workspacePage.page.getByText("Solid");
  await select.click();

  const gradOption = await workspacePage.page.getByText("Gradient");
  await gradOption.click();

  const gradTypeOptions = await workspacePage.page
    .getByTestId("colorpicker")
    .locator("div")
    .filter({ hasText: "Linear" })
    .nth(3);
  await gradTypeOptions.click();

  const gradRadialOption = await workspacePage.page
    .locator("li")
    .filter({ hasText: "Radial" });
  await gradRadialOption.click();

  const addStopBtn = await workspacePage.page.getByRole("button", {
    name: "Add stop",
  });
  await addStopBtn.click();
  await addStopBtn.click();
  await addStopBtn.click();

  const removeBtn = await workspacePage.page
    .getByTestId("colorpicker")
    .getByRole("button", { name: "Remove color" })
    .nth(2);
  await removeBtn.click();
  await removeBtn.click();

  const inputColor1 = await workspacePage.page.getByPlaceholder("Mixed").nth(1);
  await inputColor1.fill("fabada");

  const inputOpacity1 = await workspacePage.page
    .getByTestId("colorpicker")
    .getByPlaceholder("--")
    .nth(1);
  await inputOpacity1.fill("100");

  const inputColor2 = await workspacePage.page.getByPlaceholder("Mixed").nth(2);
  await inputColor2.fill("red");

  const inputOpacity2 = await workspacePage.page
    .getByTestId("colorpicker")
    .getByPlaceholder("--")
    .nth(2);
  await inputOpacity2.fill("100");
});

// Fix for https://tree.taiga.io/project/penpot/issue/9900
test("Bug 9900 - Color picker has no inputs for HSV values", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace();
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
  const swatchBox = await swatch.boundingBox();
  await swatch.click();

  const alpha = workspacePage.page.getByLabel("A", { exact: true });
  await expect(alpha).toHaveValue("100");

  const alphaSlider = workspacePage.page.getByTestId("slider-opacity");
  await alphaSlider.click();

  await expect(alpha).toHaveValue("50");
});
