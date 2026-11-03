import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Mousetrap } from "../index.js";
import {
  freshInstance,
  fire,
  makeKeyEvent,
  makeElement,
} from "./helpers.js";

describe("stopCallback", () => {
  function stopped(elementOver, eventOver = {}, combo = "ctrl+z") {
    const { mousetrap } = freshInstance();
    const event = makeKeyEvent({ target: makeElement(elementOver), ...eventOver });
    return mousetrap.stopCallback(event, event.target, combo);
  }

  it("stops inside form fields", () => {
    for (const tagName of ["INPUT", "SELECT", "TEXTAREA"]) {
      assert.equal(stopped({ tagName }), true, tagName);
    }
  });

  it("stops buttons only for tab combos, tolerating a missing combo", () => {
    assert.equal(stopped({ tagName: "BUTTON" }, {}, "shift+tab"), true);
    assert.equal(stopped({ tagName: "BUTTON" }, {}, "ctrl+z"), false);
    assert.equal(
      stopped({ tagName: "BUTTON" }, {}, undefined),
      false,
    );
  });

  it("stops editable content", () => {
    assert.equal(stopped({ contentEditable: "true" }), true);
    assert.equal(stopped({ contentEditable: "plaintext-only" }), true);
    assert.equal(stopped({ contentEditable: "false" }), false);
    assert.equal(stopped({ contentEditable: "inherit" }), false);
  });

  it("lets events through on mousetrap-marked elements", () => {
    assert.equal(stopped({ className: "mousetrap" }), false);
    assert.equal(stopped({ className: "foo mousetrap bar" }), false);
    assert.equal(stopped({ dataset: { mousetrapDontStop: "" } }), false);
  });

  it("always lets keyup through", () => {
    assert.equal(stopped({ tagName: "INPUT" }, { type: "keyup" }), false);
  });

  it("retargets through composedPath in open shadow trees", () => {
    const inner = makeElement({ tagName: "DIV" });
    const event = makeKeyEvent({
      target: makeElement({ tagName: "INPUT" }),
      composedPath: () => [inner],
    });
    const { mousetrap } = freshInstance();
    assert.equal(mousetrap.stopCallback(event, event.target, "ctrl+z"), false);
  });

  it("gates dispatch end to end: typing in an input fires nothing", () => {
    const { mousetrap, target } = freshInstance();
    let calls = 0;
    mousetrap.bind("ctrl+z", () => {
      calls++;
    });
    fire(
      target,
      makeKeyEvent({
        key: "z",
        code: "KeyZ",
        ctrlKey: true,
        target: makeElement({ tagName: "INPUT" }),
      }),
    );
    assert.equal(calls, 0);
  });
});

describe("exports", () => {
  it("exposes the shared default instance", async () => {
    const mod = await import("../index.js");
    assert.equal(typeof mod.default.bind, "function");
    assert.equal(typeof mod.default.reset, "function");
  });

  it("constructs without a target in Node: no listeners, no throw", () => {
    const mousetrap = new Mousetrap();
    assert.deepEqual(mousetrap._callbacks, {});
  });

  it("wires keydown and keyup on the given target", async () => {
    const { Mousetrap: Klass } = await import("../index.js");
    const seen = [];
    const target = {
      addEventListener(type) {
        seen.push(type);
      },
    };
    new Klass(target);
    assert.deepEqual(seen, ["keydown", "keyup"]);
  });
});
