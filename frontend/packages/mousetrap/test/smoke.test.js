import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { freshInstance, fire, makeKeyEvent } from "./helpers.js";

describe("smoke", () => {
  it("fires a bound combo and ignores it without the modifier", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("ctrl+z", () => {
      calls++;
    });

    fire(target, makeKeyEvent({ key: "z", code: "KeyZ", ctrlKey: true }));
    fire(target, makeKeyEvent({ key: "z", code: "KeyZ" }));

    assert.equal(calls, 1);
  });

  it("reset() leaves the instance deaf", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("ctrl+z", () => {
      calls++;
    });
    mousetrap.reset();

    fire(target, makeKeyEvent({ key: "z", code: "KeyZ", ctrlKey: true }));

    assert.equal(calls, 0);
  });
});
