import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { setupTokensFile, unfoldTokenTree } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens - node tree", () => {
  test("User fold/unfold color tokens", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFile(page);

    await expect(tokensSidebar).toBeVisible();

    const tokensColorGroup = tokensSidebar.getByRole("button", {
      name: "Color 92",
    });
    await expect(tokensColorGroup).toBeVisible();
    await tokensColorGroup.click();

    await unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    const colorToken = tokensSidebar.getByRole("button", {
      name: "100",
    });
    await expect(colorToken).toBeVisible();
    await tokensColorGroup.click();
    await expect(colorToken).not.toBeVisible();
  });
});
