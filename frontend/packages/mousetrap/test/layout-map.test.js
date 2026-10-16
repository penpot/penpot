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
    assert.equal(firedWithMap(spanishMap, "shift+*", event), 0);
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
});
