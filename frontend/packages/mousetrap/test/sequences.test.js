import { describe, it, beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import { freshInstance, fire, makeKeyEvent } from "./helpers.js";

function down(over) {
  return makeKeyEvent({ type: "keydown", ...over });
}

function up(over) {
  return makeKeyEvent({ type: "keyup", ...over });
}

describe("sequences", () => {
  beforeEach(() => {
    mock.timers.enable({ apis: ["setTimeout"] });
  });

  afterEach(() => {
    mock.timers.reset();
  });

  it("fires after the full sequence, not on the prefix alone", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("g v", () => {
      calls++;
    });
    fire(target, down({ key: "g", code: "KeyG" }));
    assert.equal(calls, 0);
    fire(target, down({ key: "v", code: "KeyV" }));
    assert.equal(calls, 1);
  });

  it("advances every sequence sharing a prefix", () => {
    const { mousetrap, target } = freshInstance();
    const seen = [];
    mousetrap.bind("g i", () => {
      seen.push("i");
    });
    mousetrap.bind("g t", () => {
      seen.push("t");
    });
    fire(target, down({ key: "g", code: "KeyG" }));
    fire(target, down({ key: "t", code: "KeyT" }));
    assert.deepEqual(seen, ["t"]);
  });

  it("resets on a wrong key mid-sequence", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("g v", () => {
      calls++;
    });
    fire(target, down({ key: "g", code: "KeyG" }));
    fire(target, down({ key: "e", code: "KeyE" }));
    fire(target, down({ key: "v", code: "KeyV" }));
    assert.equal(calls, 0);
    // and the sequence works again afterwards
    fire(target, down({ key: "g", code: "KeyG" }));
    fire(target, down({ key: "v", code: "KeyV" }));
    assert.equal(calls, 1);
  });

  it("expires after one second without the next key", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("g v", () => {
      calls++;
    });
    fire(target, down({ key: "g", code: "KeyG" }));
    mock.timers.tick(1001);
    fire(target, down({ key: "v", code: "KeyV" }));
    assert.equal(calls, 0);
  });

  it("supports modifiers inside sequences", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("ctrl+k u", () => {
      calls++;
    });
    fire(target, down({ key: "k", code: "KeyK", ctrlKey: true }));
    fire(target, down({ key: "u", code: "KeyU" }));
    assert.equal(calls, 1);
  });

  it("supports keyup sequences and ignores the final keyup", () => {
    const { mousetrap, target } = freshInstance();
    const seen = [];
    mousetrap.bind(
      "g v",
      () => {
        seen.push("seq");
      },
      "keyup",
    );
    mousetrap.bind(
      "v",
      () => {
        seen.push("keyup-v");
      },
      "keyup",
    );
    fire(target, up({ key: "g", code: "KeyG" }));
    fire(target, up({ key: "v", code: "KeyV" }));
    assert.deepEqual(seen, ["seq"]);
  });
});
