import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import type { SessionAnnouncement } from "@penpot/mcp-common";
import { DiscardGuard } from "./DiscardGuard.ts";
import { SessionMap, type SessionChange, type SessionEntry, type SessionStore } from "./SessionMap.ts";

/** In-memory store standing in for session storage. */
class MemoryStore implements SessionStore {
    constructor(public saved?: Record<string, SessionEntry>) {}

    async load() {
        return this.saved;
    }

    async save(entries: Record<string, SessionEntry>) {
        this.saved = structuredClone(entries);
    }
}

const TAB = { tabId: 1, windowId: 10 };
const OTHER_TAB = { tabId: 2, windowId: 10 };

function announcement(
    status: SessionAnnouncement["status"],
    sessionId: string | null = "session"
): SessionAnnouncement {
    return { type: "penpot-mcp:session", sessionId, status };
}

let store: MemoryStore;
let changes: SessionChange[];
let sessions: SessionMap;

beforeEach(() => {
    store = new MemoryStore();
    changes = [];
    sessions = new SessionMap(store);
    sessions.observe({ sessionsChanged: (change) => changes.push(change) });
});

test("maps a connected session to the announcing tab and saves it", async () => {
    await sessions.announce(TAB, announcement("connected"));
    assert.deepEqual(sessions.get("session"), TAB);
    assert.deepEqual(store.saved, { session: TAB });
    assert.deepEqual(changes, [{ tabsAdded: [1], tabsRemoved: [] }]);
});

test("ignores announcements without a connected session", async () => {
    await sessions.announce(TAB, announcement("connecting", null));
    await sessions.announce(TAB, announcement("connected", null));
    await sessions.announce(TAB, announcement("error"));
    assert.deepEqual(sessions.all(), []);
    assert.deepEqual(changes, []);
});

test("keeps the session while the plugin reconnects", async () => {
    await sessions.announce(TAB, announcement("connected"));
    await sessions.announce(TAB, announcement("connecting"));
    await sessions.announce(TAB, announcement("connected"));
    assert.equal(sessions.get("session")?.tabId, 1);
    assert.equal(changes.length, 1);
});

test("forgets the tab's session when its page announces a disconnect", async () => {
    await sessions.announce(TAB, announcement("connected"));
    await sessions.announce(TAB, announcement("disconnected", null));
    assert.equal(sessions.get("session"), undefined);
    assert.deepEqual(changes.at(-1), { tabsAdded: [], tabsRemoved: [1] });
});

test("a tab holds only the session it announced last", async () => {
    await sessions.announce(TAB, announcement("connected", "old"));
    await sessions.announce(TAB, announcement("connected", "new"));
    assert.equal(sessions.get("old"), undefined);
    assert.equal(sessions.get("new")?.tabId, 1);
    assert.deepEqual(changes.at(-1), { tabsAdded: [], tabsRemoved: [] });
});

test("forgets only the sessions of a removed tab", async () => {
    await sessions.announce(TAB, announcement("connected", "first"));
    await sessions.announce(OTHER_TAB, announcement("connected", "second"));
    await sessions.removeTab(1);
    assert.deepEqual(
        sessions.all().map(([sessionId]) => sessionId),
        ["second"]
    );
    assert.deepEqual(changes.at(-1), { tabsAdded: [], tabsRemoved: [1] });
});

test("restores the saved sessions when the worker restarts", async () => {
    await sessions.announce(TAB, announcement("connected"));
    const restarted = new SessionMap(store);
    await restarted.ready();
    assert.equal(restarted.get("session")?.tabId, 1);
});

test("changes made while restoring apply to the restored sessions", async () => {
    const saved = new MemoryStore({ first: TAB });
    const restored = new SessionMap(saved);
    await restored.announce(OTHER_TAB, announcement("connected", "second"));
    assert.deepEqual(Object.keys(saved.saved!).sort(), ["first", "second"]);
});

test("the discard guard protects tabs holding a session and releases them after", async () => {
    const calls: [number, boolean][] = [];
    const guard = new DiscardGuard({
        setAutoDiscardable: async (tabId, autoDiscardable) => {
            calls.push([tabId, autoDiscardable]);
        },
    });
    const guarded = new SessionMap(new MemoryStore());
    guarded.observe(guard);
    await guarded.announce(TAB, announcement("connected"));
    await guarded.removeTab(1);
    assert.deepEqual(calls, [
        [1, false],
        [1, true],
    ]);
});

test("the discard guard ignores tabs closed meanwhile", async () => {
    const guard = new DiscardGuard({ setAutoDiscardable: () => Promise.reject(new Error("No tab with id")) });
    guard.sessionsChanged({ tabsAdded: [1], tabsRemoved: [2] });
    await new Promise((resolve) => setImmediate(resolve));
});
