import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

// Regression test for issue #7440: Scroll position in left sidebar tabs (Layers / Assets / Tokens)
// should be preserved when switching between them.
// We use the Tokens tab because token sets list can be long (fixture ensures enough scroll height).

async function setupTokensTab(page) {
  await WorkspacePage.init(page);
  const workspace = new WorkspacePage(page);
  // Mock a file with tokens (reusing existing fixtures from tokens.spec.js)
  await workspace.setupEmptyFile();
  await workspace.mockRPC("get-team?id=*", "workspace/get-team-tokens.json");
  await workspace.mockRPC(/get\-file\?/, "workspace/get-file-tokens.json");
  await workspace.mockRPC(/get\-file\-fragment\?/, "workspace/get-file-fragment-tokens.json");
  await workspace.mockRPC("update-file?id=*", "workspace/update-file-create-rect.json");

  await workspace.goToWorkspace({
    fileId: "c7ce0794-0992-8105-8004-38f280443849",
    pageId: "66697432-c33d-8055-8006-2c62cc084cad",
  });

  const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
  await tokensTabButton.click();
  return workspace;
}

// Helper: get the first visible scroll container inside left sidebar
async function getVisibleScrollContainer(page) {
  const containers = page.locator('[data-testid="left-sidebar"] [data-scroll-container="true"]');
  const count = await containers.count();
  for (let i = 0; i < count; i++) {
    const el = containers.nth(i);
    // offsetParent null check via evaluate
    const isVisible = await el.evaluate(node => !!node && node.offsetParent !== null);
    if (isVisible) return el;
  }
  return null;
}

// Scroll to near the bottom using JS (faster & deterministic)
async function scrollToBottom(locator) {
  await locator.evaluate(el => { el.scrollTop = el.scrollHeight; });
}

// We allow a small tolerance because content could slightly change on remount.
const POSITION_TOLERANCE = 20; // pixels

// Core test: switching away and back preserves scroll position
test("Sidebar scroll position preserved when switching tabs (Tokens -> Assets -> Tokens)", async ({ page }) => {
  await setupTokensTab(page);

  const scrollEl = await getVisibleScrollContainer(page);
  expect(scrollEl, "Expected a visible scroll container in Tokens tab").not.toBeNull();

  // Ensure enough height to scroll
  const canScroll = await scrollEl.evaluate(el => el.scrollHeight > el.clientHeight + 100);
  expect(canScroll, "Fixture should provide enough items to scroll").toBeTruthy();

  await scrollToBottom(scrollEl);
  // Wait briefly to ensure scroll listener captured state
  await page.waitForTimeout(150);
  const initialPos = await scrollEl.evaluate(el => el.scrollTop);
  expect(initialPos).toBeGreaterThan(0);

  // Switch to Assets tab (if available) otherwise Layers
  let targetTab = page.getByRole("tab", { name: /Assets/i });
  if (await targetTab.count() === 0) {
    targetTab = page.getByRole("tab", { name: /Layers/i });
  }
  await targetTab.click();

  // Switch back to Tokens
  await page.getByRole("tab", { name: "Tokens" }).click();

  const restoredEl = await getVisibleScrollContainer(page);
  const restoredPos = await restoredEl.evaluate(el => el.scrollTop);

  expect(restoredPos).toBeGreaterThanOrEqual(initialPos - POSITION_TOLERANCE);
});

// Extended test: each tab retains its own scroll position independently.
// We scroll Layers and Tokens to different depths, switch around, and verify restoration.
test("Sidebar maintains independent scroll positions for Layers and Tokens", async ({ page }) => {
  await setupTokensTab(page);

  // Scroll Tokens
  const tokensScrollEl = await getVisibleScrollContainer(page);
  await scrollToBottom(tokensScrollEl);
  await page.waitForTimeout(150);
  const tokensPos = await tokensScrollEl.evaluate(el => el.scrollTop);
  expect(tokensPos).toBeGreaterThan(0);

  // Switch to Layers tab
  const layersTab = page.getByRole("tab", { name: /Layers/i });
  await layersTab.click();

  // Layers scroll container
  const layersScrollEl = await getVisibleScrollContainer(page);
  // Scroll midway (not full bottom) for distinction
  await layersScrollEl.evaluate(el => { el.scrollTop = el.scrollHeight / 2; });
  await page.waitForTimeout(150);
  const layersPos = await layersScrollEl.evaluate(el => el.scrollTop);
  expect(layersPos).toBeGreaterThan(0);

  // Switch back to Tokens and verify its position
  await page.getByRole("tab", { name: "Tokens" }).click();
  const tokensRestoredEl = await getVisibleScrollContainer(page);
  const tokensRestoredPos = await tokensRestoredEl.evaluate(el => el.scrollTop);
  expect(tokensRestoredPos).toBeGreaterThanOrEqual(tokensPos - POSITION_TOLERANCE);

  // Switch again to Layers and verify its position
  await layersTab.click();
  const layersRestoredEl = await getVisibleScrollContainer(page);
  const layersRestoredPos = await layersRestoredEl.evaluate(el => el.scrollTop);
  expect(layersRestoredPos).toBeGreaterThanOrEqual(layersPos - POSITION_TOLERANCE);
});
