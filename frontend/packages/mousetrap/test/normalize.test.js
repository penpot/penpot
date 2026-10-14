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

describe("canonical key normalization", () => {
  // [native event.key, code, canonical bind] — every _KEY_NAMES entry.
  const table = [
    [" ", "Space", "space"],
    ["Spacebar", "Space", "space"],
    ["ArrowUp", "ArrowUp", "up"],
    ["Up", "ArrowUp", "up"],
    ["ArrowDown", "ArrowDown", "down"],
    ["Down", "ArrowDown", "down"],
    ["ArrowLeft", "ArrowLeft", "left"],
    ["Left", "ArrowLeft", "left"],
    ["ArrowRight", "ArrowRight", "right"],
    ["Right", "ArrowRight", "right"],
    ["Escape", "Escape", "escape"],
    ["Esc", "Escape", "escape"],
    ["Enter", "Enter", "enter"],
    ["Backspace", "Backspace", "backspace"],
    ["Delete", "Delete", "del"],
    ["Del", "Delete", "del"],
    ["Insert", "Insert", "ins"],
    ["Ins", "Insert", "ins"],
    ["Tab", "Tab", "tab"],
    ["Home", "Home", "home"],
    ["End", "End", "end"],
    ["PageUp", "PageUp", "pageup"],
    ["PageDown", "PageDown", "pagedown"],
    ["CapsLock", "CapsLock", "capslock"],
  ];

  for (const [nativeKey, code, canonical] of table) {
    it(`maps ${nativeKey} to ${canonical}`, () => {
      assert.equal(fired(canonical, { key: nativeKey, code }), 1);
    });
  }

  it("lowercases single characters so Shift+Z matches shift+z", () => {
    assert.equal(fired("shift+z", { key: "Z", code: "KeyZ", shiftKey: true }), 1);
    assert.equal(fired("a", { key: "A", code: "KeyA" }), 1);
  });

  it("resolves a shifted digit row key to the digit itself", () => {
    // US Shift+0 types ")", AZERTY Shift+0 types "0": shift+0 must mean
    // the physical digit key on every layout (production keyCode behavior).
    assert.equal(fired("shift+0", { key: ")", code: "Digit0", shiftKey: true }), 1);
    assert.equal(fired("shift+1", { key: "!", code: "Digit1", shiftKey: true }), 1);
    assert.equal(fired("shift+2", { key: "@", code: "Digit2", shiftKey: true }), 1);
    assert.equal(fired("shift+8", { key: "*", code: "Digit8", shiftKey: true }), 1);
    // ...so the shifted glyphs are not bindable tokens for digit codes.
    assert.equal(fired("shift+)", { key: ")", code: "Digit0", shiftKey: true }), 0);
    assert.equal(fired("shift+*", { key: "*", code: "Digit8", shiftKey: true }), 0);
    // AZERTY shifted digits already type the digit: unchanged.
    assert.equal(fired("shift+0", { key: "0", code: "Digit0", shiftKey: true }), 1);
    // Unshifted digits are untouched.
    assert.equal(fired("0", { key: "0", code: "Digit0" }), 1);
    assert.equal(fired("&", { key: "&", code: "Digit1" }), 1);
  });

  it("without a layout map, punctuation resolves by glyph", () => {
    // Spanish layout: Shift + the physical "+" key (BracketRight) types "*".
    assert.equal(fired("shift+*", { key: "*", code: "BracketRight", shiftKey: true }), 1);
    assert.equal(fired("shift+]", { key: "*", code: "BracketRight", shiftKey: true }), 0);
    assert.equal(fired("+", { key: "+", code: "BracketRight" }), 1);
    // US layout: Shift + BracketRight types "}".
    assert.equal(fired("shift+}", { key: "}", code: "BracketRight", shiftKey: true }), 1);
    assert.equal(fired("shift+]", { key: "}", code: "BracketRight", shiftKey: true }), 0);
    assert.equal(fired("?", { key: "?", code: "Slash" }), 1);
  });

  it("lowercases function keys", () => {
    assert.equal(fired("f1", { key: "F1", code: "F1" }), 1);
    assert.equal(fired("f13", { key: "F13", code: "F13" }), 1);
  });

  it("ignores Dead keys and missing/empty keys without throwing", () => {
    assert.equal(fired("dead", { key: "Dead", code: "Quote" }), 0);
    assert.equal(fired("a", { key: undefined, code: "KeyA" }), 0);
    assert.equal(fired("a", { key: "", code: "KeyA" }), 0);
  });

  it("keeps numpad digits positional as num0..num9", () => {
    assert.equal(fired("num0", { key: "0", code: "Numpad0" }), 1);
    assert.equal(fired("num9", { key: "9", code: "Numpad9" }), 1);
    // NumLock off: the key says Insert but the position wins.
    assert.equal(fired("num0", { key: "Insert", code: "Numpad0" }), 1);
  });

  it("falls back to the glyph for non-digit numpad codes", () => {
    assert.equal(fired("+", { key: "+", code: "NumpadAdd" }), 1);
  });

  it("lowercases unknown long keys", () => {
    assert.equal(fired("foo", { key: "Foo", code: "Foo" }), 1);
    assert.equal(fired("ñ", { key: "Ñ", code: "Semicolon" }), 1);
  });
});
