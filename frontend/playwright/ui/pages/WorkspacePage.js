import { expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { BaseWebSocketPage } from "./BaseWebSocketPage";
import { Transit } from "../../helpers/Transit";

export class WorkspacePage extends BaseWebSocketPage {
  static TextEditor = class TextEditor {
    constructor(workspacePage) {
      this.workspacePage = workspacePage;

      // locators.
      this.fontSize = this.workspacePage.rightSidebar.getByRole("textbox", {
        name: "Font Size",
      });
      this.lineHeight = this.workspacePage.rightSidebar.getByRole("textbox", {
        name: "Line Height",
      });
      this.letterSpacing = this.workspacePage.rightSidebar.getByRole(
        "textbox",
        {
          name: "Letter Spacing",
        },
      );
    }

    get page() {
      return this.workspacePage.page;
    }

    async waitForStyle(locator, styleName) {
      return locator.evaluate(
        (element, styleName) => element.style.getPropertyValue(styleName),
        styleName,
      );
    }

    async waitForEditor() {
      return this.page.waitForSelector('[data-itype="editor"]');
    }

    async waitForRoot() {
      return this.page.waitForSelector('[data-itype="root"]');
    }

    async waitForParagraph(nth) {
      if (!nth) {
        return this.page.waitForSelector('[data-itype="paragraph"]');
      }
      return this.page.waitForSelector(
        `[data-itype="paragraph"]:nth-child(${nth})`,
      );
    }

    async waitForParagraphStyle(nth, styleName) {
      const paragraph = await this.waitForParagraph(nth);
      return this.waitForStyle(paragraph, styleName);
    }

    async waitForTextSpan(nth = 0) {
      if (!nth) {
        return this.page.waitForSelector('[data-itype="span"]');
      }
      return this.page.waitForSelector(
        `[data-itype="span"]:nth-child(${nth})`,
      );
    }

    async waitForTextSpanContent(nth = 0) {
      const textSpan = await this.waitForTextSpan(nth);
      const textContent = await textSpan.textContent();
      return textContent;
    }

    async waitForTextSpanStyle(nth, styleName) {
      const textSpan = await this.waitForTextSpan(nth);
      return this.waitForStyle(textSpan, styleName);
    }

    async startEditing() {
      await this.page.keyboard.press("Enter");
      return this.waitForEditor();
    }

    stopEditing() {
      return this.page.keyboard.press("Escape");
    }

    async moveToLeft(amount = 0) {
      for (let i = 0; i < amount; i++) {
        await this.page.keyboard.press("ArrowLeft");
      }
    }

    async moveToRight(amount = 0) {
      for (let i = 0; i < amount; i++) {
        await this.page.keyboard.press("ArrowRight");
      }
    }

    async moveFromStart(offset = 0) {
      await this.page.keyboard.press("ArrowLeft");
      await this.moveToRight(offset);
    }

    async moveFromEnd(offset = 0) {
      await this.page.keyboard.press("ArrowRight");
      await this.moveToLeft(offset);
    }

    async selectFromStart(length, offset = 0) {
      await this.moveFromStart(offset);
      await this.page.keyboard.down("Shift");
      await this.moveToRight(length);
      await this.page.keyboard.up("Shift");
    }

    async selectFromEnd(length, offset = 0) {
      await this.moveFromEnd(offset);
      await this.page.keyboard.down("Shift");
      await this.moveToLeft(length);
      await this.page.keyboard.up("Shift");
    }

    async changeNumericInput(locator, newValue) {
      await expect(locator).toBeVisible();
      await locator.focus();
      await locator.fill(`${newValue}`);
      await locator.blur();
    }

    changeFontSize(newValue) {
      return this.changeNumericInput(this.fontSize, newValue);
    }

    changeLineHeight(newValue) {
      return this.changeNumericInput(this.lineHeight, newValue);
    }

    changeLetterSpacing(newValue) {
      return this.changeNumericInput(this.letterSpacing, newValue);
    }
  };

  /**
   * This should be called on `test.beforeEach`.
   *
   * @param {Page} page
   * @returns
   */
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);

    await BaseWebSocketPage.mockRPCs(page, {
      "get-profile": "logged-in-user/get-profile-logged-in.json",
      "get-team-users?file-id=*":
        "logged-in-user/get-team-users-single-user.json",
      "get-comment-threads?file-id=*":
        "workspace/get-comment-threads-empty.json",
      "get-project?id=*": "workspace/get-project-default.json",
      "get-team?id=*": "workspace/get-team-default.json",
      "get-teams": "get-teams.json",
      "get-team-members?team-id=*":
        "logged-in-user/get-team-members-your-penpot.json",
      "get-profiles-for-file-comments?file-id=*":
        "workspace/get-profile-for-file-comments.json",
      "update-profile-props": "workspace/update-profile-empty.json",
    });
  }

  static anyTeamId = "c7ce0794-0992-8105-8004-38e630f7920a";
  static anyProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";
  static anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
  static anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

  /**
   * WebSocket mock
   *
   * @type {MockWebSocketHelper}
   */
  #ws = null;

  /**
   * Constructor
   *
   * @param {Page} page
   * @param {} [options]
   */
  constructor(page, options) {
    super(page);
    this.pageName = page.getByTestId("page-name");

    this.presentUserListItems = page
      .getByTestId("active-users-list")
      .getByAltText("Princesa Leia");

    this.viewport = page.getByTestId("viewport");
    this.rootShape = page.locator(
      `[id="shape-00000000-0000-0000-0000-000000000000"]`,
    );
    this.toolbarOptions = page.getByTestId("toolbar-options");
    this.rectShapeButton = page.getByRole("button", { name: "Rectangle (R)" });
    this.ellipseShapeButton = page.getByRole("button", { name: "Ellipse (E)" });
    this.moveButton = page.getByRole("button", { name: "Move (V)" });
    this.boardButton = page.getByRole("button", { name: "Board (B)" });
    this.toggleToolbarButton = page.getByRole("button", {
      name: "Toggle toolbar",
    });
    this.colorpicker = page.getByTestId("colorpicker");
    this.layers = page.getByTestId("layer-tree");
    this.palette = page.getByTestId("palette");
    this.sidebar = page.getByTestId("left-sidebar");
    this.rightSidebar = page.getByTestId("right-sidebar");
    this.selectionRect = page.getByTestId("workspace-selection-rect");
    this.horizontalScrollbar = page.getByTestId("horizontal-scrollbar");
    this.librariesModal = page.getByTestId("libraries-modal");
    this.togglePalettesVisibility = page.getByTestId(
      "toggle-palettes-visibility",
    );
    this.tokensUpdateCreateModal = page.getByTestId(
      "token-update-create-modal",
    );
    this.tokenThemeUpdateCreateModal = page.getByTestId(
      "token-theme-update-create-modal",
    );
    this.tokenThemesSetsSidebar = page.getByTestId("token-management-sidebar");
    this.tokensSidebar = page.getByTestId("tokens-sidebar");
    this.tokenSetItems = page.getByTestId("tokens-set-item");
    this.tokenSetGroupItems = page.getByTestId("tokens-set-group-item");
    this.tokenContextMenuForToken = page.getByTestId(
      "tokens-context-menu-for-token",
    );
    this.tokenContextMenuForSet = page.getByTestId(
      "tokens-context-menu-for-set",
    );
    this.contextMenuForShape = page.getByTestId("context-menu");
    if (options?.textEditor) {
      this.textEditor = new WorkspacePage.TextEditor(this);
    }
  }

  async goToWorkspace({
    fileId = this.fileId ?? WorkspacePage.anyFileId,
    pageId = this.pageId ?? WorkspacePage.anyPageId,
  } = {}) {
    await this.page.goto(
      `/#/workspace?team-id=${WorkspacePage.anyTeamId}&file-id=${fileId}&page-id=${pageId}`,
    );

    this.#ws = await this.waitForNotificationsWebSocket();
    await this.#ws.mockOpen();
    await this.#waitForWebSocketReadiness();
  }

  async #waitForWebSocketReadiness() {
    // TODO: find a better event to settle whether the app is ready to receive notifications via ws
    await expect(this.pageName).toHaveText("Page 1", { timeout: 30000 })
  }

  async sendPresenceMessage(fixture) {
    await this.#ws.mockMessage(JSON.stringify(fixture));
  }

  async cleanUp() {
    await this.#ws.mockClose();
  }

  async setupEmptyFile() {
    await this.mockRPCs({
      "get-profile": "logged-in-user/get-profile-logged-in.json",
      "get-team-users?file-id=*":
        "logged-in-user/get-team-users-single-user.json ",
      "get-comment-threads?file-id=*":
        "workspace/get-comment-threads-empty.json",
      "get-project?id=*": "workspace/get-project-default.json",
      "get-team?id=*": "workspace/get-team-default.json",
      "get-profiles-for-file-comments?file-id=*":
        "workspace/get-profile-for-file-comments.json",
      "get-file-object-thumbnails?file-id=*":
        "workspace/get-file-object-thumbnails-blank.json",
      "get-font-variants?team-id=*": "workspace/get-font-variants-empty.json",
      "get-file-fragment?file-id=*": "workspace/get-file-fragment-blank.json",
      "get-file-libraries?file-id=*": "workspace/get-file-libraries-empty.json",
    });

    if (this.textEditor) {
      await this.mockRPC("update-file?id=*", "text-editor/update-file.json");
    }

    // by default we mock the blank file.
    await this.mockGetFile("workspace/get-file-blank.json");
  }

  async mockGetFile(jsonFilename, options) {
    const page = this.page;
    const jsonPath = `playwright/data/${jsonFilename}`;
    const body = await readFile(jsonPath, "utf-8");
    const payload = JSON.parse(body);

    const fileId = Transit.get(payload, "id");
    const pageId = Transit.get(payload, "data", "pages", 0);
    const teamId = Transit.get(payload, "team-id");

    this.fileId = fileId ?? this.anyFileId;
    this.pageId = pageId ?? this.anyPageId;
    this.teamId = teamId ?? this.anyTeamId;

    const path = /get\-file\?/;
    const url = typeof path === "string" ? `**/api/main/methods/${path}` : path;
    const interceptConfig = {
      status: 200,
      contentType: "application/transit+json",
      ...options,
    };
    return page.route(url, (route) =>
      route.fulfill({
        ...interceptConfig,
        body,
      }),
    );
    // await this.mockRPC(/get\-file\?/, jsonFile);
  }

  async mockGetAsset(regex, asset) {
    await this.mockRPC(new RegExp(regex), asset);
  }

  async setupFileWithComments() {
    await this.mockRPCs({
      "get-comment-threads?file-id=*":
        "workspace/get-comment-threads-unread.json",
      "get-file-fragment?file-id=*&fragment-id=*":
        "viewer/get-file-fragment-single-board.json",
      "get-comments?thread-id=*": "workspace/get-thread-comments.json",
      "update-comment-thread-status":
        "workspace/update-comment-thread-status.json",
    });
  }

  async clickWithDragViewportAt(x, y, width, height) {
    await this.page.waitForTimeout(100);
    await this.viewport.hover({ position: { x, y } });
    await this.page.mouse.down();
    await this.viewport.hover({ position: { x: x + width, y: y + height } });
    await this.page.mouse.up();
  }

  async clickAt(x, y) {
    await this.page.waitForTimeout(100);
    await this.viewport.hover({ position: { x, y } });
    await this.page.mouse.down();
    await this.page.mouse.up();
  }

  /**
   * Clicks and moves from the coordinates x1,y1 to x2,y2
   *
   * @param {number} x1
   * @param {number} y1
   * @param {number} x2
   * @param {number} y2
   */
  async clickAndMove(x1, y1, x2, y2) {
    await this.page.waitForTimeout(100);
    await this.viewport.hover({ position: { x: x1, y: y1 } });
    await this.page.mouse.down();
    await this.viewport.hover({ position: { x: x2, y: y2 } });
    await this.page.mouse.up();
  }

  /**
   * Creates a new Text Shape in the specified coordinates
   * with an initial text.
   *
   * @param {number} x1
   * @param {number} y1
   * @param {number} x2
   * @param {number} y2
   * @param {string} initialText
   * @param {*} [options]
   */
  async createTextShape(x1, y1, x2, y2, initialText, options) {
    const timeToWait = options?.timeToWait ?? 100;
    await this.page.keyboard.press("T");
    await this.page.waitForTimeout(timeToWait);
    await this.clickAndMove(x1, y1, x2, y2);
    await expect(this.page.getByTestId("text-editor")).toBeVisible();

    if (initialText) {
      await this.page.keyboard.type(initialText);
    }
  }

  /**
   * Copies the selected element into the clipboard, or copy the
   * content of the locator into the clipboard.
   *
   * @returns {Promise<void>}
   */
  async copy(kind = "keyboard", locator = undefined) {
    if (kind === "context-menu" && locator) {
      await locator.click({ button: "right" });
      await this.page.getByText("Copy", { exact: true }).click();
    } else {
      await this.page.keyboard.press("ControlOrMeta+C");
    }
    // wait for the clipboard to be updated
    await this.page.waitForFunction(async () => {
      const content = await navigator.clipboard.readText()
      return content !== "";
    }, { timeout: 1000 });
  }

  async cut(kind = "keyboard", locator = undefined) {
    if (kind === "context-menu" && locator) {
      await locator.click({ button: "right" });
      await this.page.getByText("Cut", { exact: true }).click();
    } else {
      await this.page.keyboard.press("ControlOrMeta+X");
    }
    // wait for the clipboard to be updated
    await this.page.waitForFunction(async () => {
      const content = await navigator.clipboard.readText()
      return content !== "";
    }, { timeout: 1000 });

  }

  /**
   * Pastes something from the clipboard.
   *
   * @param {"keyboard"|"context-menu"} [kind="keyboard"]
   * @returns {Promise<void>}
   */
  async paste(kind = "keyboard") {
    if (kind === "context-menu") {
      await this.viewport.click({ button: "right" });
      return this.page.getByText("Paste", { exact: true }).click();
    }
    return this.page.keyboard.press("ControlOrMeta+V");
  }

  async panOnViewportAt(x, y, width, height) {
    await this.page.waitForTimeout(100);
    await this.viewport.hover({ position: { x, y } });
    await this.page.mouse.down({ button: "middle" });
    await this.viewport.hover({ position: { x: x + width, y: y + height } });
    await this.page.mouse.up({ button: "middle" });
  }

  async togglePages() {
    const pagesToggle = this.page.getByText("Pages");
    await pagesToggle.click();
  }

  async moveSelectionToShape(name) {
    await this.page.locator("rect.viewport-selrect").hover();
    await this.page.mouse.down();
    await this.viewport.getByText(name).first().hover({ force: true });
    await this.page.mouse.up();
  }

  async clickLeafLayer(name, clickOptions = {}) {
    const layer = this.layers.getByText(name).first();
    await layer.waitFor();
    await layer.click(clickOptions);
    await this.page.waitForTimeout(500);
  }

  async doubleClickLeafLayer(name, clickOptions = {}) {
    await this.clickLeafLayer(name, clickOptions);
    await this.clickLeafLayer(name, clickOptions);
  }

  async clickToggableLayer(name, clickOptions = {}) {
    const layer = this.layers
      .getByTestId("layer-row")
      .filter({ hasText: name });
    const button = layer.getByTestId("toggle-content");

    await expect(button).toBeVisible();
    await button.click(clickOptions);
    await button.waitFor({ ariaExpanded: true });
  }

  async expectSelectedLayer(name) {
    await expect(
      this.layers
        .getByTestId("layer-row")
        .filter({ has: this.page.getByText(name) }),
    ).toHaveClass(/selected/);
  }

  async expectHiddenToolbarOptions() {
    await expect(this.toolbarOptions).toHaveCSS("opacity", "0");
  }

  async clickAssets(clickOptions = {}) {
    await this.sidebar.getByText("Assets").click(clickOptions);
  }
  async clickLayers(clickOptions = {}) {
    await this.sidebar.getByText("Layers").click(clickOptions);
  }

  async openLibrariesModal(clickOptions = {}) {
    await this.sidebar.getByTestId("libraries").click(clickOptions);
    await expect(this.librariesModal).toBeVisible();
  }

  async clickLibrary(name, clickOptions = {}) {
    await this.page
      .getByTestId("library-item")
      .filter({ hasText: name })
      .getByRole("button")
      .click(clickOptions);
  }

  async closeLibrariesModal(clickOptions = {}) {
    await this.librariesModal
      .getByRole("button", { name: "Close" })
      .click(clickOptions);
  }

  async clickColorPalette(clickOptions = {}) {
    await this.palette
      .getByRole("button", { name: /Color Palette/ })
      .click(clickOptions);
  }

  async clickTogglePalettesVisibility(clickOptions = {}) {
    await this.togglePalettesVisibility.click(clickOptions);
  }

  async openTokenThemesModal(clickOptions = {}) {
    await this.tokenThemesSetsSidebar.getByText("Edit").click(clickOptions);
    await expect(this.tokenThemeUpdateCreateModal).toBeVisible();
  }

  async showComments(clickOptions = {}) {
    await this.page
      .getByRole("button", { name: "Comments (C)" })
      .click(clickOptions);
  }
}

export default WorkspacePage;
