import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { setupEmptyTokensFile, setupTokensFile } from "./helpers";

// THEMES HELPERS

const checkInputFieldWithError = async (
  tokenThemeUpdateCreateModal,
  inputLocator,
) => {
  await expect(inputLocator).toHaveAttribute("aria-invalid", "true");

  const errorMessageId = await inputLocator.getAttribute("aria-describedby");
  await expect(
    tokenThemeUpdateCreateModal.locator(`#${errorMessageId}`),
  ).toBeVisible();
};

const checkInputFieldWithoutError = async (inputLocator) => {
  expect(await inputLocator.getAttribute("aria-invalid")).toBeNull();
  expect(await inputLocator.getAttribute("aria-describedby")).toBeNull();
};

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens Themes", () => {
  test("User edits theme and activates it in the sidebar", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenThemeUpdateCreateModal } =
      await setupTokensFile(page);

    await expect(tokenThemesSetsSidebar).toBeVisible();

    await tokenThemesSetsSidebar.getByRole("button", { name: "Edit" }).click();

    await expect(tokenThemeUpdateCreateModal).toBeVisible();
    await tokenThemeUpdateCreateModal
      .getByRole("button", { name: "sets" })
      .first()
      .click();

    await tokenThemeUpdateCreateModal.getByLabel("Theme").fill("Changed");

    const lightDarkSetGroup = tokenThemeUpdateCreateModal.getByTestId(
      "tokens-set-group-item",
      {
        name: "LightDark",
        exact: true,
      },
    );
    await expect(lightDarkSetGroup).toBeVisible();

    const lightSet = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "light",
      exact: true,
    });
    const darkSet = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "dark",
      exact: true,
    });

    // Mixed set group
    await expect(lightSet.getByRole("checkbox")).toBeChecked();
    await expect(darkSet.getByRole("checkbox")).not.toBeChecked();

    // Disable all
    await lightDarkSetGroup.getByRole("checkbox").click();
    await expect(lightSet.getByRole("checkbox")).not.toBeChecked();
    await expect(darkSet.getByRole("checkbox")).not.toBeChecked();

    // Enable all
    await lightDarkSetGroup.getByRole("checkbox").click();
    await expect(lightSet.getByRole("checkbox")).toBeChecked();
    await expect(darkSet.getByRole("checkbox")).toBeChecked();

    await tokenThemeUpdateCreateModal
      .getByRole("button", {
        name: "save theme",
      })
      .click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed" + "4 active sets"),
    ).toBeVisible();

    await tokenThemeUpdateCreateModal
      .getByRole("button")
      .getByText("close")
      .click();
    await expect(tokenThemeUpdateCreateModal).not.toBeVisible();

    const themeSelect = tokenThemesSetsSidebar.getByRole("combobox");

    await themeSelect.click();
    await page
      .getByTestId("theme-select-dropdown")
      .getByRole("option", { name: "Changed" })
      .click();

    const sidebarLightSet = tokenThemesSetsSidebar.getByRole("button", {
      name: "light",
      exact: true,
    });
    const sidebarDarkSet = tokenThemesSetsSidebar.getByRole("button", {
      name: "dark",
      exact: true,
    });

    await expect(sidebarLightSet.getByRole("checkbox")).toBeChecked();
    await expect(sidebarDarkSet.getByRole("checkbox")).toBeChecked();
  });
});

test.describe("Tokens: Themes modal", () => {
  test("Delete theme", async ({ page }) => {
    const { tokenThemeUpdateCreateModal, workspacePage } =
      await setupTokensFile(page);

    workspacePage.openTokenThemesModal();

    await expect(
      tokenThemeUpdateCreateModal.getByRole("button", { name: "Delete theme" }),
    ).toHaveCount(2);

    await tokenThemeUpdateCreateModal
      .getByRole("button", { name: "Delete theme" })
      .first()
      .click();

    await expect(
      tokenThemeUpdateCreateModal.getByRole("button", { name: "Delete theme" }),
    ).toHaveCount(1);
  });

  test("Add new theme in empty file", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenThemeUpdateCreateModal } =
      await setupEmptyTokensFile(page);

    await tokenThemesSetsSidebar
      .getByRole("button", { name: "Create one." })
      .first()
      .click();

    await expect(tokenThemeUpdateCreateModal).toBeVisible();

    const groupInput = tokenThemeUpdateCreateModal.getByLabel("Group");
    const nameInput = tokenThemeUpdateCreateModal.getByLabel("Theme");
    const saveButton = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "Save theme",
    });

    await groupInput.fill("New Group name");
    await nameInput.fill("New Theme name");

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal);
    await expect(saveButton).not.toBeDisabled();

    await saveButton.click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("New Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("New Group name"),
    ).toBeVisible();
  });

  test("Add new theme", async ({ page }) => {
    const { tokenThemeUpdateCreateModal, workspacePage } =
      await setupTokensFile(page);

    workspacePage.openTokenThemesModal();

    await tokenThemeUpdateCreateModal
      .getByRole("button", {
        name: "Add new theme",
      })
      .click();

    const groupInput = tokenThemeUpdateCreateModal.getByLabel("Group");
    const nameInput = tokenThemeUpdateCreateModal.getByLabel("Theme");
    const saveButton = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "Save theme",
    });

    await groupInput.fill("Core"); // Invalid because "Core / Light" theme already exists
    await nameInput.fill("Light");

    await checkInputFieldWithError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).toBeDisabled();

    await groupInput.fill("New Group name");
    await nameInput.fill("New Theme name");

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal);
    await expect(saveButton).not.toBeDisabled();

    await saveButton.click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("New Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("New Group name"),
    ).toBeVisible();
  });

  test("Edit theme", async ({ page }) => {
    const { tokenThemeUpdateCreateModal, workspacePage } =
      await setupTokensFile(page);

    workspacePage.openTokenThemesModal();

    await expect(
      tokenThemeUpdateCreateModal.getByText("no sets"),
    ).not.toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("3 active sets"),
    ).toHaveCount(2);

    await tokenThemeUpdateCreateModal
      .getByText("3 active sets")
      .first()
      .click();

    const groupInput = tokenThemeUpdateCreateModal.getByLabel("Group");
    const nameInput = tokenThemeUpdateCreateModal.getByLabel("Theme");
    const saveButton = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "Save theme",
    });

    await groupInput.fill("Core"); // Invalid because "Core / Dark" theme already exists
    await nameInput.fill("Dark");

    await checkInputFieldWithError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).toBeDisabled();

    await groupInput.fill("Core"); // Valid because "Core / Light" theme already exists
    await nameInput.fill("Light"); // but it's the same theme we are editing

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal);
    await expect(saveButton).not.toBeDisabled();

    await nameInput.fill("Changed Theme name"); // New names should be also valid
    await groupInput.fill("Changed Group name");

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal);
    await expect(saveButton).not.toBeDisabled();

    expect(await nameInput.getAttribute("aria-invalid")).toBeNull();
    expect(await nameInput.getAttribute("aria-describedby")).toBeNull();

    const checkboxes = await tokenThemeUpdateCreateModal
      .locator('[role="checkbox"]')
      .all();

    for (const checkbox of checkboxes) {
      const isChecked = await checkbox.getAttribute("aria-checked");

      if (isChecked === "true") {
        await checkbox.click();
      }
    }

    const firstButton = await tokenThemeUpdateCreateModal
      .getByTestId("tokens-set-item")
      .first();

    await firstButton.click();

    await expect(saveButton).not.toBeDisabled();

    await saveButton.click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed Group name"),
    ).toBeVisible();
  });
});
