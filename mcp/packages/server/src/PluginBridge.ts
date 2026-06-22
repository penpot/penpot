import { WebSocket, WebSocketServer } from "ws";
import * as http from "http";
import { AbstractPluginTask, PluginTask } from "./PluginTask";
import { RemotePluginTask } from "./RemotePluginTask";
import { PluginTaskRequest, PluginTaskResponse, PluginTaskResult } from "@penpot/mcp-common";
import { createLogger } from "./logger";
import type { PenpotMcpServer } from "./PenpotMcpServer";
import type { RedisBridge } from "./RedisBridge";

const KEEP_ALIVE_TIME = 30000; // 30 seconds

interface ClientConnection {
    socket: WebSocket;
    userToken: string | null;
    pingInterval: NodeJS.Timeout;
}

/**
 * Manages WebSocket connections to Penpot plugin instances and handles plugin tasks
 * over these connections.
 */
export class PluginBridge {
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
     */
    constructor(
        public readonly mcpServer: PenpotMcpServer,
        private port: number,
        private readonly redisBridge?: RedisBridge,
        private taskTimeoutSecs: number = 30
    ) {
        this.wsServer = new WebSocketServer({ port: port });
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
            const connection: ClientConnection = { socket: ws, userToken, pingInterval };
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
                        .subscribeToTasks(userToken, (request) =>
                            this.dispatchForwardedTask(tokenForSubscription, request)
                        )
                        .catch((error) => this.logger.error(error, "Failed to subscribe to Redis task channel"));
                }
            }

            ws.on("message", (data: Buffer) => {
                this.logger.debug("Received WebSocket message: %s", data.toString());
                try {
                    const response: PluginTaskResponse<any> = JSON.parse(data.toString());
                    this.handlePluginTaskResponse(response);
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
     * Clears the per-connection keep-alive interval and removes the connection
     * from both the socket-keyed and token-keyed indexes. Safe to call with a
     * socket that is not (or no longer) registered.
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
            this.clientsByToken.delete(connection.userToken);

            if (this.redisBridge) {
                this.redisBridge
                    .unsubscribeFromTasks(connection.userToken)
                    .catch((error) => this.logger.error(error, "Failed to unsubscribe from Redis task channel"));
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
                throw new Error(
                    `No plugin instance connected for user token. Please ensure the plugin is running and connected with the correct token.`
                );
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
            void redisBridge.sendTaskRequest(userToken, task.toRequest(), (response) =>
                this.handlePluginTaskResponse(response)
            );

            // on timeout, release the response-channel subscription, since no response
            // will arrive to trigger its self-unsubscribe.
            onTimeout = () => void redisBridge.unsubscribeFromResponse(task.id);
        } else {
            const target = connection ?? this.getClientConnection();
            if (target.socket.readyState !== 1) {
                // WebSocket is not open
                throw new Error(`Plugin instance is disconnected. Task could not be sent.`);
            }

            // register the task for result correlation, then send over the socket
            this.pendingTasks.set(task.id, task);
            target.socket.send(JSON.stringify(task.toRequest()));
        }

        // Set up a timeout to reject the task if no response is received
        const timeoutHandle = setTimeout(() => {
            const pendingTask = this.pendingTasks.get(task.id);
            if (pendingTask) {
                this.pendingTasks.delete(task.id);
                this.taskTimeouts.delete(task.id);
                onTimeout?.();
                pendingTask.rejectWithError(
                    new Error(`Task ${task.id} timed out after ${this.taskTimeoutSecs} seconds`)
                );
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
     * @param userToken - The user token on whose request channel the request arrived;
     *   identifies the locally-connected plugin to dispatch to
     * @param request - The serialized task request, passed through from Redis
     */
    private dispatchForwardedTask(userToken: string, request: PluginTaskRequest): void {
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

        try {
            this.sendPluginTask(task, false, connection);
        } catch (error) {
            task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
        }
    }
}
