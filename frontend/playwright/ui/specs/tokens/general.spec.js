import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { setupEmptyTokensFileRender } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
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
