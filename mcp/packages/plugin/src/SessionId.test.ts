import assert from "node:assert/strict";
import test from "node:test";
import { SessionId } from "./SessionId.ts";

test("derives a stable 50-bit Base32 identifier from the tab and file", async () => {
    assert.equal(await SessionId.forFile("tab-1", "file-1"), "pq3gxqddgj");
    assert.equal(await SessionId.forFile("tab-1", "file-1"), "pq3gxqddgj");
    assert.equal(await SessionId.forFile("tab-2", "file-1"), "jablgbun4p");
    assert.equal(await SessionId.forFile("tab-1", "file-2"), "7s3kow4ucx");
});

test("encodes the tab and file without ambiguous boundaries", async () => {
    assert.notEqual(await SessionId.forFile("ab", "c"), await SessionId.forFile("a", "bc"));
});
