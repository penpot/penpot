import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

// Fix for https://tree.taiga.io/project/penpot/issue/9042
test("Bug 9042 - Measurement unit dropdowns for columns are cut off in grid layout edit mode", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-9042.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-fragment-9042.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "af2494d0-39ba-8184-8005-230696f6df5c",
    pageId: "af2494d0-39ba-8184-8005-230696f6df5d",
  });
  await workspacePage.clickLeafLayer("Board");
  await workspacePage.expectSelectedLayer("Board");

  const layoutContainer = workspacePage.page.getByTestId("inspect-layout");
  await layoutContainer.getByRole("button", { name: "Edit grid" }).click();
  const rowsContainer = workspacePage.page.getByTestId("inspect-layout-rows");
  await rowsContainer.click();

  await rowsContainer.getByText("FR").nth(2).click();
  await expect(rowsContainer.getByText("%")).toBeInViewport();
});
