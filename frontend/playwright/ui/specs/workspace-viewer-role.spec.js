import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { presenceFixture } from "../../data/workspace/ws-notifications";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);

  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await WorkspacePage.mockRPC(page, "get-teams", "get-teams-role-viewer.json");

  await workspacePage.goToWorkspace();
});

test("User haven't toolbar", async ({ page }) => {
  await expect(page.getByTitle("toggle toolbar")).toBeHidden();
  await expect(page.getByTitle("design")).toBeHidden();
});

test("User haven't edition menu entries", async ({ page }) => {
  await page.getByTitle("main menu").click();
  await page.getByText("file").last().click();

  await expect(page.getByText("Add as Shared Library")).toBeHidden();

  await page.getByText("edit").click();

  await expect(page.getByText("Undo")).toBeHidden();
  await expect(page.getByText("Redo")).toBeHidden();
});
