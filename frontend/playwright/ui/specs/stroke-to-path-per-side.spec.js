import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

const rootId = "00000000-0000-0000-0000-000000000000";
const frameId = "12331000-0000-8000-8000-000000000001";
const childId = "12331000-0000-8000-8000-000000000002";
const siblingId = "12331000-0000-8000-8000-000000000003";

// Use the editor's exported debug reader; actions still run through the UI.
async function documentShapes(page) {
  return page.evaluate((rootId) => {
    const objects = {};
    const visit = (id) => {
      const shape = globalThis.debug.dump_object(id);
      const children = (shape.shapes ?? []).map((id) => id.uuid ?? id);
      objects[id] = {
        ...shape,
        content: shape.type === "path" ? shape.content.toString() : null,
      };
      children.forEach(visit);
    };
    visit(rootId);
    return JSON.parse(
      JSON.stringify(objects, (_key, value) => value?.uuid ?? value),
    );
  }, rootId);
}

test("Per-side Frame strokes keep colors and hierarchy through undo and redo", async ({
  page,
}) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-stroke-path"]);
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("workspace/per-side-frame-strokes.json");
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();
  await workspace.clickLayers();
  const before = await documentShapes(page);

  await workspace.clickLeafLayer("Per-side Frame", { button: "right" });
  await page.getByText("Stroke to path", { exact: true }).click();
  await expect(
    workspace.layers.getByText("Per-side Frame (stroke)", { exact: true }),
  ).toHaveCount(2);

  const after = await documentShapes(page);
  const paths = after[rootId].shapes.filter((id) => !before[id]);
  expect(paths).toHaveLength(2);
  expect(after[frameId]).toEqual({ ...before[frameId], strokes: [] });
  expect(after[childId]).toEqual(before[childId]);
  expect(after[siblingId]).toEqual(before[siblingId]);
  expect(after[rootId].shapes).toEqual([frameId, ...paths, siblingId]);
  const strokes = [...before[frameId].strokes].reverse();
  paths.forEach((id, index) => {
    expect(after[id].type).toBe("path");
    expect(after[id]["parent-id"]).toBe(rootId);
    expect(after[id]["frame-id"]).toBe(rootId);
    expect(after[id].strokes).toEqual([]);
    expect(after[id].fills).toEqual([
      { "fill-color": strokes[index]["stroke-color"], "fill-opacity": 1 },
    ]);
    expect(after[id].content).toMatch(/M/);
    expect(after[id]["svg-attrs"]).toEqual({ fillRule: "evenodd" });
  });

  await page.keyboard.press("ControlOrMeta+Z");
  await expect.poll(() => documentShapes(page)).toEqual(before);
  await page.keyboard.press("ControlOrMeta+Shift+Z");
  await expect.poll(() => documentShapes(page)).toEqual(after);
});
