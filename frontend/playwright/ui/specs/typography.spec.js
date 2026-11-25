import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

// workspace/multiselection-typography.json: "Text with no typography" is a
// fixed 14px Rufina text, and the local library has the typographies
// "typography one" (Agdasima) and "typography 2" (IM Fell French Canon SC).
const FILE = {
  id: "c7ce0794-0992-8105-8004-38f280443849",
  pageId: "1062e0a0-8fe0-80ae-8007-e70b4993f5f0",
};

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

async function setupTypographyFile(page) {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "workspace/multiselection-typography.json",
  );
  await workspace.mockGoogleFont("rufina", "render-wasm/assets/ebgaramond.ttf");
  await workspace.mockGoogleFont(
    "agdasima",
    "render-wasm/assets/ebgaramond.ttf",
  );
  await workspace.mockGoogleFont(
    "im-fell-french-canon-sc",
    "render-wasm/assets/ebgaramond.ttf",
  );
  await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });
  await workspace.waitForFirstRender();
  return workspace;
}

function measureInput(workspace, name) {
  return workspace.rightSidebar
    .getByRole("region", { name: "shape-measures-section" })
    .getByRole("textbox", { name, exact: true });
}

// Selects "Text with no typography", switches it to auto-width and returns
// the Width input with the auto-width value.
async function setupAutoWidthText(workspace) {
  // Exact match: "Text with no typography two" also exists and comes first.
  await workspace.layers
    .getByText("Text with no typography", { exact: true })
    .click();
  const widthInput = measureInput(workspace, "Width");
  const fixedWidth = await widthInput.inputValue();

  await workspace.rightSidebar
    .getByRole("button", { name: "Auto width" })
    .click();
  await expect(
    workspace.rightSidebar.getByRole("button", {
      name: "Auto width",
      pressed: true,
    }),
  ).toBeVisible();

  // The resize is async: wait for the width to change, then check it shrank.
  await expect(widthInput).not.toHaveValue(fixedWidth);
  const autoWidth = await widthInput.inputValue();
  expect(Number(autoWidth)).toBeLessThan(Number(fixedWidth));

  return { widthInput, autoWidth };
}

// "typography 2" is larger (16px) than the text (14px) and uppercase, so the
// text must grow, and one undo must revert both the typography and the size.
async function expectGrowthAndUndo(page, widthInput, autoWidth) {
  await expect(widthInput).not.toHaveValue(autoWidth);
  expect(Number(await widthInput.inputValue())).toBeGreaterThan(
    Number(autoWidth),
  );

  await page.keyboard.press("ControlOrMeta+z");
  await expect(widthInput).toHaveValue(autoWidth);
}

test.describe("BUG 13124 - Applying a typography resizes an auto-width text", () => {
  test("from the assets panel", async ({ page }) => {
    const workspace = await setupTypographyFile(page);
    const { widthInput, autoWidth } = await setupAutoWidthText(workspace);

    await workspace.clickAssets();
    // The Typographies group starts collapsed.
    await workspace.sidebar
      .getByRole("button", { name: /^Typographies/ })
      .click();
    await workspace.sidebar
      .getByTitle("typography 2 (IM Fell French Canon SC)")
      .click();

    await expectGrowthAndUndo(page, widthInput, autoWidth);
  });

  test("from the typographies palette", async ({ page }) => {
    const workspace = await setupTypographyFile(page);
    const { widthInput, autoWidth } = await setupAutoWidthText(workspace);

    await page.getByRole("button", { name: "Typographies (Alt+T)" }).click();
    await page.getByTitle("typography 2", { exact: true }).click();

    await expectGrowthAndUndo(page, widthInput, autoWidth);
  });
});
