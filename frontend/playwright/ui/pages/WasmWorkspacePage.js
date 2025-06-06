import { expect } from "@playwright/test";
import { WorkspacePage } from "./WorkspacePage";

export class WasmWorkspacePage extends WorkspacePage {
  static async init(page) {
    await super.init(page);
    await WorkspacePage.mockConfigFlags(page, [
      "enable-feature-render-wasm",
      "enable-render-wasm-dpr",
    ]);
  }

  constructor(page) {
    super(page);
    this.canvas = page.getByTestId("canvas-wasm-shapes");
  }

  async waitForCanvasRender() {
    // FIXME: temp workaround. We will need to wait for set-objects to fully finish
    await expect(this.pageName).toHaveText("Page 1");
    await this.canvas.waitFor({ state: "visible" });
    await this.page.waitForTimeout(3000);
  }
}
