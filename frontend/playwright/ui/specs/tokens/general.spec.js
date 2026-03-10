import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { setupEmptyTokensFileRender } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-design-tokens-v1",
  ]);
});

test.describe("Tokens tab - common tests", () => {
  test("Clicking tokens tab button opens tokens sidebar tab", async ({
    page,
  }) => {
    await setupEmptyTokensFileRender(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    await expect(tokensTabPanel).toHaveText(/TOKENS/);
    await expect(tokensTabPanel).toHaveText(/Themes/);
  });
});

test("BUG 13302 - Dimension token not being highlighted after applying it", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();

  await workspacePage.mockGetFile("tokens/get-file-13302.json");
  await workspacePage.mockRPC("update-file?id=*", "tokens/update-file-13302.json");

  await workspacePage.goToWorkspace({
    fileId: "6886b62b-1979-8195-8007-8d0b92d3116a",
    pageId: "6886b62b-1979-8195-8007-8d0b92d3116b",
  });

  await workspacePage.clickLeafLayer("Rectangle");

  await workspacePage.sidebar.getByRole("tab", { name: "Tokens" }).click();

  await workspacePage.tokensSidebar
    .getByRole("button", { name: "Dimensions 1" })
    .click();

  const token = workspacePage.tokensSidebar.getByRole("checkbox", { name: "any-dimensions-token" });
  await token.click();

  // FIXME: this is a bug somewhere else in tokens, this should be fully applied
  // for a dimensions token
  await expect(token).toBeChecked({ indeterminate: true });
});
