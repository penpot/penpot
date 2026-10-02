import type { WebSocket } from "ws";
import type { PenpotSession } from "@penpot/mcp-common";

/**
 * Observable liveness state of a plugin connection.
 */
export interface PluginLivenessState {
    /** timestamp of the last plugin message, in ms since epoch. */
    lastHeartbeat: number;
    /** whether the plugin reported a browser freeze. */
    frozen: boolean;
}

/** One local Penpot WebSocket connection and its session metadata. */
export interface PenpotConnection extends PluginLivenessState {
    readonly socket: WebSocket;
    readonly userToken: string | null;
    readonly session: PenpotSession;
    readonly pingInterval: NodeJS.Timeout;
    /** whether initialization and dispatch subscriptions have completed. */
    ready: boolean;
}
