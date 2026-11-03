// Contract test for the login hop: run with plain node, no
// harness:
//
//   node --experimental-detect-module --test \
//     backend/resources/admin/js/auth.test.js
//
// (The flag lets node read the panel's extensionless-ESM `.js`
// files; the panel itself has no build and no test runner.)
// `loginUrl` takes the page url as a plain string, so no browser
// globals are needed. `auth.js` also imports `./api.js`, which only
// builds urls at import time and never touches the network.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { loginUrl } from "./auth.js";

describe("loginUrl", () => {
  it("lands on the deployment root with the auth-login screen", () => {
    assert.equal(
      loginUrl("http://localhost:3450/admin/js/auth.js"),
      "/?screen=auth-login"
    );
  });

  it("keeps working under a subpath deployment", () => {
    assert.equal(
      loginUrl("https://example.com/penpot/admin/js/auth.js"),
      "/penpot/?screen=auth-login"
    );
  });
});
