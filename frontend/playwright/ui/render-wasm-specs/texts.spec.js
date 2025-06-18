import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
  ]);
});

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
  await workspace.waitForFirstRender();
  await workspace.clickLeafLayer("this is a text");
  const fontStyle = workspace.page.getByTitle("Font Style");
  await fontStyle.click();
  const boldOption = fontStyle.getByText("bold").first();
  await boldOption.click();
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
