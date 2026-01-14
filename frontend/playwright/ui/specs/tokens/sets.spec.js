import { test, expect } from "@playwright/test";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { setupEmptyTokensFile, setupTokensFile } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens: Sets Tab", () => {
  const changeSetInput = async (sidebar, setName, finalKey = "Enter") => {
    const setInput = sidebar.locator("input:focus");
    await expect(setInput).toBeVisible();
    await setInput.fill(setName);
    await setInput.press(finalKey);
  };

  const createSet = async (sidebar, setName, finalKey = "Enter") => {
    const tokensTabButton = sidebar
      .getByRole("button", { name: "Add set" })
      .click();

    await changeSetInput(sidebar, setName, (finalKey = "Enter"));
  };

  const assertEmptySetsList = async (el) => {
    const buttons = await el.getByRole("button").allTextContents();
    const filteredButtons = buttons.filter((text) => text === "Create one.");
    await expect(filteredButtons.length).toEqual(2); // We assume there are no themes, so we have two "Create one" buttons.
  };

  const assertSetsList = async (el, sets) => {
    const buttons = await el.getByRole("button").allTextContents();
    const filteredButtons = buttons.filter(
      (text) => text && text !== "Create one.",
    );
    await expect(filteredButtons).toEqual(sets);
  };

  test("User creates sets tree structure by entering a set path", async ({
    page,
  }) => {
    const { tokenThemesSetsSidebar, tokenContextMenuForSet } =
      await setupEmptyTokensFile(page);

    const tokensTabButton = tokenThemesSetsSidebar
      .getByRole("button", { name: "Add set" })
      .click();

    await createSet(tokenThemesSetsSidebar, "core/colors/light");
    await createSet(tokenThemesSetsSidebar, "core/colors/dark");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "light",
      "dark",
    ]);

    // User renames set
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "light" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Rename").click();
    await changeSetInput(tokenThemesSetsSidebar, "light-renamed");

    // User cancels during editing
    await createSet(tokenThemesSetsSidebar, "core/colors/dark", "Escape");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "light-renamed",
      "dark",
    ]);

    // Creates nesting by renaming set with double click
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "light-renamed" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Rename").click();
    await changeSetInput(tokenThemesSetsSidebar, "nested/light");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "nested",
      "light",
      "dark",
    ]);

    // Create set in group
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Add set to this group").click();
    await changeSetInput(tokenThemesSetsSidebar, "sizes/small");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "nested",
      "light",
      "dark",
      "sizes",
      "small",
    ]);

    // User deletes set
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "nested" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Delete").click();

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "dark",
      "sizes",
      "small",
    ]);

    // User deletes all sets
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Delete").click();

    await assertEmptySetsList(tokenThemesSetsSidebar);
  });

  test("User can create & edit sets and set groups with an identical name", async ({
    page,
  }) => {
    const { tokenThemesSetsSidebar, tokenContextMenuForSet } =
      await setupEmptyTokensFile(page);

    const tokensTabButton = tokenThemesSetsSidebar
      .getByRole("button", { name: "Add set" })
      .click();

    await createSet(tokenThemesSetsSidebar, "core/colors");
    await createSet(tokenThemesSetsSidebar, "core");
    await assertSetsList(tokenThemesSetsSidebar, ["core", "colors", "core"]);
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .nth(0)
      .dblclick();
    await changeSetInput(tokenThemesSetsSidebar, "core-group-renamed");
    await assertSetsList(tokenThemesSetsSidebar, [
      "core-group-renamed",
      "colors",
      "core",
    ]);

    await page.keyboard.press(`ControlOrMeta+z`);
    await assertSetsList(tokenThemesSetsSidebar, ["core", "colors", "core"]);

    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .nth(1)
      .dblclick();
    await changeSetInput(tokenThemesSetsSidebar, "core-set-renamed");
    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "core-set-renamed",
    ]);
  });

  test("Fold/Unfold set", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenSetGroupItems } =
      await setupTokensFile(page);

    await expect(tokenThemesSetsSidebar).toBeVisible();

    const darkSet = tokenThemesSetsSidebar.getByRole("button", {
      name: "dark",
      exact: true,
    });

    await expect(darkSet).toBeVisible();

    const setGroup = await tokenSetGroupItems
      .filter({ hasText: "LightDark" })
      .first();

    const setCollapsable = await setGroup
      .getByTestId("tokens-set-group-collapse")
      .first();

    await setCollapsable.click();

    await expect(darkSet).toHaveCount(0);
  });

  test("Change current theme", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenSetItems } =
      await setupTokensFile(page);

    await expect(tokenSetItems.nth(1)).toHaveAttribute("aria-checked", "true");
    await expect(tokenSetItems.nth(2)).toHaveAttribute("aria-checked", "false");

    await tokenThemesSetsSidebar.getByTestId("theme-select").click();
    await page
      .getByTestId("theme-select-dropdown")
      .getByRole("option", { name: "Dark", exact: true })
      .click();

    await expect(tokenSetItems.nth(1)).toHaveAttribute("aria-checked", "false");
    await expect(tokenSetItems.nth(2)).toHaveAttribute("aria-checked", "true");
  });
});
