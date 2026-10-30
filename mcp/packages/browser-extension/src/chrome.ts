import type { DiscardControl } from "./DiscardGuard.ts";
import type { SessionEntry, SessionStore } from "./SessionMap.ts";

const SESSIONS_KEY = "sessions";

/**
 * Session store in `chrome.storage.session`, which lives in memory, survives service worker
 * restarts and clears when the browser closes.
 */
export class ChromeSessionStore implements SessionStore {
    async load(): Promise<Record<string, SessionEntry> | undefined> {
        const stored = await chrome.storage.session.get(SESSIONS_KEY);
        return stored[SESSIONS_KEY] as Record<string, SessionEntry> | undefined;
    }

    async save(entries: Record<string, SessionEntry>): Promise<void> {
        await chrome.storage.session.set({ [SESSIONS_KEY]: entries });
    }
}

/** Discard control through the Chrome tabs API. */
export class ChromeDiscardControl implements DiscardControl {
    async setAutoDiscardable(tabId: number, autoDiscardable: boolean): Promise<void> {
        await chrome.tabs.update(tabId, { autoDiscardable });
    }
}
