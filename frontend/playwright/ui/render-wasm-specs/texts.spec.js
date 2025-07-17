import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
  ]);
});

async function mockGetEmojiFont(workspace) {
  await workspace.mockGetAsset(
    /notocoloremoji.*\.ttf$/,
    "render-wasm/assets/notocoloremojisubset.ttf"
  );
}

async function mockGetJapaneseFont(workspace) {
  await workspace.mockGetAsset(
    /notosansjp.*\.ttf$/,
    "render-wasm/assets/notosansjpsubset.ttf"
  );
  await workspace.mockGetAsset(
    /notosanssc.*\.ttf$/,
    "render-wasm/assets/notosansjpsubset.ttf"
  );
}


test("Renders a file with texts", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text.json");

  await workspace.goToWorkspace({
    id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
    pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
  });
  await workspace.waitForFirstRender();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Updates a text font", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text.json");

  await workspace.goToWorkspace({
    id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
    pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
  });
  await workspace.waitForFirstRender({ hideUI: false });

  await workspace.clickLeafLayer("this is a text");
  const fontStyle = workspace.page.getByTitle("Font Style");
  await fontStyle.click();
  const boldOption = fontStyle.getByText("bold").first();
  await boldOption.click();

  await workspace.hideUI();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with texts that use google fonts", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text-google-fonts.json");
  await workspace.mockGoogleFont(
    "ebgaramond",
    "render-wasm/assets/ebgaramond.ttf",
  );
  await workspace.mockGoogleFont("firacode", "render-wasm/assets/firacode.ttf");

  await workspace.goToWorkspace({
    id: "434b0541-fa2f-802f-8006-5981e47bd732",
    pageId: "434b0541-fa2f-802f-8006-5981e47bd733",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with texts that use custom fonts", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text-custom-fonts.json");
  await workspace.mockRPC(
    "get-font-variants?team-id=*",
    "render-wasm/get-font-variants-custom-fonts.json",
  );
  await workspace.mockAsset(
    "2d1ffeb6-e70b-4027-bbcc-910248ba45f8",
    "render-wasm/assets/mreaves.ttf",
  );
  await workspace.mockAsset(
    "69e76833-0816-49fa-8c7b-4b97c71c6f1a",
    "render-wasm/assets/nodesto-condensed.ttf",
  );

  await workspace.goToWorkspace({
    id: "434b0541-fa2f-802f-8006-59827d964a9b",
    pageId: "434b0541-fa2f-802f-8006-59827d964a9c",
  });
  await workspace.waitForFirstRender();

  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with styled texts", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text-styles.json");

  await workspace.goToWorkspace({
    id: "6bd7c17d-4f59-815e-8006-5c2559af4939",
    pageId: "6bd7c17d-4f59-815e-8006-5c2559af493a",
  });
  await workspace.waitForFirstRender();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with texts with images", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockFileMediaAsset(
    [
      "6bd7c17d-4f59-815e-8006-5e9765e0fabd",
      "6bd7c17d-4f59-815e-8006-5e97441071cc"
    ],
    "render-wasm/assets/pattern.png",
  );
  await mockGetEmojiFont(workspace);
  await mockGetJapaneseFont(workspace);

  await workspace.mockGetFile("render-wasm/get-file-text-images.json");

  await workspace.goToWorkspace({
    id: "6bd7c17d-4f59-815e-8006-5e96453952b0",
    pageId: "6bd7c17d-4f59-815e-8006-5e96453952b1",
  });
  await workspace.waitForFirstRender();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with text decoration", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockFileMediaAsset(
    [
      "d6c33e7b-7b64-80f3-8006-78509a3a2d21",
    ],
    "render-wasm/assets/pattern.png",
  );
  await mockGetEmojiFont(workspace);
  await mockGetJapaneseFont(workspace);

  await workspace.mockGetFile("render-wasm/get-file-text-decoration.json");

  await workspace.goToWorkspace({
    id: "d6c33e7b-7b64-80f3-8006-785098582f1d",
    pageId: "d6c33e7b-7b64-80f3-8006-785098582f1e",
  });
  await workspace.waitForFirstRender();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with multiple emoji", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);

  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text-emoji-board.json");

  await mockGetEmojiFont(workspace);

  await workspace.goToWorkspace({
    id: "6bd7c17d-4f59-815e-8006-5e999f38f210",
    pageId: "6bd7c17d-4f59-815e-8006-5e999f38f211",
  });

  await workspace.waitForFirstRender();  
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Renders a file with texts with different alignments", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-file-text-align.json");

  await workspace.goToWorkspace({
    id: "692f368b-63ca-8141-8006-62925640b827",
    pageId: "692f368b-63ca-8141-8006-62925640b828",
  });
  await workspace.waitForFirstRender();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Updates text alignment edition - part 1", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-multiple-texts-base.json");

  await workspace.goToWorkspace({
    id: "6bd7c17d-4f59-815e-8006-5c1f68846e43",
    pageId: "f8b42814-8653-81cf-8006-638aacdc3ffb",
  });
  await workspace.waitForFirstRender({ hideUI: false });
  await workspace.clickLeafLayer("Text 1");

  const textOptionsButton = workspace.page.getByTestId("text-align-options-button");
  const autoWidthButton = workspace.page.getByTitle("Auto width");
  const autoHeightButton = workspace.page.getByTitle("Auto height");
  const alignMiddleButton = workspace.page.getByTitle("Align middle");
  const alignBottomButton = workspace.page.getByTitle("Align bottom");
  const alignRightButton = workspace.page.getByTitle("Align right (Ctrl+Alt+R)");

  await textOptionsButton.click();

  await workspace.clickLeafLayer("Text 1");
  await autoWidthButton.click();

  await workspace.clickLeafLayer("Text 2");
  await autoHeightButton.click();

  await workspace.clickLeafLayer("Text 3");
  await alignMiddleButton.click();
  await alignRightButton.click();

  await workspace.clickLeafLayer("Text 4");
  await alignBottomButton.click();

  await workspace.page.keyboard.press("Escape");
  await workspace.hideUI();

  await expect(workspace.canvas).toHaveScreenshot({timeout: 10000});
});

test("Updates text alignment edition - part 2", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-multiple-texts-base.json");

  await workspace.goToWorkspace({
    id: "6bd7c17d-4f59-815e-8006-5c1f68846e43",
    pageId: "f8b42814-8653-81cf-8006-638aacdc3ffb",
  });
  await workspace.waitForFirstRender({ hideUI: false });
  await workspace.clickLeafLayer("Text 1");

  const textOptionsButton = workspace.page.getByTestId("text-align-options-button");
  const alignTopButton = workspace.page.getByTitle("Align top");
  const alignMiddleButton = workspace.page.getByTitle("Align middle");
  const alignBottomButton = workspace.page.getByTitle("Align bottom");
  const alignCenterButton = workspace.page.getByTitle("Align center (Ctrl+Alt+T)");
  const alignJustifyButton = workspace.page.getByTitle("Justify (Ctrl+Alt+J)");
  const LTRButton = workspace.page.getByTitle("LTR");
  const RTLButton = workspace.page.getByTitle("RTL");

  await textOptionsButton.click();

  await workspace.clickLeafLayer("Text 5");
  await alignBottomButton.click();
  await alignTopButton.click();
  await alignCenterButton.click();

  await workspace.clickLeafLayer("Text 6");
  await alignJustifyButton.click();
  await RTLButton.click();

  await workspace.clickLeafLayer("Text 7");
  await alignJustifyButton.click();
  await RTLButton.click();
  await LTRButton.click();

  await workspace.clickLeafLayer("Text 8");
  await alignMiddleButton.click();
  await alignJustifyButton.click();
  await RTLButton.click();

  await workspace.page.keyboard.press("Escape");
  await workspace.hideUI();

  await expect(workspace.canvas).toHaveScreenshot({timeout: 10000});
});

test("Updates text alignment edition - part 3", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("render-wasm/get-multiple-texts-base.json");

  await workspace.goToWorkspace({
    id: "6bd7c17d-4f59-815e-8006-5c1f68846e43",
    pageId: "f8b42814-8653-81cf-8006-638aacdc3ffb",
  });
  await workspace.waitForFirstRender({ hideUI: false });
  await workspace.clickLeafLayer("Text 1");

  const textOptionsButton = workspace.page.getByTestId("text-align-options-button");
  const autoWidthButton = workspace.page.getByTitle("Auto width");
  const autoHeightButton = workspace.page.getByTitle("Auto height");
  const alignMiddleButton = workspace.page.getByTitle("Align middle");
  const alignBottomButton = workspace.page.getByTitle("Align bottom");
  const alignLeftButton = workspace.page.getByTitle("Align left (Ctrl+Alt+L)");
  const alignCenterButton = workspace.page.getByTitle("Align center (Ctrl+Alt+T)");
  const alignJustifyButton = workspace.page.getByTitle("Justify (Ctrl+Alt+J)");
  const RTLButton = workspace.page.getByTitle("RTL");

  await textOptionsButton.click();

  await workspace.clickLeafLayer("Text 9");
  await autoHeightButton.click();
  await alignBottomButton.click();
  await alignJustifyButton.click();
  await RTLButton.click();

  await workspace.clickLeafLayer("Text 10");
  await alignBottomButton.click();
  await alignJustifyButton.click();
  await RTLButton.click();
  await autoWidthButton.click();

  await workspace.clickLeafLayer("Text 11");
  await alignCenterButton.click();
  await alignBottomButton.click();

  await workspace.clickLeafLayer("Text 12");
  await alignCenterButton.click();
  await alignLeftButton.click();
  await alignMiddleButton.click();

  await workspace.page.keyboard.press("Escape");
  await workspace.hideUI();

  await expect(workspace.canvas).toHaveScreenshot({timeout: 10000});
});