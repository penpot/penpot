import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { TextShapeFeature } from '../../pages/features/TextShapeFeature';

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

test("User can create a new text shape", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();

  const textShapeFeature = new TextShapeFeature(page);
  await textShapeFeature.textShapeButton.click();
  await expect(textShapeFeature.textShapeButton).toHaveClass(/selected/);

  // TODO: Ver como puedo deshacerme de estas coordenadas
  // hardcodeadas.
  await textShapeFeature.createNewFromCoordiantes(
    403,
    154,
    722,
    378
  );
  await expect(textShapeFeature.textEditorContent).toBeFocused();
  await textShapeFeature.insertText("Hello, World!");

});

test("User can paste text directly", async ({ page}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();

  await page.mouse.click(400, 100);

  const textShapeFeature = new TextShapeFeature(page);
  await textShapeFeature.pasteText('Hello, World!');
  await expect(textShapeFeature.textEditorContent).toBeFocused();
});

test("User can insert new text", async ({ page }) => {

});

test("User can replace text (same node)", async ({ page }) => {

});

test("User can replace text (multiple inline nodes)", async ({ page }) => {

});

test("User can replace text (multiple paragraph nodes)", async ({ page }) => {

});

test("User can delete all text", async ({ page }) => {

});

test("User can select and replace text", async ({ page }) => {

});

test("User can select and delete text", async ({ page }) => {

});

test("User can select and change font family", async ({ page }) => {

});

test("User can select and change font size", async ({ page }) => {});

test("User can select and change font style", async ({ page }) => {});

test("User can select and change line height", async ({ page }) => {

});

test("User can select and change letter spacing", async ({ page }) => {

});

test("User can select and change text transform", async ({ page }) => {

});

test("User can select and change text align", async ({ page }) => {

});

test("User can select and change text grow type", async ({ page }) => {

});
