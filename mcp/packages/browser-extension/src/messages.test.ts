import assert from "node:assert/strict";
import test from "node:test";
import { parseAnnouncement } from "./messages.ts";

const announcement = { type: "penpot-mcp:session", sessionId: "abcdefghij", status: "connected" };

test("accepts session announcements, with or without a session ID", () => {
    for (const data of [announcement, { ...announcement, sessionId: null, status: "connecting" }]) {
        assert.deepEqual(parseAnnouncement(data), data);
    }
});

test("keeps only the announcement fields", () => {
    assert.deepEqual(parseAnnouncement({ ...announcement, extra: 1 }), announcement);
});

test("rejects other messages and malformed announcements", () => {
    for (const data of [
        null,
        "penpot-mcp:session",
        { ...announcement, type: "penpot-mcp-extension:hello" },
        { ...announcement, sessionId: "" },
        { ...announcement, sessionId: 42 },
        { ...announcement, status: "frozen" },
    ]) {
        assert.equal(parseAnnouncement(data), null, JSON.stringify(data));
    }
});
