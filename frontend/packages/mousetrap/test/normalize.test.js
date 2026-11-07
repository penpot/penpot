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
    // the physical digit key on every layout, named by keyCode.
    assert.equal(fired("shift+0", { key: ")", code: "Digit0", keyCode: 48, shiftKey: true }), 1);
    assert.equal(fired("shift+1", { key: "!", code: "Digit1", keyCode: 49, shiftKey: true }), 1);
    assert.equal(fired("shift+2", { key: "@", code: "Digit2", keyCode: 50, shiftKey: true }), 1);
    assert.equal(fired("shift+8", { key: "*", code: "Digit8", keyCode: 56, shiftKey: true }), 1);
    // AZERTY shifted digits already type the digit: unchanged.
    assert.equal(fired("shift+0", { key: "0", code: "Digit0", keyCode: 48, shiftKey: true }), 1);
    // Unshifted digits are untouched.
    assert.equal(fired("0", { key: "0", code: "Digit0", keyCode: 48 }), 1);
  });

  it("prefers the digit over the shifted glyph when both are bound", () => {
    const { mousetrap, target } = freshInstance();
    const calls = [];
    mousetrap.bind("shift+0", () => calls.push("shift+0"));
    mousetrap.bind("shift+)", () => calls.push("shift+)"));
    fire(target, makeKeyEvent({ key: ")", code: "Digit0", keyCode: 48, shiftKey: true }));
    assert.deepEqual(calls, ["shift+0"]);
  });

  it("without a layout map, punctuation resolves by US position or by glyph", () => {
    // Spanish layout: Shift + the physical "+" key (BracketRight) types "*".
    assert.equal(fired("shift+]", { key: "*", code: "BracketRight", shiftKey: true }), 1);
    assert.equal(fired("shift+*", { key: "*", code: "BracketRight", shiftKey: true }), 1);
    assert.equal(fired("+", { key: "+", code: "BracketRight" }), 1);
    // US layout: Shift + BracketRight types "}"; the glyph still matches.
    assert.equal(fired("shift+}", { key: "}", code: "BracketRight", shiftKey: true }), 1);
    assert.equal(fired("?", { key: "?", code: "Slash" }), 1);
    // US layout: Shift + Comma types "<".
    assert.equal(fired("shift+,", { key: "<", code: "Comma", shiftKey: true }), 1);
    assert.equal(fired("ctrl+shift+.", { key: ">", code: "Period", ctrlKey: true, shiftKey: true }), 1);
  });

  it("matches a glyph binding without the Shift the glyph needs", () => {
    // US layout: "?" is Shift+Slash and "+" is Shift+Equal.
    assert.equal(fired("?", { key: "?", code: "Slash", shiftKey: true }), 1);
    assert.equal(fired("+", { key: "+", code: "Equal", shiftKey: true }), 1);
    // Spanish layout: "?" is Shift+Minus.
    assert.equal(fired("?", { key: "?", code: "Minus", shiftKey: true }), 1);
  });

  it("dispatches only the most specific match", () => {
    const { mousetrap, target } = freshInstance();
    const calls = [];
    mousetrap.bind("shift+/", () => calls.push("shift+/"));
    mousetrap.bind("?", () => calls.push("?"));
    fire(target, makeKeyEvent({ key: "?", code: "Slash", shiftKey: true }));
    assert.deepEqual(calls, ["shift+/"]);
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

describe("keyForRecording", () => {
  const record = (eventOver, layoutMap = null) => {
    const { mousetrap } = freshInstance();
    mousetrap.setLayoutMap(layoutMap);
    return mousetrap.keyForRecording(makeKeyEvent(eventOver));
  };
  const spanishMap = { get: (code) => ({ BracketRight: "+", Minus: "'" })[code] };

  it("records letters and digits with a modifier by keyCode", () => {
    assert.equal(record({ key: "!", code: "Digit1", keyCode: 49, shiftKey: true }), "1");
    assert.equal(record({ key: "µ", code: "KeyM", keyCode: 77, altKey: true }), "m");
    assert.equal(record({ key: "Dead", code: "KeyE", keyCode: 69, altKey: true }), "e");
  });

  it("records punctuation by position when there is a layout map", () => {
    assert.equal(record({ key: "*", code: "BracketRight", shiftKey: true }, spanishMap), "+");
    assert.equal(record({ key: "+", code: "BracketRight" }, spanishMap), "+");
  });

  it("records the typed glyph when there is no layout map", () => {
    assert.equal(record({ key: "+", code: "BracketRight" }), "+");
    assert.equal(record({ key: "*", code: "BracketRight", shiftKey: true }), "*");
  });

  it("falls back to the US position for a dead key without a map", () => {
    assert.equal(record({ key: "Dead", code: "Quote" }), "'");
  });

  it("returns null for a modifier pressed alone", () => {
    assert.equal(record({ key: "Shift", code: "ShiftLeft", shiftKey: true }), null);
  });

  it("records a key the dispatcher then matches", () => {
    const cases = [
      [{ key: "!", code: "Digit1", keyCode: 49, shiftKey: true }, null],
      [{ key: "µ", code: "KeyM", keyCode: 77, altKey: true }, null],
      [{ key: "*", code: "BracketRight", shiftKey: true }, null],
      [{ key: "*", code: "BracketRight", shiftKey: true }, spanishMap],
      [{ key: "?", code: "Minus", shiftKey: true, ctrlKey: true }, null],
      [{ key: "?", code: "Minus", shiftKey: true, ctrlKey: true }, spanishMap],
    ];
    for (const [eventOver, map] of cases) {
      const { mousetrap, target } = freshInstance();
      mousetrap.setLayoutMap(map);
      const event = makeKeyEvent(eventOver);
      const mods = ["ctrl", "alt", "shift"].filter((m) => event[m + "Key"]);
      const combo = [...mods, mousetrap.keyForRecording(event)].join("+");
      let calls = 0;
      mousetrap.bind(combo.replace(/\+\+$/, "++"), () => {
        calls++;
      });
      fire(target, event);
      assert.equal(calls, 1, `${combo} should fire`);
    }
  });
});
