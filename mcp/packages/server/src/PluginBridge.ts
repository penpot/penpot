import { WebSocket, WebSocketServer } from "ws";
import * as http from "http";
import { z } from "zod";
import { AbstractPluginTask, PluginTask, PluginTaskError } from "./PluginTask";
import { RollbackNotices } from "./RollbackNotices";
import {
    ContentRollbackEvent,
    PluginTaskResponse,
    PluginTaskResult,
    UnclaimedRollbacksMessage,
} from "@penpot/mcp-common";
import { createLogger } from "./logger";
import { penpotSessionSchema } from "./PenpotSessionSchema";
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
 * The interval, in ms, between the heartbeat requests sent to each plugin.
 *
 * A browser runs the timers of a hidden page as rarely as once a minute, which would leave the
 * plugin's own heartbeat older than the stale threshold, but it handles socket messages at once.
 */
export const HEARTBEAT_REQUEST_INTERVAL_MS = 10_000;

/**
 * Maximum plugin heartbeat age before a connection is stale.
 *
 * This uses plugin heartbeats rather than WebSocket pongs because the browser can answer
 * protocol pings while the tab's JavaScript event loop is frozen.
 */
export const HEARTBEAT_STALE_THRESHOLD_MS = 30000;

/**
 * Limits of the queue in which tasks wait for the task a Penpot session is running.
 */
export interface TaskQueueLimits {
    /** maximum number of tasks waiting per session, not counting the running task. */
    maxQueuedTasks: number;
    /** time, in seconds, a task may wait before it fails without being sent. */
    queueTimeoutSecs: number;
}

export const DEFAULT_TASK_QUEUE_LIMITS: TaskQueueLimits = { maxQueuedTasks: 30, queueTimeoutSecs: 120 };

/**
 * Reads the task queue limits from `PENPOT_MCP_MAX_QUEUED_TASKS` and `PENPOT_MCP_QUEUE_TIMEOUT_S`.
 *
 * @param env - the environment variables
 * @throws Error if a variable is set to a value that is not a valid limit
 */
export function readTaskQueueLimits(env: Record<string, string | undefined>): TaskQueueLimits {
    const read = (name: string, fallback: number, min: number): number => {
        const raw = env[name]?.trim();
        if (!raw) {
            return fallback;
        }
        const value = Number(raw);
        if (!/^\d+$/.test(raw) || !Number.isSafeInteger(value) || value < min) {
            throw new Error(`${name} must be an integer of at least ${min}; got ${JSON.stringify(raw)}`);
        }
        return value;
    };
    return {
        maxQueuedTasks: read("PENPOT_MCP_MAX_QUEUED_TASKS", DEFAULT_TASK_QUEUE_LIMITS.maxQueuedTasks, 0),
        queueTimeoutSecs: read("PENPOT_MCP_QUEUE_TIMEOUT_S", DEFAULT_TASK_QUEUE_LIMITS.queueTimeoutSecs, 1),
    };
}

/**
 * A task waiting for the running task of its session to finish.
 */
interface QueuedTask {
    readonly task: AbstractPluginTask;
    /** the timer failing the task when it waits too long. */
    readonly timeout: NodeJS.Timeout;
}

const connectionInitSchema = z.object({
    type: z.literal("initialize"),
    session: penpotSessionSchema,
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
    public static readonly CANCELLED_BEFORE_SENDING_MESSAGE =
        "The MCP request was cancelled before the task was sent to Penpot; the task did not run.";

    private readonly logger = createLogger("PluginBridge");
    private readonly wsServer: WebSocketServer;

    private readonly connectionsBySocket: Map<WebSocket, PenpotConnection> = new Map();
    private readonly userPenpotConnectionsByToken: Map<string, UserPenpotConnections> = new Map();
    private readonly singleUserConnections = new UserPenpotConnections();
    private readonly pendingTasks: Map<string, AbstractPluginTask<any, any>> = new Map();
    private readonly taskTimeouts: Map<string, NodeJS.Timeout> = new Map();
    private readonly taskConnections: Map<string, PenpotConnection> = new Map();
    /**
     * The ID of the task each local connection's plugin is running.
     *
     * A task stays here after it timed out, since the plugin may still be running it, until the plugin
     * answers or the connection closes.
     */
    private readonly runningTasks: Map<PenpotConnection, string> = new Map();
    private readonly queuedTasks: Map<PenpotConnection, QueuedTask[]> = new Map();
    private readonly dispatcher: TaskDispatcher;
    private closing = false;

    /**
     * Collector of the rollbacks that task responses report, per tool execution.
     */
    public readonly rollbackNotices = new RollbackNotices();

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
     * @param queueLimits - Limits of the queue in which tasks wait for the running task of their session
     */
    constructor(
        public readonly mcpServer: PenpotMcpServer,
        private port: number,
        private readonly taskTimeoutSecs: number,
        private readonly redisBridge?: RedisBridge,
        private readonly queueLimits: TaskQueueLimits = DEFAULT_TASK_QUEUE_LIMITS
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
            // reject pages on foreign sites before reading any message
            const guardResult = this.mcpServer.requestGuard.check(request.headers);
            if (!guardResult.isAllowed) {
                this.logger.warn("Rejected WebSocket connection: %s", guardResult.reason);
                ws.close(1008, "Origin or host not allowed; see PENPOT_MCP_ALLOWED_HOSTS");
                return;
            }

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
                    if (message?.type === "context-update") {
                        const context = penpotSessionSchema.omit({ sessionId: true }).parse(message.context);
                        Object.assign(connection.session, context);
                        return;
                    }
                    if (message?.type === "heartbeat") {
                        return;
                    }
                    this.handlePluginTaskResponse(message as PluginTaskResponse<any>, connection);
                } catch (error) {
                    this.logger.error(error, "Failure while processing WebSocket message");
                }
            });

            ws.on("close", (code: number, reason: Buffer) => {
                this.logger.info("WebSocket connection closed (code %d%s)", code, reason.length ? `: ${reason}` : "");
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
        // the plugin answers with a heartbeat, which a frozen page cannot send
        const heartbeatRequestInterval = setInterval(() => {
            ws.send(JSON.stringify({ type: "heartbeat-request" }));
        }, HEARTBEAT_REQUEST_INTERVAL_MS);

        // register the client connection with both indexes
        const connection: PenpotConnection = {
            socket: ws,
            userToken,
            pingInterval,
            heartbeatRequestInterval,
            session,
            ready: false,
            lastHeartbeat: Date.now(),
            frozen: false,
        };
        // the same tab reconnecting reuses its session ID while its previous socket may still be half-open
        const replaced = connections.add(connection);
        this.connectionsBySocket.set(ws, connection);
        if (replaced) {
            this.displaceConnection(replaced);
        }
        await this.dispatcher.onNewConnection(connection);
        // disconnect may have removed this connection while subscriptions were pending
        if (ws.readyState === WebSocket.OPEN && this.connectionsBySocket.get(ws) === connection) {
            connection.ready = true;
            ws.send(JSON.stringify({ type: "initialized" }));
        }
    }

    /**
     * Closes a connection superseded by a newer connection for the same session.
     *
     * Uses close code 1008 so that a still-running plugin behind the old socket stops
     * reconnecting instead of competing with the newer connection for the session.
     *
     * @param connection - The superseded connection
     */
    public displaceConnection(connection: PenpotConnection): void {
        this.logger.info("Closing connection replaced by a newer connection for the same session");
        this.removeConnection(connection.socket);
        connection.socket.close(1008, "Replaced by a newer connection for this Penpot session.");
    }

    /**
     * Removes a client connection and releases all resources associated with it.
     *
     * Clears the per-connection keep-alive and heartbeat-request intervals and removes the
     * connection from the socket-keyed index and the user's session collection. The
     * token-keyed index entry is removed only when the user's last local connection closes.
     * Tasks sent over the connection and still awaiting a response fail at once. Safe to call
     * with a socket that is not (or no longer) registered.
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
        clearInterval(connection.heartbeatRequestInterval);
        this.connectionsBySocket.delete(ws);
        const connections = this.getUserConnections(connection.userToken)!;
        connections.remove(connection);
        this.rejectQueuedTasks(
            connection,
            new Error("The plugin connection closed before the task was sent; the task did not run.")
        );
        this.runningTasks.delete(connection);
        // a task sent over the closed socket can no longer be answered, since responses only count
        // from the connection the task went to
        for (const [taskId, taskConnection] of [...this.taskConnections]) {
            if (taskConnection === connection) {
                this.rejectPendingTask(taskId, new Error("The plugin connection closed before the task finished."));
            }
        }
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
     * based on the execution result. A response that comes after its task
     * timed out has no task to report its rollbacks to, so they go back to
     * the plugin, which sends them with its next response. Either way, a response
     * to the task the connection's plugin is running lets the next queued task start.
     *
     * @param response - The plugin task response containing ID and result
     * @param connection - The responding local connection, or undefined for Redis responses
     */
    private handlePluginTaskResponse(response: PluginTaskResponse<any>, connection?: PenpotConnection): void {
        const task = this.pendingTasks.get(response.id);
        if (!task) {
            this.logger.info(`Received response for unknown task ID: ${response.id}`);
            this.returnRollbacks(connection, response.rollbacks);
            this.finishRunningTask(connection, response.id);
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
            task.resolveWithResult({ data: response.data, rollbacks: response.rollbacks });
        } else {
            const error = new PluginTaskError(
                response.error || "Task execution failed (details not provided)",
                response.rollbacks
            );
            task.rejectWithError(error);
        }

        this.logger.info(`Task ${response.id} completed: success=${response.success}`);
        this.finishRunningTask(connection, response.id);
    }

    /**
     * Starts the next queued task of a connection whose plugin answered the task it was running.
     *
     * @param connection - The responding local connection, or undefined for Redis responses
     * @param taskId - The ID of the answered task
     */
    private finishRunningTask(connection: PenpotConnection | undefined, taskId: string): void {
        if (!connection || this.runningTasks.get(connection) !== taskId) {
            return;
        }
        this.runningTasks.delete(connection);
        this.startNextQueuedTask(connection);
    }

    /**
     * Sends the next queued task of an idle connection, failing those that can no longer be sent.
     *
     * @param connection - The local connection whose plugin is no longer running a task
     */
    private startNextQueuedTask(connection: PenpotConnection): void {
        const queue = this.queuedTasks.get(connection);
        while (!this.closing && queue && queue.length > 0 && !this.runningTasks.has(connection)) {
            const { task, timeout } = queue.shift()!;
            clearTimeout(timeout);
            if (task.isSettled) {
                continue;
            }
            try {
                this.assertCanSend(connection);
                this.startLocalTask(task, connection);
            } catch (error) {
                task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
            }
        }
        if (queue?.length === 0) {
            this.queuedTasks.delete(connection);
        }
    }

    /**
     * Fails all tasks waiting for the running task of a connection.
     *
     * @param connection - The local connection whose queue to clear
     * @param error - The error with which to reject the waiting tasks
     */
    private rejectQueuedTasks(connection: PenpotConnection, error: Error): void {
        const queue = this.queuedTasks.get(connection);
        if (!queue) {
            return;
        }
        this.queuedTasks.delete(connection);
        for (const { task, timeout } of queue) {
            clearTimeout(timeout);
            task.rejectWithError(error);
        }
        this.logger.info("Rejected %d queued task(s): %s", queue.length, error.message);
    }

    /**
     * Removes a task from the queue it is waiting in.
     *
     * @param task - The task to remove
     * @returns Whether the task was waiting in a queue
     */
    private removeQueuedTask(task: AbstractPluginTask): boolean {
        for (const [connection, queue] of this.queuedTasks) {
            const index = queue.findIndex((queued) => queued.task === task);
            if (index !== -1) {
                clearTimeout(queue[index].timeout);
                queue.splice(index, 1);
                if (queue.length === 0) {
                    this.queuedTasks.delete(connection);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Sends rollbacks that no tool execution received back to the plugin.
     *
     * @param connection - The plugin connection that reported them, if known
     * @param rollbacks - The rollbacks of a response whose task is gone
     */
    private returnRollbacks(
        connection: PenpotConnection | undefined,
        rollbacks: ContentRollbackEvent[] | undefined
    ): void {
        if (!connection || !rollbacks || rollbacks.length === 0) {
            return;
        }
        if (connection.socket.readyState !== 1) {
            this.logger.warn("Plugin disconnected; %d rollback(s) of a late response are lost", rollbacks.length);
            return;
        }
        const message: UnclaimedRollbacksMessage = { type: "unclaimed-rollbacks", rollbacks };
        connection.socket.send(JSON.stringify(message));
        this.logger.info("Returned %d rollback(s) of a late response to the plugin", rollbacks.length);
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
     * Rollbacks the response reports go to {@link rollbackNotices}.
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
        const signal = this.mcpServer.getSessionContext()?.signal;
        if (signal?.aborted) {
            throw new Error(PluginBridge.CANCELLED_BEFORE_SENDING_MESSAGE);
        }
        // attach the result handler before dispatch can reject the task
        const resultPromise = task.getResultPromise();
        // code that already reached the plugin cannot be stopped, so cancelling only affects a task not yet sent
        const cancel = () => {
            if (task.isSettled) {
                return;
            }
            if (this.removeQueuedTask(task) || !this.pendingTasks.has(task.id)) {
                task.rejectWithError(new Error(PluginBridge.CANCELLED_BEFORE_SENDING_MESSAGE));
            }
        };
        signal?.addEventListener("abort", cancel, { once: true });
        void this.dispatcher.dispatch(task, userToken, sessionId).catch((error) => {
            task.rejectWithError(error instanceof Error ? error : new Error(String(error)));
        });
        try {
            const result = await resultPromise;
            this.rollbackNotices.record(result.rollbacks);
            return result;
        } catch (error) {
            if (error instanceof PluginTaskError) {
                this.rollbackNotices.record(error.rollbacks);
            }
            throw error;
        } finally {
            signal?.removeEventListener("abort", cancel);
        }
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
        if (task.isSettled) {
            return;
        }
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

        // the instance holding the plugin connection may queue the task before running it, so
        // the timeout covers the queue wait as well; on timeout, release the response-channel
        // subscription, since no response will arrive to trigger its self-unsubscribe.
        this.startTaskTimeout(
            task,
            this.queueLimits.queueTimeoutSecs + this.taskTimeoutSecs,
            () => void redisBridge.unsubscribeFromResponse(task.id)
        );
    }

    /**
     * Sends a task to a ready local connection, or queues it while the connection's plugin runs another task.
     *
     * Each session runs one task at a time, so that the code and save of one task cannot interleave with
     * those of another; tasks for different sessions run in parallel. A queued task fails without being
     * sent when the queue is full, when it waits longer than the queue timeout, or when the connection closes.
     *
     * @param task - The task to send
     * @param connection - The local connection of the target session
     * @throws Error if the task cannot be sent or queued
     */
    public sendLocalTask(task: AbstractPluginTask, connection: PenpotConnection): void {
        if (task.isSettled) {
            return;
        }
        this.assertCanSend(connection);
        if (!this.runningTasks.has(connection)) {
            this.startLocalTask(task, connection);
            return;
        }

        const queue = this.queuedTasks.get(connection) ?? [];
        const { maxQueuedTasks, queueTimeoutSecs } = this.queueLimits;
        const session = JSON.stringify(connection.session.sessionId);
        if (queue.length >= maxQueuedTasks) {
            throw new Error(
                `Penpot session ${session} is busy with an earlier task and ${queue.length} more waiting ` +
                    `(at most ${maxQueuedTasks} may wait). The task was not sent; retry later.`
            );
        }
        const timeout = setTimeout(() => {
            if (this.removeQueuedTask(task)) {
                task.rejectWithError(
                    new Error(
                        `Penpot session ${session} was still running an earlier task after ${queueTimeoutSecs} ` +
                            `seconds. The task was not sent; retry later.`
                    )
                );
            }
        }, queueTimeoutSecs * 1000);
        queue.push({ task, timeout });
        this.queuedTasks.set(connection, queue);
        this.logger.info(`Queued task ${task.id} behind the running task of its session (${queue.length} waiting)`);
    }

    /**
     * Throws if a task cannot be sent over the given connection.
     *
     * @param connection - The local connection of the target session
     */
    private assertCanSend(connection: PenpotConnection): void {
        if (this.closing) {
            throw new Error("Plugin bridge is shutting down; the task was not sent.");
        }
        if (!connection.ready || connection.socket.readyState !== WebSocket.OPEN) {
            throw new Error(`Plugin instance is disconnected. Task could not be sent.`);
        }
        // the socket can be open while browser-throttled plugin JS cannot run tasks
        assertPluginResponsive(connection, Date.now());
    }

    /** Sends a task over a connection whose plugin is not running a task, with execution tracking. */
    private startLocalTask(task: AbstractPluginTask, connection: PenpotConnection): void {
        // register the task for result correlation, then send over the socket
        this.pendingTasks.set(task.id, task);
        this.taskConnections.set(task.id, connection);
        this.runningTasks.set(connection, task.id);
        connection.socket.send(JSON.stringify(task.toRequest()));
        this.startTaskTimeout(task, this.taskTimeoutSecs);
    }

    /**
     * Arms the timeout for a locally or remotely dispatched task.
     *
     * A timed-out local task keeps its session busy until the plugin answers, since it may still be running.
     *
     * @param task - The dispatched task
     * @param timeoutSecs - The time, in seconds, to wait for the response
     * @param onTimeout - Called when the task times out
     */
    private startTaskTimeout(task: AbstractPluginTask<any, any>, timeoutSecs: number, onTimeout?: () => void): void {
        // Set up a timeout to reject the task if no response is received
        const timeoutHandle = setTimeout(() => {
            if (
                this.rejectPendingTask(
                    task.id,
                    new Error(
                        `Task ${task.id} timed out after ${timeoutSecs} seconds; ` +
                            `it may still be running in Penpot, so its outcome is unknown`
                    )
                )
            ) {
                onTimeout?.();
            }
        }, timeoutSecs * 1000);

        this.taskTimeouts.set(task.id, timeoutHandle);
        this.logger.info(`Sent task ${task.id}`);
    }

    /**
     * Closes the WebSocket server and all connected client sockets.
     * Also releases task tracking and dispatch subscriptions.
     */
    public async close(): Promise<void> {
        this.closing = true;
        for (const connection of [...this.queuedTasks.keys()]) {
            this.rejectQueuedTasks(connection, new Error("Plugin bridge is shutting down; the task was not sent."));
        }
        for (const taskId of this.pendingTasks.keys()) {
            this.rejectPendingTask(taskId, new Error("Plugin bridge is shutting down; task outcome may be unknown"));
        }
        for (const socket of this.wsServer.clients) {
            this.removeConnection(socket);
            socket.terminate();
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
