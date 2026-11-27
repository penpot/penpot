import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { setupTokensFileRender } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

const gradientFlags = [
  "enable-token-gradient",
  "enable-feature-token-input",
  "enable-tokenscript",
];

const openGradientForm = async (page, modal) => {
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
  await tokensTabPanel
    .getByRole("button", { name: "Add Token: Gradient" })
    .click();
  await expect(modal).toBeVisible();
};

test.describe("Tokens - gradient", () => {
  test("The fields of a gradient token resolve with tokenscript", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal: modal } = await setupTokensFileRender(
      page,
      { flags: gradientFlags },
    );
    await openGradientForm(page, modal);
    await modal.getByLabel("Name").fill("gradient.brand");

    await test.step("an angle in turns resolves to degrees", async () => {
      await modal.getByRole("textbox", { name: "Angle" }).fill("0.25turn");
      await expect(modal.getByText("Resolved value: 90deg")).toBeVisible();
    });

    await test.step("a stop position resolves an expression", async () => {
      await modal
        .getByRole("textbox", { name: "Position" })
        .first()
        .fill("0.25 * 2");
      await expect(modal.getByText("Resolved value: 50%")).toBeVisible();
    });

    await test.step("the reference field resolves a gradient", async () => {
      await modal.getByTitle("Use a reference").click();
      await modal
        .getByRole("textbox", { name: "Reference" })
        .fill("linear-gradient(90deg, {colors.indigo.500} 0%, #0000ff 100%)");
      await expect(modal.getByText(/Resolved value:/)).toBeVisible();
    });

    await expect(page.getByText("Something wrong has happened.")).toHaveCount(
      0,
    );
  });
});
