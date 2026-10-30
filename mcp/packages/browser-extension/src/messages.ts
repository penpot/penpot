import type { McpConnectionStatus, SessionAnnouncement } from "@penpot/mcp-common";

/**
 * Message sent by the content script to the service worker.
 *
 * - `session`: the page announced its MCP session
 * - `page-hidden`: the page is being unloaded or stored in the back/forward cache
 */
export type ContentMessage = { type: "session"; announcement: SessionAnnouncement } | { type: "page-hidden" };

const STATUSES: readonly McpConnectionStatus[] = ["idle", "connecting", "connected", "disconnected", "error"];

function isSessionId(value: unknown): value is string | null {
    return value === null || (typeof value === "string" && value.length > 0 && value.length <= 128);
}

/**
 * Validates a session announcement posted on the page.
 *
 * @param data - The data of a window message
 * @returns The announcement, or null if the data is not a valid announcement
 */
export function parseAnnouncement(data: unknown): SessionAnnouncement | null {
    if (typeof data !== "object" || data === null) {
        return null;
    }
    const { type, sessionId, status } = data as Record<string, unknown>;
    if (type !== "penpot-mcp:session" || !isSessionId(sessionId) || !STATUSES.includes(status as McpConnectionStatus)) {
        return null;
    }
    return { type, sessionId, status: status as McpConnectionStatus };
}
