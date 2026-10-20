import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { freshInstance, fire, makeKeyEvent } from "./helpers.js";

function fired(bindStr, eventOver) {
  const { mousetrap, target } = freshInstance();
  let calls = 0;
  mousetrap.bind(bindStr, () => {
    calls++;
  });
  fire(target, makeKeyEvent(eventOver));
  return calls;
}

describe("modifiers", () => {
  it("matches each flag on its own", () => {
    assert.equal(fired("shift+x", { key: "x", code: "KeyX", shiftKey: true }), 1);
    assert.equal(fired("alt+x", { key: "x", code: "KeyX", altKey: true }), 1);
    assert.equal(fired("ctrl+x", { key: "x", code: "KeyX", ctrlKey: true }), 1);
    assert.equal(fired("meta+x", { key: "x", code: "KeyX", metaKey: true }), 1);
  });

  it("does not fire without the bound modifier", () => {
    assert.equal(fired("shift+x", { key: "x", code: "KeyX" }), 0);
    assert.equal(fired("ctrl+x", { key: "x", code: "KeyX", altKey: true }), 0);
  });

  it("combines modifiers regardless of bind order", () => {
    const event = { key: "q", code: "KeyQ", ctrlKey: true, altKey: true, shiftKey: true };
    assert.equal(fired("ctrl+alt+shift+q", event), 1);
    assert.equal(fired("shift+ctrl+alt+q", event), 1);
  });

  it("drops the phantom ctrl while AltGraph is active", () => {
    const altgr = {
      key: "]",
      code: "BracketRight",
      ctrlKey: true,
      altKey: true,
      getModifierState: (mod) => mod === "AltGraph",
    };
    assert.equal(fired("ctrl+alt+]", altgr), 0);
    assert.equal(fired("alt+]", altgr), 1);
  });

  it("keeps a real ctrl+alt without AltGraph", () => {
    const event = {
      key: "]",
      code: "BracketRight",
      ctrlKey: true,
      altKey: true,
      getModifierState: () => false,
    };
    assert.equal(fired("ctrl+alt+]", event), 1);
  });

  it("works when the event has no getModifierState at all", () => {
    assert.equal(
      fired("ctrl+x", { key: "x", code: "KeyX", ctrlKey: true, getModifierState: undefined }),
      1,
    );
  });
});
