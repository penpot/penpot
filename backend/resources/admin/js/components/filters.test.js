// Contract test for the deleted-filter helpers: run with plain
// node, no harness:
//
//   node --experimental-detect-module --test \
//     backend/resources/admin/js/components/filters.test.js
//
// (The flag lets node read the panel's extensionless-ESM `.js`
// files; the panel itself has no build and no test runner.)
// `deletedParams` is pure; `deletedSelect` gets a tiny document
// stub (select/option halves only).

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { deletedParams, deletedSelect } from "./filters.js";

function fakeElement() {
  return {
    className: "",
    value: "",
    textContent: "",
    children: [],
    setAttribute() {},
    appendChild(child) {
      this.children.push(child);
      return child;
    },
  };
}

globalThis.document = { createElement: () => fakeElement() };

describe("deletedParams", () => {
  it("maps the tri-state to RPC params", () => {
    assert.deepEqual(deletedParams(""), {});
    assert.deepEqual(deletedParams("active"), { deleted: false });
    assert.deepEqual(deletedParams("deleted"), { deleted: true });
    assert.deepEqual(deletedParams("bogus"), {});
    assert.deepEqual(deletedParams(undefined), {});
  });
});

describe("deletedSelect", () => {
  it("preselects known values and falls back to all", () => {
    assert.equal(deletedSelect("teams", "deleted").value, "deleted");
    assert.equal(deletedSelect("teams", "active").value, "active");
    assert.equal(deletedSelect("teams", "bogus").value, "");
    assert.equal(deletedSelect("teams", undefined).value, "");
  });

  it("labels the all-option with the noun", () => {
    const select = deletedSelect("teams", "");
    assert.equal(select.children[0].value, "");
    assert.equal(select.children[0].textContent, "All teams");
  });
});
