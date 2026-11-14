import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { MockWebSocketHelper } from "../../helpers/MockWebSocketHelper";

// ---------------------------------------------------------------------------
// The "Create typography style" button (workspace.options.convert-to-typography)
// in the text options sidebar is rendered when none of these hold for the
// selected text shape(s) (src/app/main/ui/workspace/sidebar/options/menus/text.cljs):
//
//   (when (and (not typography) (not multiple?) (not applied-token-name)) ...)
//
// A missing font is a different state: the button stays, its aria-label
// becomes the font-not-available message and it is disabled.
//
// The first test covers the font state, the second one each condition of the
// `when`.
// ---------------------------------------------------------------------------

function convertToTypographyButton(workspace) {
  return workspace.rightSidebar.getByRole("button", {
    name: "Create typography style",
  });
}

function fontNotAvailableButton(workspace) {
  return workspace.rightSidebar.getByRole("button", {
    name: /no longer available/,
  });
}

// Matches the button in either state, so "absent" cannot be satisfied by a
// rename.
function typographyButtonAnyState(workspace) {
  return workspace.rightSidebar.getByRole("button", {
    name: /Create typography style|no longer available/,
  });
}

function textSection(workspace) {
  return workspace.rightSidebar.getByRole("region", { name: "Text section" });
}

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// `getByText` matches substrings, and this fixture has "Text with no
// typography" as a prefix of "Text with no typography two". The anchored
// match assumes the row shows only the layer name: with the `:show-ids` or
// `:show-touched` debug flags the row appends text to the same node
// (layer_name.cljs:138-146), and no locator survives `:show-ids` because the
// name and the id share a text node.
function layerRow(workspace, name) {
  return workspace.layers
    .getByTestId("layer-row")
    .filter({ hasText: new RegExp(`^${escapeRegExp(name)}$`) })
    .first();
}

async function clickLayer(workspace, name, options = {}) {
  const row = layerRow(workspace, name);
  await row.click(options);
  await expect(row).toHaveAttribute("aria-checked", "true");
}

// Fixture render-wasm/get-file-text-custom-fonts.json has a text shape
// ("Penpot & Dragons") using a custom team font-id and no typography/token
// applied - otherwise exactly the state that reveals the button once its
// font resolves. Toggling the get-font-variants mock between "the team owns
// this font" and "empty" simulates the font being present vs. missing.
const CUSTOM_FONTS_FILE = {
  id: "434b0541-fa2f-802f-8006-59827d964a9b",
  pageId: "434b0541-fa2f-802f-8006-59827d964a9c",
};

test("The font of the shape decides the button", async ({ page }) => {
  await WorkspacePage.init(page);

  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "render-wasm/get-file-text-custom-fonts.json",
  );

  await test.step("font missing", async () => {
    // The team does not own the shape's custom font, so it can't be resolved.
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "workspace/get-font-variants-empty.json",
    );
    await workspace.goToWorkspace({
      fileId: CUSTOM_FONTS_FILE.id,
      pageId: CUSTOM_FONTS_FILE.pageId,
    });

    await workspace.clickLeafLayer("Penpot & Dragons");

    // The button is not removed: its label explains the missing font and it
    // is disabled. Asserting only that the "Create typography style" locator
    // is hidden passed for the wrong reason, because the label is what
    // changes.
    await expect(fontNotAvailableButton(workspace)).toBeVisible();
    await expect(fontNotAvailableButton(workspace)).toBeDisabled();
    await expect(convertToTypographyButton(workspace)).toHaveCount(0);
  });

  await test.step("font resolved", async () => {
    // The team owns the shape's custom font, so it resolves normally. The
    // last matching route wins, and the reload fetches the variants again.
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "render-wasm/get-font-variants-custom-fonts.json",
    );
    // The socket registry is keyed by URL and still holds the mock of the
    // document that is about to be destroyed, so it has to be cleared before
    // the reload; `goToWorkspace` then waits for the new document's socket
    // (back in CONNECTING) and opens it.
    MockWebSocketHelper.clear();
    await page.reload();
    await workspace.goToWorkspace({
      fileId: CUSTOM_FONTS_FILE.id,
      pageId: CUSTOM_FONTS_FILE.pageId,
    });

    await workspace.clickLeafLayer("Penpot & Dragons");

    await expect(convertToTypographyButton(workspace)).toBeVisible();
    await expect(convertToTypographyButton(workspace)).toBeEnabled();
    await expect(fontNotAvailableButton(workspace)).toHaveCount(0);
  });
});

// multiselection-typography.json holds, in the same page, a plain text, one
// with an in-file typography asset, and one with a typography token applied.
// The three ineligible cases share one page load; each step selects another
// layer.
const SHARED_FILE = {
  id: "c7ce0794-0992-8105-8004-38f280443849",
  pageId: "1062e0a0-8fe0-80ae-8007-e70b4993f5f0",
};

test("Ineligible selections hide the button", async ({ page }) => {
  await WorkspacePage.init(page);

  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "workspace/multiselection-typography.json",
  );
  await workspace.goToWorkspace({
    fileId: SHARED_FILE.id,
    pageId: SHARED_FILE.pageId,
  });

  await test.step("a plain text keeps the button", async () => {
    await clickLayer(workspace, "Text with no typography");

    await expect(convertToTypographyButton(workspace)).toBeEnabled();
  });

  await test.step("an applied typography asset hides it", async () => {
    await clickLayer(workspace, "Text with typography asset one");

    await expect(textSection(workspace)).toBeVisible();
    await expect(typographyButtonAnyState(workspace)).toHaveCount(0);
  });

  await test.step("an applied typography token hides it", async () => {
    await clickLayer(workspace, "Text with typography token one");

    await expect(textSection(workspace)).toBeVisible();
    await expect(typographyButtonAnyState(workspace)).toHaveCount(0);
  });

  await test.step("several selected texts hide it", async () => {
    await clickLayer(workspace, "Text with no typography");
    await clickLayer(workspace, "Text with no typography two", {
      modifiers: ["Shift"],
    });

    await expect(
      layerRow(workspace, "Text with no typography"),
    ).toHaveAttribute("aria-checked", "true");
    await expect(
      layerRow(workspace, "Text with no typography two"),
    ).toHaveAttribute("aria-checked", "true");
    await expect(typographyButtonAnyState(workspace)).toHaveCount(0);
  });
});
