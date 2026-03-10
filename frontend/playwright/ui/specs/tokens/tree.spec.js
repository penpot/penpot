import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { setupTokensFileRender, unfoldTokenTree } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-design-tokens-v1",
  ]);
});

test.describe("Tokens - node tree", () => {
  test("User fold/unfold color tokens", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFileRender(page);

    await expect(tokensSidebar).toBeVisible();

    const tokensColorGroup = tokensSidebar.getByRole("button", {
      name: "Color 92",
    });
    await expect(tokensColorGroup).toBeVisible();
    await tokensColorGroup.click();

    await unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    const colorToken = tokensSidebar.getByRole("checkbox", {
      name: "100",
    });
    await expect(colorToken).toBeVisible();
    await tokensColorGroup.click();
    await expect(colorToken).not.toBeVisible();
  });
});
