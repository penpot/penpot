import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { setupEmptyTokensFile } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens tab - common tests", () => {
  test("Clicking tokens tab button opens tokens sidebar tab", async ({
    page,
  }) => {
    await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    await expect(tokensTabPanel).toHaveText(/TOKENS/);
    await expect(tokensTabPanel).toHaveText(/Themes/);
  });
});
