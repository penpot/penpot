import { randomUUID } from "node:crypto";
import type { PenpotSession, PluginTaskRequest } from "@penpot/mcp-common";
import type { PenpotConnection } from "../PenpotConnection";
import type { AbstractPluginTask } from "../PluginTask";
import { RemotePluginTask } from "../RemotePluginTask";
import type { RedisBridge, SessionDiscoveryResponse } from "../RedisBridge";
import { createLogger } from "../logger";
import { SessionDiscoveryError, TaskDispatcher, type TaskDispatchHost } from "./TaskDispatcher";

/** Routes tasks over Redis and discovers live sessions across MCP instances. */
export class MultiInstanceTaskDispatcher extends TaskDispatcher {
    private readonly logger = createLogger("MultiInstanceTaskDispatcher");
    private readonly instanceId = randomUUID();
    private readonly subscribedUsers = new Set<string>();
    private readonly connectionChanges = new Map<string, Promise<void>>();
    private readonly pendingDiscoveries = new Map<string, () => void>();

    constructor(
        private readonly host: TaskDispatchHost,
        private readonly redisBridge: RedisBridge,
        private readonly discoveryTimeoutMs = 5000
    ) {
        super();
    }

    async dispatch(task: AbstractPluginTask, userToken: string | null, sessionId?: string): Promise<void> {
        const token = this.requireUserToken(userToken);
        const targetId = sessionId ?? this.selectOnlySession(await this.discoverSessions(token));
        this.host.sendRemoteTask(task, token, targetId);
    }

    async discoverSessions(userToken: string | null): Promise<PenpotSession[]> {
        const token = this.requireUserToken(userToken);
        const requestId = randomUUID();
        const responses = new Map<string, PenpotSession[]>();
        let expectedResponses: number | undefined;
        let settled = false;
        let resolve!: (sessions: PenpotSession[]) => void;
        let reject!: (error: Error) => void;
        const result = new Promise<PenpotSession[]>((res, rej) => {
            resolve = res;
            reject = rej;
        });
        const fail = () => {
            if (!settled) {
                settled = true;
                reject(new SessionDiscoveryError());
            }
        };
        const complete = () => {
            if (!settled && expectedResponses !== undefined && responses.size === expectedResponses) {
                settled = true;
                resolve([...responses.values()].flat());
            }
        };
        const timeout = setTimeout(fail, this.discoveryTimeoutMs);
        this.pendingDiscoveries.set(requestId, fail);
        const releaseResponses = () => {
            void this.redisBridge
                .unsubscribeFromDiscoveryResponses(requestId)
                .catch((error) => this.logger.error(error, "Failed to release discovery response subscription"));
        };
        // Replies can arrive before publish returns its recipient count.
        void this.redisBridge
            .sendDiscoveryRequest(token, requestId, (response: SessionDiscoveryResponse) => {
                if (settled) return;
                responses.set(response.instanceId, response.sessions);
                complete();
            })
            .then((count) => {
                expectedResponses = count;
                complete();
                // A slow subscribe/publish may finish after the discovery timeout.
                if (settled) releaseResponses();
            })
            .catch(fail);
        try {
            return await result;
        } finally {
            clearTimeout(timeout);
            this.pendingDiscoveries.delete(requestId);
            releaseResponses();
        }
    }

    async onNewConnection(connection: PenpotConnection): Promise<void> {
        const token = this.requireUserToken(connection.userToken);
        return this.changeConnections(token, async () => {
            // subscribe to this session's Redis request channel so that task requests
            // issued by other instances are dispatched to this instance
            await this.redisBridge.subscribeToTasks(token, connection.session.sessionId, (request) =>
                this.dispatchForwardedTask(connection, request)
            );
            if (!this.subscribedUsers.has(token)) {
                await this.redisBridge.subscribeToDiscovery(token, (request) => {
                    this.redisBridge.publishDiscoveryResponse(request.id, {
                        instanceId: this.instanceId,
                        sessions: this.host.getUserConnections(token)?.getSessions() ?? [],
                    });
                });
                this.subscribedUsers.add(token);
            }
        });
    }

    async onConnectionClosed(connection: PenpotConnection): Promise<void> {
        const token = this.requireUserToken(connection.userToken);
        return this.changeConnections(token, async () => {
            await this.redisBridge.unsubscribeFromTasks(token, connection.session.sessionId);
            if (!this.host.getUserConnections(token)?.size && this.subscribedUsers.has(token)) {
                this.subscribedUsers.delete(token);
                await this.redisBridge.unsubscribeFromDiscovery(token);
            }
        });
    }

    async close(): Promise<void> {
        for (const fail of this.pendingDiscoveries.values()) fail();
        await Promise.allSettled(this.connectionChanges.values());
    }

    /**
     * Dispatches a task request received over Redis to the locally-connected plugin.
     *
     * Invoked on the instance subscribed to a session's request channel when another
     * instance (or this one) issues a task request. A {@link RemotePluginTask} is created
     * so that, once the plugin responds, the outcome is published back to the issuing
     * instance's Redis response channel via the standard response-handling path.
     *
     * On failure to dispatch (e.g. the plugin is not connected here), an error response
     * is published immediately so the requester need not wait for its timeout.
     *
     * @param connection - The local connection owning the session's request channel
     * @param request - The serialized task request, passed through from Redis
     */
    private dispatchForwardedTask(connection: PenpotConnection, request: PluginTaskRequest): void {
        // The response is published on the channel keyed by the original request ID.
        const task = new RemotePluginTask(request.task, request.params, this.redisBridge, request.id);
        this.logger.debug("Dispatching remote task %s as %s to Penpot via WebSocket", request.id, task.id);

        try {
            this.host.sendLocalTask(task, connection);
        } catch (error) {
            task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
        }
    }

    /** Serializes subscription changes for a user across connects and disconnects. */
    private changeConnections(userToken: string, change: () => Promise<void>): Promise<void> {
        const previous = this.connectionChanges.get(userToken) ?? Promise.resolve();
        const current = previous.catch(() => {}).then(change);
        this.connectionChanges.set(userToken, current);
        const cleanup = () => {
            if (this.connectionChanges.get(userToken) === current) this.connectionChanges.delete(userToken);
        };
        void current.then(cleanup, cleanup);
        return current;
    }

    private requireUserToken(userToken: string | null): string {
        if (!userToken) throw new Error("Multi-instance task routing requires a user token.");
        return userToken;
    }
}
