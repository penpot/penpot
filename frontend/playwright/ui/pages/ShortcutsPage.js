import { expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { BaseWebSocketPage } from "./BaseWebSocketPage";

function decodeKeyword(value) {
  if (typeof value === "string" && value.startsWith("~:")) {
    return value.slice(2);
  }
  if (typeof value === "string" && value.startsWith("~$")) {
    return value.slice(2);
  }
  return value;
}

function decodeTransit(data) {
  if (Array.isArray(data)) {
    if (data[0] === "^ ") {
      const result = {};
      for (let i = 1; i < data.length; i += 2) {
        const key = decodeTransit(data[i]);
        const value = decodeTransit(data[i + 1]);
        result[key] = value;
      }
      return result;
    }
    return data.map(decodeTransit);
  }

  if (data !== null && typeof data === "object") {
    const result = {};
    for (const [key, value] of Object.entries(data)) {
      result[decodeKeyword(key)] = decodeTransit(value);
    }
    return result;
  }

  return decodeKeyword(data);
}

function encodeKeyword(key) {
  return `~:${key}`;
}

function encodeTransit(data) {
  if (data === null || typeof data === "boolean" || typeof data === "number") {
    return data;
  }

  if (typeof data === "string") {
    return data;
  }

  if (Array.isArray(data)) {
    return data.map(encodeTransit);
  }

  const result = {};
  for (const [key, value] of Object.entries(data)) {
    result[encodeKeyword(key)] = encodeTransit(value);
  }
  return result;
}

function decodeRequestBody(body) {
  try {
    return decodeTransit(JSON.parse(body));
  } catch {
    return null;
  }
}

export class ShortcutsPage extends BaseWebSocketPage {
  static async init(page) {
    await super.init(page);

    const profileText = await readFile(
      "playwright/data/logged-in-user/get-profile-logged-in.json",
      "utf-8",
    );
    const baseProfile = decodeTransit(JSON.parse(profileText));
    let customShortcuts = null;

    await page.route("**/api/main/methods/get-profile", (route) => {
      const profile = JSON.parse(JSON.stringify(baseProfile));
      if (customShortcuts) {
        profile.props["custom-shortcuts"] = customShortcuts;
      }
      route.fulfill({
        status: 200,
        contentType: "application/transit+json",
        body: JSON.stringify(encodeTransit(profile)),
      });
    });

    await page.route(
      "**/api/main/methods/update-profile-props",
      async (route, request) => {
        const decoded = decodeRequestBody(request.postData() ?? "{}");
        if (decoded?.props && "custom-shortcuts" in decoded.props) {
          customShortcuts = decoded.props["custom-shortcuts"];
        }
        route.fulfill({
          status: 200,
          contentType: "application/transit+json",
          body: "{}",
        });
      },
    );

    await super.mockRPC(
      page,
      "get-teams",
      "logged-in-user/get-teams-default.json",
    );
  }

  static async initWithShortcuts(page) {
    await super.init(page);

    await super.mockRPCs(page, {
      "get-profile": "logged-in-user/get-profile-logged-in.json",
      "get-teams": "logged-in-user/get-teams-default.json",
      "update-profile-props":
        "logged-in-user/update-profile-with-shortcuts.json",
    });
  }

  static async initWithNoShortcuts(page) {
    await super.init(page);

    await super.mockRPCs(page, {
      "get-profile": "logged-in-user/get-profile-no-shortcuts.json",
      "get-teams": "logged-in-user/get-teams-default.json",
      "update-profile-props":
        "logged-in-user/update-profile-with-shortcuts.json",
    });
  }

  constructor(page) {
    super(page);

    this.shortcutsSection = page.getByRole("region", { name: /shortcuts/i });
    this.searchInput = page.getByRole("textbox", { name: /shortcuts/i });

    this.allTab = page.getByRole("tab", { name: "All" });
    this.personalizedTab = page.getByRole("tab", { name: "Personalized" });
    this.disabledTab = page.getByRole("tab", { name: "Disabled" });

    this.restoreAllButton = page.getByRole("button", {
      name: /restore all/i,
    });

    this.importExportButton = page.getByRole("button", {
      name: /import\/export/i,
    });

    this.fileInput = page.locator('input[type="file"]');
  }

  async goToShortcuts() {
    await this.page.goto("#/settings/shortcuts");
    await expect(this.shortcutsSection).toBeVisible();
  }

  async searchForShortcut(term) {
    await this.searchInput.fill(term);
  }

  async clearSearch() {
    await this.searchInput.clear();
  }

  async clickTab(tabName) {
    const tab = this.page.getByRole("tab", { name: tabName });
    await tab.click();
  }

  async getShortcutRow(shortcutName) {
    return this.page.getByRole("listitem", {
      name: new RegExp(`^${shortcutName}$`, "i"),
      includeHidden: true,
    });
  }

  async expandSubsection(subsectionName) {
    const subsectionButton = this.page.getByRole("button", {
      name: subsectionName,
    });
    await subsectionButton.click();
  }

  async clickEditShortcut(shortcutName) {
    const button = this.page.getByRole("button", {
      name: new RegExp(`Edit ${shortcutName}`, "i"),
    });
    await button.click();
    const recordingArea = this.page.getByText("Press the key combination");
    await expect(recordingArea).toBeVisible();
    await recordingArea.focus();
  }

  async pressKey(key) {
    await this.page.keyboard.press(key);
  }

  async pressKeyCombo(keys) {
    for (const key of keys) {
      await this.page.keyboard.down(key);
    }
    for (const key of [...keys].reverse()) {
      await this.page.keyboard.up(key);
    }
  }

  async saveShortcut() {
    const saveButton = this.page.getByRole("button", { name: /save/i });
    await saveButton.click();
  }

  async cancelEdit() {
    const cancelButton = this.page.getByRole("button", { name: /cancel/i });
    await cancelButton.click();
  }

  async resetShortcut(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    const resetButton = row.getByRole("button", { name: /reset/i });
    await resetButton.click();
  }

  async disableShortcut(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await row
      .getByRole("button", { name: new RegExp(`Edit ${shortcutName}`, "i") })
      .click();
    const disableButton = this.page.getByRole("button", { name: /disable/i });
    await disableButton.click();
  }

  async expectShortcutCustomized(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).toHaveAttribute("data-customized", "true");
  }

  async expectShortcutNotCustomized(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).not.toHaveAttribute("data-customized", "true");
  }

  async expectShortcutHasConflict(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).toHaveAttribute("data-conflict", "true");
  }

  async expectShortcutVisible(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).toBeVisible();
  }

  async expectShortcutHidden(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).not.toBeVisible();
  }

  async restoreAllShortcuts() {
    await this.restoreAllButton.click();
    const confirmButton = this.page.getByRole("button", {
      name: "Restore",
      exact: true,
    });
    await confirmButton.click();
  }

  async exportShortcuts() {
    await this.importExportButton.click();
    const exportButton = this.page.getByRole("menuitem", { name: /export/i });
    await exportButton.click();
  }

  async importShortcuts(jsonData) {
    await this.importExportButton.click();
    const importButton = this.page.getByRole("menuitem", { name: /import$/i });
    await importButton.click();

    const buffer = Buffer.from(JSON.stringify(jsonData), "utf-8");
    await this.fileInput.setInputFiles({
      name: "shortcuts.json",
      mimeType: "application/json",
      buffer,
    });
  }

  async importShortcutsRaw(content) {
    await this.importExportButton.click();
    const importButton = this.page.getByRole("menuitem", { name: /import$/i });
    await importButton.click();

    const data =
      typeof content === "string" ? content : JSON.stringify(content);
    const buffer = Buffer.from(data, "utf-8");
    await this.fileInput.setInputFiles({
      name: "shortcuts.json",
      mimeType: "application/json",
      buffer,
    });
  }

  async getExportedJson() {
    const [download] = await Promise.all([
      this.page.waitForEvent("download"),
      this.exportShortcuts(),
    ]);

    expect(download.suggestedFilename()).toBe("penpot-shortcuts.json");

    const path = await download.path();
    const content = await readFile(path, "utf-8");
    return JSON.parse(content);
  }

  async expectShortcutDisabled(shortcutName) {
    const row = await this.getShortcutRow(shortcutName);
    await expect(row).toHaveAttribute("data-customized", "true");
    await expect(row.locator("use[href*='broken-link']")).toBeVisible();
  }
}

export default ShortcutsPage;
