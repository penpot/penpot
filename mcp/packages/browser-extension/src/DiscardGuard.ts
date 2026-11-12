import type { SessionChange, SessionMapObserver } from "./SessionMap.ts";

/** Browser control over the automatic discarding of tabs. */
export interface DiscardControl {
    setAutoDiscardable(tabId: number, autoDiscardable: boolean): Promise<void>;
}

/**
 * Keeps the browser from discarding tabs that hold an MCP session, since a discarded tab reloads
 * and loses its MCP connection. Tabs regain automatic discarding when they stop holding a session.
 */
export class DiscardGuard implements SessionMapObserver {
    constructor(private readonly control: DiscardControl) {}

    sessionsChanged(change: SessionChange): void {
        for (const tabId of change.tabsAdded) {
            this.set(tabId, false);
        }
        for (const tabId of change.tabsRemoved) {
            this.set(tabId, true);
        }
    }

    private set(tabId: number, autoDiscardable: boolean): void {
        // the tab may have been closed meanwhile
        this.control.setAutoDiscardable(tabId, autoDiscardable).catch(() => {});
    }
}
