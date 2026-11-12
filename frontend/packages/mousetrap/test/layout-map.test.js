import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { freshInstance, fire, makeKeyEvent } from "./helpers.js";

function firedWithMap(map, bindStr, eventOver) {
  const { mousetrap, target } = freshInstance();
  mousetrap.setLayoutMap(map);
  let calls = 0;
  mousetrap.bind(bindStr, () => {
    calls++;
  });
  fire(target, makeKeyEvent(eventOver));
  return calls;
}

const spanishMap = {
  get(code) {
    return code === "BracketRight" ? "+" : undefined;
  },
};

describe("layout map", () => {
  it("resolves punctuation by position, so Shift+* fires a shift++ bind", () => {
    const event = { key: "*", code: "BracketRight", shiftKey: true };
    assert.equal(firedWithMap(spanishMap, "shift++", event), 1);
  });

  it("prefers the position over the glyph when both are bound", () => {
    const { mousetrap, target } = freshInstance();
    mousetrap.setLayoutMap(spanishMap);
    const calls = [];
    mousetrap.bind("shift++", () => calls.push("shift++"));
    mousetrap.bind("shift+*", () => calls.push("shift+*"));
    fire(target, makeKeyEvent({ key: "*", code: "BracketRight", shiftKey: true }));
    assert.deepEqual(calls, ["shift++"]);
  });

  it("matches the unshifted primary on the same position", () => {
    const event = { key: "+", code: "BracketRight" };
    assert.equal(firedWithMap(spanishMap, "+", event), 1);
  });

  it("falls back to the glyph when the map misses the code", () => {
    const miss = { get: () => undefined };
    const event = { key: "*", code: "BracketRight", shiftKey: true };
    assert.equal(firedWithMap(miss, "shift+*", event), 1);
    assert.equal(firedWithMap(miss, "shift++", event), 0);
  });

  it("falls back to the glyph when the map throws", () => {
    const broken = {
      get() {
        throw new Error("no map");
      },
    };
    assert.equal(
      firedWithMap(broken, "shift+*", { key: "*", code: "BracketRight", shiftKey: true }),
      1,
    );
  });

  it("setLayoutMap(null) restores the glyph fallback", () => {
    const { mousetrap, target } = freshInstance();
    mousetrap.setLayoutMap(spanishMap);
    mousetrap.setLayoutMap(null);
    let calls = 0;
    mousetrap.bind("shift+*", () => {
      calls++;
    });
    fire(target, makeKeyEvent({ key: "*", code: "BracketRight", shiftKey: true }));
    assert.equal(calls, 1);
  });

  it("maps stay per instance", () => {
    const a = freshInstance();
    const b = freshInstance();
    a.mousetrap.setLayoutMap(spanishMap);
    let callsA = 0;
    let callsB = 0;
    a.mousetrap.bind("shift++", () => {
      callsA++;
    });
    b.mousetrap.bind("shift+*", () => {
      callsB++;
    });
    const event = { key: "*", code: "BracketRight", shiftKey: true };
    fire(a.target, makeKeyEvent(event));
    fire(b.target, makeKeyEvent(event));
    assert.equal(callsA, 1);
    assert.equal(callsB, 1);
  });

  it("letters still resolve by glyph with a map set", () => {
    const { mousetrap, target } = freshInstance();
    mousetrap.setLayoutMap(spanishMap);
    let calls = 0;
    mousetrap.bind("a", () => {
      calls++;
    });
    // AZERTY: the "A" label sits on the KeyQ position.
    fire(target, makeKeyEvent({ key: "a", code: "KeyQ" }));
    assert.equal(calls, 1);
  });

  // Spanish layout as Chrome reports it: the "'" key sits on Minus and
  // "º" on Backquote.
  const fullSpanishMap = {
    get(code) {
      return { BracketRight: "+", Minus: "'", Backquote: "º", Equal: "¡" }[code];
    },
  };

  const altGraph = (over) => ({
    getModifierState: (name) => name === "AltGraph",
    ...over,
  });

  it("fires ? on Spanish, where it is Shift + the ' key", () => {
    const event = { key: "?", code: "Minus", shiftKey: true };
    assert.equal(firedWithMap(fullSpanishMap, "?", event), 1);
  });

  it("matches the AltGr glyph before the key position (Linux)", () => {
    // AltGr+º types "\" with no ctrl or alt on Linux.
    const { mousetrap, target } = freshInstance();
    mousetrap.setLayoutMap(fullSpanishMap);
    const calls = [];
    mousetrap.bind("\\", () => calls.push("\\"));
    mousetrap.bind("º", () => calls.push("º"));
    fire(target, makeKeyEvent(altGraph({ key: "\\", code: "Backquote" })));
    assert.deepEqual(calls, ["\\"]);
  });

  it("matches the AltGr glyph when AltGr reports ctrl+alt (Windows)", () => {
    const event = altGraph({ key: "\\", code: "Backquote", ctrlKey: true, altKey: true });
    assert.equal(firedWithMap(fullSpanishMap, "\\", event), 1);
  });

  it("records the AltGr glyph, not the key position", () => {
    const { mousetrap } = freshInstance();
    mousetrap.setLayoutMap(fullSpanishMap);
    const event = makeKeyEvent(altGraph({ key: "\\", code: "Backquote" }));
    assert.equal(mousetrap.keyForRecording(event), "\\");
  });

  it("drops a map that disagrees with an unmodified punctuation key", () => {
    // Chrome on Linux keeps the map of the first layout (Spanish) while
    // US is active: the "=" key types "=" but the map says "¡".
    const { mousetrap, target } = freshInstance();
    mousetrap.setLayoutMap(fullSpanishMap);
    const calls = [];
    mousetrap.bind("=", () => calls.push("="));
    mousetrap.bind("-", () => calls.push("-"));
    fire(target, makeKeyEvent({ key: "=", code: "Equal" }));
    fire(target, makeKeyEvent({ key: "-", code: "Minus" }));
    assert.deepEqual(calls, ["=", "-"]);
    assert.equal(mousetrap._layoutMap, null);
  });

  it("drops a disagreeing map before recording", () => {
    const { mousetrap } = freshInstance();
    mousetrap.setLayoutMap(fullSpanishMap);
    assert.equal(mousetrap.keyForRecording(makeKeyEvent({ key: "=", code: "Equal" })), "=");
  });

  it("keeps a map that agrees with the typed key", () => {
    const { mousetrap, target } = freshInstance();
    mousetrap.setLayoutMap(fullSpanishMap);
    fire(target, makeKeyEvent({ key: "+", code: "BracketRight" }));
    fire(target, makeKeyEvent({ key: "*", code: "BracketRight", shiftKey: true }));
    fire(target, makeKeyEvent({ key: "Dead", code: "Quote" }));
    assert.equal(mousetrap._layoutMap, fullSpanishMap);
  });
});
