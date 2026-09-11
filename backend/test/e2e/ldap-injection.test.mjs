import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { rpcPost, extractCookie } from "./helpers/client.mjs";

async function loginWithLdap(email, password) {
  const res = await rpcPost("login-with-ldap", { email, password });
  if (res.status !== 200 || res.body.type) {
    throw new Error(
      `LDAP login failed: ${JSON.stringify(res.body)}`
    );
  }
  const cookie = extractCookie(res.setCookie);
  return { profile: res.body, cookie };
}

describe("LDAP injection — T5-N1-03", () => {

  it("normal LDAP login works with valid credentials", async () => {
    const { profile, cookie } = await loginWithLdap(
      "fry@planetexpress.com",
      "fry"
    );
    assert.equal(profile.email, "fry@planetexpress.com");
    assert.ok(profile.id, "profile should have id");
    assert.ok(cookie, "cookie should be set");
  });

  it("wildcard injection: *@planetexpress.com must not return client literal as email", async () => {
    // ATTACK SCENARIO (from Criptored audit):
    // 1. Attacker (amy) sends email="*@planetexpress.com" with her own password
    // 2. LDAP filter becomes (mail=*@planetexpress.com) — * is a wildcard
    // 3. With sizelimit=1, LDAP returns amy's entry (first match)
    // 4. Bind succeeds: amy's DN + amy's password = valid
    //
    // EXPECTED BEHAVIOR AFTER FIX (two valid outcomes):
    // A) If * is escaped: LDAP finds no match → wrong-credentials (injection blocked)
    // B) If * matches: profile email must be "amy@planetexpress.com" (directory), not "*@planetexpress.com" (client)
    //
    // Either outcome is correct — the vulnerability is fixed.
    try {
      const { profile } = await loginWithLdap("*@planetexpress.com", "amy");
      // Outcome B: login succeeded, verify email is from directory
      assert.equal(
        profile.email,
        "amy@planetexpress.com",
        "email must come from LDAP directory, not client input"
      );
    } catch (e) {
      // Outcome A: injection blocked — * is escaped, no LDAP match
      assert.ok(
        e.message.includes("wrong-credentials"),
        "wildcard should be rejected or return directory email"
      );
    }
  });

  it("identity swap: alternate email must return primary directory email", async () => {
    // Professor has two emails in LDAP: professor@ and hubert@.
    // Login with hubert@ — the profile email should be the one
    // the LDAP directory returns as attrs-email, not what the client typed.
    //
    // EXPECTED BEHAVIOR AFTER FIX:
    // Profile email should be "professor@planetexpress.com" (primary directory email),
    // NOT "hubert@planetexpress.com" (client literal).
    //
    // CURRENT BUG: email is "hubert@planetexpress.com" (client literal) — test FAILS
    const { profile, cookie } = await loginWithLdap(
      "hubert@planetexpress.com",
      "professor"
    );
    assert.ok(profile.id, "profile should have id");
    assert.ok(cookie, "cookie should be set");
    // This assertion FAILS with current code (RED) — proves the vulnerability
    assert.equal(
      profile.email,
      "professor@planetexpress.com",
      "email must come from LDAP directory, not client input"
    );
  });

  it("wrong password fails", async () => {
    try {
      await loginWithLdap("fry@planetexpress.com", "wrong-password");
      assert.fail("should have thrown");
    } catch (e) {
      assert.ok(
        e.message.includes("LDAP login failed") ||
        e.message.includes("wrong-credentials"),
        "should fail with wrong credentials"
      );
    }
  });

  it("non-existent user fails", async () => {
    try {
      await loginWithLdap("nobody@planetexpress.com", "password");
      assert.fail("should have thrown");
    } catch (e) {
      assert.ok(
        e.message.includes("LDAP login failed") ||
        e.message.includes("wrong-credentials"),
        "should fail for non-existent user"
      );
    }
  });
});
