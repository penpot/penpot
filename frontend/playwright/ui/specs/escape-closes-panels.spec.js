import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const VIEWPORT_POINTS = [
  [200, 200],
  [400, 220],
  [320, 420],
];

const openSearch = async (page) => {
  await page.getByRole("button", { name: "Search" }).click();
  const findInput = page.getByRole("textbox", { name: /^Find/ });
  await expect(findInput).toBeVisible();
  return findInput;
};

const openShortcuts = async (page) => {
  await page.keyboard.press("?");
  const searchInput = page.getByPlaceholder("Search shortcuts");
  await expect(searchInput).toBeVisible();
  return searchInput;
};

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();
});

test("Escape closes the layers search panel", async ({ page }) => {
  const findInput = await openSearch(page);

  await page.keyboard.press("Escape");

  await expect(findInput).toHaveCount(0);
});

test("Escape closes the search filters menu before the search panel", async ({
  page,
}) => {
  const findInput = await openSearch(page);
  await page.getByRole("button", { name: "Filter" }).click();
  const menuItem = page.getByText("Masks", { exact: true });
  await expect(menuItem).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(menuItem).toHaveCount(0);
  await expect(findInput).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(findInput).toHaveCount(0);
});

test("Escape closes the shortcuts panel right after it opens", async ({
  page,
}) => {
  const searchInput = await openShortcuts(page);

  await page.keyboard.press("Escape");

  await expect(searchInput).toHaveCount(0);
});

test("Escape finishes a path drawn with the shortcuts panel open", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  const searchInput = await openShortcuts(page);
  await page.getByRole("button", { name: "BASICS" }).click();

  const box = await workspacePage.viewport.boundingBox();
  await page.keyboard.press("p");
  for (const [x, y] of VIEWPORT_POINTS) {
    await page.mouse.click(box.x + x, box.y + y);
  }
  await page.keyboard.press("Escape");

  // The shortcuts panel replaces the layers tree, so the new path is
  // observed through the design tab of the selected shape.
  await expect(
    page.getByRole("region", { name: "Stroke section" }),
  ).toBeVisible();
  await expect(searchInput).toBeVisible();
});
