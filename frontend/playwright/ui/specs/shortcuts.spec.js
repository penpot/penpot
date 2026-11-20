import { test, expect } from "@playwright/test";
import ShortcutsPage from "../pages/ShortcutsPage";

// The import and reset rules are unit-tested in
// frontend/test/frontend_tests/ui/settings_shortcuts_test.cljs (77 tests):
// validation, sanitising, the command grammar, reserved combinations, the
// profile merge, the diff modal and the restore modal. What remains here is
// the wiring only a browser proves: the file input, the diff modal, the RPC
// write, the download, the alert, the reload and the reset flows.

test.beforeEach(async ({ page }) => {
  await ShortcutsPage.init(page);
});

test("Import valid custom shortcuts", async ({ page }) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();

  await shortcutsPage.importShortcuts({
    workspace: { "align-bottom": "ctrl+y" },
  });

  await shortcutsPage.confirmImportApply();

  await shortcutsPage.expectShortcutCustomized("Align bottom");

  await shortcutsPage.clickTab("Custom");
  await shortcutsPage.searchForShortcut("Align bottom");
  await shortcutsPage.expectShortcutVisible("Align bottom");
});

test("A file with nothing to apply shows the alert", async ({ page }) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();

  const alert = page
    .getByRole("alert")
    .filter({ hasText: /not a valid shortcuts export/i });

  await test.step("Broken JSON does not parse", async () => {
    await shortcutsPage.importShortcutsRaw("{invalid");
    await expect(alert).toBeVisible();

    // The error toast carries no timeout: it stays until another
    // notification replaces it or the route changes, so it has to go before
    // the next import can prove anything.
    await alert.getByRole("button", { name: "Close" }).click();
    await expect(alert).toBeHidden();
  });

  await test.step("A parsed file with no applicable entry is rejected", async () => {
    await shortcutsPage.importShortcuts({
      workspace: { "unknown-shortcut": "ctrl+y", "align-bottom": 123 },
    });
    await expect(alert).toBeVisible();
  });
});

test("Export includes customized and disabled shortcuts and imports back", async ({
  page,
}) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();
  await shortcutsPage.expandSubsection("Alignment");

  await shortcutsPage.importShortcuts({
    workspace: {
      "align-bottom": "ctrl+y",
      "align-left": "",
    },
  });

  await shortcutsPage.confirmImportApply();
  await shortcutsPage.expectShortcutCustomized("Align bottom");

  const exported = await shortcutsPage.getExportedJson();
  expect(exported.workspace).toMatchObject({
    "align-bottom": "ctrl+y",
    "align-left": "",
  });

  // The file the page wrote must pass the importer's own validation.
  await shortcutsPage.restoreAllShortcuts();
  await shortcutsPage.expectShortcutNotCustomized("Align bottom");

  await shortcutsPage.importShortcuts(exported);
  await shortcutsPage.confirmImportApply();

  await shortcutsPage.expectShortcutCustomized("Align bottom");
  await shortcutsPage.expectShortcutDisabled("Align left");
});

test("Custom shortcuts persist after reload and cancelled edits do not", async ({
  page,
}) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();

  await test.step("A cancelled edit is not saved", async () => {
    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align left");
    await shortcutsPage.pressKey("Control+i");
    await shortcutsPage.cancelEdit();

    await shortcutsPage.expectShortcutNotCustomized("Align left");
  });

  await test.step("A saved edit survives the reload", async () => {
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await page.reload();
    await shortcutsPage.goToShortcuts();

    await shortcutsPage.expectShortcutCustomized("Align bottom");
    await shortcutsPage.expectShortcutNotCustomized("Align left");
  });
});

test("Detects conflicts on edit and on import", async ({ page }) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();
  await shortcutsPage.expandSubsection("Alignment");

  const conflictAlert = page
    .getByRole("alert")
    .filter({ hasText: /Already assigned to/i });

  await test.step("Conflict with a default binding", async () => {
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Alt+a");
    await expect(conflictAlert).toBeVisible();

    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");
    await shortcutsPage.expectShortcutDisabled("Align left");
  });

  // Align bottom now holds alt+a as a custom binding.
  await test.step("Conflict with a custom binding", async () => {
    await shortcutsPage.clickEditShortcut("Align left");
    await shortcutsPage.pressKey("Alt+a");
    await expect(conflictAlert).toBeVisible();

    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align left");
    await shortcutsPage.expectShortcutDisabled("Align bottom");
  });

  // The importer disables the default binding a file takes over, so this
  // runs the diff modal's apply path with a default binding in the way.
  await test.step("Import takes a key a default binding holds", async () => {
    await shortcutsPage.restoreAllShortcuts();
    await shortcutsPage.expectShortcutNotCustomized("Align bottom");

    await shortcutsPage.importShortcuts({
      workspace: { "align-bottom": "alt+a" },
    });
    await shortcutsPage.confirmImportApply();

    await shortcutsPage.expectShortcutCustomized("Align bottom");
    await shortcutsPage.expectShortcutDisabled("Align left");

    const exported = await shortcutsPage.getExportedJson();
    expect(exported.workspace).toMatchObject({
      "align-bottom": "alt+a",
      "align-left": "",
    });
  });
});

test("Reset restores defaults and empty tabs show their placeholders", async ({
  page,
}) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();

  await test.step("Reset all restores the defaults", async () => {
    await shortcutsPage.importShortcuts({
      workspace: { "align-bottom": "ctrl+y" },
    });
    await shortcutsPage.confirmImportApply();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.restoreAllShortcuts();

    await shortcutsPage.expectShortcutNotCustomized("Align bottom");
  });

  await test.step("Empty tabs show their placeholders", async () => {
    await shortcutsPage.clickTab("Custom");
    await expect(shortcutsPage.personalizedTab).toHaveAttribute(
      "aria-selected",
      "true",
    );
    await expect(page.getByText(/No custom shortcuts yet/i)).toBeVisible();

    await shortcutsPage.clickTab("Disabled");
    await expect(shortcutsPage.disabledTab).toHaveAttribute(
      "aria-selected",
      "true",
    );
    await expect(page.getByText(/No disabled shortcuts/i)).toBeVisible();

    await shortcutsPage.clickTab("All");
    await expect(shortcutsPage.allTab).toHaveAttribute("aria-selected", "true");
  });

  await test.step("A single reset waits for the save", async () => {
    await shortcutsPage.expandSubsection("Alignment");
    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.pressKey("Control+y");
    await shortcutsPage.saveShortcut();
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.clickEditShortcut("Align bottom");
    await shortcutsPage.resetShortcut("Align bottom");

    await expect(
      page.getByText(/Saving will reset this shortcut/i),
    ).toBeVisible();

    // The reset is pending: it only lands with the save.
    await shortcutsPage.expectShortcutCustomized("Align bottom");

    await shortcutsPage.saveShortcut();

    await shortcutsPage.expectShortcutNotCustomized("Align bottom");
  });
});
