import { WebSocket, WebSocketServer } from "ws";
import * as http from "http";
import { AbstractPluginTask, PluginTask } from "./PluginTask";
import { RemotePluginTask } from "./RemotePluginTask";
import { PluginTaskRequest, PluginTaskResponse, PluginTaskResult } from "@penpot/mcp-common";
import { createLogger } from "./logger";
import type { PenpotMcpServer } from "./PenpotMcpServer";
import type { RedisBridge } from "./RedisBridge";

const KEEP_ALIVE_TIME = 30000; // 30 seconds

/**
 * Maximum plugin heartbeat age before a connection is stale.
 *
 * This uses plugin heartbeats rather than WebSocket pongs because the browser can answer
 * protocol pings while the tab's JavaScript event loop is frozen.
 */
export const HEARTBEAT_STALE_THRESHOLD_MS = 30000;

/**
 * Observable liveness state of a plugin connection.
 */
export interface PluginLivenessState {
    /** timestamp of the last plugin message, in ms since epoch. */
    lastHeartbeat: number;
    /** whether the plugin reported a browser freeze. */
    frozen: boolean;
}

interface ClientConnection extends PluginLivenessState {
    socket: WebSocket;
    userToken: string | null;
    pingInterval: NodeJS.Timeout;
}

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
 * Delay, in milliseconds, before an instance whose local plugin cannot run tasks
 * competes for a forwarded task. A responsive instance claims the task first; the
 * unresponsive one only claims it, and reports its error, if nobody else did.
 */
export const UNRESPONSIVE_CLAIM_DELAY_MS = 2000;

/**
 * Returns why a plugin connection cannot run tasks right now, or undefined if it can.
 *
 * @param state - the connection's liveness state
 * @param socketOpen - whether the connection's WebSocket is open
 * @param now - current time, in ms since epoch
 */
export function pluginUnavailableReason(
    state: PluginLivenessState,
    socketOpen: boolean,
    now: number
): Error | undefined {
    if (!socketOpen) {
        return new Error(`Plugin instance is disconnected. Task could not be sent.`);
    }
    try {
        assertPluginResponsive(state, now);
        return undefined;
    } catch (error) {
        return error instanceof Error ? error : new Error(String(error));
    }
}

/**
 * Manages WebSocket connections to Penpot plugin instances and handles plugin tasks
 * over these connections.
 */
export class PluginBridge {
    public static readonly MULTIUSER_CONNECTION_ERROR_MESSAGE = `No Penpot instance connected for user token. Please ensure that Penpot is connected and that the MCP client connection is using the correct token.`;

    private readonly logger = createLogger("PluginBridge");
    private readonly wsServer: WebSocketServer;

    private readonly connectedClients: Map<WebSocket, ClientConnection> = new Map();
    private readonly clientsByToken: Map<string, ClientConnection> = new Map();
    private readonly pendingTasks: Map<string, AbstractPluginTask<any, any>> = new Map();
    private readonly taskTimeouts: Map<string, NodeJS.Timeout> = new Map();

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

            // start the per-connection keep-alive ping interval
            const pingInterval = setInterval(() => {
                ws.ping();
            }, KEEP_ALIVE_TIME);

            // register the client connection with both indexes
            const connection: ClientConnection = {
                socket: ws,
                userToken,
                pingInterval,
                lastHeartbeat: Date.now(),
                frozen: false,
            };
            this.connectedClients.set(ws, connection);
            if (userToken) {
                // ensure only one connection per userToken
                if (this.clientsByToken.has(userToken)) {
                    this.logger.warn("Duplicate connection for given user token; rejecting new connection");
                    this.removeConnection(ws);
                    ws.close(1008, "Duplicate connection for given user token; close previous connection first.");
                    return;
                }

                this.clientsByToken.set(userToken, connection);

                // In multi-instance mode, subscribe to this token's Redis request channel so
                // that task requests issued by other instances are dispatched to this plugin.
                if (this.redisBridge) {
                    const tokenForSubscription = userToken;
                    this.redisBridge
                        .subscribeToTasks(userToken, (request) => {
                            void this.dispatchForwardedTask(tokenForSubscription, request);
                        })
                        .catch((error) => this.logger.error(error, "Failed to subscribe to Redis task channel"));
                }
            }

            ws.on("message", (data: Buffer) => {
                this.logger.debug("Received WebSocket message: %s", data.toString());
                try {
                    // any plugin message proves the page event loop is running
                    connection.lastHeartbeat = Date.now();

                    const message = JSON.parse(data.toString());
                    if (message?.type === "freeze") {
                        connection.frozen = true;
                        this.logger.info("Plugin tab reported it is being frozen by the browser");
                        return;
                    }
                    connection.frozen = false;
                    if (message?.type === "heartbeat") {
                        return;
                    }
                    this.handlePluginTaskResponse(message as PluginTaskResponse<any>);
                } catch (error) {
                    this.logger.error(error, "Failure while processing WebSocket message");
                }
            });

            ws.on("close", () => {
                this.logger.info("WebSocket connection closed");
                this.removeConnection(ws);
            });

            ws.on("error", (error) => {
                this.logger.error(error, "WebSocket connection error");
                this.removeConnection(ws);
            });
        });

        this.logger.info("WebSocket mcpServer started on port %d", this.port);
    }

    /**
     * Removes a client connection and releases all resources associated with it.
     *
     * Clears the per-connection keep-alive interval and removes the connection from the
     * socket-keyed index. The token-keyed index entry (and, in multi-instance mode, the
     * token's Redis task subscription) is removed only if it is owned by the given
     * connection. Safe to call with a socket that is not (or no longer) registered.
     *
     * @param ws - The WebSocket whose connection state should be removed
     */
    private removeConnection(ws: WebSocket): void {
        const connection = this.connectedClients.get(ws);
        if (!connection) {
            return;
        }
        clearInterval(connection.pingInterval);
        this.connectedClients.delete(ws);
        if (connection.userToken) {
            // Perform the token-keyed cleanup only if this connection owns the token registration.
            // A connection rejected as a duplicate carries the same token but must not remove token associations.
            if (this.clientsByToken.get(connection.userToken) !== connection) {
                this.logger.debug("Removed connection does not own its token registration; skipping token cleanup");
            } else {
                this.clientsByToken.delete(connection.userToken);

                if (this.redisBridge) {
                    this.redisBridge
                        .unsubscribeFromTasks(connection.userToken)
                        .catch((error) => this.logger.error(error, "Failed to unsubscribe from Redis task channel"));
                }
            }
        }
    }

    /**
     * Handles responses from the plugin for completed tasks.
     *
     * Finds the pending task by ID and resolves or rejects its promise
     * based on the execution result.
     *
     * @param response - The plugin task response containing ID and result
     */
    private handlePluginTaskResponse(response: PluginTaskResponse<any>): void {
        const task = this.pendingTasks.get(response.id);
        if (!task) {
            this.logger.info(`Received response for unknown task ID: ${response.id}`);
            return;
        }

        // Clear the timeout and remove the task from pending tasks
        const timeoutHandle = this.taskTimeouts.get(response.id);
        if (timeoutHandle) {
            clearTimeout(timeoutHandle);
            this.taskTimeouts.delete(response.id);
        }
        this.pendingTasks.delete(response.id);

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

        pendingTask.rejectWithError(error);
        this.logger.info(`Task ${taskId} rejected: ${error.message}`);
        return true;
    }

    /**
     * Determines the client connection to use for executing a task.
     *
     * In single-user mode, returns the single connected client.
     * In multi-user mode, returns the client matching the session's userToken.
     *
     * @returns The client connection to use
     * @throws Error if no suitable connection is found or if configuration is invalid
     */
    private getClientConnection(): ClientConnection {
        if (this.mcpServer.isMultiUserMode()) {
            const sessionContext = this.mcpServer.getSessionContext();
            if (!sessionContext?.userToken) {
                throw new Error("No userToken found in session context. Multi-user mode requires authentication.");
            }

            const connection = this.clientsByToken.get(sessionContext.userToken);
            if (!connection) {
                throw new Error(PluginBridge.MULTIUSER_CONNECTION_ERROR_MESSAGE);
            }

            return connection;
        } else {
            // single-user mode: return the single connected client
            if (this.connectedClients.size === 0) {
                throw new Error(
                    `No Penpot plugin instances are currently connected. Please ensure the plugin is running and connected.`
                );
            }
            if (this.connectedClients.size > 1) {
                throw new Error(
                    `Multiple (${this.connectedClients.size}) Penpot MCP Plugin instances are connected. ` +
                        `Ask the user to ensure that only one instance is connected at a time.`
                );
            }

            // return the first (and only) connection
            const connection = this.connectedClients.values().next().value;
            return <ClientConnection>connection;
        }
    }

    /**
     * Executes a plugin task by sending it to the connected Penpot plugin instance,
     * either directly via WebSocket or indirectly via Redis (depending on the configuration),
     * and awaiting the result.
     *
     * @param task - The plugin task to execute
     * @throws Error if no plugin instances are connected or available
     */
    public async executePluginTask<TResult extends PluginTaskResult<any>>(
        task: PluginTask<any, TResult>
    ): Promise<TResult> {
        this.sendPluginTask(task, this.redisBridge !== undefined);
        return await task.getResultPromise();
    }

    /**
     * Registers a task for response correlation, sends its request over the appropriate
     * transport, and arms a timeout that rejects the task if no response is received.
     *
     * The response (whether arriving over the local WebSocket or over Redis) is later
     * matched by ID in {@link handlePluginTaskResponse}, which settles the task via its
     * `resolveWithResult`/`rejectWithError` methods. The same correlation and timeout
     * handling therefore applies regardless of the transport.
     *
     * When routing via Redis, the task is rejected immediately (rather than timing out)
     * if the published request reached no instance, i.e. if no instance holds a plugin
     * connection for the session's user token, or if publishing fails outright.
     *
     * @param task - The task to dispatch
     * @param useRedis - Whether to route the request via Redis (multi-instance) rather
     *   than directly over the local WebSocket connection
     * @param connection - The connection to use for a local (non-remote) dispatch; when
     *   omitted, the session's connection is resolved via {@link getClientConnection}.
     *   Ignored when `useRedis` is true.
     * @throws Error if a local dispatch is required but no suitable connection is available
     */
    private sendPluginTask(task: AbstractPluginTask<any, any>, useRedis: boolean, connection?: ClientConnection): void {
        let onTimeout: (() => void) | undefined;

        if (useRedis) {
            const sessionContext = this.mcpServer.getSessionContext();
            if (!sessionContext?.userToken) {
                throw new Error("No userToken found in session context. Multi-user mode requires authentication.");
            }
            const userToken = sessionContext.userToken;
            const redisBridge = this.redisBridge!;
            this.logger.debug("Dispatching task %s via Redis", task.id);

            // register the task for result correlation, then publish the request via Redis
            this.pendingTasks.set(task.id, task);
            void redisBridge
                .sendTaskRequest(userToken, task.toRequest(), (response) => this.handlePluginTaskResponse(response))
                .then((receiverCount) => {
                    // fail fast when no instance received the request (no connection with matching user token in any instance)
                    if (receiverCount === 0) {
                        this.rejectPendingTask(task.id, new Error(PluginBridge.MULTIUSER_CONNECTION_ERROR_MESSAGE));
                    } else if (receiverCount > 1) {
                        // several instances hold a connection for this token; only the one that claims the task runs it
                        this.logger.warn(
                            "Task %s reached %d instances; the same user has several plugin connections",
                            task.id,
                            receiverCount
                        );
                    }
                })
                .catch((error) => {
                    this.rejectPendingTask(task.id, error instanceof Error ? error : new Error(String(error)));
                });

            // on timeout, release the response-channel subscription, since no response
            // will arrive to trigger its self-unsubscribe.
            onTimeout = () => void redisBridge.unsubscribeFromResponse(task.id);
        } else {
            const target = connection ?? this.getClientConnection();
            if (target.socket.readyState !== 1) {
                // WebSocket is not open
                throw new Error(`Plugin instance is disconnected. Task could not be sent.`);
            }

            // the socket can be open while browser-throttled plugin JS cannot run tasks
            assertPluginResponsive(target, Date.now());

            // register the task for result correlation, then send over the socket
            this.pendingTasks.set(task.id, task);
            target.socket.send(JSON.stringify(task.toRequest()));
        }

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
     * Dispatches a task request received over Redis to the locally-connected plugin.
     *
     * Invoked on the instance subscribed to a user token's request channel when another
     * instance (or this one) issues a task request. A {@link RemotePluginTask} is created
     * so that, once the plugin responds, the outcome is published back to the issuing
     * instance's Redis response channel via the standard response-handling path.
     *
     * On failure to dispatch (e.g. the plugin is not connected here), an error response
     * is published immediately so the requester need not wait for its timeout.
     *
     * When several instances hold a plugin connection for the same user token, each of
     * them receives the request. The request is claimed first, and only the instance
     * that obtains the claim dispatches it; the others ignore it, so the task is
     * executed exactly once. An instance whose plugin cannot run tasks waits
     * {@link UNRESPONSIVE_CLAIM_DELAY_MS} before claiming, so a responsive instance wins.
     *
     * @param userToken - The user token on whose request channel the request arrived;
     *   identifies the locally-connected plugin to dispatch to
     * @param request - The serialized task request, passed through from Redis
     */
    private async dispatchForwardedTask(userToken: string, request: PluginTaskRequest): Promise<void> {
        if (!this.redisBridge) {
            return;
        }

        // The response is published on the channel keyed by the original request ID.
        const task = new RemotePluginTask(request.task, request.params, this.redisBridge, request.id);
        this.logger.debug("Dispatching remote task %s as %s to Penpot via WebSocket", request.id, task.id);

        const connection = this.clientsByToken.get(userToken);
        if (!connection) {
            task.rejectWithError(new Error("Plugin not connected on the receiving instance"));
            return;
        }

        // A connection that cannot run tasks (e.g. a backgrounded tab on another device)
        // lets responsive instances claim first, and only reports its error if none did.
        const unavailable = pluginUnavailableReason(connection, connection.socket.readyState === 1, Date.now());
        if (unavailable) {
            await new Promise((resolve) => setTimeout(resolve, UNRESPONSIVE_CLAIM_DELAY_MS));
        }

        try {
            const claimed = await this.redisBridge.claimTask(request.id, this.taskTimeoutSecs * 1000);
            if (!claimed) {
                this.logger.info("Task %s was claimed by another instance; not dispatching it here", request.id);
                return;
            }
        } catch (error) {
            task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
            return;
        }

        if (unavailable) {
            task.rejectWithError(unavailable);
            return;
        }

        try {
            this.sendPluginTask(task, false, connection);
        } catch (error) {
            task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
        }
    }

    /**
     * Closes the WebSocket server and all connected client sockets.
     */
    public async close(): Promise<void> {
        return new Promise((resolve) => {
            this.wsServer.close(() => {
                this.logger.info("WebSocket server closed");
                resolve();
            });
        });
    }
}
