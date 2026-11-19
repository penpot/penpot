import type { SessionAnnouncement } from "@penpot/mcp-common";

/** The tab holding an MCP session. */
export interface SessionEntry {
    tabId: number;
    windowId: number;
}

/** Persistence of the session map across service worker restarts. */
export interface SessionStore {
    load(): Promise<Record<string, SessionEntry> | undefined>;
    save(entries: Record<string, SessionEntry>): Promise<void>;
}

/** Change of the tabs holding sessions, reported after each change of the map. */
export interface SessionChange {
    /** tabs that hold a session and held none before. */
    tabsAdded: number[];
    /** tabs that held a session and hold none now. */
    tabsRemoved: number[];
}

/** Receiver of the changes of a session map. */
export interface SessionMapObserver {
    sessionsChanged(change: SessionChange): void;
}

/**
 * Map from MCP session IDs to the tabs holding them.
 *
 * A tab holds the session it last announced as connected, until it announces a disconnect, is
 * hidden or is closed. The map is saved after each change and restored when the service worker
 * restarts.
 */
export class SessionMap {
    private entries = new Map<string, SessionEntry>();
    private readonly observers: SessionMapObserver[] = [];
    private readonly loaded: Promise<void>;

    constructor(private readonly store: SessionStore) {
        this.loaded = store.load().then(
            (saved) => {
                this.entries = new Map(Object.entries(saved ?? {}));
            },
            () => {}
        );
    }

    /** Adds a receiver of the changes of this map. */
    observe(observer: SessionMapObserver): void {
        this.observers.push(observer);
    }

    /** Resolves once the saved entries have been restored. */
    ready(): Promise<void> {
        return this.loaded;
    }

    /**
     * Records a session announcement made by the page in a tab.
     *
     * @param tab - The tab and window of the announcing page
     * @param announcement - The page's announcement
     */
    async announce(tab: { tabId: number; windowId: number }, announcement: SessionAnnouncement): Promise<void> {
        await this.loaded;
        if (announcement.status === "disconnected") {
            await this.change(() => this.deleteTab(tab.tabId));
        } else if (announcement.status === "connected" && announcement.sessionId) {
            const { sessionId } = announcement;
            await this.change(() => {
                // a tab holds a single session
                this.deleteTab(tab.tabId);
                this.entries.set(sessionId, { ...tab });
            });
        }
    }

    /**
     * Forgets the sessions held by a tab.
     *
     * @param tabId - The tab whose page was hidden, or which was closed or replaced
     */
    async removeTab(tabId: number): Promise<void> {
        await this.loaded;
        await this.change(() => this.deleteTab(tabId));
    }

    /** Returns the entry of a session, if a tab holds it. */
    get(sessionId: string): SessionEntry | undefined {
        return this.entries.get(sessionId);
    }

    /** Returns all sessions with their entries. */
    all(): [string, SessionEntry][] {
        return [...this.entries];
    }

    /** Applies a change, then saves the map and reports the change if any tab gained or lost a session. */
    private async change(apply: () => void): Promise<void> {
        const before = this.tabs();
        const snapshot = JSON.stringify([...this.entries]);
        apply();
        if (JSON.stringify([...this.entries]) === snapshot) {
            return;
        }
        const after = this.tabs();
        await this.store.save(Object.fromEntries(this.entries));
        const change = {
            tabsAdded: [...after].filter((tabId) => !before.has(tabId)),
            tabsRemoved: [...before].filter((tabId) => !after.has(tabId)),
        };
        for (const observer of this.observers) {
            observer.sessionsChanged(change);
        }
    }

    private deleteTab(tabId: number): void {
        for (const [sessionId, entry] of [...this.entries]) {
            if (entry.tabId === tabId) {
                this.entries.delete(sessionId);
            }
        }
    }

    private tabs(): Set<number> {
        return new Set([...this.entries.values()].map((entry) => entry.tabId));
    }
}
