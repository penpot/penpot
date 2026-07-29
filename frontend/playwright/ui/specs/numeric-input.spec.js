import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-token-input"]);
});

test("BUG 14226: Numeric inputs in the design panel reject values with leading whitespace", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-copy-paste.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-copy-paste-fragment.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "870f9f10-87b5-8137-8005-934804124660",
    pageId: "870f9f10-87b5-8137-8005-934804124661",
  });

  // Select first shape
  await page.getByTestId("layer-item").getByRole("button").first().click();
  await workspacePage.layers.getByTestId("layer-row").nth(0).click();

  // Check if measures section is visible
  const measuresSection = workspacePage.rightSidebar.getByRole("region", {
    name: "shape-measures-section",
  });
  await expect(measuresSection).toBeVisible();

  // Width
  const widthInput = measuresSection.getByRole("textbox", {
    name: "Width",
    exact: true,
  });
  await expect(widthInput).toHaveValue("360");

  await widthInput.fill("100");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("   100");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("   100    ");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("100    ");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("98+2");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("98 + 2");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("  98 + 2  ");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("  98+2  ");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");

  await widthInput.fill("  asdasdasdasd  ");
  await widthInput.press("Enter");
  await expect(widthInput).toHaveValue("100");
});

test("BUG 10001: Negative margins are allowed on the numeric input", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-copy-paste.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-copy-paste-fragment.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "870f9f10-87b5-8137-8005-934804124660",
    pageId: "870f9f10-87b5-8137-8005-934804124661",
  });

  // Select first shape
  await page.getByTestId("layer-item").getByRole("button").first().click();
  await workspacePage.layers.getByTestId("layer-row").nth(5).click();

  const addLayout = workspacePage.rightSidebar.getByRole("button", {
    name: "Add layout",
  });

  await addLayout.click();

  const flexLayout = workspacePage.rightSidebar.getByRole("button", {
    name: "Flex layout",
  });
  await flexLayout.click();

  const layoutSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Layout container section",
  });

  await expect(layoutSection).toBeVisible();

  await workspacePage.layers.getByTestId("layer-row").nth(6).click();
    await page.waitForTimeout(500);

  const layoutItemSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Layout item section",
  });

  await expect(layoutItemSection).toBeVisible();

  const verticalMarginInput = layoutItemSection.getByRole("textbox", {
    name: "Vertical margin",
  });

  await expect(verticalMarginInput).toBeVisible();
  
  await verticalMarginInput.fill("-10");
  await verticalMarginInput.press("Enter");
  await expect(verticalMarginInput).toHaveValue("-10");
});
