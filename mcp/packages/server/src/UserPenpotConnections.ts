import type { PenpotSession } from "@penpot/mcp-common";
import type { PenpotConnection } from "./PenpotConnection";
import { WebSocket } from "ws";

/** Local Penpot connections belonging to one user, indexed by session ID. */
export class UserPenpotConnections {
    private readonly connectionsBySessionId = new Map<string, PenpotConnection>();

    get size(): number {
        return this.connectionsBySessionId.size;
    }

    /** Registers a connection, returning the connection it replaces for the same session, if any. */
    add(connection: PenpotConnection): PenpotConnection | undefined {
        const { sessionId } = connection.session;
        const replaced = this.connectionsBySessionId.get(sessionId);
        this.connectionsBySessionId.set(sessionId, connection);
        return replaced;
    }

    remove(connection: PenpotConnection): void {
        const { sessionId } = connection.session;
        if (this.connectionsBySessionId.get(sessionId) === connection) {
            this.connectionsBySessionId.delete(sessionId);
        }
    }

    /** Returns the registered connection for a session, whether or not it is ready. */
    getRegistered(sessionId: string): PenpotConnection | undefined {
        return this.connectionsBySessionId.get(sessionId);
    }

    get(sessionId: string): PenpotConnection | undefined {
        const connection = this.connectionsBySessionId.get(sessionId);
        return connection?.ready && connection.socket.readyState === WebSocket.OPEN ? connection : undefined;
    }

    getSessions(): PenpotSession[] {
        return [...this.connectionsBySessionId.values()]
            .filter((connection) => connection.ready && connection.socket.readyState === WebSocket.OPEN)
            .map((connection) => ({ ...connection.session }));
    }
}
