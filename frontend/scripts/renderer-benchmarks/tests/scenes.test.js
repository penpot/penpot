import assert from "node:assert/strict";
import test from "node:test";
import { scenarios } from "../../../resources/wasm-playground/scenes/index.js";
import {
  serializeText,
  validateCount,
} from "../../../resources/wasm-playground/scenes/shared.js";

test("all seven workloads reproduce the same exact scene for a seed", () => {
  assert.deepEqual(
    scenarios.map((scenario) => scenario.id),
    ["rects", "paths", "plus", "texts", "masks", "clips", "shadows"],
  );
  for (const scenario of scenarios) {
    const params = scenario.cases[0].params;
    assert.deepEqual(
      scenario.createScene(params, 42),
      scenario.createScene(params, 42),
    );
  }
});

test("shape counts are integers before allocation", () => {
  assert.equal(validateCount("1000"), 1000);
  for (const value of ["", "2x", -1, 1.5, Infinity, true, null, 0, 100001]) {
    assert.throws(() => validateCount(value), /count/i);
  }
});

test("scenes reject ignored parameters and malformed text dimensions", () => {
  for (const scenario of scenarios) {
    assert.throws(
      () => scenario.createScene({ ...scenario.cases[0].params, typo: 1 }, 42),
      /Unsupported parameter/,
    );
  }
  const texts = scenarios.find((s) => s.id === "texts");
  for (const override of [
    { width: -1 },
    { words: [] },
    { maxLines: 1.5 },
    { minFontSize: 100 },
  ]) {
    assert.throws(() =>
      texts.createScene({ ...texts.cases[0].params, ...override }, 42),
    );
  }
});

test("every shape is reachable exactly once and agrees with its parent", () => {
  for (const scenario of scenarios) {
    const scene = scenario.createScene(scenario.cases[0].params, 123);
    const key = (id) => id.join(":");
    const shapes = new Map(scene.shapes.map((shape) => [key(shape.id), shape]));
    assert.equal(shapes.size, scene.shapes.length);
    const seen = new Set();
    function visit(parent, children) {
      for (const id of children) {
        const child = shapes.get(key(id));
        assert.ok(child, `${scenario.id}: missing child`);
        assert.deepEqual(child.parent, parent);
        assert.ok(!seen.has(key(id)), `${scenario.id}: duplicate child`);
        seen.add(key(id));
        visit(id, child.children ?? []);
      }
    }
    visit([0, 0, 0, 0], scene.children);
    assert.equal(seen.size, scene.shapes.length, scenario.id);
  }
});

test("text payload follows the current Rust paragraph and span ABI", () => {
  const bytes = serializeText("Penpot café", 24, 0xff112233);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const encoded = new TextEncoder().encode("Penpot café");
  assert.equal(bytes.length, 16 + 1344 + encoded.length);
  assert.equal(view.getUint32(0, true), 1);
  assert.equal(view.getUint8(4), 0); // Left, not Center.
  assert.equal(view.getFloat32(16 + 4, true), 24);
  assert.equal(view.getInt32(16 + 16, true), 400);
  assert.equal(view.getUint32(16 + 56, true), encoded.length);
  assert.equal(view.getUint32(16 + 60, true), 1);
  assert.equal(view.getUint32(16 + 64 + 4, true), 0xff112233);
  assert.deepEqual(bytes.subarray(16 + 1344), encoded);
});

test("clip effects use valid enum values and actual booleans", () => {
  const scenario = scenarios.find(({ id }) => id === "clips");
  const scene = scenario.createScene(scenario.cases[0].params, 42);
  assert.equal(scene.shapes.length, 2);
  for (const shape of scene.shapes) {
    if (shape.blur) assert.equal(shape.blur[1], false);
    for (const shadow of shape.shadows ?? []) {
      assert.ok([0, 1].includes(shadow[5]));
      assert.equal(typeof shadow[6], "boolean");
    }
  }
});
