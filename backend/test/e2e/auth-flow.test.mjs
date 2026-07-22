import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  createDemoProfile,
  login,
  setupTestProfile,
} from "./helpers/auth.mjs";
import { rpcPost } from "./helpers/client.mjs";

describe("auth flow", () => {
  it("creates a demo profile", async () => {
    const { email, password } = await createDemoProfile();
    assert.match(email, /^demo-.*\.demo@example\.com$/);
    assert.ok(password.length > 0);
  });

  it("logs in with valid credentials", async () => {
    const { email, password } = await createDemoProfile();
    const { profile, cookie } = await login(email, password);

    assert.equal(profile.email, email);
    assert.equal(profile.isDemo, true);
    assert.ok(profile.id, "profile should have id");
    assert.ok(profile.defaultProjectId, "profile should have defaultProjectId");
    assert.ok(profile.defaultTeamId, "profile should have defaultTeamId");
    assert.ok(cookie, "cookie should be set");
  });

  it("login sets session cookie", async () => {
    const { email, password } = await createDemoProfile();
    const { cookie } = await login(email, password);
    assert.ok(cookie, "auth-token cookie should be extracted");
    assert.ok(cookie.length > 10, "cookie should have meaningful length");
  });

  it("login fails with wrong password", async () => {
    const { email } = await createDemoProfile();
    try {
      await login(email, "wrong-password");
      assert.fail("should have thrown");
    } catch (e) {
      assert.ok(e.message.includes("Login failed"));
    }
  });

  it("login fails with non-existent email", async () => {
    try {
      await login("nonexistent@example.com", "some-password");
      assert.fail("should have thrown");
    } catch (e) {
      assert.ok(e.message.includes("Login failed"));
    }
  });

  it("authenticated RPC with cookie", async () => {
    const { profile, cookie } = await setupTestProfile();
    const res = await rpcPost("get-profile", {}, { cookieToken: cookie });
    assert.equal(res.status, 200);
    assert.equal(res.body.id, profile.id);
    assert.equal(res.body.email, profile.email);
  });

  it("unauthenticated RPC returns anonymous profile", async () => {
    const res = await rpcPost("get-profile", {});
    assert.equal(res.status, 200);
    // Anonymous profile has uuid/zero as id
    assert.equal(res.body.id, "00000000-0000-0000-0000-000000000000");
  });

  it("setupTestProfile returns all fields", async () => {
    const { profile, cookie, email, password } = await setupTestProfile();
    assert.ok(profile.id);
    assert.ok(profile.defaultProjectId);
    assert.ok(cookie);
    assert.ok(email);
    assert.ok(password);
  });
});
