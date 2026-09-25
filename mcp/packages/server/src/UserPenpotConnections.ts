import type { PenpotSession } from "@penpot/mcp-common";
import type { PenpotConnection } from "./PenpotConnection";
import { WebSocket } from "ws";

/** Local Penpot connections belonging to one user, indexed by session ID. */
export class UserPenpotConnections {
    private readonly connectionsBySessionId = new Map<string, PenpotConnection>();

    get size(): number {
        return this.connectionsBySessionId.size;
    }

    add(connection: PenpotConnection): void {
        const { sessionId } = connection.session;
        if (this.connectionsBySessionId.has(sessionId)) {
            throw new Error("A Penpot connection with this session ID already exists.");
        }
        this.connectionsBySessionId.set(sessionId, connection);
    }

    remove(connection: PenpotConnection): void {
        const { sessionId } = connection.session;
        if (this.connectionsBySessionId.get(sessionId) === connection) {
            this.connectionsBySessionId.delete(sessionId);
        }
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
