import { expect } from "@playwright/test";
import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class WorkspacePage extends BaseWebSocketPage {
  /**
   * This should be called on `test.beforeEach`.
   *
   * @param {Page} page
   * @returns
   */
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);

    await BaseWebSocketPage.mockRPC(
      page,
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-team-users?file-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-empty.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-project?id=*",
      "workspace/get-project-default.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-team?id=*",
      "workspace/get-team-default.json",
    );
    await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams.json");

    await BaseWebSocketPage.mockRPC(
      page,
      "get-team-members?team-id=*",
      "logged-in-user/get-team-members-your-penpot.json",
    );

    await BaseWebSocketPage.mockRPC(
      page,
      "get-profiles-for-file-comments?file-id=*",
      "workspace/get-profile-for-file-comments.json",
    );
  }

  static anyTeamId = "c7ce0794-0992-8105-8004-38e630f7920a";
  static anyProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";
  static anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
  static anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

  #ws = null;

  constructor(page) {
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
    this.tokenThemesSetsSidebar = page.getByTestId("token-themes-sets-sidebar");
    this.tokensSidebar = page.getByTestId("tokens-sidebar");
    this.tokenSetItems = page.getByTestId("tokens-set-item");
    this.tokenSetGroupItems = page.getByTestId("tokens-set-group-item");
    this.tokenContextMenuForToken = page.getByTestId(
      "tokens-context-menu-for-token",
    );
  }

  async goToWorkspace({
    fileId = WorkspacePage.anyFileId,
    pageId = WorkspacePage.anyPageId,
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
    await expect(this.pageName).toHaveText("Page 1");
  }

  async sendPresenceMessage(fixture) {
    await this.#ws.mockMessage(JSON.stringify(fixture));
  }

  async cleanUp() {
    await this.#ws.mockClose();
  }

  async setupEmptyFile() {
    await this.mockRPC(
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );
    await this.mockRPC(
      "get-team-users?file-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await this.mockRPC(
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-empty.json",
    );
    await this.mockRPC(
      "get-project?id=*",
      "workspace/get-project-default.json",
    );
    await this.mockRPC("get-team?id=*", "workspace/get-team-default.json");
    await this.mockRPC(
      "get-profiles-for-file-comments?file-id=*",
      "workspace/get-profile-for-file-comments.json",
    );
    await this.mockRPC(/get\-file\?/, "workspace/get-file-blank.json");
    await this.mockRPC(
      "get-file-object-thumbnails?file-id=*",
      "workspace/get-file-object-thumbnails-blank.json",
    );
    await this.mockRPC(
      "get-font-variants?team-id=*",
      "workspace/get-font-variants-empty.json",
    );
    await this.mockRPC(
      "get-file-fragment?file-id=*",
      "workspace/get-file-fragment-blank.json",
    );
    await this.mockRPC(
      "get-file-libraries?file-id=*",
      "workspace/get-file-libraries-empty.json",
    );
  }

  async clickWithDragViewportAt(x, y, width, height) {
    await this.page.waitForTimeout(100);
    await this.viewport.hover({ position: { x, y } });
    await this.page.mouse.down();
    await this.viewport.hover({ position: { x: x + width, y: y + height } });
    await this.page.mouse.up();
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
    await layer.click(clickOptions);
  }

  async clickToggableLayer(name, clickOptions = {}) {
    const layer = this.layers
      .getByTestId("layer-row")
      .filter({ has: this.page.getByText(name) });
    await layer.getByRole("button").click(clickOptions);
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
      .getByRole("button", { name: "Color Palette (Alt+P)" })
      .click(clickOptions);
  }

  async clickColorPalette(clickOptions = {}) {
    await this.palette
      .getByRole("button", { name: "Color Palette (Alt+P)" })
      .click(clickOptions);
  }

  async clickTogglePalettesVisibility(clickOptions = {}) {
    await this.togglePalettesVisibility.click(clickOptions);
  }
}
