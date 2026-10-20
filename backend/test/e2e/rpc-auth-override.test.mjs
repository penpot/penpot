import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { setupTestProfile } from "./helpers/auth.mjs";
import { rpcPost } from "./helpers/client.mjs";

describe("RPC auth context override", () => {
  it("transit body cannot override server profile-id (get-profile)", async () => {
    // ATTACK SCENARIO:
    // 1. Attacker A logs in and gets a session cookie (server binds
    //    profile-id A to the request).
    // 2. A POSTs a transit body smuggling the namespaced key
    //    :app.rpc/profile-id with victim B's id. Transit is a
    //    superset of JSON, so the body is hand-written, no client
    //    library needed: {"~:app.rpc/profile-id": "~u<uuid>"}.
    // 3. get-profile only reads ::rpc/profile-id and its schema
    //    ([:map]) accepts anything.
    //
    // EXPECTED BEHAVIOR AFTER FIX: the response holds A's profile.
    // Before the fix it held B's profile (id + email leak).
    const attacker = await setupTestProfile();
    const victim = await setupTestProfile();

    const transitBody =
      `{"~:app.rpc/profile-id": "~u${victim.profile.id}"}`;

    const res = await rpcPost("get-profile", transitBody, {
      cookieToken: attacker.cookie,
      contentType: "application/transit+json",
      accept: "application/transit+json",
      query: "transit_verbose=1",
    });

    assert.equal(res.status, 200);
    // Verbose transit map, dependency-free parse:
    // {"~:id":"~u<uuid>","~:email":"...",...}
    const data = JSON.parse(res.body);
    assert.equal(
      data["~:id"],
      `~u${attacker.profile.id}`,
      "profile must come from the session, not the request body"
    );
    assert.ok(
      !res.body.includes(victim.profile.id),
      "victim id must not leak into the response"
    );
  });
});
