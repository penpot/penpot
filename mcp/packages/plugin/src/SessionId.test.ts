import assert from "node:assert/strict";
import test from "node:test";
import { SessionId } from "./SessionId.ts";

test("derives a stable 50-bit Base32 identifier from only the Penpot user session", async () => {
    assert.equal(await SessionId.forSession("tab-1"), "ftdmhltgwk");
    assert.equal(await SessionId.forSession("tab-1"), "ftdmhltgwk");
    assert.equal(await SessionId.forSession("tab-2"), "cb3oexpdmd");
});

test("shortens a UUID session to ten copyable Base32 characters", async () => {
    const sessionId = await SessionId.forSession("a7457b7c-cf45-80d4-8008-ba5b1d08e6c8");
    assert.match(sessionId, /^[a-z2-7]{10}$/);
});
