import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("BUG 13305 - Fix resize board to fit content", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile("workspace/get-file-13305.json");
  await workspacePage.mockRPC("update-file?id=*", "workspace/update-file-13305.json");

  await workspacePage.goToWorkspace({
    fileId: "9666e946-78e8-8111-8007-8fe5f0f454bf",
    pageId: "9666e946-78e8-8111-8007-8fe5f0f49ac6",
  });

  await workspacePage.clickLeafLayer("Board");
  await workspacePage.rightSidebar.getByRole("button", { name: "Resize board to fit content" }).click();

  await expect(workspacePage.rightSidebar.getByTitle("Width").getByRole("textbox")).toHaveValue("630");
  await expect(workspacePage.rightSidebar.getByTitle("Height").getByRole("textbox")).toHaveValue("630");
  await expect(workspacePage.rightSidebar.getByTitle("X axis").getByRole("textbox")).toHaveValue("110");
  await expect(workspacePage.rightSidebar.getByTitle("Y axis").getByRole("textbox")).toHaveValue("110");
});