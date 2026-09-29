import { test, expect } from "@playwright/test";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";
import { setupTokensFileRender, unfoldTokenType } from "./tokens/helpers";

// Regression tests for issue #7440: scroll position in the left sidebar
// tabs (Layers / Assets / Tokens) must survive tab switches.
// Ported from closed PR #7544 and adapted to the current implementation
// (scroll store held in the sidebar + async restore on mount) and to the
// current fixtures and page objects.

// We allow a small tolerance because content can shift slightly on remount.
const POSITION_TOLERANCE = 20; // pixels

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

async function visibleScrollContainers(page) {
  const containers = page.locator(
    '[data-testid="left-sidebar"] [data-scroll-container="true"]',
  );
  const count = await containers.count();
  const visible = [];
  for (let i = 0; i < count; i++) {
    const el = containers.nth(i);
    // eslint-disable-next-line no-await-in-loop
    if (await el.evaluate((node) => !!node && node.offsetParent !== null)) {
      visible.push(el);
    }
  }
  return visible;
}

async function scrollableContainers(page) {
  const visible = await visibleScrollContainers(page);
  const scrollable = [];
  for (const el of visible) {
    // eslint-disable-next-line no-await-in-loop
    if (
      await el.evaluate((node) => node.scrollHeight > node.clientHeight + 100)
    ) {
      scrollable.push(el);
    }
  }
  return scrollable;
}

async function scrollToBottom(locator) {
  await locator.evaluate((el) => {
    el.scrollTop = el.scrollHeight;
  });
  // Programmatic scrolls dispatch scroll events asynchronously; wait for
  // the save handler before switching tabs.
  await locator.page().waitForTimeout(150);
}

async function expectScrollRestored(locator, expected) {
  // Restore runs in a post-mount effect with frame retries, so poll
  // instead of asserting immediately after the tab click.
  await expect
    .poll(async () => locator.evaluate((el) => el.scrollTop), {
      timeout: 10000,
    })
    .toBeGreaterThanOrEqual(expected - POSITION_TOLERANCE);
}

test("Sidebar scroll position preserved when switching tabs", async ({
  page,
}) => {
  const { tokensSidebar } = await setupTokensFileRender(page);
  await unfoldTokenType(tokensSidebar, "color");

  const containers = await scrollableContainers(page);
  expect(
    containers.length,
    "Tokens tab should have a scrollable container",
  ).toBeGreaterThan(0);
  const scrollEl = containers[0];

  await scrollToBottom(scrollEl);
  const initialPos = await scrollEl.evaluate((el) => el.scrollTop);
  expect(initialPos).toBeGreaterThan(0);

  await page.getByRole("tab", { name: "Assets" }).click();
  await page.getByRole("tab", { name: "Tokens" }).click();

  const restored = (await scrollableContainers(page))[0];
  await expectScrollRestored(restored, initialPos);
});

test("Sidebar maintains independent scroll positions per tab", async ({
  page,
}) => {
  const { tokensSidebar } = await setupTokensFileRender(page);
  await unfoldTokenType(tokensSidebar, "color");

  // Scroll Tokens to the bottom.
  const tokensContainers = await scrollableContainers(page);
  expect(
    tokensContainers.length,
    "Tokens tab should have a scrollable container",
  ).toBeGreaterThan(0);
  await scrollToBottom(tokensContainers[0]);
  const tokensPos = await tokensContainers[0].evaluate((el) => el.scrollTop);
  expect(tokensPos).toBeGreaterThan(0);

  // Switch to Layers: fresh panel, must start at top (not contaminated
  // by the Tokens position saved under a different key).
  await page.getByRole("tab", { name: "Layers" }).click();
  const layersVisible = await visibleScrollContainers(page);
  expect(layersVisible.length).toBeGreaterThan(0);
  const layersPos = await layersVisible[0].evaluate((el) => el.scrollTop);
  expect(layersPos).toBe(0);

  // Back to Tokens: its own position must be restored, not Layers'.

  // Back to Tokens: its own position must be restored, not Layers'.
  await page.getByRole("tab", { name: "Tokens" }).click();
  const tokensRestored = (await scrollableContainers(page))[0];
  await expectScrollRestored(tokensRestored, tokensPos);

  // And back to Layers: still untouched at top.
  await page.getByRole("tab", { name: "Layers" }).click();
  const layersAgain = await visibleScrollContainers(page);
  const layersAgainPos = await layersAgain[0].evaluate((el) => el.scrollTop);
  expect(layersAgainPos).toBe(0);
});
