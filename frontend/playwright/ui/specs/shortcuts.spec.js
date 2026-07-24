import { test, expect } from "@playwright/test";
import ShortcutsPage from "../pages/ShortcutsPage";

const customShortcutsFlag = "enable-custom-shortcuts";

test.beforeEach(async ({ page }) => {
  await ShortcutsPage.init(page);
  await ShortcutsPage.mockConfigFlags(page, [customShortcutsFlag]);
});

test.describe("Shortcuts Settings Page", () => {
  test("Shortcuts page loads correctly", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await expect(shortcutsPage.shortcutsSection).toBeVisible();
    await expect(shortcutsPage.searchInput).toBeVisible();
  });

  test("Tabs are visible and clickable", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await expect(shortcutsPage.allTab).toBeVisible();
    await expect(shortcutsPage.personalizedTab).toBeVisible();
    await expect(shortcutsPage.disabledTab).toBeVisible();

    await shortcutsPage.clickTab("Personalized");
    await expect(shortcutsPage.personalizedTab).toHaveAttribute(
      "aria-selected",
      "true",
    );
    const personalizedPlacehonder = page.getByText(/Head to All to start/i);
    await expect(personalizedPlacehonder).toBeVisible();

    await shortcutsPage.clickTab("Disabled");
    await expect(shortcutsPage.disabledTab).toHaveAttribute(
      "aria-selected",
      "true",
    );
    const disabledPlaceholder = page.getByText(/There are not disabled/i);
    await expect(disabledPlaceholder).toBeVisible();

    await shortcutsPage.clickTab("All");
    await expect(shortcutsPage.allTab).toHaveAttribute("aria-selected", "true");
  });
});

test.describe("Shortcut Customization", () => {
  test("User can edit a shortcut", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    // Expand subsection
    await shortcutsPage.expandSubsection("Alignment");

    // Start edition of the shortcut
    await shortcutsPage.clickEditShortcut("Align bottom");

    // Press a new key combination
    await shortcutsPage.pressKey("Control+y");

    // Save the shortcut
    await shortcutsPage.saveShortcut();

    // Verify the shortcut is now customized
    await shortcutsPage.expectShortcutCustomized("Align bottom");
  });
});

test.describe("Shortcut Import", () => {
  test("Import valid custom shortcuts", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      workspace: { "align-bottom": "ctrl+y" },
    });

    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.searchForShortcut("Align bottom");
    await shortcutsPage.clickTab("Personalized");
    await shortcutsPage.expectShortcutVisible("Align bottom");
  });

  test("Import invalid JSON syntax", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcutsRaw("{invalid");

    await expect(
      page.getByRole("alert").filter({ hasText: /Invalid data/i }),
    ).toBeVisible();
  });

  test("Import valid JSON with invalid schema", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      workspace: { "unknown-shortcut": "ctrl+y" },
    });

    await expect(
      page.getByRole("alert").filter({ hasText: /Invalid data/i }),
    ).toBeVisible();
  });

  test("Import JSON without workspace context accepted", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      dashboard: { "toggle-theme": "alt+m" },
    });

    await expect(
      page.getByRole("alert").filter({ hasText: /Invalid data/i }),
    ).not.toBeVisible();
  });

  test("Import JSON with unsupported shortcut format", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      workspace: { escape: 123 },
    });

    await expect(
      page.getByRole("alert").filter({ hasText: /Invalid data/i }),
    ).toBeVisible();
  });

  test("Import conflicting shortcuts", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      workspace: {
        "align-bottom": "alt+a",
      },
    });

    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.expectShortcutDisabled("Align left");

    const exported = await shortcutsPage.getExportedJson();
    expect(exported.workspace).toMatchObject({
      "align-bottom": "alt+a",
      "align-left": "",
    });
  });
});

test.describe("Shortcut Export", () => {
  test("Export customized shortcuts", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    const exported = await shortcutsPage.getExportedJson();
    expect(exported).toHaveProperty("workspace");
    expect(exported.workspace).toMatchObject({
      "align-bottom": "ctrl+y",
    });
  });

  test("Export includes disabled shortcuts", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      workspace: { "align-bottom": "" },
    });

    await shortcutsPage.searchForShortcut("Align bottom");
    await shortcutsPage.expectShortcutDisabled("Align bottom");

    const exported = await shortcutsPage.getExportedJson();
    expect(exported.workspace).toMatchObject({
      "align-bottom": "",
    });
  });
});

test.describe("Shortcut Conflict Detection", () => {
  test("Detects conflict and disables old shortcut on save", async ({
    page,
  }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Alt+a");

    await expect(
      page.getByRole("alert").filter({ hasText: /Combination assigned to/i }),
    ).toBeVisible();

    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");
    await shortcutsPage.expectShortcutDisabled("Align left");

    const exported = await shortcutsPage.getExportedJson();
    expect(exported.workspace).toMatchObject({
      "align-bottom": "alt+a",
      "align-left": "",
    });
  });
});

test.describe("Shortcut Reset", () => {
  test("Reset after import restores defaults", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.importShortcuts({
      workspace: { "align-bottom": "ctrl+y" },
    });
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.restoreAllShortcuts();
    await shortcutsPage.expectShortcutNotCustomized("Align bottom");
  });
});

test.describe("Shortcut Persistence", () => {
  test("Custom shortcuts persist after reload", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await page.reload();
    await shortcutsPage.goToShortcuts();
    await shortcutsPage.expectShortcutCustomized("Align bottom");
  });
});

test.describe("Cancel Shortcut Editing", () => {
  test("Cancel editing does not save changes", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.cancelEdit();

    await shortcutsPage.expectShortcutNotCustomized("Align bottom");
  });
});

test.describe("Duplicate Shortcut Prevention", () => {
  test("Assigning same shortcut to two actions shows conflict and disables first", async ({
    page,
  }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expandSubsection("Alignment");

    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.clickEditShortcut("Align left");
    await shortcutsPage.pressKey("Control+y");

    await expect(
      page.getByRole("alert").filter({ hasText: /Combination assigned to/i }),
    ).toBeVisible();

    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align left");

    const exported = await shortcutsPage.getExportedJson();
    expect(exported.workspace).toMatchObject({
      "align-left": "ctrl+y",
      "align-bottom": "",
    });
  });
});

test.describe("Shortcut Round-Trip", () => {
  test("Export, reset, import restores configuration", async ({ page }) => {
    const shortcutsPage = new ShortcutsPage(page);
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    const exported = await shortcutsPage.getExportedJson();

    await shortcutsPage.restoreAllShortcuts();
    await shortcutsPage.expectShortcutNotCustomized("Align bottom");

    await shortcutsPage.importShortcuts(exported);
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    const reExported = await shortcutsPage.getExportedJson();
    expect(reExported).toEqual(exported);
  });
});
