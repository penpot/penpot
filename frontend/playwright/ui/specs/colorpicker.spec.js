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
