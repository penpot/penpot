import type { PenpotSession } from "@penpot/mcp-common";
import type { PenpotConnection } from "../PenpotConnection";
import type { AbstractPluginTask } from "../PluginTask";
import type { UserPenpotConnections } from "../UserPenpotConnections";

/** Local connection access and tracked task execution provided by PluginBridge. */
export interface TaskDispatchHost {
    getUserConnections(userToken: string | null): UserPenpotConnections | undefined;
    sendLocalTask(task: AbstractPluginTask, connection: PenpotConnection): void;
    sendRemoteTask(task: AbstractPluginTask, userToken: string, sessionId: string): void;
}

/** Session discovery, task routing, and connection subscription lifecycle. */
export abstract class TaskDispatcher {
    abstract discoverSessions(userToken: string | null): Promise<PenpotSession[]>;
    abstract dispatch(task: AbstractPluginTask, userToken: string | null, sessionId?: string): Promise<void>;
    abstract onNewConnection(connection: PenpotConnection): Promise<void>;
    abstract onConnectionClosed(connection: PenpotConnection): Promise<void>;
    abstract close(): Promise<void>;

    /** Selects the sole session from a complete discovery result. */
    protected selectOnlySession(sessions: PenpotSession[]): string {
        if (sessions.length === 0) {
            throw new Error("No Penpot sessions are connected. Please connect the Penpot MCP plugin and retry.");
        }
        if (sessions.length !== 1) {
            throw new Error(
                "Multiple Penpot sessions are connected. Ask the user to select one of the active sessions, " +
                    "then retry with its sessionId:\n" +
                    sessions
                        .map(
                            (session) =>
                                `${session.sessionId}: ${JSON.stringify(session.fileName)} (file ${session.fileId})`
                        )
                        .join("\n")
            );
        }
        return sessions[0].sessionId;
    }
}

/** Error returned when discovery cannot establish the complete set of sessions. */
export class SessionDiscoveryError extends Error {
    constructor() {
        super("Penpot session discovery failed. This may be due to temporary server load. You can retry the request.");
        this.name = "SessionDiscoveryError";
    }
}
