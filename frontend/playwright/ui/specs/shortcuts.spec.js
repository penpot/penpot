import { test, expect } from "@playwright/test";
import ShortcutsPage from "../pages/ShortcutsPage";

// The import and reset rules are unit-tested in
// frontend/test/frontend_tests/ui/settings_shortcuts_test.cljs (77 tests):
// validation, sanitising, the command grammar, reserved combinations, the
// profile merge, the diff modal and the restore modal. What remains here is
// the wiring only a browser proves: the file input, the diff modal, the RPC
// write, the download, the alert, the reload and the reset flows.
//
// Removed tests and what carries their rule now:
// - "Shortcuts page loads correctly" -> goToShortcuts() in every test below.
// - "Tabs are visible and clickable" -> the reset test's empty-tab step.
// - "User can edit a shortcut" -> the persistence and conflict tests.
// - "Import invalid JSON syntax" -> the invalid-file test, first step.
// - "Import valid JSON with invalid schema" -> the invalid-file test,
//   second step. The data rule is validate-imported-shortcuts (unit).
// - "Import JSON without workspace context accepted" -> the
//   accepts-missing-workspace unit test plus the positive import test.
// - "Import JSON with unsupported shortcut format" -> the same gate as the
//   invalid-file test. The data rule is validate-imported-shortcuts (unit).
// - "Import conflicting shortcuts" -> the conflict test plus
//   import-custom-shortcuts-disables-conflicting-default (unit).
// - "Export includes disabled shortcuts" -> the export test, which imports
//   one bound and one empty entry.
// - "Reset button shows pending message..." -> the reset test's last step.
// - "Cancel editing does not save changes" -> the persistence test.
// - "Assigning same shortcut to two actions..." -> the conflict test.
// - "Export, reset, import restores configuration" -> the export, reset and
//   import tests, one behavior each.

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
  });

  await test.step("A parsed file with no applicable entry is rejected", async () => {
    await shortcutsPage.importShortcuts({
      workspace: { "unknown-shortcut": "ctrl+y" },
    });
    await expect(alert).toBeVisible();
  });
});

test("Export includes customized and disabled shortcuts", async ({ page }) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();

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

test("Detects conflict and disables old shortcut on save", async ({ page }) => {
  const shortcutsPage = new ShortcutsPage(page);
  await shortcutsPage.goToShortcuts();

  await shortcutsPage.expandSubsection("Alignment");
  await shortcutsPage.clickEditShortcut("Align bottom");
  await shortcutsPage.pressKey("Alt+a");

  await expect(
    page.getByRole("alert").filter({ hasText: /Already assigned to/i }),
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

    await shortcutsPage.saveShortcut();

    await shortcutsPage.expectShortcutNotCustomized("Align bottom");
  });
});
