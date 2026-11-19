import assert from "node:assert/strict";
import test from "node:test";
import { getPopupState } from "./PopupState.ts";
import { SessionMap } from "./SessionMap.ts";

test("lists the sessions and their tabs", async () => {
    const sessions = new SessionMap({ load: async () => undefined, save: async () => {} });
    await sessions.announce(
        { tabId: 7, windowId: 1 },
        { type: "penpot-mcp:session", sessionId: "tab", status: "connected" }
    );
    assert.deepEqual(getPopupState(sessions), { sessions: [{ sessionId: "tab", tabId: 7 }] });
});
