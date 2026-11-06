import type { SessionMap } from "./SessionMap.ts";

/** Request of the popup for the extension's state. */
export interface PopupStateRequest {
    type: "popup:get-state";
}

/** State of the extension shown in the popup. */
export interface PopupState {
    sessions: { sessionId: string; tabId: number }[];
}

/**
 * Collects the state shown in the popup.
 *
 * @param sessions - The map of sessions to tabs
 */
export function getPopupState(sessions: SessionMap): PopupState {
    return {
        sessions: sessions.all().map(([sessionId, entry]) => ({ sessionId, tabId: entry.tabId })),
    };
}
