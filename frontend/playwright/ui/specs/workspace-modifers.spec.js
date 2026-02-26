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

test("BUG 13382 - Fix problem with flex layout", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile("workspace/get-file-13382.json");

  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-13382-fragment.json",
  );

  await workspacePage.mockRPC("update-file?id=*", "workspace/update-file-empty.json");

  await workspacePage.goToWorkspace({
    fileId: "52c4e771-3853-8190-8007-9506c70e8100",
    pageId: "ecb0cfd0-0f0b-81f7-8007-950628f9665b",
  });

  await workspacePage.clickToggableLayer("A");
  await workspacePage.clickToggableLayer("B");
  await workspacePage.clickToggableLayer("C");
  await workspacePage.clickLeafLayer("R2");

  const heightText = workspacePage.rightSidebar.getByTitle("Height").getByPlaceholder('--');
  await heightText.fill("200");
  await heightText.press("Enter");

  await workspacePage.clickLeafLayer("B");
  await expect(workspacePage.rightSidebar.getByTitle("Height").getByRole("textbox")).toHaveValue("340");

});

test("BUG 13468 - Fix problem with flex propagation", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile("workspace/get-file-13468.json");

  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-13468-fragment.json",
  );

  await workspacePage.mockRPC("update-file?id=*", "workspace/update-file-empty.json");

  await workspacePage.goToWorkspace({
    fileId: "3a4d7ec7-c391-8146-8007-9a05c41da6b9",
    pageId: "95b23c15-79f9-81ba-8007-99d81b5290dd",
  });
0
  await workspacePage.clickToggableLayer("Parent");
  await workspacePage.clickToggableLayer("Container");

  await workspacePage.sidebar.getByRole('button', { name: 'Show' }).click();

  await workspacePage.clickLeafLayer("Container");
  await expect(workspacePage.rightSidebar.getByTitle("Height").getByRole("textbox")).toHaveValue("76");
});


