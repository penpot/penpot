import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { freshInstance, fire, makeKeyEvent, makeSpiedEvent } from "./helpers.js";

describe("bind and dispatch", () => {
  it("binds a single key", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("m", () => {
      calls++;
    });
    fire(target, makeKeyEvent({ key: "m", code: "KeyM" }));
    assert.equal(calls, 1);
  });

  it("binds an array of combos to the same callback", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    const cb = () => {
      calls++;
    };
    mousetrap.bind(["del", "backspace"], cb);
    fire(target, makeKeyEvent({ key: "Delete", code: "Delete" }));
    fire(target, makeKeyEvent({ key: "Backspace", code: "Backspace" }));
    assert.equal(calls, 2);
  });

  it("supports the plus forms", () => {
    const { mousetrap, target } = freshInstance();
    let plus = 0;
    let shiftPlus = 0;
    mousetrap.bind("+", () => {
      plus++;
    });
    mousetrap.bind("shift++", () => {
      shiftPlus++;
    });
    fire(target, makeKeyEvent({ key: "+", code: "Equal" }));
    fire(target, makeKeyEvent({ key: "*", code: "BracketRight", shiftKey: true }));
    // "*" normalizes by glyph, so "shift++" (shift + "+" key) does not fire;
    // this pins the layout-independent behavior for the "+" physical key.
    assert.equal(plus, 1);
    assert.equal(shiftPlus, 0);
  });

  it("resolves bind-string aliases to canonical tokens", () => {
    const cases = [
      ["control+z", { key: "z", code: "KeyZ", ctrlKey: true }],
      ["command+z", { key: "z", code: "KeyZ", metaKey: true }],
      ["option+q", { key: "q", code: "KeyQ", altKey: true }],
      ["mod+z", { key: "z", code: "KeyZ", ctrlKey: true }],
      ["return", { key: "Enter", code: "Enter" }],
      ["esc", { key: "Escape", code: "Escape" }],
      ["plus", { key: "+", code: "Equal" }],
    ];
    for (const [combo, eventOver] of cases) {
      const { mousetrap, target } = freshInstance();
      let calls = 0;
      mousetrap.bind(combo, () => {
        calls++;
      });
      fire(target, makeKeyEvent(eventOver));
      assert.equal(calls, 1, combo);
    }
  });

  it("defaults to keydown and honors explicit keyup", () => {
    const { mousetrap, target } = freshInstance();
    let downs = 0;
    let ups = 0;
    mousetrap.bind("x", () => {
      downs++;
    });
    mousetrap.bind(
      "y",
      () => {
        ups++;
      },
      "keyup",
    );
    fire(target, makeKeyEvent({ key: "x", code: "KeyX", type: "keyup" }));
    fire(target, makeKeyEvent({ key: "y", code: "KeyY" }));
    fire(target, makeKeyEvent({ key: "y", code: "KeyY", type: "keyup" }));
    assert.equal(downs, 0);
    assert.equal(ups, 1);
  });

  it("fires a bare modifier on keyup", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind(
      "shift",
      () => {
        calls++;
      },
      "keyup",
    );
    fire(target, makeKeyEvent({ key: "Shift", code: "ShiftLeft", type: "keyup", shiftKey: true }));
    assert.equal(calls, 1);
  });

  it("calls preventDefault and stopPropagation when the callback returns false", () => {
    const { mousetrap, target } = freshInstance();
    mousetrap.bind("ctrl+s", () => false);
    const { event, calls } = makeSpiedEvent({ key: "s", code: "KeyS", ctrlKey: true });
    fire(target, event);
    assert.equal(calls.preventDefault, 1);
    assert.equal(calls.stopPropagation, 1);
  });

  it("reset() clears every binding", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("a", () => {
      calls++;
    });
    mousetrap.reset();
    fire(target, makeKeyEvent({ key: "a", code: "KeyA" }));
    assert.equal(calls, 0);
  });

  it("overwrite replaces the previous callback for the combo", () => {
    const { mousetrap, target } = freshInstance();
    const seen = [];
    mousetrap.bind("ctrl+z", () => {
      seen.push("old");
    });
    mousetrap.bind(
      "ctrl+z",
      () => {
        seen.push("new");
      },
      undefined,
      true,
    );
    fire(target, makeKeyEvent({ key: "z", code: "KeyZ", ctrlKey: true }));
    assert.deepEqual(seen, ["new"]);
  });

  it("unbind pins the current noop behavior (TODO: really remove)", () => {
    // NOTE: unbind() binds an empty function on top instead of removing
    // the original, so the original still fires. Pinned as-is; fixing it
    // is a behavior change out of scope.
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("a", () => {
      calls++;
    });
    mousetrap.unbind("a");
    fire(target, makeKeyEvent({ key: "a", code: "KeyA" }));
    assert.equal(calls, 1);
  });

  it("trigger dispatches a bind registered with an explicit action", () => {
    const { mousetrap } = freshInstance();
    let calls = 0;
    let seenCombo = null;
    mousetrap.bind(
      "ctrl+q",
      (e, combo) => {
        calls++;
        seenCombo = combo;
      },
      "keydown",
    );
    mousetrap.trigger("ctrl+q", "keydown");
    assert.equal(calls, 1);
    assert.equal(seenCombo, "ctrl+q");
  });

  it("trigger documents the default-action quirk (TODO)", () => {
    // NOTE: binds without an explicit action are stored under
    // "combo:undefined", so trigger(combo, "keydown") misses them.
    // Pinned as-is; fixing it is a behavior change out of scope.
    const { mousetrap } = freshInstance();
    let calls = 0;
    mousetrap.bind("ctrl+q", () => {
      calls++;
    });
    mousetrap.trigger("ctrl+q", "keydown");
    assert.equal(calls, 0);
  });
});
