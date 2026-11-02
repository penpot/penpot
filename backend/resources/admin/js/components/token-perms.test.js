// Contract test for the token permission grouping: run with plain
// node, no harness:
//
//   node --experimental-detect-module --test \
//     backend/resources/admin/js/components/token-perms.test.js
//
// (The flag lets node read the panel's extensionless-ESM `.js`
// files; the panel itself has no build and no test runner.)

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { describePermission, groupPermissions } from "./token-perms.js";

describe("describePermission", () => {
  it("marks listings as reads under their resource", () => {
    assert.deepEqual(describePermission("admin:get-team-members"), {
      perm: "admin:get-team-members",
      resource: "Teams",
      label: "Team members",
      kind: "read",
      warning: null,
    });
  });

  it("marks mutations as writes", () => {
    const described = describePermission("admin:enable-team-feature");
    assert.equal(described.resource, "Teams");
    assert.equal(described.kind, "write");
  });

  it("warns on destructive and data-moving actions", () => {
    assert.equal(
      describePermission("admin:delete-team").warning,
      "Destructive action."
    );
    assert.equal(
      describePermission("admin:export-files").warning,
      "Moves file data in or out of this instance."
    );
    assert.equal(describePermission("admin:get-teams").warning, null);
  });

  it("treats unknown shapes with caution", () => {
    const described = describePermission("admin:frobnicate");
    assert.equal(described.resource, "Other");
    assert.equal(described.kind, "write");
  });
});

describe("groupPermissions", () => {
  it("buckets by resource in panel order with sorted labels", () => {
    const groups = groupPermissions([
      "admin:get-teams",
      "admin:block-profile",
      "admin:delete-team",
      "admin:get-profiles",
    ]);
    assert.deepEqual(
      groups.map((group) => group.resource),
      ["Users", "Teams"]
    );
    assert.deepEqual(
      groups[1].items.map((item) => item.perm),
      ["admin:delete-team", "admin:get-teams"]
    );
  });

  it("never mentions superuser: the bypass is not a grant", () => {
    const groups = groupPermissions(["admin:get-teams", "superuser"]);
    assert.deepEqual(
      groups.map((group) => group.resource),
      ["Teams", "Other"]
    );
  });
});
