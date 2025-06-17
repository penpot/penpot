import { expect } from "@playwright/test";
import { WorkspacePage } from "./WorkspacePage";

export class WasmWorkspacePage extends WorkspacePage {
  static async init(page) {
    await super.init(page);
    await WorkspacePage.mockConfigFlags(page, [
      "enable-feature-render-wasm",
      "enable-render-wasm-dpr",
    ]);

    await page.addInitScript(() => {
      document.addEventListener("wasm:set-objects-finished", () => {
        window.wasmSetObjectsFinished = true;
      });
    });
  }

  constructor(page) {
    super(page);
    this.canvas = page.getByTestId("canvas-wasm-shapes");
  }

  async waitForFirstRender() {
    await expect(this.pageName).toHaveText("Page 1");
    await this.canvas.waitFor({ state: "visible" });
    await this.page.waitForFunction(() => {
      return window.wasmSetObjectsFinished;
    });
  }

  static async mockGoogleFont(page, fontSlug, assetFilename, options = {}) {
    const url = new RegExp(`/internal/gfonts/font/${fontSlug}`);
    return await page.route(url, (route) =>
      route.fulfill({
        status: 200,
        path: `playwright/data/${assetFilename}`,
        contentType: "application/font-ttf",
        ...options,
      }),
    );
  }

  async mockGoogleFont(fontSlug, assetFilename, options) {
    return WasmWorkspacePage.mockGoogleFont(
      this.page,
      fontSlug,
      assetFilename,
      options,
    );
  }
}
