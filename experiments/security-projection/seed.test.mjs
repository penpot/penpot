import test from "node:test";
import assert from "node:assert/strict";

import {
  collectSubtree,
  cycling,
  hasComponentRef,
  pickTemplateFrame,
  rewriteParagraphs,
} from "./seed.mjs";

const ZERO = "00000000-0000-0000-0000-000000000000";

function fixture() {
  return {
    [ZERO]: { id: ZERO, type: "frame", frameId: ZERO, parentId: ZERO, shapes: ["f1", "f2"] },
    f1: {
      id: "f1", type: "frame", frameId: ZERO, parentId: ZERO, shapes: ["t1", "r1"],
    },
    t1: {
      id: "t1", type: "text", frameId: "f1", parentId: "f1",
      content: {
        type: "root",
        children: [{
          type: "paragraph-set",
          children: [{ type: "paragraph", children: [{ text: "old", fontSize: "16" }] }],
        }],
      },
    },
    r1: {
      id: "r1", type: "rect", frameId: "f1", parentId: "f1",
      interactions: [{ actionType: "open-url", url: "http://old.example" }],
    },
    f2: { id: "f2", type: "frame", frameId: ZERO, parentId: ZERO, shapes: ["c1"] },
    c1: { id: "c1", type: "rect", frameId: "f2", parentId: "f2", componentId: "comp-1" },
  };
}

test("collectSubtree returns the node and its descendants in tree order", () => {
  assert.deepEqual(collectSubtree(fixture(), "f1"), ["f1", "t1", "r1"]);
});

test("hasComponentRef sees component instances anywhere in the subtree", () => {
  const objects = fixture();
  assert.equal(hasComponentRef(objects, "f1"), false);
  assert.equal(hasComponentRef(objects, "f2"), true);
});

test("pickTemplateFrame skips frames with component refs", () => {
  const frame = pickTemplateFrame(fixture());
  assert.equal(frame.id, "f1");
});

test("pickTemplateFrame throws when no plain frame has text and a link", () => {
  const objects = fixture();
  delete objects.r1.interactions;
  assert.throws(() => pickTemplateFrame(objects), /no plain frame/);
});

test("rewriteParagraphs replaces the text and keeps the span styles", () => {
  const objects = fixture();
  rewriteParagraphs(objects.t1.content, cycling(["hello"]));
  const span = objects.t1.content.children[0].children[0].children[0];
  assert.equal(span.text, "hello");
  assert.equal(span.fontSize, "16");
});

test("cycling wraps around", () => {
  const next = cycling(["a", "b"]);
  assert.equal(next(), "a");
  assert.equal(next(), "b");
  assert.equal(next(), "a");
});
