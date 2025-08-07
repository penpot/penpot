export class BasePage {
  static async mockRPC(page, path, jsonFilename, options) {
    if (!page) {
      throw new TypeError("Invalid page argument. Must be a Playwright page.");
    }
    if (typeof path !== "string" && !(path instanceof RegExp)) {
      throw new TypeError(
        "Invalid path argument. Must be a string or a RegExp.",
      );
    }

    const url = typeof path === "string" ? `**/api/rpc/command/${path}` : path;
    const interceptConfig = {
      status: 200,
      contentType: "application/transit+json",
      ...options,
    };
    return page.route(url, (route) =>
      route.fulfill({
        ...interceptConfig,
        path: `playwright/data/${jsonFilename}`,
      }),
    );
  }

  static async mockFileMediaAsset(page, assetId, assetFilename, options) {
    const ids = Array.isArray(assetId) ? assetId : [assetId];

    for (const id of ids) {
      const url = `**/assets/by-file-media-id/${id}`;

      await page.route(url, (route) =>
        route.fulfill({
          path: `playwright/data/${assetFilename}`,
          status: 200,
          ...options,
        }),
      );
    }
  }

  static async mockAsset(page, assetId, assetFilename, options) {
    const ids = Array.isArray(assetId) ? assetId : [assetId];

    for (const id of ids) {
      const url = `**/assets/by-id/${id}`;

      await page.route(url, (route) =>
        route.fulfill({
          path: `playwright/data/${assetFilename}`,
          status: 200,
          ...options,
        }),
      );
    }
  }

  static async mockFileMediaAsset(page, assetId, assetFilename, options) {
    const ids = Array.isArray(assetId) ? assetId : [assetId];

    for (const id of ids) {
      const url = `**/assets/by-file-media-id/${id}`;

      await page.route(url, (route) =>
        route.fulfill({
          path: `playwright/data/${assetFilename}`,
          status: 200,
          ...options,
        }),
      );
    }
  }

  static async mockConfigFlags(page, flags) {
    const url = "**/js/config.js?ts=*";
    return await page.route(url, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/javascript",
        body: `var penpotFlags = "${flags.join(" ")}";`,
      }),
    );
  }

  #page = null;

  constructor(page) {
    this.#page = page;
  }

  get page() {
    return this.#page;
  }

  async mockRPC(path, jsonFilename, options) {
    return BasePage.mockRPC(this.page, path, jsonFilename, options);
  }

  async mockConfigFlags(flags) {
    return BasePage.mockConfigFlags(this.page, flags);
  }

  async mockFileMediaAsset(assetId, assetFilename, options) {
    return BasePage.mockFileMediaAsset(
      this.page,
      assetId,
      assetFilename,
      options,
    );
  }

  async mockAsset(assetId, assetFilename, options) {
    return BasePage.mockAsset(this.page, assetId, assetFilename, options);
  }
}

export default BasePage;
