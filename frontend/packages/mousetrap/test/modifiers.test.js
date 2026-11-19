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

  it("drops the phantom ctrl and alt while AltGraph is active", () => {
    const altgr = {
      key: "]",
      code: "BracketRight",
      ctrlKey: true,
      altKey: true,
      getModifierState: (mod) => mod === "AltGraph",
    };
    assert.equal(fired("ctrl+alt+]", altgr), 0);
    assert.equal(fired("alt+]", altgr), 0);
    assert.equal(fired("]", altgr), 1);
  });

  it("falls back to ctrl+alt for a letter that AltGr turns into a glyph", () => {
    // Spanish layout on Windows: Ctrl+Alt+E types "€".
    const event = {
      key: "€",
      code: "KeyE",
      keyCode: 69,
      ctrlKey: true,
      altKey: true,
      getModifierState: (mod) => mod === "AltGraph",
    };
    assert.equal(fired("ctrl+alt+e", event), 1);
    assert.equal(fired("€", event), 1);
  });

  it("matches letters and digits by keyCode when a modifier changes the glyph", () => {
    // Shift+1 types "!" on US and Spanish layouts.
    assert.equal(fired("shift+1", { key: "!", code: "Digit1", keyCode: 49, shiftKey: true }), 1);
    // Spanish layout: Shift+0 types "=".
    assert.equal(fired("shift+0", { key: "=", code: "Digit0", keyCode: 48, shiftKey: true }), 1);
    // macOS: Option+M types "µ" and Option+E is a dead key.
    assert.equal(fired("alt+m", { key: "µ", code: "KeyM", keyCode: 77, altKey: true }), 1);
    assert.equal(fired("alt+e", { key: "Dead", code: "KeyE", keyCode: 69, altKey: true }), 1);
    assert.equal(fired("alt+shift+5", { key: "%", code: "Digit5", keyCode: 53, altKey: true, shiftKey: true }), 1);
  });

  it("keeps AZERTY letters by layout, not by position", () => {
    // AZERTY: the key at the US "W" position types "z" and has keyCode 90.
    assert.equal(fired("ctrl+z", { key: "z", code: "KeyW", keyCode: 90, ctrlKey: true }), 1);
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
