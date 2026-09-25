import type { PenpotSession } from "@penpot/mcp-common";
import type { AbstractPluginTask } from "../PluginTask";
import { TaskDispatcher, type TaskDispatchHost } from "./TaskDispatcher";

/** Routes tasks to connections held by this MCP server instance. */
export class SingleInstanceTaskDispatcher extends TaskDispatcher {
    constructor(private readonly host: TaskDispatchHost) {
        super();
    }

    async discoverSessions(userToken: string | null): Promise<PenpotSession[]> {
        return this.host.getUserConnections(userToken)?.getSessions() ?? [];
    }

    async dispatch(task: AbstractPluginTask, userToken: string | null, sessionId?: string): Promise<void> {
        const targetId = sessionId ?? this.selectOnlySession(await this.discoverSessions(userToken));
        const connection = this.host.getUserConnections(userToken)?.get(targetId);
        if (!connection) {
            throw new Error(`Penpot session ${JSON.stringify(targetId)} is not connected for this user.`);
        }
        this.host.sendLocalTask(task, connection);
    }

    async onNewConnection(): Promise<void> {}
    async onConnectionClosed(): Promise<void> {}
    async close(): Promise<void> {}
}
