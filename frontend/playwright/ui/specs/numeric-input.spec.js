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

test("BUG 10638 - Invalid padding input on multi-selection must not persist strings", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);

  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(/get\-file\?/, "design/get-file-9543.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "design/get-file-fragment-10638.json",
  );
  await workspacePage.mockRPC(
    "update-file?id=*",
    "design/update-file-9543.json",
  );

  // Inspect every persisted change: layout padding and gap values must be
  // numbers (the backend rejects strings with a Malli validation error).
  // In the transit payload, :set operations carry the attr name and its
  // value as siblings: {"~:attr": "~:layout-padding", "~:val": {...}}.
  const layoutAttrs = ["~:layout-padding", "~:layout-gap"];
  const seenLayoutValues = [];
  const badLayoutValues = [];
  const collectLayoutValues = (node) => {
    if (!node || typeof node !== "object") {
      return;
    }
    const attr = node["~:attr"];
    const val = node["~:val"];
    if (layoutAttrs.includes(attr) && val && typeof val === "object") {
      for (const [prop, leaf] of Object.entries(val)) {
        seenLayoutValues.push(`${attr} ${prop}`);
        if (typeof leaf !== "number") {
          badLayoutValues.push(`${attr} ${prop} = ${JSON.stringify(leaf)}`);
        }
      }
    }
    for (const value of Object.values(node)) {
      collectLayoutValues(value);
    }
  };
  page.on("request", (request) => {
    if (request.url().includes("/api/main/methods/update-file")) {
      const body = request.postData();
      if (body) {
        collectLayoutValues(JSON.parse(body));
      }
    }
  });

  await workspacePage.goToWorkspace({
    fileId: "525a5d8b-028e-80e7-8005-aa6cad42f27d",
    pageId: "525a5d8b-028e-80e7-8005-aa6cad42f27e",
  });

  await workspacePage.clickLeafLayer("Board");
  await workspacePage.clickLeafLayer("Second", { modifiers: ["Shift"] });

  const toggle = workspacePage.page.getByRole("button", {
    name: "Show 4 sided padding options",
  });
  await toggle.click();

  // Paddings differ between the boards (0 vs 33), so the expanded inputs
  // have no committed value. Committing invalid text must persist nothing.
  const topPaddingInput = workspacePage.page.getByRole("textbox", {
    name: "Top padding",
  });
  await topPaddingInput.click();
  await topPaddingInput.fill("abc");
  await topPaddingInput.press("Enter");

  // A valid change afterwards guarantees at least one persisted update.
  const leftPaddingInput = workspacePage.page.getByRole("textbox", {
    name: "Left padding",
  });
  const updateRequest = page.waitForRequest(
    "**/api/main/methods/update-file?*",
    {
      timeout: 15000,
    },
  );
  await leftPaddingInput.fill("5");
  await leftPaddingInput.press("Enter");
  await updateRequest;

  // Guard against a vacuous pass: the valid change above must have
  // persisted at least one layout-padding value.
  expect(seenLayoutValues).not.toEqual([]);
  expect(badLayoutValues).toEqual([]);
});
