import { WebSocket, WebSocketServer } from "ws";
import * as http from "http";
import { z } from "zod";
import { AbstractPluginTask, PluginTask } from "./PluginTask";
import { PluginTaskResponse, PluginTaskResult } from "@penpot/mcp-common";
import { createLogger } from "./logger";
import type { PenpotMcpServer } from "./PenpotMcpServer";
import type { RedisBridge } from "./RedisBridge";
import type { PenpotConnection, PluginLivenessState } from "./PenpotConnection";
import { UserPenpotConnections } from "./UserPenpotConnections";
import type { TaskDispatcher, TaskDispatchHost } from "./dispatch/TaskDispatcher";
import { SingleInstanceTaskDispatcher } from "./dispatch/SingleInstanceTaskDispatcher";
import { MultiInstanceTaskDispatcher } from "./dispatch/MultiInstanceTaskDispatcher";

const KEEP_ALIVE_TIME = 30000; // 30 seconds
const INITIALIZATION_TIMEOUT_MS = 10_000;

/**
 * Maximum plugin heartbeat age before a connection is stale.
 *
 * This uses plugin heartbeats rather than WebSocket pongs because the browser can answer
 * protocol pings while the tab's JavaScript event loop is frozen.
 */
export const HEARTBEAT_STALE_THRESHOLD_MS = 30000;

const connectionInitSchema = z.object({
    type: z.literal("initialize"),
    session: z.object({
        sessionId: z.string().min(1).max(128),
        fileId: z.string().min(1).max(128),
        fileName: z.string(),
    }),
});

/**
 * Throws if the plugin tab cannot currently run tasks.
 *
 * A socket can stay open while the page event loop is paused, so task dispatch must check
 * plugin-level liveness before sending work.
 */
export function assertPluginResponsive(
    state: PluginLivenessState,
    now: number,
    staleThresholdMs: number = HEARTBEAT_STALE_THRESHOLD_MS
): void {
    if (state.frozen) {
        throw new Error(
            `The Penpot plugin tab has been frozen by the browser and cannot run tasks. ` +
                `Please click/focus the Penpot tab to wake it, then retry.`
        );
    }

    const heartbeatAge = now - state.lastHeartbeat;
    if (heartbeatAge > staleThresholdMs) {
        throw new Error(
            `The Penpot plugin tab appears to be suspended by the browser (no heartbeat for ` +
                `${Math.round(heartbeatAge / 1000)}s). Please click/focus the Penpot tab to wake it, ` +
                `then retry.`
        );
    }
}

/**
 * Manages WebSocket connections to Penpot plugin instances and handles plugin tasks
 * over these connections.
 */
export class PluginBridge implements TaskDispatchHost {
    public static readonly MULTIUSER_CONNECTION_ERROR_MESSAGE = `No Penpot instance connected for user token. Please ensure that Penpot is connected and that the MCP client connection is using the correct token.`;

    private readonly logger = createLogger("PluginBridge");
    private readonly wsServer: WebSocketServer;

    private readonly connectionsBySocket: Map<WebSocket, PenpotConnection> = new Map();
    private readonly userPenpotConnectionsByToken: Map<string, UserPenpotConnections> = new Map();
    private readonly singleUserConnections = new UserPenpotConnections();
    private readonly pendingTasks: Map<string, AbstractPluginTask<any, any>> = new Map();
    private readonly taskTimeouts: Map<string, NodeJS.Timeout> = new Map();
    private readonly taskConnections: Map<string, PenpotConnection> = new Map();
    private readonly dispatcher: TaskDispatcher;

    /**
     * Creates the plugin bridge and starts its WebSocket server.
     *
     * @param mcpServer - The owning MCP server
     * @param port - The port on which to listen for plugin WebSocket connections
     * @param redisBridge - Optional Redis bridge enabling multi-instance task routing.
     *   When provided, tasks handled by this instance are routed to the instance
     *   holding the relevant plugin's WebSocket connection (which may be this same
     *   instance) via Redis, rather than dispatched directly over a local socket.
     * @param taskTimeoutSecs - Timeout, in seconds, for plugin task execution
     *   (defaults to {@link DEFAULT_TASK_TIMEOUT_SECS})
     */
    constructor(
        public readonly mcpServer: PenpotMcpServer,
        private port: number,
        private readonly taskTimeoutSecs: number,
        private readonly redisBridge?: RedisBridge
    ) {
        this.dispatcher = redisBridge
            ? new MultiInstanceTaskDispatcher(this, redisBridge)
            : new SingleInstanceTaskDispatcher(this);
        this.wsServer = new WebSocketServer({ port: port, host: mcpServer.host });
        this.setupWebSocketHandlers();
    }

    /**
     * Sets up WebSocket connection handlers for plugin communication.
     *
     * Manages client connections and provides bidirectional communication
     * channel between the MCP mcpServer and Penpot plugin instances.
     */
    private setupWebSocketHandlers(): void {
        this.wsServer.on("connection", (ws: WebSocket, request: http.IncomingMessage) => {
            // extract userToken from query parameters
            const url = new URL(request.url!, `ws://${request.headers.host}`);
            const userToken = url.searchParams.get("userToken");

            // require userToken if running in multi-user mode
            if (this.mcpServer.isMultiUserMode() && !userToken) {
                this.logger.warn("Connection attempt without userToken in multi-user mode - rejecting");
                ws.close(1008, "Missing userToken parameter");
                return;
            }

            if (userToken) {
                this.logger.info("New WebSocket connection established (token provided)");
            } else {
                this.logger.info("New WebSocket connection established");
            }

            // require metadata before registering a connection for dispatch
            const initializationTimeout = setTimeout(() => {
                ws.close(1008, "Connection initialization timed out; please update the Penpot MCP plugin.");
            }, INITIALIZATION_TIMEOUT_MS);
            let initializing = false;
            ws.on("message", (data: Buffer) => {
                this.logger.debug("Received WebSocket message: %s", data.toString());
                try {
                    const message = JSON.parse(data.toString());
                    const connection = this.connectionsBySocket.get(ws);
                    if (!connection) {
                        if (initializing) {
                            return;
                        }
                        const initialization = connectionInitSchema.safeParse(message);
                        if (!initialization.success) {
                            ws.close(1008, "Expected connection metadata; please update the Penpot MCP plugin.");
                            return;
                        }
                        initializing = true;
                        void this.initializeConnection(
                            ws,
                            this.mcpServer.isMultiUserMode() ? userToken : null,
                            initialization.data.session
                        )
                            .then(() => clearTimeout(initializationTimeout))
                            .catch((error) => {
                                this.logger.error(error, "Failed to initialize Penpot connection");
                                clearTimeout(initializationTimeout);
                                this.removeConnection(ws);
                                ws.close(1011, "Failed to initialize Penpot connection; please retry.");
                            });
                        return;
                    }
                    // any plugin message proves the page event loop is running
                    connection.lastHeartbeat = Date.now();
                    if (message?.type === "freeze") {
                        connection.frozen = true;
                        this.logger.info("Plugin tab reported it is being frozen by the browser");
                        return;
                    }
                    connection.frozen = false;
                    if (message?.type === "heartbeat") {
                        return;
                    }
                    this.handlePluginTaskResponse(message as PluginTaskResponse<any>, connection);
                } catch (error) {
                    this.logger.error(error, "Failure while processing WebSocket message");
                }
            });

            ws.on("close", () => {
                this.logger.info("WebSocket connection closed");
                clearTimeout(initializationTimeout);
                this.removeConnection(ws);
            });

            ws.on("error", (error) => {
                this.logger.error(error, "WebSocket connection error");
                clearTimeout(initializationTimeout);
                this.removeConnection(ws);
                ws.terminate();
            });
        });

        this.logger.info("WebSocket mcpServer started on port %d", this.port);
    }

    private async initializeConnection(
        ws: WebSocket,
        userToken: string | null,
        session: PenpotConnection["session"]
    ): Promise<void> {
        let connections = this.getUserConnections(userToken);
        if (!connections) {
            connections = new UserPenpotConnections();
            this.userPenpotConnectionsByToken.set(userToken!, connections);
        }
        // start the per-connection keep-alive ping interval
        const pingInterval = setInterval(() => {
            ws.ping();
        }, KEEP_ALIVE_TIME);

        // register the client connection with both indexes
        const connection: PenpotConnection = {
            socket: ws,
            userToken,
            pingInterval,
            session,
            ready: false,
            lastHeartbeat: Date.now(),
            frozen: false,
        };
        try {
            connections.add(connection);
        } catch {
            this.logger.warn("Duplicate connection for given session ID; rejecting new connection");
            clearInterval(connection.pingInterval);
            ws.close(1008, "A Penpot connection with this session ID already exists.");
            return;
        }
        this.connectionsBySocket.set(ws, connection);
        await this.dispatcher.onNewConnection(connection);
        // disconnect may have removed this connection while subscriptions were pending
        if (ws.readyState === WebSocket.OPEN && this.connectionsBySocket.get(ws) === connection) {
            connection.ready = true;
            ws.send(JSON.stringify({ type: "initialized" }));
        }
    }

    /**
     * Removes a client connection and releases all resources associated with it.
     *
     * Clears the per-connection keep-alive interval and removes the connection from the
     * socket-keyed index and the user's session collection. The token-keyed index entry
     * is removed only when the user's last local connection closes. Safe to call with a
     * socket that is not (or no longer) registered.
     *
     * @param ws - The WebSocket whose connection state should be removed
     */
    private removeConnection(ws: WebSocket): void {
        const connection = this.connectionsBySocket.get(ws);
        if (!connection) {
            return;
        }
        connection.ready = false;
        clearInterval(connection.pingInterval);
        this.connectionsBySocket.delete(ws);
        const connections = this.getUserConnections(connection.userToken)!;
        connections.remove(connection);
        if (connection.userToken !== null && connections.size === 0) {
            this.userPenpotConnectionsByToken.delete(connection.userToken);
        }
        void this.dispatcher
            .onConnectionClosed(connection)
            .catch((error) => this.logger.error(error, "Failed to remove connection subscriptions"));
    }

    /**
     * Handles responses from the plugin for completed tasks.
     *
     * Finds the pending task by ID and resolves or rejects its promise
     * based on the execution result.
     *
     * @param response - The plugin task response containing ID and result
     * @param connection - The responding local connection, or undefined for Redis responses
     */
    private handlePluginTaskResponse(response: PluginTaskResponse<any>, connection?: PenpotConnection): void {
        const task = this.pendingTasks.get(response.id);
        if (!task) {
            this.logger.info(`Received response for unknown task ID: ${response.id}`);
            return;
        }

        if (this.taskConnections.get(response.id) !== connection) {
            return;
        }

        // Clear the timeout and remove the task from pending tasks
        const timeoutHandle = this.taskTimeouts.get(response.id);
        if (timeoutHandle) {
            clearTimeout(timeoutHandle);
            this.taskTimeouts.delete(response.id);
        }
        this.pendingTasks.delete(response.id);
        this.taskConnections.delete(response.id);

        // Resolve or reject the task's promise based on the result
        if (response.success) {
            task.resolveWithResult({ data: response.data });
        } else {
            const error = new Error(response.error || "Task execution failed (details not provided)");
            task.rejectWithError(error);
        }

        this.logger.info(`Task ${response.id} completed: success=${response.success}`);
    }

    /**
     * Rejects a still-pending task with the given error, releasing its correlation state.
     *
     * Clears the task's timeout (if armed) and removes the task from the pending-task
     * index before rejecting its promise. Safe to call for a task that has already been
     * settled (e.g. by a response or a timeout), in which case nothing happens.
     *
     * @param taskId - The ID of the task to reject
     * @param error - The error with which to reject the task
     * @returns Whether the task was still pending and has been rejected
     */
    private rejectPendingTask(taskId: string, error: Error): boolean {
        const pendingTask = this.pendingTasks.get(taskId);
        if (!pendingTask) {
            return false;
        }

        const timeoutHandle = this.taskTimeouts.get(taskId);
        if (timeoutHandle) {
            clearTimeout(timeoutHandle);
            this.taskTimeouts.delete(taskId);
        }
        this.pendingTasks.delete(taskId);
        this.taskConnections.delete(taskId);

        pendingTask.rejectWithError(error);
        this.logger.info(`Task ${taskId} rejected: ${error.message}`);
        return true;
    }

    getUserConnections(userToken: string | null): UserPenpotConnections | undefined {
        return userToken === null ? this.singleUserConnections : this.userPenpotConnectionsByToken.get(userToken);
    }

    /**
     * Executes a plugin task by sending it to the connected Penpot plugin instance,
     * either directly via WebSocket or indirectly via Redis (depending on the configuration),
     * and awaiting the result.
     *
     * @param task - The plugin task to execute
     * @param sessionId - The target session; when omitted, discovery must yield exactly one session
     * @throws Error if no plugin instances are connected or available
     */
    public async executePluginTask<TResult extends PluginTaskResult<any>>(
        task: PluginTask<any, TResult>,
        sessionId?: string
    ): Promise<TResult> {
        let userToken: string | null = null;
        if (this.mcpServer.isMultiUserMode()) {
            const sessionContext = this.mcpServer.getSessionContext();
            if (!sessionContext?.userToken) {
                throw new Error("No userToken found in session context. Multi-user mode requires authentication.");
            }
            userToken = sessionContext.userToken;
        }
        // attach the result handler before dispatch can reject the task
        const result = task.getResultPromise();
        void this.dispatcher.dispatch(task, userToken, sessionId).catch((error) => {
            task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
        });
        return await result;
    }

    /**
     * Registers a task for response correlation, sends its request over Redis,
     * and arms a timeout that rejects the task if no response is received.
     *
     * The response (whether arriving over the local WebSocket or over Redis) is later
     * matched by ID in {@link handlePluginTaskResponse}, which settles the task via its
     * `resolveWithResult`/`rejectWithError` methods. The same correlation and timeout
     * handling therefore applies regardless of the transport.
     *
     * When routing via Redis, the task is rejected immediately (rather than timing out)
     * if the published request reached no instance, i.e. if no instance holds a plugin
     * connection for the user's session, or if publishing fails outright.
     *
     * @param task - The task to dispatch
     * @param userToken - The user token identifying the target session's owner
     * @param sessionId - The target Penpot session
     */
    public sendRemoteTask(task: AbstractPluginTask, userToken: string, sessionId: string): void {
        const redisBridge = this.redisBridge!;
        this.logger.debug("Dispatching task %s via Redis", task.id);

        // register the task for result correlation, then publish the request via Redis
        this.pendingTasks.set(task.id, task);
        void redisBridge
            .sendTaskRequest(userToken, sessionId, task.toRequest(), (response) =>
                this.handlePluginTaskResponse(response)
            )
            .then((receiverCount) => {
                // fail fast when no instance received the request (no connection with matching user token and session ID in any instance)
                if (receiverCount === 0) {
                    this.rejectPendingTask(
                        task.id,
                        new Error(`Penpot session ${JSON.stringify(sessionId)} is not connected for this user.`)
                    );
                }
            })
            .catch((error) => {
                this.rejectPendingTask(task.id, error instanceof Error ? error : new Error(String(error)));
            });

        // on timeout, release the response-channel subscription, since no response
        // will arrive to trigger its self-unsubscribe.
        this.startTaskTimeout(task, () => void redisBridge.unsubscribeFromResponse(task.id));
    }

    /** Sends a task directly to a ready local connection, with execution tracking. */
    public sendLocalTask(task: AbstractPluginTask, connection: PenpotConnection): void {
        const target = connection;
        if (!target.ready || target.socket.readyState !== 1) {
            // WebSocket is not open
            throw new Error(`Plugin instance is disconnected. Task could not be sent.`);
        }

        // the socket can be open while browser-throttled plugin JS cannot run tasks
        assertPluginResponsive(target, Date.now());

        // register the task for result correlation, then send over the socket
        this.pendingTasks.set(task.id, task);
        this.taskConnections.set(task.id, target);
        target.socket.send(JSON.stringify(task.toRequest()));
        this.startTaskTimeout(task);
    }

    /** Arms the shared execution timeout for a locally or remotely dispatched task. */
    private startTaskTimeout(task: AbstractPluginTask<any, any>, onTimeout?: () => void): void {
        // Set up a timeout to reject the task if no response is received
        const timeoutHandle = setTimeout(() => {
            if (
                this.rejectPendingTask(
                    task.id,
                    new Error(`Task ${task.id} timed out after ${this.taskTimeoutSecs} seconds`)
                )
            ) {
                onTimeout?.();
            }
        }, this.taskTimeoutSecs * 1000);

        this.taskTimeouts.set(task.id, timeoutHandle);
        this.logger.info(`Sent task ${task.id}`);
    }

    /**
     * Closes the WebSocket server and all connected client sockets.
     * Also releases task tracking and dispatch subscriptions.
     */
    public async close(): Promise<void> {
        for (const socket of this.wsServer.clients) {
            this.removeConnection(socket);
            socket.terminate();
        }
        for (const taskId of this.pendingTasks.keys()) {
            this.rejectPendingTask(taskId, new Error("MCP server is shutting down."));
        }
        await this.dispatcher.close();
        return new Promise((resolve) => {
            this.wsServer.close(() => {
                this.logger.info("WebSocket server closed");
                resolve();
            });
        });
    }
}
